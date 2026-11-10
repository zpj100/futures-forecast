'use strict';

const API = '';
const state = {
  products: [],
  forecasts: {},
  quotes: {},
  activeTab: 'market',
  activeCode: 'AL',
  activePeriod: '1m',
  compare: false,
  compareSeries: [],
  bars: [],
  hzCode: 'AL',
  histCode: 'AL',
  histDays: 30,
  hover: null,
  refreshMs: 15000,
  theme: 'dark',
};

const $ = (s) => document.querySelector(s);
const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
};

const fmt = (v, d = 2) =>
  v === null || v === undefined || Number.isNaN(v)
    ? '—'
    : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d });

const fmtPct = (v, d = 2) =>
  v === null || v === undefined ? '—' : (v >= 0 ? '+' : '') + Number(v).toFixed(d) + '%';

function productOf(code) {
  return state.products.find((p) => p.code === code) || { code, short: code, unit: '', name: code };
}

function fmtTs(ts) {
  if (ts == null) return '';
  if (typeof ts === 'number') {
    const d = new Date(ts);
    const p = (n) => String(n).padStart(2, '0');
    return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  }
  return ts.length >= 16 ? ts.slice(5, 16) : ts;
}

const PRICE_COLOR = { up: '#f6465d', down: '#0ecb81', flat: '#94a3b8' };
const CMP_COLOR = { AL: '#f6465d', AO: '#0ecb81', AHD: '#4c8dff' };
const colorFor = (code) => CMP_COLOR[code] || '#4c8dff';

function toast(msg, kind) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show' + (kind ? ' ' + kind : '');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { t.className = 'toast'; }, 2600);
}

