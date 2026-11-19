// Otto geometry, shared by every brand board and SVG file.
// Same procedural tentacles as the TV launcher (Main.dc.html), so every pose matches.
const f = (v) => Math.round(v * 10) / 10;

function sample(segs) {
  const N = 18;
  const pts = [];
  for (let s = 0; s < segs.length; s++) {
    const [a, b, c, e] = segs[s];
    for (let i = (s === 0 ? 0 : 1); i <= N; i++) {
      const t = i / N;
      const m = 1 - t;
      const x = m * m * m * a[0] + 3 * m * m * t * b[0] + 3 * m * t * t * c[0] + t * t * t * e[0];
      const y = m * m * m * a[1] + 3 * m * m * t * b[1] + 3 * m * t * t * c[1] + t * t * t * e[1];
      let dx = 3 * m * m * (b[0] - a[0]) + 6 * m * t * (c[0] - b[0]) + 3 * t * t * (e[0] - c[0]);
      let dy = 3 * m * m * (b[1] - a[1]) + 6 * m * t * (c[1] - b[1]) + 3 * t * t * (e[1] - c[1]);
      if (Math.abs(dx) + Math.abs(dy) < 0.0001) { dx = e[0] - a[0]; dy = e[1] - a[1]; }
      const len = Math.sqrt(dx * dx + dy * dy) || 1;
      pts.push({ x, y, nx: -dy / len, ny: dx / len, dist: 0, u: 0, seg: s });
    }
  }
  let total = 0;
  for (let i = 1; i < pts.length; i++) {
    total += Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y);
    pts[i].dist = total;
  }
  for (const p of pts) p.u = total ? p.dist / total : 0;
  return pts;
}

const halfWidth = (w0, w1, u) => (w0 + (w1 - w0) * Math.pow(u, 0.9)) / 2;

function taper(segs, w0, w1) {
  const p = sample(segs);
  const L = [];
  const R = [];
  for (const q of p) {
    const w = halfWidth(w0, w1, q.u);
    L.push(f(q.x + q.nx * w) + ' ' + f(q.y + q.ny * w));
    R.push(f(q.x - q.nx * w) + ' ' + f(q.y - q.ny * w));
  }
  const r = f(w1 / 2);
  let d = 'M' + L.join('L');
  d += 'A' + r + ' ' + r + ' 0 0 0 ' + R[R.length - 1];
  for (let i = R.length - 2; i >= 0; i--) d += 'L' + R[i];
  return d + 'Z';
}

function suck(segs, w0, w1, count) {
  const p = sample(segs);
  let d = '';
  for (let k = 0; k < count; k++) {
    const u = 0.42 + (0.86 - 0.42) * (count === 1 ? 0 : k / (count - 1));
    let q = p[0];
    for (const c of p) if (Math.abs(c.u - u) < Math.abs(q.u - u)) q = c;
    const hw = halfWidth(w0, w1, q.u);
    const r = Math.max(0.8, hw * 0.34);
    const cx = q.x - q.nx * hw * 0.4;
    const cy = q.y - q.ny * hw * 0.4;
    d += 'M' + f(cx - r) + ' ' + f(cy) + 'a' + f(r) + ' ' + f(r) + ' 0 1 0 ' + f(2 * r) + ' 0a' + f(r) + ' ' + f(r) + ' 0 1 0 ' + f(-2 * r) + ' 0';
  }
  return d;
}

// width of the taper where the first segment ends (to continue a tentacle in front of the body)
function jointWidth(segs, w0, w1) {
  const p = sample(segs);
  const j = p.filter((q) => q.seg === 0).pop();
  return halfWidth(w0, w1, j.u) * 2;
}

function arm(bx, by, ex, ey, s) {
  const c = ex < bx ? -1 : 1;
  return [
    [[bx, by], [bx + (ex - bx) * 0.08, by + (ey - by) * 0.6], [bx + (ex - bx) * 0.5, ey], [ex, ey]],
    [[ex, ey], [ex + c * 5 * s, ey], [ex + c * 7.5 * s, ey - 5 * s], [ex + c * 3.5 * s, ey - 7.5 * s]]
  ];
}

