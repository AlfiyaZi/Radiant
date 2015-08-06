#version 330 core

// Shadow
struct ShadowInfo {
	sampler2D shadowMap;
	mat4 projectionMatrix;
	mat4 viewMatrix;
};

// Light
struct PointLight {
	vec3 position;
	vec3 color;
	float energy;
	float distance;
	bool castShadows;
};

struct DirectionalLight {
	vec3 direction;
	vec3 color;
	float energy;
	bool castShadows;
};

// Material
struct Material {
	vec3 diffuseColor;
	vec3 specularColor;
	
	float specularIntensity;
	vec2 tiling;
	
	float hardness;
	
	sampler2D diffuseMap;
	sampler2D normalMap;
	sampler2D specularMap;
	
	bool hasDiffuseMap;
	bool hasNormalMap;
	bool hasSpecularMap;
	
	bool receiveShadows;
};

uniform ShadowInfo shadowInfo;
uniform samplerCube shadowCubeMap;

uniform PointLight pointLight;
uniform DirectionalLight dirLight;

uniform bool isPointLight;
uniform bool isDirLight;

uniform Material material;

// Pass
in vec3 pass_position;
in vec2 pass_texCoord;
in vec3 pass_normal;
in vec3 pass_tangent;
in vec4 pass_shadowCoord;

vec4 shadowCoord;

/* Converts a vector to a color */
vec4 toColor(vec4 v) {
	return 0.5 * v + 0.5;
}

/* Converts a color back to a vector */
vec4 fromColor(vec4 c) {
	return 2 * c - 1;
}

/* Calculates the normal of the fragment using a normal map */
vec3 calcNormal(vec3 src_normal, vec3 src_tangent, vec2 texCoord) {
	vec3 normal = normalize(src_normal);
	vec3 tangent = normalize(src_tangent);
	tangent = normalize(tangent - dot(tangent, normal) * normal);
	vec3 bitangent = cross(tangent, normal);
	vec3 mapnorm = texture(material.normalMap, texCoord * material.tiling).rgb * 2.0 - 1.0;
	
	mat3 TBN = mat3(tangent, bitangent, normal);
	normal = normalize(TBN * mapnorm);
	return normal;
}

/* Calculates point light attribution */
float calcPointAtt(PointLight light, vec3 lightDir) {
	float lightLength = length(lightDir);
	float x = lightLength / light.distance;
    float fAtt = max(0, 1 - pow(x, 0.2));

    return fAtt;
}

/* Calculates the diffuse contribution of the light */
float calcDiffuse(vec3 lightDir, vec3 normal) {
	return clamp(dot(normal, normalize(lightDir)), 0, 1);
}

/* Calculates the specular contribution of the light */
float calcSpec(vec3 lightDir, vec3 camDir, vec3 normal, float hardness) {
	vec3 half = normalize(normalize(lightDir) + normalize(camDir));
	float fPhong = pow(max(dot(half, normal), 0), hardness);
	//if(material.hasSpecularMap) {
	//	fPhong *= texture(material.specularMap, pass_texCoord * material.tiling).xyz;
	//}
    
	return fPhong;
}

/* Calculates whether a given fragment is in shadow for directional lights */
float getDirVisibility(float bias) {
	float factor = 0;
	float xOffset = 1.0 / 1024;
	float yOffset = 1.0 / 1024;
	
	for (int y = -1; y <= 1; y++) {
		for (int x = -1; x <= 1; x++) {
			float sx = shadowCoord.x + x * xOffset;
			float sy = shadowCoord.y + y * yOffset;
			if (texture(shadowInfo.shadowMap, shadowCoord.xy).z < shadowCoord.z - bias) {
				factor += 0;
			} else {
				factor += 1;
			}
		}
	}
	
	return (factor / 18.0);
}

/* Calculates whether a given fragment is in shadow for point lights */
float getPointVisibility(float bias, vec3 lightDir) {
	float factor = 0;
	float xOffset = 1.0 / 1024;
	float yOffset = 1.0 / 1024;
	
	float dist = length(lightDir);
	
	for (int z = -1; z <= 1; z++) {
		for (int y = -1; y <= 1; y++) {
			for (int x = -1; x <= 1; x++) {
				//if (y == 0) { continue; }
				//if (z == 0) { continue; }
				//if (x == 0) { continue; }
				float sx = -lightDir.x + x * 8 * xOffset;
				float sy = -lightDir.y + y * 8 * yOffset;
				float sz = -lightDir.z + z * 8 * yOffset;
				float sample = texture(shadowCubeMap, vec3(sx, sy, sz)).r;
				
				if (dist - bias < sample) {
					factor++;
				}
			}
		}
	}
	
	return (factor / 54.0) + 0.5;
}
