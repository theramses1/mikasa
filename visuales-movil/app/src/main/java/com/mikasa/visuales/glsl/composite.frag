#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform samplerExternalOES uCamTex;
uniform mat4 uStMatrix;   // matriz de la SurfaceTexture
uniform float uRot;       // giros de 90° (horario) que necesita el sensor: 0..3
uniform vec2 uFlip;       // espejo en x / y (en coordenadas de pantalla)
uniform vec2 uBufRes;     // tamaño del frame ya girado (derecho)
uniform vec2 uRes;        // tamaño de salida

vec2 cover(vec2 uv, float ta, vec2 r) {
  float rc = r.x / max(r.y, 1.0);
  vec2 s = ta > rc ? vec2(1.0, rc / ta) : vec2(ta / rc, 1.0);
  return (uv - 0.5) * s + 0.5;
}

void main() {
  vec2 q = cover(vUv, uRes.x / uRes.y, uBufRes);
  if (uFlip.x > 0.5) q.x = 1.0 - q.x;
  if (uFlip.y > 0.5) q.y = 1.0 - q.y;
  // Imagen derecha (u, v) -> coordenadas del buffer del sensor (s, t).
  vec2 st;
  if (uRot < 0.5)      st = q;
  else if (uRot < 1.5) st = vec2(1.0 - q.y, q.x);
  else if (uRot < 2.5) st = vec2(1.0 - q.x, 1.0 - q.y);
  else                 st = vec2(q.y, 1.0 - q.x);
  vec2 tc = (uStMatrix * vec4(st, 0.0, 1.0)).xy;
  fragColor = vec4(texture(uCamTex, tc).rgb, 1.0);
}