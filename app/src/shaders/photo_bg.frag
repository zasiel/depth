#version 400
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

// Cyote3D filled-background layer: opaque, never cut (viewer/app.js with isBg = true).

#include <data/shaders/common.glsl>

layout (std140, set = 3, binding = 0) uniform MaterialUniform {
  vec4 geom;
  vec4 view;
  vec4 tear;
} g_MaterialUniform;

layout (set = 3, binding = 1) uniform sampler2D colorMap;  // bg.jpg

layout(location = 0) in vec2 vUv;
layout(location = 1) in float vC;

layout (location = 0) out vec4 outColor;

void main() {
  outColor = vec4(texture(colorMap, vUv).rgb, 1.0);
}
