/**
 * Hand-rolled SVG charts. No library, by decision (SPEC section 27.3).
 *
 * Two correctness rules the charts here exist to enforce, which a generic library fights:
 *
 *  1. GAPS STAY GAPS. `holdings_history` only has rows for days the app actually ran, and
 *     there are known holes. Drawing a straight line across a missing week invents a price
 *     history that never happened, so the line chart breaks the path instead.
 *  2. NULL IS NOT ZERO. Four multibagger dimensions are nullable, where null means "could
 *     not measure" (B-019). The radar omits those axes and labels them, rather than
 *     plotting them at the origin and implying a score of zero.
 */

const NS = 'http://www.w3.org/2000/svg';

const COLORS = {
  navy: '#1a237e',
  navyLight: '#3949ab',
  profit: '#2e7d32',
  loss: '#c62828',
  warn: '#f57c00',
  info: '#1976d2',
  rule: '#e0e0e0',
  ink: '#333333',
  muted: '#666666',
  faint: '#9e9e9e',
};

export const SERIES_COLORS = [COLORS.navyLight, COLORS.profit, COLORS.warn, COLORS.info, COLORS.loss, '#6a1b9a'];

function svgEl(name, attrs = {}) {
  const node = document.createElementNS(NS, name);
  for (const [k, v] of Object.entries(attrs)) {
    if (v !== null && v !== undefined) node.setAttribute(k, String(v));
  }
  return node;
}

function svgRoot(width, height, cls = 'chart') {
  const svg = svgEl('svg', {
    viewBox: `0 0 ${width} ${height}`,
    width: '100%',
    height,
    class: cls,
    preserveAspectRatio: 'xMidYMid meet',
    role: 'img',
  });
  return svg;
}

/** Native tooltip — free, accessible, and no JS hover machinery to maintain. */
function tip(node, text) {
  const t = svgEl('title');
  t.textContent = text;
  node.append(t);
  return node;
}

function niceTicks(min, max, count = 4) {
  if (min === max) return [min];
  const span = max - min;
  const raw = span / count;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const norm = raw / mag;
  const step = (norm >= 5 ? 10 : norm >= 2 ? 5 : norm >= 1 ? 2 : 1) * mag;
  const out = [];
  for (let v = Math.ceil(min / step) * step; v <= max + step * 0.001; v += step) {
    out.push(Number(v.toFixed(10)));
  }
  return out;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function dateLabel(iso) {
  const m = String(iso).match(/^(\d{4})-(\d{2})-(\d{2})/);
  return m ? `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]}` : String(iso);
}

function dayNumber(iso) {
  const m = String(iso).match(/^(\d{4})-(\d{2})-(\d{2})/);
  return m ? Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])) / 86400000 : 0;
}

/**
 * Multi-series time line.
 *
 * @param series [{name, color, points:[{x:isoDate, y:number|null}], axis:'left'|'right', format}]
 *   A null/missing `y`, or a date gap wider than `maxGapDays`, breaks the path — see rule 1.
 */