// eyelid; c = 0 is the relaxed resting lid, c = 1 covers the whole eye (blink)
function lid(cx, cy, r, c = 0) {
  const ye = cy - 0.5 * r + 1.6 * r * c;
  const yc = cy - 1.2 * r + 2.4 * r * c;
  return 'M' + f(cx - r - 1.5) + ' ' + f(ye) + 'Q' + f(cx) + ' ' + f(yc) + ' ' + f(cx + r + 1.5) + ' ' + f(ye) + 'V' + f(cy - r - 2) + 'H' + f(cx - r - 1.5) + 'Z';
}

function star(cx, cy, r) {
  return 'M' + f(cx) + ' ' + f(cy - r) + 'Q' + f(cx) + ' ' + f(cy) + ' ' + f(cx + r) + ' ' + f(cy) + 'Q' + f(cx) + ' ' + f(cy) + ' ' + f(cx) + ' ' + f(cy + r) + 'Q' + f(cx) + ' ' + f(cy) + ' ' + f(cx - r) + ' ' + f(cy) + 'Q' + f(cx) + ' ' + f(cy) + ' ' + f(cx) + ' ' + f(cy - r) + 'Z';
}

function rrect(x, y, w, h, r) {
  return 'M' + f(x + r) + ' ' + f(y) + 'H' + f(x + w - r) + 'A' + r + ' ' + r + ' 0 0 1 ' + f(x + w) + ' ' + f(y + r) + 'V' + f(y + h - r) + 'A' + r + ' ' + r + ' 0 0 1 ' + f(x + w - r) + ' ' + f(y + h) + 'H' + f(x + r) + 'A' + r + ' ' + r + ' 0 0 1 ' + f(x) + ' ' + f(y + h - r) + 'V' + f(y + r) + 'A' + r + ' ' + r + ' 0 0 1 ' + f(x + r) + ' ' + f(y) + 'Z';
}

function circ(cx, cy, r) {
  return 'M' + f(cx - r) + ' ' + f(cy) + 'a' + f(r) + ' ' + f(r) + ' 0 1 0 ' + f(2 * r) + ' 0a' + f(r) + ' ' + f(r) + ' 0 1 0 ' + f(-2 * r) + ' 0Z';
}

const COL = {
  body: '#F2B25C', shade: '#E39D4E', freckle: '#DD9446', suck: '#FAD49C', back: '#DE9748',
  eye: '#FFF7EA', pupil: '#2B221D', hl: '#FFFFFF', blush: '#EC8F5E', smile: '#8A4A22',
  mouth: '#7A3E1C', tongue: '#E8875A', ink: '#C9D1DB',
  lid: '#485569', screen: '#262E3A', base: '#5E6B80'
};

// ---------- full-body Otto, viewBox 0 0 200 200 ----------
// The waving pose of the TV (120 box) and the cheering pose (160 box) are mapped into one 200 box,
// so every pose has the same body, the same scale and the same 8 tentacles.
const K = 34 / 30;
const W = (p) => [100 + (p[0] - 60) * K, 88 + (p[1] - 50) * K];
const CH = (p) => [p[0] + 20, p[1] + 16];
const mapG = (g, fn) => g.map((seg) => seg.map(fn));
const armW = (bx, by, ex, ey, s) => mapG(arm(bx, by, ex, ey, s), W);
const armC = (bx, by, ex, ey, s) => mapG(arm(bx, by, ex, ey, s), CH);

const AW0 = 9 * K;
const AW1 = 2.2 * K;
const A = {
  backL: armW(36, 76, 20, 100, 0.75),
  backR: armW(84, 76, 100, 100, 0.75),
  f1: armW(45, 80, 31, 108, 0.7),
  f2: armW(55, 82, 44, 113, 0.65),
  f3: armW(65, 82, 76, 113, 0.65),
  f4: armW(75, 80, 89, 108, 0.7),
  sideL: armW(33, 64, 14, 80, 0.8),
  sideR: armW(87, 64, 106, 80, 0.8),
  wave: mapG([[[84, 62], [96, 60], [102, 50], [102, 38]], [[102, 38], [102, 32], [98, 28], [94, 31]]], W),
  chin: mapG([[[37, 82], [22, 90], [14, 72], [26, 62]], [[26, 62], [33, 57], [43, 60], [44, 68]]], W),
  cB: [armC(52, 100, 30, 126, 0.9), armC(108, 100, 130, 126, 0.9)],
  cF: [armC(62, 104, 44, 134, 0.8), armC(74, 106, 62, 141, 0.7), armC(86, 106, 98, 141, 0.7), armC(98, 104, 116, 134, 0.8)],
  cU: [
    mapG([[[50, 84], [36, 82], [26, 66], [24, 48]], [[24, 48], [23, 40], [29, 35], [34, 39]]], CH),
    mapG([[[110, 84], [124, 82], [134, 66], [136, 48]], [[136, 48], [137, 40], [131, 35], [126, 39]]], CH)
  ]
};

