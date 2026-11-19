#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;     // escena con el efecto
uniform sampler2D uLyr;     // capa de letras (blanco/gris sobre transparente; bitmap con el origen arriba)
uniform sampler2D uComp;    // cámara sin efecto (para "video en el texto")
uniform vec2 uScale;        // encuadre: <1 recorta, >1 deja bandas negras
uniform vec2 uRes;          // resolución de render
uniform float uFlash;
uniform float uLyrOn, uLyrFx, uTime, uBass, uBeat, uHue;

float A(vec2 uv) { return texture(uLyr, vec2(uv.x, 1.0 - uv.y)).a; }
vec4 LY(vec2 uv) { return texture(uLyr, vec2(uv.x, 1.0 - uv.y)); }
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
  vec2 base = (vUv - 0.5) * uScale + 0.5;
  if (any(lessThan(base, vec2(0.0))) || any(greaterThan(base, vec2(1.0)))) {
    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
    return;
  }
  vec3 scene = texture(uTex, base).rgb;
  if (uLyrOn > 0.5) {
    vec2 uv = base;
    float t = uTime;
    int fx = int(uLyrFx + 0.5);
    if (fx == 2) {                                   // Ola: el texto ondula con los graves
      uv.x += sin(uv.y * 25.0 + t * 4.0) * 0.004 * (1.0 + 3.0 * uBass);
      uv.y += sin(uv.x * 18.0 + t * 3.0) * 0.003 * (1.0 + 2.0 * uBass);
    } else if (fx == 3) {                            // Glitch: franjas desplazadas en los golpes
      float row = floor(uv.y * 40.0), tt = floor(t * 20.0);
      if (hash(vec2(row, tt)) < 0.12 + 0.6 * uBeat) uv.x += (hash(vec2(row, tt + 1.0)) - 0.5) * 0.08 * (0.3 + uBeat);
    }
    vec4 L = LY(uv);
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
    } else {                                         // Video en el texto: las letras muestran la cámara
      float edge = clamp(blurA(uv, 4.0) * 2.2 - a, 0.0, 1.0);
      scene *= 0.6;
      col = texture(uComp, uv).rgb * 1.5 * L.rgb + 0.05;
      scene = mix(scene, vec3(1.0), edge * 0.9);
    }
    scene = mix(scene, col, a);
  }
  fragColor = vec4(clamp(scene + uFlash, 0.0, 1.0), 1.0);
}