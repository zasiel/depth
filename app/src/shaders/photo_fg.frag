#version 400
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

// Cyote3D foreground layer, fragment stage. Port of the `frag` shader in viewer/app.js.
//
//  * tear cut: a triangle stretched across a depth jump has an interpolated closeness that matches
//    neither texel it covers; cut it so the layers separate exactly at the pixel boundary;
//  * matted edge: soft alpha (linearly filtered) with decontaminated colour, blended over the
//    background layer drawn behind it.
//
// depthMap and alphaMap carry their data in the ALPHA channel only (RGB = 0), so the result is the
// same whether or not the texture is sampled through an sRGB format.

#include <data/shaders/common.glsl>

layout (std140, set = 3, binding = 0) uniform MaterialUniform {
  vec4 geom;
  vec4 view;
  vec4 tear;  // x: tear threshold, y: tear on (1) / off (0)
} g_MaterialUniform;

layout (set = 3, binding = 1) uniform sampler2D colorMap;  // fg.jpg (decontaminated colour)
layout (set = 3, binding = 2) uniform sampler2D depthMap;  // .a = round(closeness * 255), nearest
layout (set = 3, binding = 3) uniform sampler2D alphaMap;  // .a = foreground alpha, linear

layout(location = 0) in vec2 vUv;
layout(location = 1) in float vC;

layout (location = 0) out vec4 outColor;

void main() {
  vec3 col = texture(colorMap, vUv).rgb;

  if (g_MaterialUniform.tear.y > 0.5) {
    float cTexel = textureLod(depthMap, vUv, 0.0).a;
    if (abs(vC - cTexel) > g_MaterialUniform.tear.x) discard;
  }

  float a = textureLod(alphaMap, vUv, 0.0).a;
  if (a < 0.02) discard;

  outColor = vec4(col, a);
}