export function lineChart(series, {
  height = 260,
  width = 900,
  maxGapDays = 5,
  yZeroLine = false,
  formatLeft = (v) => String(Math.round(v)),
  formatRight = (v) => String(Math.round(v)),
} = {}) {
  const live = series.filter((s) => s.points && s.points.some((p) => p.y !== null && p.y !== undefined));
  if (live.length === 0) return null;

  const padL = 58;
  const padR = live.some((s) => s.axis === 'right') ? 52 : 16;
  const padT = 12;
  const padB = 26;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  const allX = live.flatMap((s) => s.points.map((p) => dayNumber(p.x)));
  const xMin = Math.min(...allX);
  const xMax = Math.max(...allX);
  const xSpan = xMax - xMin || 1;
  const sx = (iso) => padL + ((dayNumber(iso) - xMin) / xSpan) * plotW;

  function scaleFor(axis) {
    const vals = live.filter((s) => (s.axis || 'left') === axis)
      .flatMap((s) => s.points.map((p) => p.y))
      .filter((v) => v !== null && v !== undefined && Number.isFinite(v));
    if (vals.length === 0) return null;
    let lo = Math.min(...vals);
    let hi = Math.max(...vals);
    if (yZeroLine) { lo = Math.min(lo, 0); hi = Math.max(hi, 0); }
    if (lo === hi) { lo -= 1; hi += 1; }
    const pad = (hi - lo) * 0.08;
    lo -= pad; hi += pad;
    return { lo, hi, y: (v) => padT + plotH - ((v - lo) / (hi - lo)) * plotH };
  }

  const left = scaleFor('left');
  const right = scaleFor('right');
  const svg = svgRoot(width, height);

  // Horizontal gridlines + left axis labels
  if (left) {
    for (const t of niceTicks(left.lo, left.hi)) {
      const y = left.y(t);
      svg.append(svgEl('line', { x1: padL, y1: y, x2: padL + plotW, y2: y, stroke: COLORS.rule, 'stroke-width': 1 }));
      const lbl = svgEl('text', { x: padL - 8, y: y + 4, 'text-anchor': 'end', fill: COLORS.muted, 'font-size': 10.5 });
      lbl.textContent = formatLeft(t);
      svg.append(lbl);
    }
  }
  if (right) {
    for (const t of niceTicks(right.lo, right.hi)) {
      const y = right.y(t);
      const lbl = svgEl('text', { x: padL + plotW + 8, y: y + 4, fill: COLORS.muted, 'font-size': 10.5 });
      lbl.textContent = formatRight(t);
      svg.append(lbl);
    }
  }

  if (yZeroLine && left && left.lo < 0 && left.hi > 0) {
    const y = left.y(0);
    svg.append(svgEl('line', { x1: padL, y1: y, x2: padL + plotW, y2: y, stroke: COLORS.ink, 'stroke-width': 1.2, 'stroke-dasharray': '3 3' }));
  }

  // X labels: first, middle, last — enough to orient without crowding
  const xs = [...new Set(live.flatMap((s) => s.points.map((p) => p.x)))].sort();
  for (const iso of [xs[0], xs[Math.floor(xs.length / 2)], xs[xs.length - 1]].filter(Boolean)) {
    const t = svgEl('text', {
      x: sx(iso), y: height - 8, 'text-anchor': 'middle', fill: COLORS.muted, 'font-size': 10.5,
    });
    t.textContent = dateLabel(iso);
    svg.append(t);
  }

  live.forEach((s, i) => {
    const scale = (s.axis || 'left') === 'right' ? right : left;
    if (!scale) return;
    const color = s.color || SERIES_COLORS[i % SERIES_COLORS.length];

    // Build sub-paths, breaking on a null value or a date gap (rule 1).
    const runs = [];
    let run = [];
    let prevDay = null;
    for (const p of s.points) {
      const bad = p.y === null || p.y === undefined || !Number.isFinite(p.y);
      const day = dayNumber(p.x);
      const jumped = prevDay !== null && day - prevDay > maxGapDays;
      if (bad || jumped) {
        if (run.length) runs.push(run);
        run = bad ? [] : [p];
      } else {
        run.push(p);
      }
      prevDay = bad ? null : day;
    }
    if (run.length) runs.push(run);

    for (const r of runs) {
      if (r.length === 1) {
        svg.append(svgEl('circle', { cx: sx(r[0].x), cy: scale.y(r[0].y), r: 2.4, fill: color }));
        continue;
      }
      const d = r.map((p, j) => `${j === 0 ? 'M' : 'L'}${sx(p.x).toFixed(2)},${scale.y(p.y).toFixed(2)}`).join(' ');
      if (s.fill) {
        const area = `${d} L${sx(r[r.length - 1].x).toFixed(2)},${padT + plotH} L${sx(r[0].x).toFixed(2)},${padT + plotH} Z`;
        svg.append(svgEl('path', { d: area, fill: color, opacity: 0.1 }));
      }
      svg.append(svgEl('path', {
        d, fill: 'none', stroke: color, 'stroke-width': 2,
        'stroke-linejoin': 'round', 'stroke-linecap': 'round',
      }));
    }

    // Hover targets only on the last ~60 points, so a 3-year series stays light.
    const pts = s.points.filter((p) => p.y !== null && p.y !== undefined && Number.isFinite(p.y));
    for (const p of pts.slice(-60)) {
      const fmt = s.format || ((v) => String(v));
      svg.append(tip(svgEl('circle', {
        cx: sx(p.x), cy: scale.y(p.y), r: 8, fill: 'transparent', style: 'cursor:crosshair',
      }), `${dateLabel(p.x)} — ${s.name}: ${fmt(p.y)}`));
    }
  });

  return svg;
}

