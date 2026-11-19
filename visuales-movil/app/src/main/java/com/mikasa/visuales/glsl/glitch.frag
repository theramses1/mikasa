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
  vec2 uv = vUv;
  float t = floor(uTime * 15.0);
  float amt = uIntensity * (0.15 + 1.2 * uBeat + 0.4 * uHigh);
  float rows = mix(10.0, 60.0, hash(vec2(t, 7.0)));
  float row = floor(uv.y * rows);
  if (hash(vec2(row, t)) < 0.35 * amt) uv.x += (hash(vec2(row, t + 1.0)) - 0.5) * 0.25 * amt;
  vec2 g = vec2(16.0, 9.0);
  vec2 cell = floor(uv * g);
  float sh = 0.015 * amt;
  vec3 col = vec3(cam(uv + vec2(sh, 0.0)).r, cam(uv).g, cam(uv - vec2(sh, 0.0)).b);
  if (hash(cell + vec2(t * 0.37, t)) < 0.12 * amt)
    col = texture(uPrev, uv + vec2(0.0, 0.01 * hash(cell))).rgb;
  if (hash(cell + t) < 0.03 * amt) col = 1.0 - col;
  float lv = mix(256.0, 4.0, uBeat * uIntensity);
  col = floor(col * lv) / lv;
  col *= 0.88 + 0.12 * sin(vUv.y * uRes.y * 1.2 + uTime * 20.0);
  return col;
}
void main() {
  fragColor = vec4(clamp(effect(), 0.0, 1.0), 1.0);
}