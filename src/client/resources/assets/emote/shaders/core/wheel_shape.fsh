#version 330
#extension GL_ARB_separate_shader_objects : require

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;

void main() {
    // Red stores the signed distance in [-8, 8] wheel units. Green enables the shadow.
    vec2 mask = texture(Sampler0, texCoord0).rg;
    float distance = (mask.r - 0.5) * 16.0;
    float pixelWidth = max(length(vec2(dFdx(distance), dFdy(distance))), 0.01);
    float coverage = clamp(0.5 + distance / pixelWidth, 0.0, 1.0);
    float shadow = mask.g * 0.30 * smoothstep(-4.0, 0.0, distance) * (1.0 - coverage);

    float faceAlpha = vertexColor.a * coverage;
    float alpha = faceAlpha + vertexColor.a * shadow * (1.0 - faceAlpha);
    fragColor = vec4(vertexColor.rgb * (faceAlpha / max(alpha, 0.0001)), alpha) * ColorModulator;
}
