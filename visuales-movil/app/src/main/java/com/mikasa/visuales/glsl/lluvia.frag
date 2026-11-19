#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uCam;     // imagen de la cámara ya derecha (a resolución de render)
uniform sampler2D uPrev;    // frame anterior (para feedback / estelas)
uniform vec2  uRes;         // resolución de render
uniform float uTime;
uniform float uBass, uMid, uHigh, uLevel; // bandas de audio 0..1 (aprox.)
uniform float uBeat;        // envolvente del golpe: 1 en el beat y decae
uniform float uIntensity;   // 0..1, control del usuario
uniform float uHue;         // 0..1, color base

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

vec3 cam(vec2 uv) { return texture(uCam, uv).rgb; }
vec2 mirrorWrap(vec2 q) { return 1.0 - abs(1.0 - mod(q, 2.0)); }

vec3 hsv2rgb(vec3 c) {
  vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
  return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
vec3 hueShift(vec3 c, float a) {
  const vec3 k = vec3(0.57735);
  float ca = cos(a);
  return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}
float hash(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}
mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
vec3 effect() {
  vec2 grid = vec2(80.0, floor(80.0 * uRes.y / uRes.x * 1.4));
  vec2 g = vUv * grid;
  vec2 id = floor(g);
  vec2 f = fract(g);
  float speed = (0.5 + hash(vec2(id.x, 1.0)) * 1.5) * (0.3 + 0.5 * uIntensity);
  float trail = pow(fract(id.y / grid.y * 1.5 + uTime * speed + hash(vec2(id.x, 7.0))), 6.0);
  float l = dot(cam((id + 0.5) / grid), LUMA);
  vec2 sub = floor(f * vec2(3.0, 5.0));
  float glyph = step(0.45, hash(id * 7.13 + sub + floor(uTime * (2.0 + 6.0 * hash(id)))));
  glyph *= step(0.12, f.x) * step(0.08, f.y);
  float bright = glyph * (l * 1.4 * (0.35 + trail * 1.2) + trail * 0.25) * (1.0 + uBeat * uIntensity);
  vec3 tint = mix(vec3(0.0, 1.0, 0.35), hsv2rgb(vec3(uHue, 0.9, 1.0)), 0.3);
  return tint * bright + vec3(trail * l * glyph) * 0.4;
}
void main() {
  fragColor = vec4(clamp(effect(), 0.0, 1.0), 1.0);
}