// Motor WebGL2:
//   1) sube hasta 3 cámaras a la GPU,
//   2) las mezcla según el modo (una / dividida / imagen-en-imagen / superposición),
//   3) aplica el efecto con feedback (ping-pong de framebuffers),
//   4) dibuja en el canvas con blackout y strobe.

const MAX_CAMS = 3;

class Renderer {
  constructor(canvas) {
    this.canvas = canvas;
    const gl = canvas.getContext('webgl2', { antialias: false, alpha: false, preserveDrawingBuffer: false });
    if (!gl) throw new Error('Este navegador no soporta WebGL2. Usa Chrome o Edge actualizados.');
    this.gl = gl;
    this.floatFb = !!gl.getExtension('EXT_color_buffer_float');

    // Triángulo que cubre toda la pantalla.
    this.vbo = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);

    this.programs = EFFECTS.map((e) => this._program(HEADER + e.frag + MAIN, e.name));
    this.composite = this._program(COMPOSITE_FRAG, 'mezclador');
    this.copy = this._program(COPY_FRAG, 'copy');

    this.camTex = [];
    this.camRes = [];
    for (let i = 0; i < MAX_CAMS; i++) {
      const t = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, t);
      this._texParams();
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 255]));
      this.camTex.push(t);
      this.camRes.push([16, 9]);
    }

    // Capa de letras (canvas 2D transparente).
    this.lyrTex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, this.lyrTex);
    this._texParams();
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 0]));

    this.fbos = [];
    this.comp = null;
    this.read = 0;
    this.resize(canvas.width, canvas.height);
  }

  _texParams() {
    const gl = this.gl;
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  }

  _program(fragSrc, name) {
    const gl = this.gl;
    const compile = (type, src) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
        throw new Error(`Error compilando shader "${name}":\n${gl.getShaderInfoLog(s)}`);
      }
      return s;
    };
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, VERT));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fragSrc));
    gl.bindAttribLocation(p, 0, 'aPos');
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      throw new Error(`Error enlazando "${name}": ${gl.getProgramInfoLog(p)}`);
    }
    const uniforms = {};
    const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
    for (let i = 0; i < n; i++) {
      const info = gl.getActiveUniform(p, i);
      uniforms[info.name] = gl.getUniformLocation(p, info.name);
    }
    return { p, uniforms };
  }

  _target(w, h, float) {
    const gl = this.gl;
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    this._texParams();
    if (float) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, w, h, 0, gl.RGBA, gl.HALF_FLOAT, null);
    else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    const fb = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    gl.clearColor(0, 0, 0, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);
    return { fb, tex };
  }

  resize(w, h) {
    const gl = this.gl;
    this.canvas.width = w;
    this.canvas.height = h;
    [...this.fbos, this.comp].filter(Boolean).forEach((f) => { gl.deleteFramebuffer(f.fb); gl.deleteTexture(f.tex); });
    this.fbos = [this._target(w, h, this.floatFb), this._target(w, h, this.floatFb)];
    this.comp = this._target(w, h, false);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  // Sube el frame de la cámara `slot` (un <video> o <canvas>).
  uploadSource(slot, source, w, h) {
    if (!source || !w || !h) return false;
    const gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, this.camTex[slot]);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    this.camRes[slot] = [w, h];
    return true;
  }

  uploadLyrics(canvas) {
    const gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, this.lyrTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);   // el canvas 2D tiene el origen arriba
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, canvas);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
  }

  _draw(fb) {
    const gl = this.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  // u: parámetros de audio/control. mix: { order:[slots], sel, prevSel, fade, mode, mirror }
  render(effectIndex, u, mix) {
    const gl = this.gl;
    const w = this.canvas.width, h = this.canvas.height;
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.viewport(0, 0, w, h);

    // 1) Mezclador de cámaras -> comp
    {
      const P = this.composite, U = P.uniforms;
      gl.useProgram(P.p);
      for (let i = 0; i < MAX_CAMS; i++) {
        gl.activeTexture(gl.TEXTURE0 + i);
        gl.bindTexture(gl.TEXTURE_2D, this.camTex[i]);
        gl.uniform1i(U[`uC${i}`], i);
        gl.uniform2f(U[`uR${i}`], this.camRes[i][0], this.camRes[i][1]);
      }
      const o = mix.order;
      gl.uniform2f(U.uRes, w, h);
      gl.uniform3f(U.uOrder, o[0] ?? 0, o[1] ?? 0, o[2] ?? 0);
      gl.uniform1f(U.uN, Math.max(1, o.length));
      gl.uniform1f(U.uMode, mix.mode);
      gl.uniform1f(U.uSel, mix.sel);
      gl.uniform1f(U.uPrevSel, mix.prevSel);
      gl.uniform1f(U.uMix, mix.fade);
      gl.uniform1f(U.uMirror, mix.mirror ? 1 : 0);
      this._draw(this.comp.fb);
    }

    // 2) Efecto -> framebuffer (lee el frame anterior para feedback).
    const src = this.fbos[this.read];
    const dst = this.fbos[1 - this.read];
    {
      const P = this.programs[effectIndex], U = P.uniforms;
      gl.useProgram(P.p);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, this.comp.tex);
      gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, src.tex);
      const set1 = (name, v) => { if (U[name]) gl.uniform1f(U[name], v); };
      if (U.uCam) gl.uniform1i(U.uCam, 0);
      if (U.uPrev) gl.uniform1i(U.uPrev, 1);
      if (U.uRes) gl.uniform2f(U.uRes, w, h);
      set1('uTime', u.time);
      set1('uBass', u.bass); set1('uMid', u.mid); set1('uHigh', u.high); set1('uLevel', u.level);
      set1('uBeat', u.beat);
      set1('uIntensity', u.intensity);
      set1('uHue', u.hue);
      this._draw(dst.fb);
    }

    // 3) Framebuffer -> pantalla con blackout y strobe.
    {
      const U = this.copy.uniforms;
      gl.useProgram(this.copy.p);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, dst.tex);
      gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, this.lyrTex);
      gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, this.comp.tex);
      gl.uniform1i(U.uTex, 0);
      gl.uniform1i(U.uLyr, 1);
      gl.uniform1i(U.uComp, 2);
      gl.uniform2f(U.uRes, w, h);
      gl.uniform1f(U.uMaster, u.master);
      gl.uniform1f(U.uFlash, u.flash);
      gl.uniform1f(U.uLyrOn, u.lyrOn ? 1 : 0);
      gl.uniform1f(U.uLyrFx, u.lyrFx || 0);
      gl.uniform1f(U.uTime, u.time);
      gl.uniform1f(U.uBass, u.bass);
      gl.uniform1f(U.uBeat, u.beat);
      gl.uniform1f(U.uHue, u.hue);
      this._draw(null);
    }

    this.read = 1 - this.read;
  }
}