/* ------------------------------------------------------------------ fetch */
async function getJSON(url, opts) {
  const r = await fetch(API + url, Object.assign({ cache: 'no-store' }, opts || {}));
  if (!r.ok) throw new Error(url + ' ' + r.status);
  return r.json();
}
async function postJSON(url, body) {
  return getJSON(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
}

/* ------------------------------------------------------------- loaders */
async function loadProducts() {
  const d = await getJSON('/api/products');
  state.products = d.products || [];
  renderQuoteCards();
  renderProdTabs();
  renderFcProdSeg();
  renderHistCodeSeg();
  renderDataMeta();
  $('#aboutVer').textContent = (d.horizons && d.horizons.length)
    ? 'v2.7 · ' + d.horizons.length + ' 周期' : 'v2.7';
}

async function loadForecast() {
  const d = await getJSON('/api/forecast');
  state.forecasts = d.forecasts || {};
  renderHorizons();
  renderIndicators();
}

async function loadKline(code, period) {
  const d = await getJSON(`/api/app/kline?code=${code}&period=${period}&limit=800`);
  state.bars = d.bars || [];
  drawChart();
}

async function loadCompare(period) {
  const series = [];
  await Promise.all(state.products.map(async (p) => {
    const d = await getJSON(`/api/app/kline?code=${p.code}&period=${period}&limit=800`);
    series.push({ code: p.code, bars: d.bars || [], color: colorFor(p.code) });
  }));
  state.compareSeries = series;
  drawChart();
}

async function loadHistory(code) {
  const d = await getJSON(`/api/history?code=${code}&days=${state.histDays}`);
  renderHistory(d);
}

async function loadDbStatus() {
  try {
    const [db, af] = await Promise.all([getJSON('/api/db/status'), getJSON('/api/autofill/status')]);
    renderDbStatus(db);
    renderAutofill(af);
  } catch (e) { console.error(e); }
}

/* ------------------------------------------------------------- quote cards */
function renderQuoteCards() {
  const wrap = $('#quoteCards');
  wrap.textContent = '';
  for (const p of state.products) {
    const q = state.quotes[p.code] || p.quote || {};
    const last = q.last;
    const prev = q.prev_close;
    const chg = last != null && prev ? ((last - prev) / prev) * 100 : null;
    const cls = chg == null ? '' : chg > 0.0001 ? 'is-up' : chg < -0.0001 ? 'is-down' : '';

    const card = el('article', 'card ' + cls);
    const head = el('div', 'card-head');
    head.append(el('span', 'card-name', p.short), el('span', 'card-code', p.code));
    card.append(head);

    const price = el('div', 'card-price', fmt(last, last > 1000 ? 1 : 2));
    card.append(price);

    const delta = el('div', 'card-delta ' + (chg == null ? 'flat' : chg > 0 ? 'up' : chg < 0 ? 'down' : 'flat'));
    const diff = last != null && prev ? last - prev : null;
    delta.textContent =
      chg == null ? '—' : `${diff >= 0 ? '+' : ''}${fmt(diff, 2)}　${fmtPct(chg)}`;
    card.append(delta);

    const meta = el('div', 'card-meta');
    const mk = (label, value) => {
      const s = el('span');
      s.append(document.createTextNode(label + ' '));
      s.append(el('b', '', value));
      return s;
    };
    meta.append(mk('最高', fmt(q.high, 1)), mk('最低', fmt(q.low, 1)), mk('单位', p.unit || ''));
    card.append(meta);
    wrap.append(card);
  }
}

/* ------------------------------------------------------------------ tabs */
function renderProdTabs() {
  const wrap = $('#prodTabs');
  wrap.textContent = '';
  for (const p of state.products) {
    const b = el('button', p.code === state.activeCode ? 'on' : '', p.short);
    b.setAttribute('role', 'tab');
    b.onclick = () => {
      state.activeCode = p.code;
      renderProdTabs();
      if (state.compare) loadCompare(state.activePeriod).catch(() => {});
      else loadKline(p.code, state.activePeriod).catch(() => {});
    };
    wrap.append(b);
  }
}

function renderFcProdSeg() {
  const wrap = $('#fcProdSeg');
  wrap.textContent = '';
  for (const p of state.products) {
    const b = el('button', p.code === state.hzCode ? 'on' : '', p.short);
    b.onclick = () => {
      state.hzCode = p.code;
      renderFcProdSeg();
      renderHorizons();
      renderIndicators();
    };
    wrap.append(b);
  }
}

function renderHistCodeSeg() {
  const wrap = $('#histCodeSeg');
  wrap.textContent = '';
  for (const p of state.products) {
    const b = el('button', p.code === state.histCode ? 'on' : '', p.short);
    b.onclick = () => {
      state.histCode = p.code;
      renderHistCodeSeg();
      loadHistory(p.code).catch(() => {});
    };
    wrap.append(b);
  }
}

/* ------------------------------------------------------------------ chart */
const cv = $('#mainChart');
const ctx = cv.getContext('2d');

function resizeCanvas() {
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const rect = cv.getBoundingClientRect();
  cv.width = Math.max(1, Math.round(rect.width * dpr));
  cv.height = Math.max(1, Math.round(rect.height * dpr));
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}

function niceTicks(min, max, count) {
  const span = max - min || 1;
  const raw = span / count;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const norm = raw / mag;
  let step;
  if (norm <= 1) step = 1;
  else if (norm <= 2) step = 2;
  else if (norm <= 2.5) step = 2.5;
  else if (norm <= 5) step = 5;
  else step = 10;
  step *= mag;
  const out = [];
  const start = Math.ceil(min / step) * step;
  for (let v = start; v <= max + 1e-9; v += step) out.push(v);
  return out;
}

function drawChart() {
  if (state.compare && state.compareSeries.length) drawCompare();
  else drawSingle();
}

function drawSingle() {
  resizeCanvas();
  const W = cv.clientWidth, H = cv.clientHeight;
  const padL = 62, padR = 16, padT = 14, padB = 26;
  const iw = W - padL - padR, ih = H - padT - padB;
  ctx.clearRect(0, 0, W, H);
  const bars = state.bars;
  if (!bars.length) {
    ctx.fillStyle = '#5f7085';
    ctx.font = '13px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('暂无数据', W / 2, H / 2);
    $('#chartRange').textContent = '—';
    $('#chartLegend').textContent = '';
    return;
  }
  const closes = bars.map((b) => b.c);
  const highs = bars.map((b) => (b.h == null ? b.c : b.h));
  const lows = bars.map((b) => (b.l == null ? b.c : b.l));
  let min = Math.min(...lows), max = Math.max(...highs);
  const padV = (max - min) * 0.08 || max * 0.002 || 1;
  min -= padV; max += padV;
  const x = (i) => padL + (bars.length === 1 ? iw / 2 : (i / (bars.length - 1)) * iw);
  const y = (v) => padT + ih - ((v - min) / (max - min)) * ih;

  const ticks = niceTicks(min, max, 5);
  ctx.font = '11px ui-monospace, monospace';
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (const t of ticks) {
    const yy = y(t);
    ctx.strokeStyle = getCss('--grid', '#17202f');
    ctx.beginPath(); ctx.moveTo(padL, yy); ctx.lineTo(W - padR, yy); ctx.stroke();
    ctx.fillStyle = '#5f7085';
    ctx.fillText(fmt(t, t > 1000 ? 0 : 2), padL - 8, yy);
  }
  const xCount = Math.min(6, bars.length);
  ctx.textAlign = 'center'; ctx.textBaseline = 'top';
  for (let k = 0; k < xCount; k++) {
    const idx = Math.round((k / Math.max(xCount - 1, 1)) * (bars.length - 1));
    const xx = x(idx);
    ctx.fillStyle = '#5f7085';
    ctx.fillText(fmtTs(bars[idx].ts), Math.min(Math.max(xx, padL + 22), W - padR - 22), H - padB + 7);
  }
  const up = closes[closes.length - 1] >= closes[0];
  const lineColor = up ? PRICE_COLOR.up : PRICE_COLOR.down;
  const grad = ctx.createLinearGradient(0, padT, 0, padT + ih);
  grad.addColorStop(0, up ? 'rgba(246,70,93,.22)' : 'rgba(14,203,129,.22)');
  grad.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.beginPath();
  ctx.moveTo(x(0), y(closes[0]));
  for (let i = 1; i < closes.length; i++) ctx.lineTo(x(i), y(closes[i]));
  ctx.lineTo(x(closes.length - 1), padT + ih);
  ctx.lineTo(x(0), padT + ih);
  ctx.closePath();
  ctx.fillStyle = grad; ctx.fill();
  ctx.beginPath();
  ctx.moveTo(x(0), y(closes[0]));
  for (let i = 1; i < closes.length; i++) ctx.lineTo(x(i), y(closes[i]));
  ctx.strokeStyle = lineColor; ctx.lineWidth = 1.6; ctx.lineJoin = 'round'; ctx.stroke();

  const lastY = y(closes[closes.length - 1]);
  ctx.setLineDash([4, 4]);
  ctx.strokeStyle = 'rgba(76,141,255,.45)'; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(padL, lastY); ctx.lineTo(W - padR, lastY); ctx.stroke();
  ctx.setLineDash([]);
  if (state.hover != null && state.hover >= 0 && state.hover < bars.length) {
    const hx = x(state.hover);
    ctx.strokeStyle = 'rgba(147,163,184,.35)';
    ctx.beginPath(); ctx.moveTo(hx, padT); ctx.lineTo(hx, padT + ih); ctx.stroke();
    ctx.beginPath(); ctx.arc(hx, y(closes[state.hover]), 3.2, 0, Math.PI * 2);
    ctx.fillStyle = lineColor; ctx.fill();
  }
  $('#chartRange').textContent = `${fmtTs(bars[0].ts)} → ${fmtTs(bars[bars.length - 1].ts)}　共 ${bars.length} 根`;
  $('#chartLegend').textContent = `收盘 ${fmt(closes[closes.length - 1], 2)}　区间 ${fmt(Math.min(...lows), 2)} ~ ${fmt(Math.max(...highs), 2)}`;
}

function drawCompare() {
  resizeCanvas();
  const W = cv.clientWidth, H = cv.clientHeight;
  const padL = 62, padR = 16, padT = 14, padB = 26;
  const iw = W - padL - padR, ih = H - padT - padB;
  ctx.clearRect(0, 0, W, H);
  const series = state.compareSeries.filter((s) => s.bars.length);
  if (!series.length) {
    ctx.fillStyle = '#5f7085'; ctx.font = '13px system-ui, sans-serif';
    ctx.textAlign = 'center'; ctx.fillText('暂无数据', W / 2, H / 2);
    $('#chartRange').textContent = '—'; $('#chartLegend').textContent = '';
    return;
  }
  const minLen = Math.min(...series.map((s) => s.bars.length));
  // 归一化为相对窗口首点的涨跌幅（%）
  const norm = series.map((s) => {
    const c0 = s.bars[0].c;
    return s.bars.slice(0, minLen).map((b) => (b.c - c0) / c0 * 100);
  });
  let min = Infinity, max = -Infinity;
  for (const arr of norm) for (const v of arr) { if (v < min) min = v; if (v > max) max = v; }
  if (min === max) { min -= 1; max += 1; }
  const padV = (max - min) * 0.08 || 1;
  min -= padV; max += padV;
  const x = (i) => padL + (minLen === 1 ? iw / 2 : (i / (minLen - 1)) * iw);
  const y = (v) => padT + ih - ((v - min) / (max - min)) * ih;

  const ticks = niceTicks(min, max, 5);
  ctx.font = '11px ui-monospace, monospace';
  ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
  for (const t of ticks) {
    const yy = y(t);
    ctx.strokeStyle = getCss('--grid', '#17202f');
    ctx.beginPath(); ctx.moveTo(padL, yy); ctx.lineTo(W - padR, yy); ctx.stroke();
    ctx.fillStyle = '#5f7085';
    ctx.fillText(t.toFixed(2) + '%', padL - 8, yy);
  }
  const xCount = Math.min(6, minLen);
  ctx.textAlign = 'center'; ctx.textBaseline = 'top';
  const primary = series[0].bars;
  for (let k = 0; k < xCount; k++) {
    const idx = Math.round((k / Math.max(xCount - 1, 1)) * (minLen - 1));
    const xx = x(idx);
    ctx.fillStyle = '#5f7085';
    ctx.fillText(fmtTs(primary[idx].ts), Math.min(Math.max(xx, padL + 22), W - padR - 22), H - padB + 7);
  }
  series.forEach((s, si) => {
    const arr = norm[si];
    ctx.beginPath();
    ctx.moveTo(x(0), y(arr[0]));
    for (let i = 1; i < arr.length; i++) ctx.lineTo(x(i), y(arr[i]));
    ctx.strokeStyle = s.color; ctx.lineWidth = 1.6; ctx.lineJoin = 'round'; ctx.stroke();
  });
  if (state.hover != null && state.hover >= 0 && state.hover < minLen) {
    const hx = x(state.hover);
    ctx.strokeStyle = 'rgba(147,163,184,.35)';
    ctx.beginPath(); ctx.moveTo(hx, padT); ctx.lineTo(hx, padT + ih); ctx.stroke();
    series.forEach((s) => {
      ctx.beginPath(); ctx.arc(hx, y(norm[series.indexOf(s)][state.hover]), 3, 0, Math.PI * 2);
      ctx.fillStyle = s.color; ctx.fill();
    });
  }
  $('#chartRange').textContent = `三品种对比 · 相对窗口首点涨跌幅　共 ${minLen} 根对齐`;
  $('#chartLegend').textContent = series.map((s) => `${productOf(s.code).short} ${s.color}`).join('　');
}

function getCss(varName, fallback) {
  const v = getComputedStyle(document.body).getPropertyValue(varName).trim();
  return v || fallback;
}

/* -------------------------------------------------------------- horizons */
function dirClass(d) {
  if (d === '上涨') return 'up';
  if (d === '下跌') return 'down';
  if (d === '震荡') return 'flat';
  return 'na';
}

function renderHorizons() {
  const wrap = $('#horizons');
  wrap.textContent = '';
  const f = state.forecasts[state.hzCode];
  if (!f || !f.horizons) {
    wrap.append(el('p', 'muted sm', '预测计算中…'));
    return;
  }
  for (const h of f.horizons) {
    const box = el('div', 'hz');
    box.append(el('div', 'hz-label', h.label));
    const dc = dirClass(h.direction);
    box.append(el('div', 'hz-dir ' + dc, h.direction));
    const bar = el('div', 'hz-bar');
    const fill = el('i', dc);
    fill.style.width = Math.round(Math.abs(h.p_up - 0.5) * 200) + '%';
    if (h.direction === '数据不足') fill.style.width = '0%';
    bar.append(fill);
    box.append(bar);
    const meter = el('div', 'hz-meter');
    meter.append(el('span', '', 'P(涨) ' + (h.p_up * 100).toFixed(1) + '%'));
    meter.append(el('span', '', fmtPct(h.exp_move_pct, 2)));
    box.append(meter);
    const sub = el('div', 'hz-meter');
    sub.append(el('span', '', '胜率 ' + (h.accuracy * 100).toFixed(1) + '%'));
    sub.append(el('span', '', '目标 ' + fmt(h.target_price, 2)));
    box.append(sub);
    if (h.note) box.append(el('div', 'hz-note', h.note));
    wrap.append(box);
  }
}

/* ------------------------------------------------------------ indicators */
const IND_MAP = [
  ['rsi', 'RSI(14)', 1],
  ['macd_hist', 'MACD 柱', 4],
  ['atr_pct', 'ATR 占价 %', 3],
  ['boll_pos', '布林位置 0-1', 3],
  ['vol20_pct', '20 期波动率 %', 3],
  ['range_pos', '120 期区间位置', 3],
  ['ema5', 'EMA5', 2],
  ['ema20', 'EMA20', 2],
  ['ema60', 'EMA60', 2],
];

function renderIndicators() {
  const wrap = $('#indicators');
  wrap.textContent = '';
  const f = state.forecasts[state.hzCode];
  const ind = (f && f.indicators) || {};
  for (const [key, label, d] of IND_MAP) {
    const cell = el('div', 'ind');
    cell.append(el('div', 'ind-k', label));
    const v = ind[key];
    let cls = '';
    if (key === 'macd_hist' && typeof v === 'number') cls = v > 0 ? 'up' : v < 0 ? 'down' : '';
    const val = el('div', 'ind-v ' + cls, v === undefined ? '—' : fmt(v, d));
    cell.append(val);
    wrap.append(cell);
  }
  const rec = $('#recommendation');
  rec.textContent = '';
  if (f) {
    rec.append(el('strong', '', `综合研判：${f.overall}（评分 ${fmt(f.score, 2)}）　`),
      document.createTextNode(f.recommendation || ''));
  }
}

/* ------------------------------------------------------------ history tab */
function renderHistory(d) {
  const body = $('#histBody');
  body.textContent = '';
  const rows = (d && d.horizons) || [];
  $('#histMeta').textContent = `品种 ${productOf(state.histCode).name} · 窗口 ${d ? d.days : 30} 天`;
  if (!rows.length) {
    const tr = el('tr');
    const td = el('td'); td.colSpan = 5; td.className = 'muted sm';
    td.textContent = '暂无历史记录（预测将随运行逐轮累积）';
    tr.append(td); body.append(tr);
    return;
  }
  for (const h of rows) {
    const tr = el('tr');
    tr.append(el('td', '', h.label));
    tr.append(el('td', '', Number(h.total).toLocaleString('zh-CN')));
    tr.append(el('td', '', Number(h.hits).toLocaleString('zh-CN')));
    const acc = h.accuracy == null ? '—' : (h.accuracy * 100).toFixed(1) + '%';
    tr.append(el('td', h.accuracy == null ? '' : (h.accuracy >= 0.5 ? 'up' : 'down'), acc));
    tr.append(el('td', 'muted sm', h.total ? '' : '样本不足'));
    body.append(tr);
  }
}

/* ------------------------------------------------------------ db / autofill */
function renderDbStatus(db) {
  const wrap = $('#dbStatus');
  wrap.textContent = '';
  if (!db || !db.periods) { wrap.append(el('p', 'muted sm', '—')); return; }
  const head = el('div', 'db-head', '各品种×周期覆盖（根数）');
  wrap.append(head);
  const grid = el('div', 'db-grid');
  for (const p of state.products) {
    for (const period of ['1m', '5m', '15m', '30m', '60m', '1d']) {
      const k = `${p.code}|${period}`;
      const rec = db.periods[k] || { n: 0 };
      const cell = el('div', 'db-cell');
      cell.append(el('div', 'db-k', `${p.short} ${period}`));
      cell.append(el('div', 'db-n', Number(rec.n).toLocaleString('zh-CN')));
      grid.append(cell);
    }
  }
  wrap.append(grid);
}

function renderAutofill(af) {
  const wrap = $('#autofillStatus');
  wrap.textContent = '';
  if (!af || !af.ok) { wrap.append(el('p', 'muted sm', '尚未执行启动自检')); return; }
  const head = el('div', 'db-head', `启动自检 · 扫描 ${af.scan_days} 天 · 覆盖率阈值 ${Math.round(af.coverage_threshold * 100)}%`);
  wrap.append(head);
  let missing = 0, unrecoverable = 0;
  const list = el('div', 'af-list');
  for (const pr of af.products || []) {
    missing += pr.missing_count || 0;
    unrecoverable += pr.unrecoverable_count || 0;
    if (pr.missing_count || pr.unrecoverable_count) {
      const line = el('div', 'af-line');
      const seg = [];
      (pr.missing || []).forEach((m) => seg.push(`窗口内缺失 ${m.expected - m.actual}/${m.expected}`));
      (pr.unrecoverable || []).forEach((m) => seg.push(`不可补 ${m.expected - m.actual}/${m.expected}`));
      line.append(el('b', '', productOf(pr.code).short + '：'));
      line.append(document.createTextNode(seg.join('；') || '完整'));
      list.append(line);
    }
  }
  wrap.append(el('div', 'af-sum',
    `窗口内缺失时段 ${missing}，已补齐 ${af.filled_reachable || 0}；窗口外不可补 ${unrecoverable}`));
  wrap.append(list);
  if (af.note) wrap.append(el('div', 'muted sm', af.note));
}

/* -------------------------------------------------------------- data meta */
function renderDataMeta() {
  const wrap = $('#dataMeta');
  wrap.textContent = '';
  for (const p of state.products) {
    const cell = el('div', 'data-cell');
    cell.append(el('div', 't', p.short));
    const rows = [
      ['实时快照', p.snapshots ?? 0],
      ['1 分钟 K 线', p.minute_bars ?? 0],
      ['日线', p.daily_bars ?? 0],
    ];
    for (const [k, v] of rows) {
      const r = el('div', 'r');
      r.append(el('span', '', k), el('b', '', Number(v).toLocaleString('zh-CN')));
      cell.append(r);
    }
    wrap.append(cell);
  }
}

/* ------------------------------------------------------------------- ws */
let ws;
function connectWS() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws`);
  ws.onopen = () => { $('#connDot').className = 'dot live'; $('#connText').textContent = '实时连接'; };
  ws.onclose = () => {
    $('#connDot').className = 'dot offline'; $('#connText').textContent = '已断开，重连中…';
    setTimeout(connectWS, 4000);
  };
  ws.onerror = () => ws.close();
  ws.onmessage = (ev) => {
    let d;
    try { d = JSON.parse(ev.data); } catch { return; }
    if (d.quotes) { state.quotes = d.quotes; renderQuoteCards(); }
    if (d.forecast) {
      for (const [code, f] of Object.entries(d.forecast)) {
        if (state.forecasts[code]) {
          state.forecasts[code].price = f.price;
          state.forecasts[code].overall = f.overall;
          state.forecasts[code].score = f.score;
          if (f.horizons) state.forecasts[code].horizons = f.horizons;
        }
      }
      renderHorizons();
    }
  };
}

/* ----------------------------------------------------------------- clock */
function tickClock() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  $('#clock').textContent = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/* ------------------------------------------------------------- tab switch */
function switchTab(name) {
  state.activeTab = name;
  for (const t of $('#topTabs').children) t.classList.toggle('on', t.dataset.tab === name);
  for (const v of document.querySelectorAll('.view')) v.classList.toggle('active', v.id === 'v-' + name);
  if (name === 'market') { if (state.compare) loadCompare(state.activePeriod).catch(() => {}); else loadKline(state.activeCode, state.activePeriod).catch(() => {}); }
  else if (name === 'forecast') loadForecast().catch(() => {});
  else if (name === 'history') loadHistory(state.histCode).catch(() => {});
  else if (name === 'settings') loadDbStatus().catch(() => {});
}

/* ------------------------------------------------------------- interaction */
$('#topTabs').addEventListener('click', (e) => {
  const b = e.target.closest('.tab'); if (!b) return; switchTab(b.dataset.tab);
});

$('#periodSeg').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-period]'); if (!b) return;
  state.activePeriod = b.dataset.period;
  for (const x of $('#periodSeg').children) x.classList.toggle('on', x === b);
  if (state.compare) loadCompare(state.activePeriod).catch(() => {});
  else loadKline(state.activeCode, state.activePeriod).catch(() => {});
});

$('#compareChk').addEventListener('change', (e) => {
  state.compare = e.target.checked;
  if (state.compare) loadCompare(state.activePeriod).catch(() => {});
  else loadKline(state.activeCode, state.activePeriod).catch(() => {});
});

cv.addEventListener('mousemove', (e) => {
  const rect = cv.getBoundingClientRect();
  const padL = 62, padR = 16;
  const iw = rect.width - padL - padR;
  const ref = state.compare ? Math.min(...state.compareSeries.map((s) => s.bars.length)) : state.bars.length;
  const rel = Math.min(Math.max(e.clientX - rect.left - padL, 0), iw);
  const idx = ref <= 1 ? 0 : Math.round((rel / iw) * (ref - 1));
  state.hover = idx; drawChart();
  const b = state.compare
    ? state.compareSeries[0].bars[idx]
    : state.bars[idx];
  if (!b) return;
  const tip = $('#chartTip');
  tip.hidden = false;
  tip.innerHTML =
    `<div>${fmtTs(b.ts)}</div>` +
    `<div>开 ${fmt(b.o, 2)}　高 ${fmt(b.h, 2)}</div>` +
    `<div>低 ${fmt(b.l, 2)}　收 ${fmt(b.c, 2)}</div>` +
    `<div>量 ${fmt(b.v, 0)}</div>`;
  const tw = tip.offsetWidth || 150;
  let left = e.clientX - rect.left + 14;
  if (left + tw > rect.width - 8) left = e.clientX - rect.left - tw - 14;
  tip.style.left = Math.max(4, left) + 'px';
  tip.style.top = Math.min(Math.max(e.clientY - rect.top - 10, 4), rect.height - 92) + 'px';
});

cv.addEventListener('mouseleave', () => { state.hover = null; $('#chartTip').hidden = true; drawChart(); });

let rt;
window.addEventListener('resize', () => { clearTimeout(rt); rt = setTimeout(drawChart, 120); });

/* ------------------------------------------------------------- settings */
function applyTheme(theme) {
  state.theme = theme;
  document.body.className = 'theme-' + theme;
  document.getElementById('themeColor').setAttribute('content', theme === 'light' ? '#eef2f7' : '#05070C');
  for (const b of $('#themeSeg').children) b.classList.toggle('on', b.dataset.theme === theme);
  try { localStorage.setItem('ff_web_theme', theme); } catch (e) {}
}
$('#themeSeg').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-theme]'); if (!b) return; applyTheme(b.dataset.theme);
});

function applyRefresh(ms) {
  state.refreshMs = ms;
  for (const b of $('#refreshSeg').children) b.classList.toggle('on', Number(b.dataset.ms) === ms);
  try { localStorage.setItem('ff_web_refresh', String(ms)); } catch (e) {}
  restartPoll();
}
$('#refreshSeg').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-ms]'); if (!b) return; applyRefresh(Number(b.dataset.ms));
});

async function loadLlmConfig() {
  try {
    const c = await getJSON('/api/llm/config');
    $('#llmBase').value = c.base || '';
    $('#llmModel').value = c.model || '';
    $('#llmMask').textContent = c.hasKey ? '（已配置）' : '（未配置）';
  } catch (e) {}
}
$('#btnLlmSave').addEventListener('click', async () => {
  try {
    await postJSON('/api/llm/config', {
      base: $('#llmBase').value.trim(), model: $('#llmModel').value.trim(),
      key: $('#llmKey').value,
    });
    $('#llmKey').value = '';
    await loadLlmConfig();
    toast('大模型配置已保存（Key 仅存服务端）');
  } catch (e) { toast('保存失败：' + e.message, 'err'); }
});
$('#btnLlmClear').addEventListener('click', async () => {
  try {
    await postJSON('/api/llm/config', { base: '', model: '', key: '__CLEAR__' });
    await loadLlmConfig();
    toast('API Key 已清除');
  } catch (e) { toast('清除失败：' + e.message, 'err'); }
});

$('#btnLlm').addEventListener('click', async () => {
  const box = $('#llmBox');
  box.textContent = '分析中…';
  $('#btnLlm').disabled = true;
  try {
    const d = await postJSON('/api/llm/analyze', { code: state.hzCode, period: state.activePeriod });
    if (d.ok) box.textContent = d.text;
    else box.textContent = '分析失败：' + (d.error || '未知错误');
  } catch (e) { box.textContent = '请求失败：' + e.message; }
  $('#btnLlm').disabled = false;
});

$('#btnAutofill').addEventListener('click', async () => {
  $('#btnAutofill').disabled = true;
  try {
    const d = await postJSON('/api/autofill/run', {});
    if (d && d.ok) toast('启动自检补全完成');
    else toast('补全失败', 'err');
    await loadDbStatus();
  } catch (e) { toast('补全失败：' + e.message, 'err'); }
  $('#btnAutofill').disabled = false;
});

/* ------------------------------------------------------------- poll loop */
let pollTimer = null;
function restartPoll() {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(() => {
    if (state.activeTab === 'market') {
      if (state.compare) loadCompare(state.activePeriod).catch(() => {});
      else loadKline(state.activeCode, state.activePeriod).catch(() => {});
    } else if (state.activeTab === 'forecast') loadForecast().catch(() => {});
    else if (state.activeTab === 'history') loadHistory(state.histCode).catch(() => {});
    else if (state.activeTab === 'settings') loadDbStatus().catch(() => {});
  }, state.refreshMs);
}

/* ------------------------------------------------------------------- boot */
async function boot() {
  try { state.theme = localStorage.getItem('ff_web_theme') || 'dark'; } catch (e) {}
  try { state.refreshMs = Number(localStorage.getItem('ff_web_refresh')) || 15000; } catch (e) {}
  applyTheme(state.theme);
  applyRefresh(state.refreshMs);

  tickClock();
  setInterval(tickClock, 1000);
  try {
    await loadProducts();
    await Promise.all([
      loadForecast(),
      loadKline(state.activeCode, state.activePeriod),
    ]);
  } catch (e) { console.error(e); }
  connectWS();
  restartPoll();
  setInterval(() => { loadProducts().catch(() => {}); }, 30000);
}

boot();
