package radiant.engine;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.BufferUtils;

import radiant.assets.AssetLoader;
import radiant.assets.material.Material;
import radiant.assets.material.Shading;
import radiant.assets.model.ModelLoader;
import radiant.assets.shader.Shader;
import radiant.assets.texture.TextureData;
import radiant.assets.texture.TextureLoader;
import radiant.engine.components.AttachedTo;
import radiant.engine.components.Camera;
import radiant.engine.components.DirectionalLight;
import radiant.engine.components.Mesh;
import radiant.engine.components.MeshRenderer;
import radiant.engine.components.PointLight;
import radiant.engine.components.ReflectionProbe;
import radiant.engine.components.Transform;
import radiant.engine.core.diag.Log;
import radiant.engine.core.errors.AssetLoaderException;
import radiant.engine.core.file.Path;
import radiant.engine.core.math.Matrix4f;
import radiant.engine.core.math.Vector3f;

public class DeferredRenderer extends Renderer {
	private FrameBuffer shadowBuffer;
	private FrameBuffer reflBuffer;
	
	private FrameBuffer gBuffer = null;
	private int colorTex = -1;
	private int normalTex = -1;
	private int positionTex = -1;
	private int specularTex = -1;
	private int depthTex = -1;
	Mesh quad = null;
	
