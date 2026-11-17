// Motor WebGL2: sube el frame de cámara a la GPU, aplica el efecto con feedback
// (ping-pong de framebuffers) y dibuja el resultado en el canvas.

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
    this.copy = this._program(COPY_FRAG, 'copy');

    this.camTex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, this.camTex);
    this._texParams();
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 255]));

    this.fbos = [];
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

  resize(w, h) {
    const gl = this.gl;
    this.canvas.width = w;
    this.canvas.height = h;
    this.fbos.forEach((f) => { gl.deleteFramebuffer(f.fb); gl.deleteTexture(f.tex); });
    this.fbos = [0, 1].map(() => {
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      this._texParams();
      if (this.floatFb) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, w, h, 0, gl.RGBA, gl.HALF_FLOAT, null);
      else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      const fb = gl.createFramebuffer();
      gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
      gl.clearColor(0, 0, 0, 1);
      gl.clear(gl.COLOR_BUFFER_BIT);
      return { fb, tex };
    });
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  // source: <video> o <canvas>. Devuelve false si todavía no hay imagen.
  uploadSource(source, w, h) {
    if (!source || !w || !h) return false;
    const gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, this.camTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    this.camW = w;
    this.camH = h;
    return true;
  }

  render(effectIndex, u) {
    const gl = this.gl;
    const w = this.canvas.width, h = this.canvas.height;
    const src = this.fbos[this.read];
    const dst = this.fbos[1 - this.read];

    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.viewport(0, 0, w, h);

    // 1) Efecto -> framebuffer (lee el frame anterior para feedback).
    const prog = this.programs[effectIndex];
    gl.useProgram(prog.p);
    const U = prog.uniforms;
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, this.camTex);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, src.tex);
    const set1 = (name, v) => { if (U[name]) gl.uniform1f(U[name], v); };
    if (U.uCam) gl.uniform1i(U.uCam, 0);
    if (U.uPrev) gl.uniform1i(U.uPrev, 1);
    if (U.uRes) gl.uniform2f(U.uRes, w, h);
    if (U.uCamRes) gl.uniform2f(U.uCamRes, this.camW || 16, this.camH || 9);
    set1('uTime', u.time);
    set1('uBass', u.bass); set1('uMid', u.mid); set1('uHigh', u.high); set1('uLevel', u.level);
    set1('uBeat', u.beat);
    set1('uIntensity', u.intensity);
    set1('uHue', u.hue);
    set1('uMirror', u.mirror ? 1 : 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, dst.fb);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    // 2) Framebuffer -> pantalla con blackout y strobe.
    gl.useProgram(this.copy.p);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, dst.tex);
    gl.uniform1i(this.copy.uniforms.uTex, 0);
    gl.uniform1f(this.copy.uniforms.uMaster, u.master);
    gl.uniform1f(this.copy.uniforms.uFlash, u.flash);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    this.read = 1 - this.read;
  }
}