const BODY = 'M66 88A34 34 0 0 1 134 88V114Q134 124 124 124H76Q66 124 66 114Z';
const SHADE = 'M123 63A34 34 0 0 1 134 88V114Q134 124 126 124Q129 120 129 114V92C129 80 127 70 123 63Z';
const FRECKLES = circ(112, 64, 2.6) + circ(120, 71, 1.8) + circ(104, 59, 1.5);
const EYE_L = W([49, 58]);
const EYE_R = W([71, 58]);
const ER = 9 * K;

const P = (d, fill, extra = '') => '<path d="' + d + '" fill="' + fill + '"' + extra + '></path>';
const S = (d, stroke, w, extra = '') => '<path d="' + d + '" fill="none" stroke="' + stroke + '" stroke-width="' + f(w) + '" stroke-linecap="round" stroke-linejoin="round"' + extra + '></path>';

function bodyMarkup() {
  return P(BODY, COL.body) + P(SHADE, COL.shade) + P(FRECKLES, COL.freckle);
}

function eyesOpen(look = [1.5, 1.5], withLids = true, blink = 0) {
  let m = P(circ(EYE_L[0], EYE_L[1], ER) + circ(EYE_R[0], EYE_R[1], ER), COL.eye);
  const pd = (e) => circ(e[0] + look[0] * K, e[1] + look[1] * K, 5 * K);
  m += P(pd(EYE_L) + pd(EYE_R), COL.pupil);
  const hd = (e) => circ(e[0] + (look[0] + 1.9) * K, e[1] + (look[1] - 2.3) * K, 1.7 * K);
  m += P(hd(EYE_L) + hd(EYE_R), COL.hl);
  if (withLids) m += P(lid(EYE_L[0], EYE_L[1], ER, blink) + lid(EYE_R[0], EYE_R[1], ER, blink), COL.body);
  return m;
}

function blush(left = true, right = true) {
  const l = W([39, 69]);
  const r = W([80, 70]);
  let m = '';
  if (left) m += '<ellipse cx="' + f(l[0]) + '" cy="' + f(l[1]) + '" rx="' + f(4.5 * K) + '" ry="' + f(2.8 * K) + '" fill="' + COL.blush + '" opacity="0.45"></ellipse>';
  if (right) m += '<ellipse cx="' + f(r[0]) + '" cy="' + f(r[1]) + '" rx="' + f(4.5 * K) + '" ry="' + f(2.8 * K) + '" fill="' + COL.blush + '" opacity="0.45"></ellipse>';
  return m;
}

function smile() {
  const a = W([56, 70.5]);
  const c = W([60, 74]);
  const b = W([64, 70.5]);
  return S('M' + f(a[0]) + ' ' + f(a[1]) + 'Q' + f(c[0]) + ' ' + f(c[1]) + ' ' + f(b[0]) + ' ' + f(b[1]), COL.smile, 2.2 * K);
}

const T = (g, w0 = AW0, w1 = AW1) => taper(g, w0, w1);
const SK = (g, w0 = AW0, w1 = AW1, n = 3) => suck(g, w0, w1, n);

