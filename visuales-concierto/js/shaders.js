// Shaders GLSL (WebGL2).
// Flujo: cámaras -> COMPOSITE (mezclador de hasta 3 cámaras) -> efecto -> COPY (blackout/strobe) -> pantalla.
// Cada efecto define `vec3 effect()`; HEADER y MAIN se concatenan alrededor.

const VERT = `#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
  vUv = aPos * 0.5 + 0.5;
  gl_Position = vec4(aPos, 0.0, 1.0);
}`;

// ---------- Mezclador de cámaras ----------
const COMPOSITE_FRAG = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uC0, uC1, uC2;   // una textura por cámara
uniform vec2 uR0, uR1, uR2;        // resolución de cada cámara
uniform vec2 uRes;
uniform vec3 uOrder;               // ranuras activas, en orden
uniform float uN;                  // cuántas cámaras activas (1..3)
uniform float uMode;               // 0 una, 1 dividida, 2 imagen-en-imagen, 3 superposición
uniform float uSel, uPrevSel, uMix; // cámara al aire, anterior y fundido entre ambas
uniform float uMirror;

vec2 cover(vec2 uv, float ta, vec2 r) {
  float rc = r.x / max(r.y, 1.0);
  vec2 s = ta > rc ? vec2(1.0, rc / ta) : vec2(ta / rc, 1.0);
  return (uv - 0.5) * s + 0.5;
}
vec3 camS(float slot, vec2 uv, float ta) {
  vec2 r = slot < 0.5 ? uR0 : (slot < 1.5 ? uR1 : uR2);
  vec2 q = cover(uv, ta, r);
  if (uMirror > 0.5) q.x = 1.0 - q.x;
  if (slot < 0.5) return texture(uC0, q).rgb;
  if (slot < 1.5) return texture(uC1, q).rgb;
  return texture(uC2, q).rgb;
}
float slotAt(float i) { return i < 0.5 ? uOrder.x : (i < 1.5 ? uOrder.y : uOrder.z); }

void main() {
  float A = uRes.x / uRes.y;
  vec3 mainCam = mix(camS(uPrevSel, vUv, A), camS(uSel, vUv, A), uMix);
  vec3 col = mainCam;
  if (uMode > 0.5 && uN > 1.5) {
    if (uMode < 1.5) {                       // pantalla dividida en columnas
      float i = min(floor(vUv.x * uN), uN - 1.0);
      vec2 l = vec2(fract(vUv.x * uN), vUv.y);
      col = camS(slotAt(i), l, A / uN);
      if (i > 0.5 && l.x * uRes.x / uN < 3.0) col = vec3(0.0);
    } else if (uMode < 2.5) {                // imagen en imagen
      float w = 0.26;
      float bh = w * A / 1.7778;
      float k = 0.0;
      for (int j = 0; j < 3; j++) {
        if (float(j) >= uN) break;
        float s = slotAt(float(j));
        if (abs(s - uSel) < 0.5) continue;
        vec2 o = vec2(1.0 - 0.02 - w, 0.03 + k * (bh + 0.02));
        vec2 l = (vUv - o) / vec2(w, bh);
        if (all(greaterThanEqual(l, vec2(0.0))) && all(lessThanEqual(l, vec2(1.0)))) {
          col = camS(s, l, 1.7778);
          vec2 e = min(l, 1.0 - l) * vec2(w, bh) * uRes;
          if (min(e.x, e.y) < 3.0) col = vec3(1.0);
        }
        k += 1.0;
      }
    } else {                                 // superposición (mezcla "screen")
      vec3 acc = vec3(0.0);
      for (int j = 0; j < 3; j++) {
        if (float(j) >= uN) break;
        acc = 1.0 - (1.0 - acc) * (1.0 - camS(slotAt(float(j)), vUv, A) * 0.8);
      }
      col = acc;
    }
  }
  fragColor = vec4(col, 1.0);
}`;

// ---------- Cabecera común de los efectos ----------
const HEADER = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uCam;     // imagen de cámaras ya mezclada (a resolución de salida)
uniform sampler2D uPrev;    // frame anterior (para feedback / estelas)
uniform vec2  uRes;         // resolución de salida
uniform float uTime;
uniform float uBass, uMid, uHigh, uLevel; // bandas de audio 0..1 (aprox.)
uniform float uBeat;        // envolvente del golpe: 1 en el beat y decae
uniform float uIntensity;   // 0..1, control del operador
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
`;

