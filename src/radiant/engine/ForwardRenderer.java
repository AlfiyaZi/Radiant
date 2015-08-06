package radiant.engine;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL14.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30.*;

import java.util.ArrayList;
import java.util.List;

import radiant.assets.AssetLoader;
import radiant.assets.material.Material;
import radiant.assets.material.Shading;
import radiant.assets.shader.Shader;
import radiant.assets.texture.TextureData;
import radiant.engine.components.AttachedTo;
import radiant.engine.components.Camera;
import radiant.engine.components.DirectionalLight;
import radiant.engine.components.Mesh;
import radiant.engine.components.MeshRenderer;
import radiant.engine.components.PointLight;
import radiant.engine.components.Transform;
import radiant.engine.core.diag.Clock;
import radiant.engine.core.file.Path;
import radiant.engine.core.math.Matrix4f;
import radiant.engine.core.math.Vector3f;

public class ForwardRenderer extends Renderer {
	private FrameBuffer shadowBuffer;
	
	private int drawCalls = 0;
	private Clock clock = new Clock();
	
	@Override
	public void create() {
		setGlParameters();
		loadShaders();
	}
	
	@Override
	public void destroy() {
		
	}
	
	/**
	 * Sets the basic OpenGL parameters concerning back face culling,
	 * texture wrapping and alpha handling.
	 */
	private void setGlParameters() {
		glEnable(GL_CULL_FACE);
		glCullFace(GL_BACK);
		
		glClearColor(clearColor.x, clearColor.y, clearColor.z, 1.0f);
		
		shadowBuffer = new FrameBuffer();
	}
	
	/**
	 * Initialise all the shader buckets
	 */
	private void loadShaders() {
		shaders.put(Shading.NONE, null);
		shaders.put(Shading.UNSHADED, AssetLoader.loadShader(new Path("shaders/unshaded")));
		shaders.put(Shading.DIFFUSE, AssetLoader.loadShader(new Path("shaders/diffuse")));
		shaders.put(Shading.NORMAL, AssetLoader.loadShader(new Path("shaders/normal")));
		shaders.put(Shading.SPECULAR, AssetLoader.loadShader(new Path("shaders/specular")));
		shaders.put(Shading.SHADOW, AssetLoader.loadShader(new Path("shaders/shadow")));
		shaders.put(Shading.TEXTURE, AssetLoader.loadShader(new Path("shaders/texture")));
		
		for(Shader shader: shaders.values()) {
			shaderMap.put(shader, new ArrayList<Entity>());
		}
	}
	
	/**
	 * Renders the current complete scene graph
	 */
	@Override
	public void update() {
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		
		// Reset the draw calls before the next render
		drawCalls = 0;
		
		// If there is no main camera in the scene, nothing can be rendered
		if(scene.mainCamera == null) {
			return;
		}
		
		clock.start();
		
		// Divide the meshes into shader buckets
		divideMeshes();
		
		Camera camera = scene.mainCamera.getComponent(Camera.class);
		Transform ct = scene.mainCamera.getComponent(Transform.class);
		
		// Generate the shadow maps
		glEnable(GL_DEPTH_TEST);
		glDisable(GL_BLEND);
		
		generateShadowMaps();

		// Set the viewport to the normal window size
		glViewport(0, 0, Window.width, Window.height);
		
		// Set the clear color
		glClearColor(clearColor.x, clearColor.y, clearColor.z, 1);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		
		camera.loadProjectionMatrix(projectionMatrix);
		// Calculate view matrix
		viewMatrix.setIdentity();
		viewMatrix.rotate(Vector3f.negate(ct.rotation));
		viewMatrix.translate(Vector3f.negate(ct.position));
		
		// Render all the meshes associated with a shader
		Matrix4f biasMatrix = new Matrix4f();
		biasMatrix.array[0] = 0.5f;
		biasMatrix.array[5] = 0.5f;
		biasMatrix.array[10] = 0.5f;
		biasMatrix.array[12] = 0.5f;
		biasMatrix.array[13] = 0.5f;
		biasMatrix.array[14] = 0.5f;
		
		// Multiply diffuse texture with the lighting
		Shader shader = shaders.get(Shading.TEXTURE);
		glUseProgram(shader.handle);

		glUniform1f(glGetUniformLocation(shader.handle, "ambientLight"), 0.1f);
		
		renderScene(shader, ct, camera);
		
		// Enable the blending of light contributions
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_ONE, GL_ONE, GL_ONE, GL_ZERO);
		glDepthFunc(GL_LEQUAL);
		
		// Unshaded
		shader = shaders.get(Shading.UNSHADED);
		glUseProgram(shader.handle);
		
