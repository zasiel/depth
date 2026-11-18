#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

// Cyote3D photo layer, vertex stage. Port of the `vert` shader in viewer/app.js.
//
// The mesh is a flat grid on the window plane. Per vertex, `position.xy` is the window-plane
// position in metres (window centre at 0,0) and `position.z` carries the closeness of the texel
// under that vertex (0 far .. 1 near), baked by the app from the 16-bit depth PNG. Baking it
// avoids relying on vertex texture fetch and on texture-coordinate precision.
//
// A pixel of closeness c sits on the ray from the nominal eye E (winD in front of the window,
// optionally swayed by eyeOff) through its window position, at z = 1 / (invFar + c * P)
// window distances. The subject closeness `subj` lands exactly on the window plane.

#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>

layout(location = 0) out vec2 vUv;
layout(location = 1) out float vC;

layout (std140, set = 3, binding = 0) uniform MaterialUniform {
  vec4 geom;  // x: depth strength P, y: subject closeness, z: image aspect (w/h), w: window width (m)
  vec4 view;  // x: nominal eye distance winD (m), yz: virtual eye offset (window widths), w: depth bias
  vec4 tear;  // x: tear threshold, y: tear on (1) / off (0)
} g_MaterialUniform;

void main() {
  App2VertexUnpacked app = getApp2VertexUnpacked();

  float P = g_MaterialUniform.geom.x;
  float subj = g_MaterialUniform.geom.y;
  float aspect = g_MaterialUniform.geom.z;
  float winW = g_MaterialUniform.geom.w;
  float winD = g_MaterialUniform.view.x;
  vec2 eyeOff = g_MaterialUniform.view.yz;
  float bias = g_MaterialUniform.view.w;

  vec2 flatPos = app.position.xy;
  float c = app.position.z;

  vUv = vec2(flatPos.x / winW + 0.5, 0.5 - flatPos.y * aspect / winW);
  vC = c;

  float invFar = 1.0 - subj * P;
  float z = bias / (invFar + c * P);
  vec3 E = vec3(eyeOff * winW, 0.0);
  vec3 wp = vec3(flatPos, -winD);
  vec3 p = E + (wp - E) * z;
  p.z += winD;

  vec4 wPos4 = g_PrimitiveUniform.worldFromObject * vec4(p, 1.0f);
  gl_Position = getClipFromWorld() * wPos4;
  postprocessPosition(gl_Position);
}