const MAIN = `
void main() {
  fragColor = vec4(clamp(effect(), 0.0, 1.0), 1.0);
}`;

const EFFECTS = [
  // ---------------- 1-5 ----------------
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
  col *= 1.0 - 0.6 * dot(c, c) * (1.0 - 0.5 * uLevel);
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
  // El origen del espejo recorre la imagen para no quedarse en una zona vacía.
  vec2 q = vec2(cos(a), sin(a)) * r * 1.4 + 0.5 + 0.22 * vec2(sin(uTime * 0.13), cos(uTime * 0.11));
  vec3 col = cam(mirrorWrap(q));
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
    col = texture(uPrev, uv + vec2(0.0, 0.01 * hash(cell))).rgb;
  if (hash(cell + t) < 0.03 * amt) col = 1.0 - col;
  float lv = mix(256.0, 4.0, uBeat * uIntensity);
  col = floor(col * lv) / lv;
  col *= 0.88 + 0.12 * sin(vUv.y * uRes.y * 1.2 + uTime * 20.0);
  return col;
}`,
  },

  // ---------------- 6-10 ----------------
  {
    id: 'espejo4',
    name: 'Espejo Cuádruple',
    desc: 'La imagen se refleja en cuatro cuadrantes que giran; los graves acercan el centro.',
    frag: `