		// Draw the objects into depth buffer first, for culling
		//glDrawBuffer(GL_NONE);
		
		//renderScene(shader, ct, camera);

		//glDrawBuffer(GL_BACK);

		for(Entity entity: shaderMap.get(shader)) {
			drawMesh(shader, entity);
		}
		
		// Diffuse
		shader = shaders.get(Shading.DIFFUSE);
		glUseProgram(shader.handle);
		
		glUniformMatrix4(shader.biasMatrixLoc, false, biasMatrix.getBuffer());
		glUniformMatrix4(shader.projectionMatrixLoc, false, projectionMatrix.getBuffer());
		glUniformMatrix4(shader.viewMatrixLoc, false, viewMatrix.getBuffer());
		
		for (PointLight light: scene.pointLights) {
			uploadPointLight(shader, light);
			
			for(Entity entity: shaderMap.get(shader)) {				
				drawMesh(shader, entity);
			}
		}
		for (DirectionalLight light: scene.dirLights) {
			uploadDirectionalLight(shader, light);
			
			for(Entity entity: shaderMap.get(shader)) {				
				drawMesh(shader, entity);
			}
		}
		
		// Normal
		shader = shaders.get(Shading.NORMAL);
		glUseProgram(shader.handle);
		
		glUniformMatrix4(shader.projectionMatrixLoc, false, projectionMatrix.getBuffer());
		glUniformMatrix4(shader.viewMatrixLoc, false, viewMatrix.getBuffer());
		
		for (PointLight light: scene.pointLights) {
			uploadPointLight(shader, light);
			
			for(Entity entity: shaderMap.get(shader)) {				
				drawMesh(shader, entity);
			}
		}
		for (DirectionalLight light: scene.dirLights) {
			uploadDirectionalLight(shader, light);
			
			for(Entity entity: shaderMap.get(shader)) {				
				drawMesh(shader, entity);
			}
		}
		
		// Specular
		shader = shaders.get(Shading.SPECULAR);
		glUseProgram(shader.handle);
		
		glUniformMatrix4(shader.biasMatrixLoc, false, biasMatrix.getBuffer());
		glUniform3f(shader.cameraPositionLoc, ct.position.x, ct.position.y, ct.position.z);
		glUniformMatrix4(shader.projectionMatrixLoc, false, projectionMatrix.getBuffer());
		glUniformMatrix4(shader.viewMatrixLoc, false, viewMatrix.getBuffer());
		
		for (PointLight light: scene.pointLights) {
			uploadPointLight(shader, light);
			
			for(Entity entity: shaderMap.get(shader)) {				
				drawMesh(shader, entity);
			}
		}
		for (DirectionalLight light: scene.dirLights) {
			uploadDirectionalLight(shader, light);
			
			for(Entity entity: shaderMap.get(shader)) {				
				drawMesh(shader, entity);
			}
		}
		