/** Legend matching a lineChart's series. */
export function legend(series) {
  const wrap = document.createElement('div');
  wrap.className = 'chart-legend';
  series.forEach((s, i) => {
    const item = document.createElement('span');
    const key = document.createElement('span');
    key.className = 'key';
    key.style.background = s.color || SERIES_COLORS[i % SERIES_COLORS.length];
    item.append(key, document.createTextNode(s.name));
    wrap.append(item);
  });
  return wrap;
}

/** Sparkline for a table row. Null when there is not enough history to be meaningful. */
export function sparkline(values, { width = 104, height = 24 } = {}) {
  const pts = (values || []).filter((v) => Number.isFinite(v));
  if (pts.length < 2) return null;

  const lo = Math.min(...pts);
  const hi = Math.max(...pts);
  const span = hi - lo || 1;
  const svg = svgRoot(width, height, 'chart spark');
  const step = width / (pts.length - 1);
  const rising = pts[pts.length - 1] >= pts[0];

  const d = pts.map((v, i) => `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(2)},${(height - 2 - ((v - lo) / span) * (height - 4)).toFixed(2)}`).join(' ');
  svg.append(svgEl('path', {
    d, fill: 'none', stroke: rising ? COLORS.profit : COLORS.loss,
    'stroke-width': 1.6, 'stroke-linejoin': 'round',
  }));
  return tip(svg, `${pts.length} days: ${pts[0].toFixed(2)} to ${pts[pts.length - 1].toFixed(2)}`);
}

/**
 * Horizontal bars. Handles negatives with a centred zero line, which is what excess return
 * and Information Coefficient need — a chart that cannot show "worse than the index" is
 * useless for judging picks.
 *
 * @param items [{label, value, color, note}]
 */
export function barChart(items, {
  width = 760,
  rowHeight = 26,
  labelWidth = 168,
  format = (v) => v.toFixed(1),
  reference = null,
} = {}) {
  const live = (items || []).filter((i) => Number.isFinite(i.value));
  if (live.length === 0) return null;

  const height = live.length * rowHeight + 24;
  const plotL = labelWidth;
  const plotW = width - labelWidth - 62;

  const max = Math.max(...live.map((i) => i.value), 0);
  const min = Math.min(...live.map((i) => i.value), 0);
  const span = max - min || 1;
  const zeroX = plotL + ((0 - min) / span) * plotW;

  const svg = svgRoot(width, height);

  if (min < 0) {
    svg.append(svgEl('line', { x1: zeroX, y1: 4, x2: zeroX, y2: height - 20, stroke: COLORS.ink, 'stroke-width': 1.2 }));
  }
  if (reference !== null && reference > min && reference < max) {
    const rx = plotL + ((reference - min) / span) * plotW;
    svg.append(svgEl('line', {
      x1: rx, y1: 4, x2: rx, y2: height - 20,
      stroke: COLORS.info, 'stroke-width': 1.4, 'stroke-dasharray': '4 3',
    }));
    const rl = svgEl('text', { x: rx + 4, y: height - 8, fill: COLORS.info, 'font-size': 10 });
    rl.textContent = `useful signal ${format(reference)}`;
    svg.append(rl);
  }

  live.forEach((item, i) => {
    const y = i * rowHeight + 6;
    const barH = rowHeight - 11;
    const v = item.value;
    const x = v >= 0 ? zeroX : plotL + ((v - min) / span) * plotW;
    const w = Math.max(1.5, Math.abs((v / span) * plotW));
    const color = item.color || (v >= 0 ? COLORS.profit : COLORS.loss);

    const label = svgEl('text', { x: labelWidth - 9, y: y + barH - 2, 'text-anchor': 'end', fill: COLORS.ink, 'font-size': 11.5 });
    label.textContent = item.label;
    svg.append(tip(label, item.note || item.label));

    svg.append(tip(svgEl('rect', { x, y, width: w, height: barH, fill: color, rx: 2 }),
      `${item.label}: ${format(v)}${item.note ? ' — ' + item.note : ''}`));

    const val = svgEl('text', { x: x + w + 6, y: y + barH - 2, fill: COLORS.muted, 'font-size': 11 });
    val.textContent = format(v);
    svg.append(val);
  });

  return svg;
}