vec3 effect() {
  vec2 c = (vUv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  c = abs(c);
  c = rot(uTime * 0.1 * uIntensity + uMid * 0.4 * uIntensity) * c;
  c = abs(c);
  float z = 1.0 - 0.35 * uBass * uIntensity;
  vec3 col = cam(mirrorWrap(c * 1.6 * z));
  col *= 1.0 + 0.6 * uBeat * uIntensity;
  return col;
}`,
  },
  {
    id: 'tunel',
    name: 'Túnel',
    desc: 'La cámara se envuelve en un túnel infinito; viaja más rápido con la música.',
    frag: `
vec3 effect() {
  vec2 p = (vUv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  float r = length(p) + 1e-3;
  float a = atan(p.y, p.x);
  vec2 q = vec2(a / 6.2831853 + uTime * 0.04 + uMid * 0.1 * uIntensity,
                0.25 / r + uTime * (0.25 + 0.4 * uIntensity) + uBass * 0.15);
  vec3 col = cam(mirrorWrap(q * vec2(2.0, 1.0)));
  col = mix(col, hueShift(col, r * 3.0 + uHue * 6.2831), 0.4 * uIntensity);
  col *= smoothstep(0.0, 0.35, r) * (1.0 + 0.6 * uBeat * uIntensity);
  return col;
}`,
  },
  {
    id: 'semitono',
    name: 'Semitono',
    desc: 'Puntos de imprenta: el tamaño de cada punto sigue la luz de la imagen y late con los graves.',
    frag: `
vec3 effect() {
  float cells = mix(130.0, 45.0, uIntensity) * (1.0 - 0.3 * uBeat * uIntensity);
  vec2 grid = vec2(cells, cells * uRes.y / uRes.x);
  vec2 g = vUv * grid;
  vec2 f = fract(g) - 0.5;
  vec3 c = cam((floor(g) + 0.5) / grid);
  float l = dot(c, LUMA);
  float rad = sqrt(l) * 0.55 * (1.0 + 0.4 * uBass);
  float d = smoothstep(rad, rad - 0.08, length(f));
  vec3 ink = mix(c, hsv2rgb(vec3(uHue + uTime * 0.02, 0.8, 1.0)), 0.5 * uIntensity);
  return ink * d * 1.3;
}`,
  },
  {
    id: 'pixeles',
    name: 'Píxeles',
    desc: 'Mosaico retro de baja resolución; los píxeles se agrandan en cada golpe.',
    frag: `
vec3 effect() {
  float size = 4.0 + 70.0 * uIntensity * (0.15 + 0.7 * uBeat + 0.3 * uBass);
  vec2 px = vec2(size) / uRes;
  vec3 col = cam((floor(vUv / px) + 0.5) * px);
  col = floor(col * 6.0) / 6.0;
  vec2 f = fract(vUv / px);
  col *= 0.8 + 0.2 * step(0.08, f.x) * step(0.08, f.y);
  return col;
}`,
  },
  {
    id: 'termica',
    name: 'Visión Térmica',
    desc: 'Paleta de cámara térmica; el volumen general "calienta" la imagen.',
    frag: `
vec3 thermal(float t) {
  vec3 c = mix(vec3(0.0, 0.0, 0.2), vec3(0.5, 0.0, 0.8), smoothstep(0.0, 0.25, t));
  c = mix(c, vec3(1.0, 0.0, 0.0), smoothstep(0.25, 0.5, t));
  c = mix(c, vec3(1.0, 0.8, 0.0), smoothstep(0.5, 0.75, t));
  return mix(c, vec3(1.0), smoothstep(0.75, 1.0, t));
}
vec3 effect() {
  float l = dot(cam(vUv), LUMA);
  l = clamp(l * (0.8 + 0.6 * uLevel * uIntensity) + 0.2 * uBeat * uIntensity, 0.0, 1.0);
  return hueShift(thermal(l), (uHue - 0.8) * 6.2831);
}`,
  },

  // ---------------- 11-15 ----------------
  {
    id: 'liquido',
    name: 'Ondas Líquidas',
    desc: 'La imagen ondula como agua; los graves agrandan las olas y los agudos las rizan.',
    frag: `
vec3 effect() {
  vec2 uv = vUv;
  float amp = (0.004 + 0.03 * uBass + 0.015 * uBeat) * uIntensity;
  uv.x += sin(uv.y * 12.0 + uTime * 2.0) * amp + sin(uv.y * 31.0 - uTime * 3.0) * amp * 0.5 * uHigh;
  uv.y += cos(uv.x * 10.0 + uTime * 1.7) * amp;
  vec3 col = cam(uv);
  return mix(col, hueShift(col, sin(uv.y * 6.0 + uTime) * 1.5 * uMid), 0.5 * uIntensity);
}`,
  },
  {
    id: 'multi',
    name: 'Multiplicador',
    desc: 'Cuadrícula de copias, cada una con su color; una se ilumina al ritmo.',
    frag: `
vec3 effect() {
  float n = floor(2.0 + 3.0 * uIntensity);
  vec2 g = vUv * n;
  vec2 id = floor(g);
  vec2 f = fract(g);
  vec3 col = cam(f);
  float i = id.x + id.y * n;
  col = hueShift(col, i * 0.7 + uHue * 6.2831);
  float pick = floor(hash(vec2(floor(uTime * 4.0), 3.0)) * n * n);
  if (abs(i - pick) < 0.5) col *= 1.0 + 1.5 * uBeat;
  col *= 0.4 + 0.6 * step(0.015, f.x) * step(0.015, f.y);
  return col;
}`,
  },
  {
    id: 'popart',
    name: 'Pop Art',
    desc: 'Cuatro copias en serigrafía de colores planos que cambian con la música.',
    frag: `
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
}`,
  },
  {
    id: 'onda',
    name: 'Onda Expansiva',
    desc: 'Cada golpe lanza un anillo que deforma la imagen desde el centro.',
    frag: `
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
}`,
  },
  {
    id: 'vortice',
    name: 'Vórtice',
    desc: 'Remolino que retuerce el centro de la imagen con los graves.',
    frag: `
vec3 effect() {
  float A = uRes.x / uRes.y;
  vec2 p = (vUv - 0.5) * vec2(A, 1.0);
  float r = length(p);
  float twist = (1.5 * uBass + 0.8 * uBeat + 0.3 * sin(uTime * 0.5)) * uIntensity;
  float a = twist * smoothstep(0.8, 0.0, r);
  p = rot(a) * p;
  vec3 col = cam(p / vec2(A, 1.0) + 0.5);
  return mix(col, hueShift(col, a * 2.0), 0.5);
}`,
  },

  // ---------------- 16-20 ----------------
  {
    id: 'rayos',
    name: 'Rayos de Luz',
    desc: 'Las zonas brillantes (focos, instrumentos) lanzan rayos; se alargan con el volumen.',
    frag: `