	@Override
	public void create() {
		glEnable(GL_CULL_FACE);
		glCullFace(GL_BACK);

		clearColor.set(0.286f, 0.36f, 0.396f);
		
		shadowBuffer = new FrameBuffer();
		reflBuffer = new FrameBuffer();
		
		loadShaders();
		
		gBuffer = new FrameBuffer();
		gBuffer.bind();
		
		colorTex = TextureLoader.create(GL_RGBA8, Window.width, Window.height, GL_RGBA, GL_UNSIGNED_BYTE, null);
		gBuffer.setTexture(GL_COLOR_ATTACHMENT0, colorTex);
		
		normalTex = TextureLoader.create(GL_RGBA16, Window.width, Window.height, GL_RGBA, GL_UNSIGNED_BYTE, null);
		gBuffer.setTexture(GL_COLOR_ATTACHMENT1, normalTex);
		
		positionTex = TextureLoader.create(GL_RGBA32F, Window.width, Window.height, GL_RGBA, GL_FLOAT, null);
		gBuffer.setTexture(GL_COLOR_ATTACHMENT2, positionTex);
		
		specularTex = TextureLoader.create(GL_RGBA32F, Window.width, Window.height, GL_RGBA, GL_FLOAT, null);
		gBuffer.setTexture(GL_COLOR_ATTACHMENT3, specularTex);
		
		depthTex = TextureLoader.create(GL_DEPTH_COMPONENT24, Window.width, Window.height, GL_DEPTH_COMPONENT, GL_FLOAT, null);
		gBuffer.setTexture(GL_DEPTH_ATTACHMENT, depthTex);

		IntBuffer drawBuffers = BufferUtils.createIntBuffer(4);
		drawBuffers.put(GL_COLOR_ATTACHMENT0);
		drawBuffers.put(GL_COLOR_ATTACHMENT1);
		drawBuffers.put(GL_COLOR_ATTACHMENT2);
		drawBuffers.put(GL_COLOR_ATTACHMENT3);
		drawBuffers.flip();

		glDrawBuffers(drawBuffers);
		
		gBuffer.validate();
		
		setClearColor(clearColor.x, clearColor.y, clearColor.z, 1);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		gBuffer.unbind();
		
		try {
			quad = ModelLoader.loadModel(new Path("res/primitives/Quad.obj")).getMeshes().get(0);
		} catch (AssetLoaderException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Initialise all the shader buckets
	 */
	private void loadShaders() {
		shaders.put(Shading.NONE, null);
		shaders.put(Shading.AMBIENT, AssetLoader.loadShader(new Path("shaders/ambient")));
		shaders.put(Shading.GBUFFER, AssetLoader.loadShader(new Path("shaders/gbuffer")));
		shaders.put(Shading.DEFERRED, AssetLoader.loadShader(new Path("shaders/deferred")));
		shaders.put(Shading.SHADOW, AssetLoader.loadShader(new Path("shaders/shadow")));
		
		for(Shader shader: shaders.values()) {
			shaderMap.put(shader, new ArrayList<Entity>());
		}
	}

	@Override
	public void update() {		
		// If there is no main camera in the scene, nothing can be rendered
		if(scene.mainCamera == null) {
			return;
		}
		
		// Divide the meshes into shader buckets
		divideMeshes();
		
		Camera camera = scene.mainCamera.getComponent(Camera.class);
		Transform ct = scene.mainCamera.getComponent(Transform.class);
		
		glDisable(GL_BLEND);
		glEnable(GL_DEPTH_TEST);
		
		// Generate the shadow maps for each light
		genShadowMaps();
		
		// Generate the reflection maps for each reflection probe
		//genReflectionMaps();
		
		render(camera, ct);
	}

	private void render(Camera camera, Transform t) {
		gBuffer.bind();
		glViewport(0, 0, Window.width, Window.height);
		
		gBuffer.setClearColor(clearColor.x, clearColor.y, clearColor.z, 1);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		renderScene(shaders.get(Shading.GBUFFER), t, camera);
		gBuffer.unbind();

		glViewport(0, 0, Window.width, Window.height);
		setClearColor(clearColor.x, clearColor.y, clearColor.z, 1);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		
		glDisable(GL_DEPTH_TEST);
		glBlendFunc(GL_ONE, GL_ONE);
		glDisable(GL_BLEND);
		
		Shader shader = shaders.get(Shading.AMBIENT);
		glUseProgram(shader.handle);
		
		glUniform1f(glGetUniformLocation(shader.handle, "ambientLight"), scene.ambient);
		
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, colorTex);
		glUniform1i(glGetUniformLocation(shader.handle, "colorTex"), 0);
		
		projectionMatrix.setIdentity();
		viewMatrix.setIdentity();
		modelMatrix.setIdentity();
		
		// Upload matrices to the shader
		glUniformMatrix4(shader.uniform("projectionMatrix"), false, projectionMatrix.getBuffer());
		glUniformMatrix4(shader.uniform("viewMatrix"), false, viewMatrix.getBuffer());
		glUniformMatrix4(shader.uniform("modelMatrix"), false, modelMatrix.getBuffer());
		
		glBindVertexArray(quad.handle);
		glDrawArrays(GL_TRIANGLES, 0, quad.getNumFaces() * 3);
		glBindVertexArray(0);
		
		glEnable(GL_BLEND);
		
		shader = shaders.get(Shading.DEFERRED);
		glUseProgram(shader.handle);
		
		glUniform3f(glGetUniformLocation(shader.handle, "camPos"), t.position.x, t.position.y, t.position.z);
		
		projectionMatrix.setIdentity();
		viewMatrix.setIdentity();
		modelMatrix.setIdentity();
		
		// Upload the textures to the shader
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, colorTex);
		glUniform1i(glGetUniformLocation(shader.handle, "colorTex"), 0);
		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D, normalTex);
		glUniform1i(glGetUniformLocation(shader.handle, "normalTex"), 1);
		glActiveTexture(GL_TEXTURE2);
		glBindTexture(GL_TEXTURE_2D, positionTex);
		glUniform1i(glGetUniformLocation(shader.handle, "positionTex"), 2);
		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_2D, specularTex);
		glUniform1i(glGetUniformLocation(shader.handle, "specularTex"), 3);
		
		// Upload matrices to the shader
		glUniformMatrix4(shader.uniform("projectionMatrix"), false, projectionMatrix.getBuffer());
		glUniformMatrix4(shader.uniform("viewMatrix"), false, viewMatrix.getBuffer());
		glUniformMatrix4(shader.uniform("modelMatrix"), false, modelMatrix.getBuffer());
		
		// Render all the meshes associated with a shader
		Matrix4f biasMatrix = new Matrix4f();
		biasMatrix.array[0] = 0.5f;
		biasMatrix.array[5] = 0.5f;
		biasMatrix.array[10] = 0.5f;
		biasMatrix.array[12] = 0.5f;
		biasMatrix.array[13] = 0.5f;
		biasMatrix.array[14] = 0.5f;
		glUniformMatrix4(shader.uniform("biasMatrix"), false, biasMatrix.getBuffer());
		
		for (PointLight light: scene.pointLights) {
			uploadPointLight(shader, light);
			
			glBindVertexArray(quad.handle);
			glDrawArrays(GL_TRIANGLES, 0, quad.getNumFaces() * 3);
			glBindVertexArray(0);
		}
		for (DirectionalLight light: scene.dirLights) {
			uploadDirectionalLight(shader, light);
			
			glBindVertexArray(quad.handle);
			glDrawArrays(GL_TRIANGLES, 0, quad.getNumFaces() * 3);
			glBindVertexArray(0);
		}
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
		
		glUniformMatrix4(shader.uniform("projectionMatrix"), false, projectionMatrix.getBuffer());
		glUniformMatrix4(shader.uniform("viewMatrix"), false, viewMatrix.getBuffer());
		
		for(Entity entity: scene.getEntities()) {
			Mesh mesh = entity.getComponent(Mesh.class);
			
			if(mesh != null) {
				drawMesh(shader, entity);
			}
		}
	}
	
	private void genShadowMaps() {
		// Generate the shadow maps
		for (DirectionalLight light: scene.dirLights) {
			Transform lightT = light.owner.getComponent(Transform.class);
			// TODO Make convex hull of scene to get parameters
			Camera lightC = new Camera(-20, 20, -20, 20, -30, 30);
			lightC.loadProjectionMatrix(light.shadowInfo.projectionMatrix);
			light.shadowInfo.viewMatrix.setIdentity();
			light.shadowInfo.viewMatrix.rotate(Vector3f.negate(lightT.rotation));
			light.shadowInfo.viewMatrix.translate(Vector3f.negate(lightT.position));
			
			if (light.shadowInfo != null) {
				int resolution = light.shadowInfo.resolution;
				
				// Set the viewport to the size of the shadow map
				glViewport(0, 0, resolution, resolution);
				
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

				renderScene(shader, lightT, lightC);
			}
		}
		for (PointLight light: scene.pointLights) {
			Transform lightT = light.owner.getComponent(Transform.class);
			Camera lightC = new Camera(90, 1, 0.1f, light.shadowDistance);
			
			for (int i = 0; i < 6; i++) {
				lightT.rotation = CubeMap.transforms[i];
				
				// Set the viewport to the size of the shadow map
				glViewport(0, 0, light.shadowMap.getResolution(), light.shadowMap.getResolution());
				
				// Set the shadow shader to render the shadow map with
				Shader shader = shaders.get(Shading.SHADOW);
				glUseProgram(shader.handle);
				
				// Set up the framebuffer and validate it
				shadowBuffer.bind();
				shadowBuffer.setDepthCubeMap(light.shadowMap, GL_TEXTURE_CUBE_MAP_POSITIVE_X + i);
				shadowBuffer.disableColor();
				shadowBuffer.validate();
				
				// Upload the light matrices
				glUniform3f(shader.uniform("lightPos"), lightT.position.x, lightT.position.y, lightT.position.z); 
				
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
	 * Uploads a point light to the shader
	 * @param shader The shader currently in use
	 * @param lights The point light to upload
	 */
	private void uploadPointLight(Shader shader, PointLight light) {
		Entity e = light.owner;
		Transform lightT = e.getComponent(Transform.class);
		
		glActiveTexture(GL_TEXTURE4);
		glBindTexture(GL_TEXTURE_CUBE_MAP, light.shadowMap.depthMap);
		glUniform1i(shader.uniform("shadowCubeMap"), 4);

		glUniform1i(shader.uniform("isPointLight"), 1);
		glUniform1i(shader.uniform("isDirLight"), 0);
		glUniform3f(shader.uniform("pointLight.position"), lightT.position.x, lightT.position.y, lightT.position.z);
		glUniform3f(shader.uniform("pointLight.color"), light.color.x, light.color.y, light.color.z);
		glUniform1f(shader.uniform("pointLight.energy"), light.energy);
		glUniform1f(shader.uniform("pointLight.distance"), light.distance);
		
		if (light.castShadows) {
			glUniform1i(shader.uniform("pointLight.castShadows"), 1);
			glUniform1f(shader.uniform("pointLight.bias"), light.shadowBias);
		} else {
			glUniform1i(shader.uniform("pointLight.castShadows"), 0);
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
		glUniform1i(shader.uniform("shadowInfo.shadowMap"), 5);
		glUniformMatrix4(shader.uniform("shadowInfo.projectionMatrix"), false, shadowInfo.projectionMatrix.getBuffer());
		glUniformMatrix4(shader.uniform("shadowInfo.viewMatrix"), false, shadowInfo.viewMatrix.getBuffer());
		
		glUniform1i(shader.uniform("isPointLight"), 0);
		glUniform1i(shader.uniform("isDirLight"), 1);
		glUniform3f(shader.uniform("dirLight.direction"), dir.x, dir.y, dir.z);
		glUniform3f(shader.uniform("dirLight.color"), light.color.x, light.color.y, light.color.z);
		glUniform1f(shader.uniform("dirLight.energy"), light.energy);
		if (light.castShadows) {
			glUniform1i(shader.uniform("dirLight.castShadows"), 1);
			glUniform1f(shader.uniform("dirLight.bias"), light.shadowBias);
		} else {
			glUniform1i(shader.uniform("dirLight.castShadows"), 0);
		}
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
	 * Uploads the specified material to the shaders
	 * @param shader The shader currently in use
	 * @param mat    The material to be uploaded
	 */
	private void uploadMaterial(Shader shader, Material mat) {
		// Colors
		glUniform3f(shader.uniform("material.diffuseColor"),	mat.diffuseColor.x, mat.diffuseColor.y, mat.diffuseColor.z);
		glUniform3f(shader.uniform("material.specularColor"), mat.specularColor.x, mat.specularColor.y, mat.specularColor.z);
		
		glUniform1f(shader.uniform("material.specularIntensity"), mat.specularIntensity);
		glUniform2f(shader.uniform("material.tiling"), mat.tiling.x, mat.tiling.y);
		glUniform1f(shader.uniform("material.hardness"), mat.hardness);
		
		if(mat.receiveShadows) {
			glUniform1i(shader.uniform("material.receiveShadows"), 1);
		} else {
			glUniform1i(shader.uniform("material.receiveShadows"), 0);
		}
		
		// Diffuse texture
		if(mat.diffuseMap != null) {
			TextureData diffuseMap = AssetLoader.loadTexture(mat.diffuseMap);

			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_2D, diffuseMap.handle);
			glUniform1i(shader.uniform("material.diffuseMap"), 0);

			// Let the shader know we uploaded a diffuse map
			glUniform1i(shader.uniform("material.hasDiffuseMap"), 1);
		} else {
			glUniform1i(shader.uniform("material.hasDiffuseMap"), 0);
		}
		// Normal texture
		if(mat.normalMap != null) {
			TextureData normalMap = AssetLoader.loadTexture(mat.normalMap);

			glActiveTexture(GL_TEXTURE1);
			glBindTexture(GL_TEXTURE_2D, normalMap.handle);
			glUniform1i(shader.uniform("material.normalMap"), 1);
			
			// Let the shader know we uploaded a normal map
			glUniform1i(shader.uniform("material.hasNormalMap"), 1);
		} else {
			glUniform1i(shader.uniform("material.hasNormalMap"), 0);
		}
		// Specular texture
		if(mat.specularMap != null) {
			TextureData specularMap = AssetLoader.loadTexture(mat.specularMap);

			glActiveTexture(GL_TEXTURE2);
			glBindTexture(GL_TEXTURE_2D, specularMap.handle);
			glUniform1i(shader.uniform("material.specularMap"), 2);

			// Let the shader know we uploaded a specular map
			glUniform1i(shader.uniform("material.hasSpecularMap"), 1);
		} else {
			glUniform1i(shader.uniform("material.hasSpecularMap"), 0);
		}
	}
	
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
		glUniformMatrix4(shader.uniform("modelMatrix"), false, modelMatrix.getBuffer());
		
		if(mr.material != null) {
			uploadMaterial(shader, mr.material);
		}

		glBindVertexArray(mesh.handle);
		glDrawArrays(GL_TRIANGLES, 0, mesh.getNumFaces() * 3);
		glBindVertexArray(0);
	}
	
	@Override
	public void destroy() {
		// TODO Auto-generated method stub
		
	}
}