/** Donut for composition (sector mix, market-cap mix). @param slices [{label, value, color}] */
export function donut(slices, { size = 210, thickness = 34 } = {}) {
  const live = (slices || []).filter((s) => Number.isFinite(s.value) && s.value > 0);
  if (live.length === 0) return null;

  const total = live.reduce((a, s) => a + s.value, 0);
  const r = size / 2 - 4;
  const inner = r - thickness;
  const cx = size / 2;
  const cy = size / 2;
  const svg = svgRoot(size, size);

  let angle = -Math.PI / 2;
  live.forEach((s, i) => {
    const sweep = (s.value / total) * Math.PI * 2;
    const end = angle + sweep;
    const large = sweep > Math.PI ? 1 : 0;
    const p = (rad, a) => `${(cx + rad * Math.cos(a)).toFixed(2)},${(cy + rad * Math.sin(a)).toFixed(2)}`;
    const d = [
      `M${p(r, angle)}`,
      `A${r},${r} 0 ${large} 1 ${p(r, end)}`,
      `L${p(inner, end)}`,
      `A${inner},${inner} 0 ${large} 0 ${p(inner, angle)}`,
      'Z',
    ].join(' ');
    svg.append(tip(svgEl('path', {
      d, fill: s.color || SERIES_COLORS[i % SERIES_COLORS.length], stroke: '#fff', 'stroke-width': 1.5,
    }), `${s.label}: ${((s.value / total) * 100).toFixed(1)}%`));
    angle = end;
  });

  const count = svgEl('text', { x: cx, y: cy - 2, 'text-anchor': 'middle', fill: COLORS.navy, 'font-size': 22, 'font-weight': 600 });
  count.textContent = String(live.length);
  const cap = svgEl('text', { x: cx, y: cy + 15, 'text-anchor': 'middle', fill: COLORS.muted, 'font-size': 10.5 });
  cap.textContent = 'groups';
  svg.append(count, cap);
  return svg;
}

/**
 * Radar over 0-100 dimensions.
 *
 * @param dims [{label, value:number|null}]
 *   A null value means "could not be measured". Its axis is drawn but the polygon SKIPS it
 *   and the label is greyed with a note — never plotted at 0, which would read as a score
 *   of zero (B-019, SPEC section 21 rule 7).
 */
