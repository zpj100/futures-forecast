'use strict';

const API = '';
const state = {
  products: [],
  forecasts: {},
  quotes: {},
  news: [],
  activeCode: 'AL',
  activePeriod: '1m',
  newsCode: '',
  bars: [],
  hzCode: 'AL',
  hover: null,
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

const fmtPct = (v, d = 2) => (v === null || v === undefined ? '—' : (v >= 0 ? '+' : '') + Number(v).toFixed(d) + '%');

function productOf(code) {
  return state.products.find((p) => p.code === code) || { code, short: code, unit: '', name: code };
}

/* ------------------------------------------------------------------ fetch */
async function getJSON(url) {
  const r = await fetch(API + url, { cache: 'no-store' });
  if (!r.ok) throw new Error(url + ' ' + r.status);
  return r.json();
}

async function loadProducts() {
  const d = await getJSON('/api/products');
  state.products = d.products;
  renderQuoteCards();
  renderProdTabs();
  renderFcProdSeg();
  renderDataMeta();
}

async function loadForecast() {
  const d = await getJSON('/api/forecast');
  state.forecasts = d.forecasts || {};
  renderHorizons();
  renderIndicators();
}

async function loadKline(code, period) {
  const d = await getJSON(`/api/kline/${code}?period=${period}&limit=400`);
  state.bars = d.bars || [];
  drawChart();
}

async function loadNews(code) {
  const d = await getJSON('/api/news?limit=40' + (code ? `&code=${code}` : ''));
  state.news = d.news || [];
  renderNews();
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

/* ------------------------------------------------------------------- tabs */
function renderProdTabs() {
  const wrap = $('#prodTabs');
  wrap.textContent = '';
  for (const p of state.products) {
    const b = el('button', p.code === state.activeCode ? 'on' : '', p.short);
    b.setAttribute('role', 'tab');
    b.setAttribute('aria-selected', String(p.code === state.activeCode));
    b.onclick = () => {
      state.activeCode = p.code;
      renderProdTabs();
      loadKline(p.code, state.activePeriod);
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
  resizeCanvas();
  const W = cv.clientWidth;
  const H = cv.clientHeight;
  const padL = 62, padR = 16, padT = 14, padB = 26;
  const iw = W - padL - padR;
  const ih = H - padT - padB;

  ctx.clearRect(0, 0, W, H);
  if (!state.bars.length) {
    ctx.fillStyle = '#5f7085';
    ctx.font = '13px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('暂无数据', W / 2, H / 2);
    return;
  }

  const bars = state.bars;
  const closes = bars.map((b) => b.c);
  const highs = bars.map((b) => (b.h == null ? b.c : b.h));
  const lows = bars.map((b) => (b.l == null ? b.c : b.l));

  let min = Math.min(...lows);
  let max = Math.max(...highs);
  const padV = (max - min) * 0.08 || max * 0.002 || 1;
  min -= padV;
  max += padV;

  const x = (i) => padL + (bars.length === 1 ? iw / 2 : (i / (bars.length - 1)) * iw);
  const y = (v) => padT + ih - ((v - min) / (max - min)) * ih;

  // grid + y labels
  const ticks = niceTicks(min, max, 5);
  ctx.font = '11px ui-monospace, monospace';
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (const t of ticks) {
    const yy = y(t);
    ctx.strokeStyle = '#17202f';
    ctx.beginPath();
    ctx.moveTo(padL, yy);
    ctx.lineTo(W - padR, yy);
    ctx.stroke();
    ctx.fillStyle = '#5f7085';
    ctx.fillText(fmt(t, t > 1000 ? 0 : 2), padL - 8, yy);
  }

  // x labels
  const xCount = Math.min(6, bars.length);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  for (let k = 0; k < xCount; k++) {
    const idx = Math.round((k / Math.max(xCount - 1, 1)) * (bars.length - 1));
    const ts = bars[idx].ts || '';
    const label = ts.length >= 16 ? ts.slice(5, 16) : ts;
    const xx = x(idx);
    ctx.fillStyle = '#5f7085';
    ctx.fillText(label, Math.min(Math.max(xx, padL + 22), W - padR - 22), H - padB + 7);
  }

  // gradient fill
  const up = closes[closes.length - 1] >= closes[0];
  const lineColor = up ? '#f6465d' : '#0ecb81';
  const grad = ctx.createLinearGradient(0, padT, 0, padT + ih);
  grad.addColorStop(0, up ? 'rgba(246,70,93,.22)' : 'rgba(14,203,129,.22)');
  grad.addColorStop(1, 'rgba(0,0,0,0)');

  ctx.beginPath();
  ctx.moveTo(x(0), y(closes[0]));
  for (let i = 1; i < closes.length; i++) ctx.lineTo(x(i), y(closes[i]));
  ctx.lineTo(x(closes.length - 1), padT + ih);
  ctx.lineTo(x(0), padT + ih);
  ctx.closePath();
  ctx.fillStyle = grad;
  ctx.fill();

  // line
  ctx.beginPath();
  ctx.moveTo(x(0), y(closes[0]));
  for (let i = 1; i < closes.length; i++) ctx.lineTo(x(i), y(closes[i]));
  ctx.strokeStyle = lineColor;
  ctx.lineWidth = 1.6;
  ctx.lineJoin = 'round';
  ctx.stroke();

  // last price marker
  const lastY = y(closes[closes.length - 1]);
  ctx.setLineDash([4, 4]);
  ctx.strokeStyle = 'rgba(76,141,255,.45)';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(padL, lastY);
  ctx.lineTo(W - padR, lastY);
  ctx.stroke();
  ctx.setLineDash([]);

  // hover crosshair
  if (state.hover != null && state.hover >= 0 && state.hover < bars.length) {
    const hx = x(state.hover);
    ctx.strokeStyle = 'rgba(147,163,184,.35)';
    ctx.beginPath();
    ctx.moveTo(hx, padT);
    ctx.lineTo(hx, padT + ih);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(hx, y(closes[state.hover]), 3.2, 0, Math.PI * 2);
    ctx.fillStyle = lineColor;
    ctx.fill();
  }

  // range + legend
  $('#chartRange').textContent =
    `${bars[0].ts || ''} → ${bars[bars.length - 1].ts || ''}　共 ${bars.length} 根`;
  $('#chartLegend').textContent = `收盘 ${fmt(closes[closes.length - 1], 2)}　区间 ${fmt(Math.min(...lows), 2)} ~ ${fmt(Math.max(...highs), 2)}`;
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
    const s = el('strong', '', `综合研判：${f.overall}（评分 ${fmt(f.score, 2)}）　`);
    rec.append(s, document.createTextNode(f.recommendation || ''));
  }
}

/* ------------------------------------------------------------------- news */
function renderNews() {
  const wrap = $('#news');
  wrap.textContent = '';
  if (!state.news.length) {
    wrap.append(el('p', 'muted sm', '暂无资讯'));
    return;
  }
  for (const n of state.news) {
    const a = el('a', 'news-item');
    a.href = n.url || '#';
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    if (!n.url) a.onclick = (e) => e.preventDefault();

    const top = el('div', 'news-top');
    top.append(el('span', 'news-src', n.source || ''));
    const ts = (n.ts || '').replace('T', ' ').slice(0, 16);
    if (ts) top.append(el('span', '', ts));
    for (const c of (n.codes || '').split(',').filter(Boolean)) {
      top.append(el('span', 'news-tag', productOf(c).short));
    }
    a.append(top);
    a.append(el('div', 'news-title', n.title));
    if (n.summary) a.append(el('div', 'news-sum', n.summary));
    wrap.append(a);
  }
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
function connectWS() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws`);
  ws.onopen = () => {
    $('#connDot').className = 'dot live';
    $('#connText').textContent = '实时连接';
  };
  ws.onclose = () => {
    $('#connDot').className = 'dot offline';
    $('#connText').textContent = '已断开，重连中…';
    setTimeout(connectWS, 4000);
  };
  ws.onerror = () => ws.close();
  ws.onmessage = (ev) => {
    let d;
    try { d = JSON.parse(ev.data); } catch { return; }
    if (d.quotes) {
      state.quotes = d.quotes;
      renderQuoteCards();
    }
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

/* ------------------------------------------------------------- interaction */
$('#periodSeg').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-period]');
  if (!b) return;
  state.activePeriod = b.dataset.period;
  for (const x of $('#periodSeg').children) x.classList.toggle('on', x === b);
  loadKline(state.activeCode, state.activePeriod);
});

$('#newsSeg').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-code]');
  if (!b) return;
  state.newsCode = b.dataset.code;
  for (const x of $('#newsSeg').children) x.classList.toggle('on', x === b);
  loadNews(state.newsCode);
});

cv.addEventListener('mousemove', (e) => {
  const rect = cv.getBoundingClientRect();
  const padL = 62, padR = 16;
  const iw = rect.width - padL - padR;
  const rel = Math.min(Math.max(e.clientX - rect.left - padL, 0), iw);
  const idx = state.bars.length <= 1 ? 0 : Math.round((rel / iw) * (state.bars.length - 1));
  state.hover = idx;
  drawChart();

  const b = state.bars[idx];
  if (!b) return;
  const tip = $('#chartTip');
  tip.hidden = false;
  tip.innerHTML =
    `<div>${b.ts || ''}</div>` +
    `<div>开 ${fmt(b.o, 2)}　高 ${fmt(b.h, 2)}</div>` +
    `<div>低 ${fmt(b.l, 2)}　收 ${fmt(b.c, 2)}</div>` +
    `<div>量 ${fmt(b.v, 0)}</div>`;
  const tw = tip.offsetWidth || 150;
  let left = e.clientX - rect.left + 14;
  if (left + tw > rect.width - 8) left = e.clientX - rect.left - tw - 14;
  tip.style.left = Math.max(4, left) + 'px';
  tip.style.top = Math.min(Math.max(e.clientY - rect.top - 10, 4), rect.height - 92) + 'px';
});

cv.addEventListener('mouseleave', () => {
  state.hover = null;
  $('#chartTip').hidden = true;
  drawChart();
});

let rt;
window.addEventListener('resize', () => {
  clearTimeout(rt);
  rt = setTimeout(drawChart, 120);
});

/* ------------------------------------------------------------------- boot */
async function boot() {
  tickClock();
  setInterval(tickClock, 1000);
  try {
    await loadProducts();
    await Promise.all([
      loadForecast(),
      loadKline(state.activeCode, state.activePeriod),
      loadNews(''),
    ]);
  } catch (e) {
    console.error(e);
  }
  connectWS();
  setInterval(() => { loadForecast().catch(() => {}); }, 20000);
  setInterval(() => { loadProducts().catch(() => {}); }, 30000);
  setInterval(() => { loadKline(state.activeCode, state.activePeriod).catch(() => {}); }, 60000);
  setInterval(() => { loadNews(state.newsCode).catch(() => {}); }, 120000);
}

boot();
