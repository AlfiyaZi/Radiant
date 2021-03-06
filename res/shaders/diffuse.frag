// Pass
uniform mat4 modelMatrix;

out vec4 out_Color;

void main(void) {
	float visibility = 1.0;
	float bias = 0.005;
	
	vec3 refl = vec3(0, 0, 0);
	
	// Calculate the location of this fragment (pixel) in world coordinates
    vec3 position = (modelMatrix * vec4(pass_position, 1)).xyz;
    
	//Normals
    vec3 normal = normalize(transpose(inverse(mat3(modelMatrix))) * pass_normal);
    
    // Point lighting
	if (isPointLight) {
		PointLight light = pointLight;
    	
    	vec3 lightDir = light.position - position;
    	
	    // Calculate the diffuse contribution
	    float fAtt = calcPointAtt(light, lightDir);
	    float fDiffuse = calcDiffuse(lightDir, normal);
		refl += material.diffuseColor * light.color * light.energy * fDiffuse * fAtt;
		
		// Shadows
		if (material.receiveShadows && light.castShadows) {
			visibility = getPointVisibility(bias * 20, lightDir);
		}
	}
	
	// Directional lighting
	if (isDirLight) {
		DirectionalLight light = dirLight;
	
		// Calculate the vector from this pixels surface to the light source
		vec3 lightDir = -light.direction;
		vec3 lightColor = light.color;
		
		// Calculate the cosine of the angle of incidence (brightness)
		float fDiffuse = clamp(dot(normal, normalize(lightDir)), 0, 1);
		
		refl += material.diffuseColor * light.color * fDiffuse * light.energy;
		
		// Shadows
		if (material.receiveShadows && light.castShadows) {
			visibility = getDirVisibility(bias);
		}
	}
	
	out_Color = vec4(refl * visibility, 1);
}