export function radar(dims, { size = 300, max = 100 } = {}) {
  const axes = (dims || []).filter(Boolean);
  if (axes.length < 3) return null;

  const cx = size / 2;
  const cy = size / 2 + 4;
  const r = size / 2 - 46;
  const svg = svgRoot(size, size + 10);
  const angleAt = (i) => (i / axes.length) * Math.PI * 2 - Math.PI / 2;

  for (const ring of [0.25, 0.5, 0.75, 1]) {
    const pts = axes.map((_, i) => `${(cx + r * ring * Math.cos(angleAt(i))).toFixed(1)},${(cy + r * ring * Math.sin(angleAt(i))).toFixed(1)}`).join(' ');
    svg.append(svgEl('polygon', { points: pts, fill: 'none', stroke: COLORS.rule, 'stroke-width': 1 }));
  }

  axes.forEach((_, i) => {
    svg.append(svgEl('line', {
      x1: cx, y1: cy,
      x2: cx + r * Math.cos(angleAt(i)), y2: cy + r * Math.sin(angleAt(i)),
      stroke: COLORS.rule, 'stroke-width': 1,
    }));
  });

  const measured = axes.map((a, i) => ({ ...a, i })).filter((a) => Number.isFinite(a.value));

  if (measured.length >= 3) {
    const pts = measured.map((a) => {
      const rad = (Math.max(0, Math.min(max, a.value)) / max) * r;
      return `${(cx + rad * Math.cos(angleAt(a.i))).toFixed(1)},${(cy + rad * Math.sin(angleAt(a.i))).toFixed(1)}`;
    }).join(' ');
    svg.append(svgEl('polygon', { points: pts, fill: COLORS.navyLight, 'fill-opacity': 0.24, stroke: COLORS.navyLight, 'stroke-width': 2 }));
  }

  for (const a of measured) {
    const rad = (Math.max(0, Math.min(max, a.value)) / max) * r;
    svg.append(tip(svgEl('circle', {
      cx: cx + rad * Math.cos(angleAt(a.i)), cy: cy + rad * Math.sin(angleAt(a.i)),
      r: 3.4, fill: COLORS.navy,
    }), `${a.label}: ${a.value} out of ${max}`));
  }

  axes.forEach((a, i) => {
    const ang = angleAt(i);
    const lx = cx + (r + 20) * Math.cos(ang);
    const ly = cy + (r + 20) * Math.sin(ang);
    const anchor = Math.abs(Math.cos(ang)) < 0.3 ? 'middle' : Math.cos(ang) > 0 ? 'start' : 'end';
    const isMissing = !Number.isFinite(a.value);
    const t = svgEl('text', {
      x: lx, y: ly + 3, 'text-anchor': anchor,
      fill: isMissing ? COLORS.faint : COLORS.ink,
      'font-size': 10, 'font-style': isMissing ? 'italic' : 'normal',
    });
    t.textContent = a.label;
    svg.append(tip(t, isMissing
      ? `${a.label}: not measured for this stock — the shape below excludes it`
      : `${a.label}: ${a.value} out of ${max}`));
  });

  return svg;
}

/** Radial gauge for a bounded metric (HHI, health score). */
export function gauge(value, { min = 0, max = 10000, size = 190, bands = [], label = '' } = {}) {
  if (!Number.isFinite(value)) return null;

  const cx = size / 2;
  const cy = size / 2 + 14;
  const r = size / 2 - 20;
  const svg = svgRoot(size, size * 0.78);
  const frac = Math.max(0, Math.min(1, (value - min) / (max - min)));
  const arc = (from, to, color, w) => {
    const a0 = Math.PI + from * Math.PI;
    const a1 = Math.PI + to * Math.PI;
    const large = to - from > 0.5 ? 1 : 0;
    return svgEl('path', {
      d: `M${(cx + r * Math.cos(a0)).toFixed(2)},${(cy + r * Math.sin(a0)).toFixed(2)} A${r},${r} 0 ${large} 1 ${(cx + r * Math.cos(a1)).toFixed(2)},${(cy + r * Math.sin(a1)).toFixed(2)}`,
      fill: 'none', stroke: color, 'stroke-width': w, 'stroke-linecap': 'butt',
    });
  };

  if (bands.length) {
    let prev = 0;
    for (const b of bands) {
      const to = Math.max(0, Math.min(1, (b.upTo - min) / (max - min)));
      if (to > prev) svg.append(tip(arc(prev, to, b.color, 12), b.label || ''));
      prev = to;
    }
    if (prev < 1) svg.append(arc(prev, 1, COLORS.rule, 12));
  } else {
    svg.append(arc(0, 1, COLORS.rule, 12));
  }

  svg.append(arc(0, frac, COLORS.navy, 5));

  const needleAngle = Math.PI + frac * Math.PI;
  svg.append(svgEl('line', {
    x1: cx, y1: cy,
    x2: cx + (r - 4) * Math.cos(needleAngle), y2: cy + (r - 4) * Math.sin(needleAngle),
    stroke: COLORS.ink, 'stroke-width': 2.4, 'stroke-linecap': 'round',
  }));
  svg.append(svgEl('circle', { cx, cy, r: 4, fill: COLORS.ink }));

  const v = svgEl('text', { x: cx, y: cy - 18, 'text-anchor': 'middle', fill: COLORS.navy, 'font-size': 24, 'font-weight': 600 });
  v.textContent = Math.round(value).toLocaleString('en-IN');
  svg.append(v);

  if (label) {
    const l = svgEl('text', { x: cx, y: cy + 22, 'text-anchor': 'middle', fill: COLORS.muted, 'font-size': 11 });
    l.textContent = label;
    svg.append(l);
  }
  return svg;
}