// pose: neutral | wave | cheer | sleep | think
// opts: look [dx,dy] for the pupils, sides (side tentacles), held (drawn under the waving tentacle), after (drawn last)
function pose(name, opts = {}) {
  let m = opts.before || '';
  if (name === 'cheer') {
    const sp = [
      [32, 34, 7, COL.body, 1], [170, 32, 8, '#FFFFFF', 0.85], [30, 92, 5, '#FFFFFF', 0.6],
      [172, 90, 6, COL.body, 1], [100, 26, 6, '#FFFFFF', 0.7]
    ];
    for (const s of sp) m += P(star(s[0], s[1], s[2]), s[3], s[4] < 1 ? ' fill-opacity="' + s[4] + '"' : '');
    const dots = [[56, 30, 2, COL.body, 1], [146, 26, 2.2, '#FFFFFF', 0.7], [170, 62, 1.8, '#FFFFFF', 0.6], [28, 62, 2, COL.body, 0.8]];
    for (const d of dots) m += P(circ(d[0], d[1], d[2]), d[3], d[4] < 1 ? ' fill-opacity="' + d[4] + '"' : '');
    m += P(A.cB.map((g) => T(g, 10, 2.4)).join(''), COL.back);
    m += P(A.cF.map((g) => T(g, 10, 2.4)).join(''), COL.body);
    m += P(A.cF.map((g) => SK(g, 10, 2.4)).join(''), COL.suck);
    m += bodyMarkup();
    m += S('M80 92Q86 82 92 92', COL.pupil, 4.6) + S('M108 92Q114 82 120 92', COL.pupil, 4.6);
    m += '<ellipse cx="77" cy="102" rx="6" ry="3.6" fill="' + COL.blush + '" opacity="0.5"></ellipse>';
    m += '<ellipse cx="123" cy="102" rx="6" ry="3.6" fill="' + COL.blush + '" opacity="0.5"></ellipse>';
    m += P('M90 100H110Q108 113 100 113Q92 113 90 100Z', COL.mouth);
    m += P('M95 108.5Q100 105 105 108.5Q103 112 100 112Q97 112 95 108.5Z', COL.tongue);
    m += P(A.cU.map((g) => T(g, 11, 3)).join(''), COL.body);
    m += P(A.cU.map((g) => SK(g, 11, 3)).join(''), COL.suck);
    return m;
  }

  const front = [A.f1, A.f2, A.f3, A.f4];
  const sides = opts.sides || (name === 'wave' ? [A.sideL] : name === 'think' ? [A.sideR] : [A.sideL, A.sideR]);
  m += P(T(A.backL) + T(A.backR), COL.back);
  if (name === 'think') m += P(T(A.chin, AW0, 2.4 * K), COL.body);
  m += P(front.concat(sides).map((g) => T(g)).join(''), COL.body);
  m += P(front.map((g) => SK(g)).join(''), COL.suck);

  if (name === 'sleep') {
    m += '<g transform="rotate(-7 100 124)">';
    m += bodyMarkup();
    const cl = (e) => 'M' + f(e[0] - 7) + ' ' + f(e[1] + 1) + 'Q' + f(e[0]) + ' ' + f(e[1] + 6) + ' ' + f(e[0] + 7) + ' ' + f(e[1] + 1);
    m += S(cl(EYE_L) + cl(EYE_R), COL.pupil, 3.2);
    m += blush();
    m += '<ellipse cx="100" cy="113" rx="2.6" ry="2.2" fill="' + COL.mouth + '"></ellipse>';
    m += '</g>';
    const z = (x, y, s) => 'M' + x + ' ' + y + 'h' + s + 'l' + (-s) + ' ' + s + 'h' + s;
    m += S(z(137, 68, 7), COL.ink, 2.6) + S(z(149, 49, 10), COL.ink, 3) + S(z(163, 26, 13), COL.ink, 3.4);
    return m;
  }

  m += bodyMarkup();
  if (name === 'think') {
    m += eyesOpen([2.8, -3.2], false);
    m += blush(false, true);
    const a = W([55, 71.5]);
    const c = W([59, 70.2]);
    const b = W([64.5, 71]);
    m += S('M' + f(a[0]) + ' ' + f(a[1]) + 'Q' + f(c[0]) + ' ' + f(c[1]) + ' ' + f(b[0]) + ' ' + f(b[1]), COL.smile, 2.2 * K);
    // the curl of the chin tentacle passes in front of the face
    const wj = jointWidth(A.chin, AW0, 2.4 * K);
    m += P(taper([A.chin[1]], wj, 2.4 * K), COL.body);
    m += P(suck([A.chin[1]], wj, 2.4 * K, 2), COL.suck);
    m += P(circ(138, 66, 2.8) + circ(148, 50, 4.2) + circ(161, 30, 6), COL.ink);
    return m;
  }
  m += eyesOpen(opts.look || [1.5, 1.5]);
  m += blush();
  m += smile();
  if (name === 'wave') m += (opts.held || '') + P(T(A.wave, AW0, 2.5 * K), COL.body);
  return m + (opts.after || '');
}

