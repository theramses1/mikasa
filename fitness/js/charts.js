// Gráficas SVG mínimas (sin dependencias, funcionan offline).

const fmtShort = (s) => {
  const [, m, d] = s.split('-');
  return `${Number(d)}/${Number(m)}`;
};

// series: [{ points: [{ x: 'YYYY-MM-DD', y }], cls, line, dots }]
export function lineChart(series, { height = 180, dates, yDecimals = 1, unit = '' } = {}) {
  const W = 340;
  const H = height;
  const padL = 38;
  const padR = 8;
  const padT = 10;
  const padB = 22;
  const all = series.flatMap((s) => s.points.filter((p) => p.y != null));
  if (!all.length) return `<div class="chart-empty">Sin datos todavía</div>`;
  const xs = dates || [...new Set(all.map((p) => p.x))].sort();
  const xIndex = new Map(xs.map((x, i) => [x, i]));
  let min = Math.min(...all.map((p) => p.y));
  let max = Math.max(...all.map((p) => p.y));
  if (max - min < 1e-9) {
    min -= 1;
    max += 1;
  }
  const padY = (max - min) * 0.08;
  min -= padY;
  max += padY;
  const n = Math.max(1, xs.length - 1);
  const X = (x) => padL + ((xIndex.get(x) ?? 0) / n) * (W - padL - padR);
  const Y = (y) => padT + (1 - (y - min) / (max - min)) * (H - padT - padB);
  const grid = [0, 0.5, 1]
    .map((t) => {
      const v = min + (max - min) * (1 - t);
      const y = padT + t * (H - padT - padB);
      return `<line class="grid" x1="${padL}" x2="${W - padR}" y1="${y}" y2="${y}"/><text class="axis" x="${padL - 4}" y="${y + 3}" text-anchor="end">${v.toFixed(yDecimals)}${unit}</text>`;
    })
    .join('');
  const xl = `<text class="axis" x="${padL}" y="${H - 6}">${fmtShort(xs[0])}</text><text class="axis" x="${W - padR}" y="${H - 6}" text-anchor="end">${fmtShort(xs[xs.length - 1])}</text>`;
  const body = series
    .map((s) => {
      const pts = s.points.filter((p) => p.y != null && xIndex.has(p.x));
      let out = '';
      if (s.line !== false && pts.length > 1) {
        out += `<polyline class="ln ${s.cls || ''}" points="${pts.map((p) => `${X(p.x).toFixed(1)},${Y(p.y).toFixed(1)}`).join(' ')}"/>`;
      }
      if (s.dots || pts.length === 1) {
        out += pts.map((p) => `<circle class="dot ${s.cls || ''}" cx="${X(p.x).toFixed(1)}" cy="${Y(p.y).toFixed(1)}" r="2.6"/>`).join('');
      }
      return out;
    })
    .join('');
  return `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img">${grid}${xl}${body}</svg>`;
}

export function sparkline(values, cls = '') {
  const v = values.filter((x) => x != null);
  if (v.length < 2) return '';
  const W = 120;
  const H = 32;
  const min = Math.min(...v);
  const max = Math.max(...v);
  const r = max - min || 1;
  const pts = v.map((y, i) => `${((i / (v.length - 1)) * W).toFixed(1)},${(H - 3 - ((y - min) / r) * (H - 6)).toFixed(1)}`).join(' ');
  return `<svg class="spark" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none"><polyline class="ln ${cls}" points="${pts}"/></svg>`;
}

export function ring(value, target, label, sub) {
  const r = 52;
  const C = 2 * Math.PI * r;
  const pct = target > 0 ? Math.min(1, value / target) : 0;
  const over = target > 0 && value > target * 1.05;
  return `<div class="ring-wrap"><svg class="ring" viewBox="0 0 120 120">
    <circle class="ring-bg" cx="60" cy="60" r="${r}"/>
    <circle class="ring-fg ${over ? 'over' : ''}" cx="60" cy="60" r="${r}" stroke-dasharray="${(pct * C).toFixed(1)} ${C.toFixed(1)}" transform="rotate(-90 60 60)"/>
  </svg><div class="ring-label"><strong>${label}</strong><span>${sub}</span></div></div>`;
}

export function bars(items, { max } = {}) {
  // items: [{ label, value }]
  const m = max ?? Math.max(1, ...items.map((i) => i.value));
  return `<div class="hbars">${items
    .map(
      (i) =>
        `<div class="hbar"><span class="hbar-label">${i.label}</span><span class="hbar-track"><span class="hbar-fill" style="width:${Math.min(100, (i.value / m) * 100).toFixed(0)}%"></span></span><span class="hbar-val">${i.value}</span></div>`,
    )
    .join('')}</div>`;
}
