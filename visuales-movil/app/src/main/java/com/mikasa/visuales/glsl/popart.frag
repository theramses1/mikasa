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
  vec2 g = vUv * 2.0;
  vec2 id = floor(g);
  vec2 f = fract(g);
  float l = dot(cam(f), LUMA);
  l = smoothstep(0.15 - 0.1 * uBass, 0.75, l);
  float t = floor(l * 2.99) / 2.0;
  float h = uHue + (id.x + id.y * 2.0) * 0.25 + uTime * 0.02 * uIntensity + uBeat * 0.1;
  vec3 dark = hsv2rgb(vec3(h + 0.5, 0.8, 0.3));
  vec3 mid  = hsv2rgb(vec3(h, 0.9, 0.9));
  vec3 lite = hsv2rgb(vec3(h + 0.15, 0.4, 1.0));
  vec3 col = t < 0.25 ? dark : (t < 0.75 ? mid : lite);
  return col * (1.0 + 0.4 * uBeat * uIntensity);
}
void main() {
  fragColor = vec4(clamp(effect(), 0.0, 1.0), 1.0);
}