// Shaders GLSL (WebGL2) de los efectos.
// Cada efecto define `vec3 effect()`; HEADER y MAIN se concatenan alrededor.

const VERT = `#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
  vUv = aPos * 0.5 + 0.5;
  gl_Position = vec4(aPos, 0.0, 1.0);
}`;

const HEADER = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uCam;     // cámara en vivo
uniform sampler2D uPrev;    // frame anterior (para feedback / estelas)
uniform vec2  uRes;         // resolución de salida
uniform vec2  uCamRes;      // resolución de la cámara
uniform float uTime;
uniform float uBass, uMid, uHigh, uLevel; // bandas de audio 0..1 (aprox.)
uniform float uBeat;        // envolvente del golpe: 1 en el beat y decae
uniform float uIntensity;   // 0..1, control del operador
uniform float uHue;         // 0..1, color base
uniform float uMirror;      // 1 = espejo horizontal

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

// Ajuste "cover": llena la salida sin deformar la cámara.
vec2 camUv(vec2 uv) {
  float ra = uRes.x / uRes.y;
  float rc = uCamRes.x / max(uCamRes.y, 1.0);
  vec2 s = ra > rc ? vec2(1.0, rc / ra) : vec2(ra / rc, 1.0);
  uv = (uv - 0.5) * s + 0.5;
  if (uMirror > 0.5) uv.x = 1.0 - uv.x;
  return uv;
}
vec3 cam(vec2 uv) { return texture(uCam, camUv(uv)).rgb; }

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
`;

const MAIN = `
void main() {
  fragColor = vec4(clamp(effect(), 0.0, 1.0), 1.0);
}`;

const EFFECTS = [
  {
    id: 'pulso',
    name: 'Pulso RGB',
    desc: 'Zoom con el bombo, separación cromática con los graves y destello en cada golpe.',
    frag: `
vec3 effect() {
  vec2 c = vUv - 0.5;
  c *= 1.0 - (0.10 * uBeat + 0.05 * uBass) * uIntensity;
  float sh = (0.003 + 0.035 * uBass + 0.02 * uBeat) * uIntensity;
  vec2 dir = normalize(c + 1e-5) * length(c) * 2.0;
  vec3 col;
  col.r = cam(c + 0.5 + dir * sh).r;
  col.g = cam(c + 0.5).g;
  col.b = cam(c + 0.5 - dir * sh).b;
  vec3 tint = hsv2rgb(vec3(uHue + uTime * 0.03, 0.7, 1.0));
  col = mix(col, col * tint * 1.4, 0.35 * uMid * uIntensity);
  col *= 1.0 + 0.7 * uBeat * uIntensity;
  col *= 1.0 - 0.6 * dot(c, c) * (1.0 - 0.5 * uLevel);   // viñeta
  return col;
}`,
  },
  {
    id: 'caleido',
    name: 'Caleidoscopio',
    desc: 'Espejos radiales que giran con los medios; los graves "respiran" el centro.',
    frag: `
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
  vec2 q = vec2(cos(a), sin(a)) * r + 0.5
         + 0.08 * vec2(sin(uTime * 0.21), cos(uTime * 0.17));
  q = 1.0 - abs(1.0 - mod(q, 2.0));          // repetición en espejo
  vec3 col = cam(q);
  col = mix(col, hueShift(col, uHue * 6.2831 + r * 4.0 * uHigh), 0.6 * uIntensity);
  col *= 1.0 + 0.6 * uBeat * uIntensity;
  col *= smoothstep(1.3, 0.25, r);
  return col;
}`,
  },
  {
    id: 'neon',
    name: 'Bordes Neón',
    desc: 'Detecta siluetas (Sobel) y las dibuja en neón con estela; los agudos encienden las líneas.',
    frag: `
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
}`,
  },
  {
    id: 'estela',
    name: 'Estela Psicodélica',
    desc: 'Feedback de video: cada frame deja rastro que gira, hace zoom y cambia de color con la música.',
    frag: `
vec3 effect() {
  float aspect = uRes.x / uRes.y;
  vec2 c = vUv - 0.5;
  c.x *= aspect;
  float ang = 0.004 + 0.035 * uMid * uIntensity * sin(uTime * 0.4);
  float z = 0.992 - 0.04 * uBass * uIntensity;
  c = rot(ang) * c * z;
  c.x /= aspect;
  vec3 prev = texture(uPrev, c + 0.5).rgb;
  prev = hueShift(prev, 0.03 + 0.2 * uHigh);
  vec3 now = cam(vUv);
  float decay = 0.86 + 0.12 * uIntensity;
  vec3 col = max(prev * decay - 0.003, now * (0.85 + 0.6 * uBeat));
  return mix(col, now, 0.12);
}`,
  },
  {
    id: 'glitch',
    name: 'Glitch / Datamosh',
    desc: 'Cortes horizontales, bloques congelados, RGB desfasado y posterizado en cada golpe.',
    frag: `
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
    col = texture(uPrev, uv + vec2(0.0, 0.01 * hash(cell))).rgb;   // bloque "moshed"
  if (hash(cell + t) < 0.03 * amt) col = 1.0 - col;
  float lv = mix(256.0, 4.0, uBeat * uIntensity);
  col = floor(col * lv) / lv;
  col *= 0.88 + 0.12 * sin(vUv.y * uRes.y * 1.2 + uTime * 20.0);   // scanlines
  return col;
}`,
  },
];

// Pase final a pantalla: blackout/fundido y strobe (no se retroalimenta).
const COPY_FRAG = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;
uniform float uMaster;
uniform float uFlash;
void main() {
  vec3 c = texture(uTex, vUv).rgb + uFlash;
  fragColor = vec4(clamp(c, 0.0, 1.0) * uMaster, 1.0);
}`;