// ---------- boot loop: neutral Otto with every tentacle on its own pivot ----------
const BOOT_ARMS = [
  { key: 'sL', g: A.sideL, fill: COL.body, suck: false },
  { key: 'bL', g: A.backL, fill: COL.back, suck: false },
  { key: 'f1', g: A.f1, fill: COL.body, suck: true },
  { key: 'f2', g: A.f2, fill: COL.body, suck: true },
  { key: 'f3', g: A.f3, fill: COL.body, suck: true },
  { key: 'f4', g: A.f4, fill: COL.body, suck: true },
  { key: 'bR', g: A.backR, fill: COL.back, suck: false },
  { key: 'sR', g: A.sideR, fill: COL.body, suck: false }
];
const BOOT_PIVOTS = BOOT_ARMS.map((a) => [f(a.g[0][0][0]), f(a.g[0][0][1])]);
const BOOT_FRAMES = 36;
const BOOT_FPS = 24;

// Same function is copied as text into the live board, so the keyframes and the loop match.
const BOOT_FN = 'function bootPose(n) {\n' +
  '  const ph = 2 * Math.PI * n / 36;\n' +
  '  const amps = [5, 3.5, 6, 6, 6, 6, 3.5, 5];\n' +
  '  const ang = amps.map(function (a, i) { return Math.round(a * Math.sin(ph - i * 0.55) * 10) / 10; });\n' +
  '  const bob = Math.round(-2.5 * (1 - Math.cos(ph)) / 2 * 10) / 10;\n' +
  '  const blinks = { 28: 0.5, 29: 1, 30: 1, 31: 0.55, 32: 0.15 };\n' +
  '  return { ang: ang, bob: bob, blink: blinks[n] || 0 };\n' +
  '}';
// eslint-disable-next-line no-new-func
const bootPose = new Function(BOOT_FN + '\nreturn bootPose;')();

function bootArmsBack(transforms) {
  // back tentacles first, then the front ones (with suckers)
  let m = '';
  const order = ['bL', 'bR', 'sL', 'f1', 'f2', 'f3', 'f4', 'sR'];
  for (const key of order) {
    const a = BOOT_ARMS.find((x) => x.key === key);
    m += '<g transform="' + transforms[key] + '">' + P(T(a.g), a.fill) + (a.suck ? P(SK(a.g), COL.suck) : '') + '</g>';
  }
  return m;
}

function bootFace(lidL, lidR, lashOp) {
  let m = P(circ(EYE_L[0], EYE_L[1], ER) + circ(EYE_R[0], EYE_R[1], ER), COL.eye);
  const pd = (e) => circ(e[0] + 1.5 * K, e[1] + 1.5 * K, 5 * K);
  m += P(pd(EYE_L) + pd(EYE_R), COL.pupil);
  const hd = (e) => circ(e[0] + 3.4 * K, e[1] - 0.8 * K, 1.7 * K);
  m += P(hd(EYE_L) + hd(EYE_R), COL.hl);
  m += P(lidL, COL.body) + P(lidR, COL.body);
  const lash = (e) => 'M' + f(e[0] - 0.75 * ER) + ' ' + f(e[1] + 0.2 * ER) + 'Q' + f(e[0]) + ' ' + f(e[1] + 0.65 * ER) + ' ' + f(e[0] + 0.75 * ER) + ' ' + f(e[1] + 0.2 * ER);
  m += S(lash(EYE_L) + lash(EYE_R), COL.pupil, 2.8, ' opacity="' + lashOp + '"');
  m += blush() + smile();
  return m;
}