		clock.end();
		//System.out.println("Total: " + clock.getNanoseconds());
	}
	
	@Override
	protected void renderScene(Shader shader, Transform transform, Camera camera) {
		Matrix4f projectionMatrix = new Matrix4f();
		Matrix4f viewMatrix = new Matrix4f();
		
		camera.loadProjectionMatrix(projectionMatrix);
		// Calculate view matrix
		viewMatrix.setIdentity();
		viewMatrix.rotate(Vector3f.negate(transform.rotation));
		viewMatrix.translate(Vector3f.negate(transform.position));
		
		glUseProgram(shader.handle);
		
		glUniformMatrix4(shader.projectionMatrixLoc, false, projectionMatrix.getBuffer());
		glUniformMatrix4(shader.viewMatrixLoc, false, viewMatrix.getBuffer());
		
		for(Entity entity: scene.getEntities()) {
			Mesh mesh = entity.getComponent(Mesh.class);
			
			if(mesh != null) {
				drawMesh(shader, entity);
			}
		}
	}
	
	private void generateShadowMaps() {
		// Generate the shadow maps
		for (DirectionalLight light: scene.dirLights) {
			Transform lightT = light.owner.getComponent(Transform.class);
			Camera lightC = new Camera(-10, 10, -10, 10, -20, 20);
			lightC.loadProjectionMatrix(light.shadowInfo.projectionMatrix);
			light.shadowInfo.viewMatrix.setIdentity();
			light.shadowInfo.viewMatrix.rotate(Vector3f.negate(lightT.rotation));
			light.shadowInfo.viewMatrix.translate(Vector3f.negate(lightT.position));
			
			if (light.shadowInfo != null) {
				// Set the viewport to the size of the shadow map
				glViewport(0, 0, 1024, 1024); // FIXME variable size
				
				// Set the shadow shader to render the shadow map with
				Shader shader = shaders.get(Shading.SHADOW);
				glUseProgram(shader.handle);
				
				// Set up the framebuffer and validate it
				shadowBuffer.bind();
				shadowBuffer.setTexture(GL_DEPTH_ATTACHMENT, light.shadowInfo.shadowMap);
				shadowBuffer.disableColor();
				shadowBuffer.validate();
				
				// Clear the framebuffer and render the scene from the view of the light
				glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
				
				glDisable(GL_CULL_FACE);
				
				renderScene(shader, lightT, lightC);

				glEnable(GL_CULL_FACE);
			}
		}
		for (PointLight light: scene.pointLights) {
			Transform lightT = light.owner.getComponent(Transform.class);
			Camera lightC = new Camera(90, 1, 0.1f, 20);
			
			for (int i = 0; i < 6; i++) {
				lightT.rotation = CubeMap.transforms[i];
				
				// Set the viewport to the size of the shadow map
				glViewport(0, 0, light.shadowMap.getResolution(), light.shadowMap.getResolution());
				
				// Set the shadow shader to render the shadow map with
				Shader shader = shaders.get(Shading.SHADOW);
				glUseProgram(shader.handle);
				
				// Set up the framebuffer and validate it
				shadowBuffer.bind();
				shadowBuffer.setCubeMap(light.shadowMap, GL_TEXTURE_CUBE_MAP_POSITIVE_X + i);
				shadowBuffer.enableColor(GL_COLOR_ATTACHMENT0);
				shadowBuffer.validate();

				// Upload the light matrices
				glUniform3f(shader.siLightPosLoc, lightT.position.x, lightT.position.y, lightT.position.z); 
				
				// Set the clear color to be the furthest distance possible
				shadowBuffer.setClearColor(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, 1);
				
				// Clear the framebuffer and render the scene from the view of the light
				glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

				renderScene(shaders.get(Shading.SHADOW), lightT, lightC);
			}
		}
		shadowBuffer.unbind();
	}
	
	/**
	 * Divide the meshes in the scene into their appropriate shader buckets
	 */
	private void divideMeshes() {
		for(List<Entity> meshes: shaderMap.values()) {
			meshes.clear();
		}
		for(Entity e: scene.getEntities()) {
			Mesh mesh = e.getComponent(Mesh.class);
			MeshRenderer mr = e.getComponent(MeshRenderer.class);
			if(mesh == null || mr == null) {
				continue;
			}
			
			Shader shader = shaders.get(mr.material.shading);
			shaderMap.get(shader).add(e);
		}
	}

	/**
	 * Uploads a point light to the shader
	 * @param shader The shader currently in use
	 * @param lights The point light to upload
	 */
	private void uploadPointLight(Shader shader, PointLight light) {
		Entity e = light.owner;
		Transform lightT = e.getComponent(Transform.class);
		
		glActiveTexture(GL_TEXTURE4);
		glBindTexture(GL_TEXTURE_CUBE_MAP, light.shadowMap.colorMap);
		glUniform1i(shader.siCubeMapLoc, 4);

		glUniform1i(shader.isPointLightLoc, 1);
		glUniform1i(shader.isDirLightLoc, 0);
		glUniform3f(shader.plPositionLoc, lightT.position.x, lightT.position.y, lightT.position.z);
		glUniform3f(shader.plColorLoc, light.color.x, light.color.y, light.color.z);
		glUniform1f(shader.plEnergyLoc, light.energy);
		glUniform1f(shader.plDistanceLoc, light.distance);
		if (light.castShadows) {
			glUniform1i(shader.plCastShadowsLoc, 1);
		} else {
			glUniform1i(shader.plCastShadowsLoc, 0);
		}
	}
	
	/**
	 * Uploads a directional light to the shader
	 * @param shader The shader currently in use
	 * @param lights The directional light to upload
	 */
	private void uploadDirectionalLight(Shader shader, DirectionalLight light) {
		Entity e = light.owner;
		Transform lightT = e.getComponent(Transform.class);
		
		Matrix4f m = new Matrix4f();
		m.rotate(lightT.rotation);
		Vector3f dir = new Vector3f(0, 0, -1);
		dir = m.transform(dir, 0);
		
		glActiveTexture(GL_TEXTURE5);
		ShadowInfo shadowInfo = light.shadowInfo;
		glBindTexture(GL_TEXTURE_2D, shadowInfo.shadowMap);
		glUniform1i(shader.siMapLoc, 5);
		glUniformMatrix4(shader.siProjectionLoc, false, shadowInfo.projectionMatrix.getBuffer());
		glUniformMatrix4(shader.siViewLoc, false, shadowInfo.viewMatrix.getBuffer());
		
		glUniform1i(shader.isPointLightLoc, 0);
		glUniform1i(shader.isDirLightLoc, 1);
		glUniform3f(shader.dlDirectionLoc, dir.x, dir.y, dir.z);
		glUniform3f(shader.dlColorLoc, light.color.x, light.color.y, light.color.z);
		glUniform1f(shader.dlEnergyLoc, light.energy);
		if (light.castShadows) {
			glUniform1i(shader.dlCastShadowsLoc, 1);
		} else {
			glUniform1i(shader.dlCastShadowsLoc, 0);
		}
	}
	
	/**
	 * Uploads the specified material to the shaders
	 * @param shader The shader currently in use
	 * @param mat    The material to be uploaded
	 */
	private void uploadMaterial(Shader shader, Material mat) {
		// Colors
		glUniform3f(shader.diffuseColorLoc,	mat.diffuseColor.x, mat.diffuseColor.y, mat.diffuseColor.z);
		glUniform3f(shader.specularColorLoc, mat.specularColor.x, mat.specularColor.y, mat.specularColor.z);
		
		glUniform1f(shader.specularIntensityLoc, mat.specularIntensity);
		glUniform2f(shader.tilingLoc, mat.tiling.x, mat.tiling.y);
		glUniform1f(shader.hardnessLoc, mat.hardness);
		
		if(mat.receiveShadows) {
			glUniform1i(shader.receiveShadowsLoc, 1);
		} else {
			glUniform1i(shader.receiveShadowsLoc, 0);
		}
		
		// Diffuse texture
		if(mat.diffuseMap != null) {
			TextureData diffuseMap = AssetLoader.loadTexture(mat.diffuseMap);

			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_2D, diffuseMap.handle);
			glUniform1i(shader.diffuseMapLoc, 0);

			// Let the shader know we uploaded a diffuse map
			glUniform1i(shader.hasDiffuseMapLoc, 1);
		} else {
			glUniform1i(shader.hasDiffuseMapLoc, 0);
		}
		// Normal texture
		if(mat.normalMap != null) {
			TextureData normalMap = AssetLoader.loadTexture(mat.normalMap);

			glActiveTexture(GL_TEXTURE1);
			glBindTexture(GL_TEXTURE_2D, normalMap.handle);
			glUniform1i(shader.normalMapLoc, 1);
			
			// Let the shader know we uploaded a normal map
			glUniform1i(shader.hasNormalMapLoc, 1);
		} else {
			glUniform1i(shader.hasNormalMapLoc, 0);
		}
		// Specular texture
		if(mat.specularMap != null) {
			TextureData specularMap = AssetLoader.loadTexture(mat.specularMap);

			glActiveTexture(GL_TEXTURE2);
			glBindTexture(GL_TEXTURE_2D, specularMap.handle);
			glUniform1i(shader.specularMapLoc, 2);

			// Let the shader know we uploaded a specular map
			glUniform1i(shader.hasSpecularMapLoc, 1);
		} else {
			glUniform1i(shader.hasSpecularMapLoc, 0);
		}
	}
	
	/**
	 * Draws the mesh associated with the given entity
	 * @param shader The shader currently in use
	 * @param entity The entity that has the mesh component to be drawn
	 */
	@Override
	protected void drawMesh(Shader shader, Entity entity) {
		Transform transform = entity.getComponent(Transform.class);
		Mesh mesh           = entity.getComponent(Mesh.class);
		MeshRenderer mr     = entity.getComponent(MeshRenderer.class);
		AttachedTo attached = entity.getComponent(AttachedTo.class);
		
		if(transform == null) {
			return;
		}
		
		modelMatrix.setIdentity();
		
		// Go up the hierarchy and stack transformations if this entity has a parent
		if(attached != null) {
			Entity parent = attached.parent;
			Transform parentT = parent.getComponent(Transform.class);

			modelMatrix.translate(parentT.position);
			modelMatrix.rotate(parentT.rotation);
			//modelMatrix.scale(parentT.scale); //FIXME allow scaling
		}
		
		modelMatrix.translate(transform.position);
		modelMatrix.rotate(transform.rotation);
		modelMatrix.scale(transform.scale);
		
		// Upload matrices to the shader
		glUniformMatrix4(shader.modelMatrixLoc, false, modelMatrix.getBuffer());
		
		if(mr.material != null) {
			uploadMaterial(shader, mr.material);
		}
		
		glBindVertexArray(mesh.handle);
		glDrawArrays(GL_TRIANGLES, 0, mesh.getNumFaces() * 3);
		glBindVertexArray(0);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE2);
		glBindTexture(GL_TEXTURE_2D, 0);
		
		// Add a drawcall to the counter
		drawCalls++;
	}
}