vec3 effect() {
  vec2 c = vec2(0.5) + 0.1 * vec2(sin(uTime * 0.3), cos(uTime * 0.23));
  vec2 d = vUv - c;
  float len = (0.15 + 0.35 * uLevel + 0.2 * uBeat) * uIntensity;
  vec3 acc = vec3(0.0);
  float wsum = 0.0;
  for (int i = 0; i < 24; i++) {
    float t = float(i) / 24.0;
    vec3 s = cam(c + d * (1.0 - len * t));
    s *= smoothstep(0.45, 0.9, dot(s, LUMA));
    float w = 1.0 - t;
    acc += s * w;
    wsum += w;
  }
  vec3 rays = acc / wsum * hsv2rgb(vec3(uHue, 0.4, 1.0)) * 2.5;
  return cam(vUv) * 0.7 + rays;
}`,
  },
  {
    id: 'lluvia',
    name: 'Lluvia Digital',
    desc: 'La imagen se dibuja con columnas de símbolos verdes que caen.',
    frag: `
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
}`,
  },
  {
    id: 'solar',
    name: 'Solarizado',
    desc: 'Los tonos se invierten por encima de un umbral que se mueve con la música; negativo en golpes fuertes.',
    frag: `
vec3 effect() {
  vec3 c = cam(vUv);
  float th = 0.5 + 0.3 * sin(uTime * 0.7) - 0.25 * uBass * uIntensity;
  vec3 s = mix(c, 1.0 - c, step(th, c));
  s = hueShift(s, uHue * 6.2831 + uTime * 0.3 * uIntensity + uMid * 2.0);
  s = mix(c, s * 1.3, uIntensity);
  return mix(s, 1.0 - s, step(0.75, uBeat) * uIntensity);
}`,
  },
  {
    id: 'infinito',
    name: 'Cuadro Infinito',
    desc: 'Efecto Droste: la imagen contiene una copia de sí misma, una y otra vez, girando.',
    frag: `
vec3 effect() {
  vec2 c = vUv - 0.5;
  float s = 0.82 - 0.12 * uBass * uIntensity;
  float a = 0.03 * sin(uTime * 0.4) + 0.06 * uMid * uIntensity;
  vec3 now = cam(vUv);
  if (all(lessThan(abs(c), vec2(0.5 * s)))) {
    vec3 prev = texture(uPrev, rot(a) * (c / s) + 0.5).rgb;
    prev = hueShift(prev, 0.08 + 0.25 * uHigh * uIntensity);
    vec2 e = (0.5 * s - abs(c)) * uRes;
    vec3 frame = hsv2rgb(vec3(uHue + uTime * 0.05, 0.8, 1.0)) * (0.6 + uBeat);
    return min(e.x, e.y) < 3.0 ? frame : mix(now, prev, 0.85);
  }
  return now;
}`,
  },
  {
    id: 'topo',
    name: 'Curvas de Nivel',
    desc: 'La imagen se convierte en un mapa topográfico de líneas de colores que fluyen.',
    frag: `