function bootOtto(n) {
  const p = bootPose(n);
  const tr = {};
  BOOT_ARMS.forEach((a, i) => { tr[a.key] = 'rotate(' + p.ang[i] + ' ' + BOOT_PIVOTS[i][0] + ' ' + BOOT_PIVOTS[i][1] + ')'; });
  return '<g transform="translate(0 ' + p.bob + ')">' + bootArmsBack(tr) + bodyMarkup() +
    bootFace(lid(EYE_L[0], EYE_L[1], ER, p.blink), lid(EYE_R[0], EYE_R[1], ER, p.blink), p.blink >= 0.9 ? 1 : 0) + '</g>';
}

// ---------- logo: Otto peeking out of a laptop, viewBox 0 0 64 64 ----------
const LG = [
  [[[20.5, 28], [20.2, 35], [17.5, 40.5], [16.5, 45]], [[16.5, 45], [15.9, 48.8], [12, 49.6], [12.1, 46.2]]],
  [[[27.5, 28], [27.5, 36], [25.5, 43], [26, 48]], [[26, 48], [26.2, 51.5], [22.2, 52], [22.3, 48.6]]],
  [[[36.5, 28], [36.5, 36], [38.5, 43], [38, 48]], [[38, 48], [37.8, 51.5], [41.8, 52], [41.7, 48.6]]],
  [[[43.5, 28], [43.8, 35], [46.5, 40.5], [47.5, 45]], [[47.5, 45], [48.1, 48.8], [52, 49.6], [51.9, 46.2]]]
];
const LOGO_ARMS = taper(LG[0], 5.4, 1.5) + taper(LG[1], 5.8, 1.6) + taper(LG[2], 5.8, 1.6) + taper(LG[3], 5.4, 1.5);
const LAPTOP_BASE = 'M1.5 56H62.5L60.5 59.5Q60 60.5 58.8 60.5H5.2Q4 60.5 3.5 59.5Z';
const LOGO =
  P('M17 34V24A15 15 0 0 1 47 24V34Z', COL.body) +
  P('M42 12.8A15 15 0 0 1 47 24V34H44.2V25C44.2 20 43.4 16 42 12.8Z', COL.shade) +
  P(circ(36, 13.2, 1.3) + circ(40.2, 16.4, 0.9), COL.freckle) +
  P(circ(26, 21.5, 4.6) + circ(38, 21.5, 4.6), COL.eye) +
  P(circ(26.7, 22.2, 2.6) + circ(38.7, 22.2, 2.6), COL.pupil) +
  P(circ(27.7, 21.1, 0.95) + circ(39.7, 21.1, 0.95), COL.hl) +
  S('M30.4 26.4Q32 27.9 33.6 26.4', COL.smile, 1.1) +
  P(rrect(6, 30, 52, 26, 5), COL.lid) +
  P(rrect(9.5, 33.5, 45, 19, 2.5), COL.screen) +
  P(LAPTOP_BASE, COL.base) +
  P(LOGO_ARMS, COL.body);

// one color: separate shapes, so overlaps simply merge (no masks, works as an Android vector)
const MONO_ARMS = taper(LG[0], 7, 2.6) + taper(LG[1], 7.4, 2.8) + taper(LG[2], 7.4, 2.8) + taper(LG[3], 7, 2.6);
function logoMono(color) {
  return '<path d="M17 34V24A15 15 0 0 1 47 24V34Z' + circ(26, 21.5, 5) + circ(38, 21.5, 5) + '" fill="' + color + '" fill-rule="evenodd"></path>' +
    P(circ(26.6, 22.2, 2.5) + circ(38.6, 22.2, 2.5), color) +
    '<path d="' + rrect(6, 30, 52, 26, 5) + rrect(10.5, 34.5, 43, 17, 2) + '" fill="' + color + '" fill-rule="evenodd"></path>' +
    P(LAPTOP_BASE, color) +
    P(MONO_ARMS, color);
}

module.exports = {
  AW0, AW1, mapG, armW,
  f, sample, taper, suck, arm, lid, star, rrect, circ, COL, A, K, W, P, S,
  pose, bootOtto, bootPose, BOOT_FN, BOOT_ARMS, BOOT_PIVOTS, BOOT_FRAMES, BOOT_FPS,
  bodyMarkup, bootArmsBack, bootFace, EYE_L, EYE_R, ER, T, SK, LOGO, logoMono
};