/**
 * Scatter with labelled points - "are my biggest positions my best businesses?" (SPEC 46.3).
 *
 * @param points [{x, y, label, color, note}]
 * @param xLabel/yLabel axis captions; xRef/yRef draw a dashed reference line
 */
export function scatter(points, {
  width = 760,
  height = 320,
  xLabel = '',
  yLabel = '',
  xRef = null,
  yRef = null,
  formatX = (v) => String(Math.round(v)),
  formatY = (v) => String(Math.round(v)),
  yMin = null,
  yMax = null,
} = {}) {
  const live = (points || []).filter((p) => Number.isFinite(p.x) && Number.isFinite(p.y));
  if (live.length === 0) return null;

  const padL = 52;
  const padR = 18;
  const padT = 14;
  const padB = 38;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  const xs = live.map((p) => p.x);
  const ys = live.map((p) => p.y);
  let xLo = 0;
  let xHi = Math.max(...xs) * 1.12 || 1;
  let yLo = yMin === null ? Math.min(...ys) : yMin;
  let yHi = yMax === null ? Math.max(...ys) : yMax;
  if (yLo === yHi) { yLo -= 1; yHi += 1; }
  const sx = (v) => padL + ((v - xLo) / (xHi - xLo)) * plotW;
  const sy = (v) => padT + plotH - ((v - yLo) / (yHi - yLo)) * plotH;

  const svg = svgRoot(width, height);

  for (const t of niceTicks(yLo, yHi, 4)) {
    const y = sy(t);
    svg.append(svgEl('line', { x1: padL, y1: y, x2: padL + plotW, y2: y, stroke: COLORS.rule, 'stroke-width': 1 }));
    const lbl = svgEl('text', { x: padL - 8, y: y + 4, 'text-anchor': 'end', fill: COLORS.muted, 'font-size': 10.5 });
    lbl.textContent = formatY(t);
    svg.append(lbl);
  }
  for (const t of niceTicks(xLo, xHi, 5)) {
    const x = sx(t);
    const lbl = svgEl('text', { x, y: height - padB + 16, 'text-anchor': 'middle', fill: COLORS.muted, 'font-size': 10.5 });
    lbl.textContent = formatX(t);
    svg.append(lbl);
  }
  if (xRef !== null) {
    svg.append(svgEl('line', { x1: sx(xRef), y1: padT, x2: sx(xRef), y2: padT + plotH, stroke: COLORS.info, 'stroke-width': 1.2, 'stroke-dasharray': '4 3' }));
  }
  if (yRef !== null) {
    svg.append(svgEl('line', { x1: padL, y1: sy(yRef), x2: padL + plotW, y2: sy(yRef), stroke: COLORS.info, 'stroke-width': 1.2, 'stroke-dasharray': '4 3' }));
  }
  if (xLabel) {
    const t = svgEl('text', { x: padL + plotW / 2, y: height - 4, 'text-anchor': 'middle', fill: COLORS.muted, 'font-size': 11 });
    t.textContent = xLabel;
    svg.append(t);
  }
  if (yLabel) {
    const t = svgEl('text', {
      x: 12, y: padT + plotH / 2, fill: COLORS.muted, 'font-size': 11, 'text-anchor': 'middle',
      transform: `rotate(-90 12 ${padT + plotH / 2})`,
    });
    t.textContent = yLabel;
    svg.append(t);
  }

  for (const p of live) {
    const cx = sx(p.x);
    const cy = sy(p.y);
    const r = 4 + Math.min(6, Math.sqrt(p.x));
    svg.append(tip(svgEl('circle', { cx, cy, r, fill: p.color || COLORS.navyLight, 'fill-opacity': 0.75, stroke: '#fff', 'stroke-width': 1 }),
      p.note || `${p.label}: ${formatX(p.x)}, ${formatY(p.y)}`));
    if (p.label) {
      const t = svgEl('text', { x: cx + r + 3, y: cy + 3.5, fill: COLORS.ink, 'font-size': 10.5 });
      t.textContent = p.label;
      svg.append(tip(t, p.note || p.label));
    }
  }
  return svg;
}

export { COLORS };