vec3 effect() {
  float l = dot(cam(vUv), LUMA);
  float bands = mix(6.0, 20.0, uIntensity);
  float v = l * bands - uTime * (0.5 + 2.0 * uMid);
  float dist = min(fract(v), 1.0 - fract(v));
  float line = smoothstep(0.12 + 0.12 * uBeat, 0.0, dist);
  vec3 hue = hsv2rgb(vec3(uHue + floor(v) / bands * 0.5, 0.8, 1.0));
  return hue * line * (1.0 + 0.5 * uHigh) + hue * 0.12 * l;
}`,
  },
];

// Pase final a pantalla: capa de letras con sus 5 efectos, strobe y blackout (no se retroalimenta).
const COPY_FRAG = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;     // escena con el efecto
uniform sampler2D uLyr;     // capa de letras (blanco/gris sobre transparente)
uniform sampler2D uComp;    // cámaras sin efecto (para "video en el texto")
uniform vec2 uRes;
uniform float uMaster, uFlash;
uniform float uLyrOn, uLyrFx, uTime, uBass, uBeat, uHue;

float A(vec2 uv) { return texture(uLyr, uv).a; }
float blurA(vec2 uv, float r) {
  vec2 px = r / uRes;
  float s = 0.0;
  for (int i = 0; i < 12; i++) {
    float a = float(i) * 0.5235988;
    vec2 o = vec2(cos(a), sin(a));
    s += A(uv + o * px) + A(uv + o * px * 0.5);
  }
  return s / 24.0;
}
vec3 hsv2rgb(vec3 c) {
  vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
  return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }

void main() {
  vec3 scene = texture(uTex, vUv).rgb;
  if (uLyrOn > 0.5) {
    vec2 uv = vUv;
    float t = uTime;
    int fx = int(uLyrFx + 0.5);
    if (fx == 2) {                                   // Ola: el texto ondula con los graves
      uv.x += sin(uv.y * 25.0 + t * 4.0) * 0.004 * (1.0 + 3.0 * uBass);
      uv.y += sin(uv.x * 18.0 + t * 3.0) * 0.003 * (1.0 + 2.0 * uBass);
    } else if (fx == 3) {                            // Glitch: franjas desplazadas en los golpes
      float row = floor(uv.y * 40.0), tt = floor(t * 20.0);
      if (hash(vec2(row, tt)) < 0.12 + 0.6 * uBeat) uv.x += (hash(vec2(row, tt + 1.0)) - 0.5) * 0.08 * (0.3 + uBeat);
    }
    vec4 L = texture(uLyr, uv);
    float a = L.a;
    float shade = blurA(uv, 10.0);
    scene *= 1.0 - 0.55 * shade;                     // sombra para que se lea sobre cualquier imagen
    vec3 hue = hsv2rgb(vec3(uHue + t * 0.03, 0.8, 1.0));
    vec3 col = L.rgb;
    if (fx == 0) {                                   // Neón: halo de color que respira con los graves
      float glow = blurA(uv, 8.0 + 14.0 * uBass);
      scene += hue * glow * (1.0 + 1.5 * uBeat);
      col = mix(L.rgb, hue + 0.35, 0.45) * L.rgb;
    } else if (fx == 1) {                            // Cromático: canales RGB separados con el ritmo
      float d = (2.0 + 16.0 * uBass + 12.0 * uBeat) / uRes.x;
      float ar = A(uv + vec2(d, 0.0)), ab = A(uv - vec2(d, 0.0));
      float b = max(dot(L.rgb, vec3(0.333)), 0.5);
      scene = scene * (1.0 - max(a, max(ar, ab))) + vec3(ar, a, ab) * b;
      a = 0.0;
    } else if (fx == 2) {                            // Ola: degradado de color que fluye
      col = L.rgb * hsv2rgb(vec3(uHue + 0.12 * sin(uv.x * 6.0 + t * 2.0) + 0.1 * uv.y, 0.75, 1.0)) * (1.0 + 0.5 * uBeat);
    } else if (fx == 3) {                            // Glitch: parpadeo de colores
      float k = hash(vec2(floor(uv.y * 30.0), floor(t * 12.0)));
      col = L.rgb * mix(vec3(1.0), hsv2rgb(vec3(uHue + k, 0.9, 1.0)), step(0.7 - 0.4 * uBeat, k));
    } else {                                         // Video en el texto: las letras muestran las cámaras
      float edge = clamp(blurA(uv, 4.0) * 2.2 - a, 0.0, 1.0);
      scene *= 0.6;
      col = texture(uComp, uv).rgb * 1.5 * L.rgb + 0.05;
      scene = mix(scene, vec3(1.0), edge * 0.9);
    }
    scene = mix(scene, col, a);
  }
  fragColor = vec4(clamp(scene + uFlash, 0.0, 1.0) * uMaster, 1.0);
}`;
