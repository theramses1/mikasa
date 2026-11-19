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
  vec2 p = (vUv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  float r = length(p);
  float a = atan(p.y, p.x);
  float seg = 2.0 * floor(3.0 + 3.0 * uIntensity);
  float k = 6.2831853 / seg;
  a += uTime * 0.15 + uMid * 0.8 * uIntensity;
  a = mod(a, k);
  a = abs(a - k * 0.5);
  r *= 1.1 - 0.4 * uBass * uIntensity;
  // El origen del espejo recorre la imagen para no quedarse en una zona vacía.
  vec2 q = vec2(cos(a), sin(a)) * r * 1.4 + 0.5 + 0.22 * vec2(sin(uTime * 0.13), cos(uTime * 0.11));
  vec3 col = cam(mirrorWrap(q));
  col = mix(col, hueShift(col, uHue * 6.2831 + r * 4.0 * uHigh), 0.6 * uIntensity);
  col *= 1.0 + 0.6 * uBeat * uIntensity;
  col *= smoothstep(1.3, 0.25, r);
  return col;
}
void main() {
  fragColor = vec4(clamp(effect(), 0.0, 1.0), 1.0);
}