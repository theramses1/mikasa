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
float L(vec2 uv) { return dot(cam(uv), LUMA); }
vec3 effect() {
  vec2 px = 1.5 / uRes;
  float tl = L(vUv + px * vec2(-1, 1)), t = L(vUv + px * vec2(0, 1)), tr = L(vUv + px * vec2(1, 1));
  float l  = L(vUv + px * vec2(-1, 0)),                                r  = L(vUv + px * vec2(1, 0));
  float bl = L(vUv + px * vec2(-1,-1)), b = L(vUv + px * vec2(0,-1)), br = L(vUv + px * vec2(1,-1));
  float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
  float gy = -bl - 2.0 * b - br + tl + 2.0 * t + tr;
  float e = length(vec2(gx, gy)) * (1.0 + 2.5 * uHigh * uIntensity);
  e = smoothstep(0.08, 0.5, e);
  vec3 hue = hsv2rgb(vec3(uHue + vUv.y * 0.25 + uTime * 0.05 + uMid * 0.3, 0.9, 1.0));
  vec3 col = hue * e * (1.2 + 2.0 * uBeat * uIntensity);
  vec3 prev = texture(uPrev, (vUv - 0.5) * (0.995 - 0.015 * uBass) + 0.5).rgb;
  col = max(col, prev * (0.80 + 0.13 * uIntensity) - 0.004);
  col += cam(vUv) * 0.10 * (1.0 - uIntensity);
  return col;
}
void main() {
  fragColor = vec4(clamp(effect(), 0.0, 1.0), 1.0);
}