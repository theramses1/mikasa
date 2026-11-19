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
  float A = uRes.x / uRes.y;
  vec2 p = (vUv - 0.5) * vec2(A, 1.0);
  float r = length(p);
  float ring = (1.0 - uBeat) * 1.2;
  float d = r - ring;
  float wave = exp(-d * d / 0.0064) * uBeat;
  vec2 dir = normalize(p + 1e-5);
  vec3 col = cam(vUv - dir * wave * 0.08 * uIntensity / vec2(A, 1.0));
  col += hsv2rgb(vec3(uHue + r, 0.8, 1.0)) * wave * 0.6 * uIntensity;
  return col * (1.0 + 0.3 * uBass * uIntensity);
}
void main() {
  fragColor = vec4(clamp(effect(), 0.0, 1.0), 1.0);
}