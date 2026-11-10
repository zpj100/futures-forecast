/* 学记期货 · 趋势预测 2.1 —— 手机端单页应用
   数据来自本机 Native 桥（Java 采集 + SQLite），无外部后端依赖。 */
(function () {
  'use strict';

  var N = window.Native || null;
  var $ = function (s) { return document.querySelector(s); };
  var DEMO = !N;
  // 调试/预览用：?theme=dark|light 可强制配色（正常启动不会命中）
  var FORCED = (location.search.match(/[?&]theme=(dark|light)/) || [])[1] || '';

  var S = {
    state: null,
    code: 'AL',
    period: '1m',
    tab: 'market',
    histCode: '',
    settings: {},
    update: null,
    llmBusy: false,
    compare: false,   // 三品种叠加（涨跌幅归一化）
    hidden: {},       // 对比模式下被隐藏的序列
    chart: null,      // 当前图表几何，供十字星重绘
    crossTimer: 0,
    crossActive: false, // 按住十字星期间冻结曲线跟随
    predBusy: false,
    lastSig: null,       // 上次渲染的「数据 + 视图」签名，5 秒定时拉取无变化时跳过重建
    // 服务端对照开关：on/base 持久化到 localStorage，snap 为最近一次成功快照
    server: {
      on: false, base: 'http://127.0.0.1:8000', snap: null,
      bars: [], snapCode: '', snapPeriod: '', lastBarTs: 0,
      ts: 0, version: '', compareText: '', failUntil: 0
    },
    // 数据库同步（手机 ↔ 服务端）：base 持久化到 localStorage，busy 防并发
    db: {
      busy: false,
      base: (function () {
        try { return localStorage.getItem('ff_db_base') || 'http://127.0.0.1:8000'; }
        catch (e) { return 'http://127.0.0.1:8000'; }
      })(),
      failedBatches: 0,
      warns: []
    }
  };

  var PERIODS = [
    { k: '1s', label: '1秒', tick: 0 },
    { k: '30s', label: '30秒', tick: 30 },
    { k: '1m', label: '1分', step: 1 },
    { k: '5m', label: '5分', step: 5 },
    { k: '15m', label: '15分', step: 15 },
    { k: '30m', label: '30分', step: 30 },
    { k: '60m', label: '60分', step: 60 },
    { k: '1d', label: '日线', step: 0 }
  ];

  var INTERVALS = [
    { v: 5, label: '5秒' },
    { v: 10, label: '10秒' },
    { v: 30, label: '30秒' },
    { v: 60, label: '1分钟' },
    { v: 300, label: '5分钟' }
  ];

  var THEMES = [
    { v: 'dark', label: '黑夜' },
    { v: 'light', label: '白天' }
  ];

  // ---------------- 主题 ----------------

  function isLight() {
    return document.documentElement.classList.contains('light');
  }

  /** 应用配色。notify=true 时同时写盘并同步原生底色/状态栏。 */
  function applyTheme(t, notify) {
    var light = t === 'light';
    document.documentElement.classList.toggle('light', light);
    var meta = document.getElementById('themeColor');
    if (meta) meta.setAttribute('content', light ? '#EEF2F7' : '#05070C');
    if (notify && N && N.setTheme) {
      try { N.setTheme(t); } catch (e) { /* ignore */ }
    }
  }

  function upColor() { return isLight() ? '#d92d20' : '#f6465d'; }
  function downColor() { return isLight() ? '#0e8f52' : '#2ebd85'; }
  function upRGB() { return isLight() ? '217,45,32' : '246,70,93'; }
  function downRGB() { return isLight() ? '14,143,82' : '46,189,133'; }

  // ---------------- 工具 ----------------

  function num(v, d) {
    if (v === null || v === undefined || isNaN(v)) return '—';
    return Number(v).toFixed(d === undefined ? 2 : d);
  }

  function dirClass(dir) {
    if (dir === '上涨' || dir === '看涨') return 'up';
    if (dir === '下跌' || dir === '看跌') return 'down';
    return 'flat';
  }

  function chgClass(v) { return v > 0 ? 'up' : v < 0 ? 'down' : 'flat'; }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function timeStr(ms) {
    if (!ms) return '—';
    var d = new Date(ms);
    var p = function (n) { return n < 10 ? '0' + n : '' + n; };
    return p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  function nowStr() {
    var d = new Date();
    var p = function (n) { return n < 10 ? '0' + n : '' + n; };
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }

  function dateStr(ms) {
    if (!ms) return '—';
    var d = new Date(ms);
    var p = function (n) { return n < 10 ? '0' + n : '' + n; };
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
  }

  function toast(msg) {
    var t = $('#toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(t._tm);
    t._tm = setTimeout(function () { t.classList.remove('show'); }, 2200);
  }

  function setLive(text, cls) {
    var el = $('#live');
    el.className = 'live' + (cls ? ' ' + cls : '');
    el.innerHTML = '<span class="live-dot"></span>' + esc(text);
  }

  // ---------------- 数据拉取 ----------------

  /**
   * 变化检测签名：整份 state 快照（含最新报价时间戳、K 线尾点、统计/预测/同步时间等）
   * 叠加当前品种 / 周期 / 对比 / 配色。任一变化才需要重新取数与重建 DOM。
   */
  function stateSig(raw) {
    var ps = (S.state && S.state.products) || [];
    var qts = 0;   // 最新报价时间戳：图表最后一根（尾点）随它变化
    ps.forEach(function (p) {
      var t = p.quote && p.quote.ts;
      if (t && t > qts) qts = t;
    });
    // 服务端模式：用快照 K 线末根 ts 作为数据版本信号。不能把每次都变化的
    // snap.server.ts 或整份快照 JSON 放进签名，否则 5 秒轮询会反复重建 DOM。
    var srv = (S.server.on && S.server.snap)
      ? '|srv:' + (S.server.lastBarTs || 0) : '';
    return [raw, qts, S.code, S.period, S.compare ? 1 : 0, isLight() ? 1 : 0, srv].join('|');
  }

  /** force=true 用于显式刷新（启动 / 手动刷新 / native 推送 / 预测或同步完成），始终重绘。 */
  function pull(force) {
    var raw = null;
    var served = false;
    // 服务端对照：单次 pull 只发一次 serverGet；失败冷却期内（定时轮询）先用本地
    if (S.server.on && N && typeof N.serverGet === 'function'
        && (force || Date.now() >= (S.server.failUntil || 0))) {
      try {
        var snap = JSON.parse(N.serverGet(snapshotPath()));
        if (!snap || snap.error || !snap.state || !snap.kline) {
          throw new Error((snap && snap.error) || '服务端返回异常');
        }
        applySnapshot(snap);       // 映射成同构 S.state + 缓存 bars，复用现有渲染函数
        S.server.failUntil = 0;
        raw = 'server';            // 稳定占位；真实变化信号由 stateSig 的 srv 段负责
        served = true;
      } catch (e) {
        serverFallback(e);         // 提示 + 清快照 + 冷却，绝不中断下面的本地渲染
      }
    }
    if (!served) {
      clearServerSnap();
      try {
        raw = DEMO ? JSON.stringify(demoState()) : N.state();
      } catch (e) {
        setLive('读取失败', 'err');
        return;
      }
      if (!raw) return;
      try {
        S.state = JSON.parse(raw);
      } catch (e) {
        return;
      }
    }
    // 选中品种保持有效
    var codes = (S.state.products || []).map(function (p) { return p.code; });
    if (codes.length && codes.indexOf(S.code) < 0) S.code = codes[0];

    var sig = stateSig(raw);
    var live = function () {
      if (S.state.last_error) setLive('数据源异常', 'err');
      else setLive('已更新 ' + nowStr(), 'ok');
    };
    if (!force && sig === S.lastSig) {
      // 5 秒定时拉取但数据未变化：跳过卡片重建、K 线重取、迷你走势与监听器绑定
      live();
      return;
    }
    S.lastSig = sig;
    renderQuotes();
    if (!S.crossActive) renderChart();   // 按住十字星时先冻结，松手后再跟最新
    renderIndicators();
    renderForecast();
    renderStats();
    live();
  }

  // ---------------- 行情 ----------------

  function renderQuotes() {
    var box = $('#quoteCards');
    var ps = S.state.products || [];
    if (!ps.length) {
      box.innerHTML = '<div class="empty">暂无行情</div>';
      return;
    }
    var html = '';
    ps.forEach(function (p) {
      var q = p.quote || {};
      var chg = q.change_pct;
      var last = q.last;
      html += '<div class="qcard' + (p.code === S.code ? ' active' : '') + '" data-code="' + p.code + '">'
        + '<div class="qname">' + esc(p.short) + '</div>'
        + '<div class="qprice ' + chgClass(chg) + '">' + (last ? num(last, last > 1000 ? 0 : 2) : '—') + '</div>'
        + '<div class="qchg ' + chgClass(chg) + '">'
        + (chg === undefined ? '—' : (chg > 0 ? '+' : '') + num(chg, 2) + '%')
        + '</div>'
        + '<canvas class="qspark" data-spark="' + p.code + '"></canvas>'
        + '<div class="qbar"></div>'
        + '</div>';
    });
    box.innerHTML = html;
    // 卡片点击统一由 #quoteCards 上的事件委托处理（见 bind），这里不再重复绑定监听器
    // 迷你走势
    Array.prototype.forEach.call(box.querySelectorAll('canvas[data-spark]'), function (cv) {
      var code = cv.getAttribute('data-spark');
      var pts = [];
      try {
        pts = JSON.parse(DEMO ? JSON.stringify(demoSpark(code)) : N.sparkline(code, 40));
      } catch (e) { pts = []; }
      drawSpark(cv, pts);
    });
  }

  function drawSpark(cv, pts) {
    var w = cv.clientWidth || 120, h = 26;
    var dpr = window.devicePixelRatio || 1;
    cv.width = w * dpr;
    cv.height = h * dpr;
    var ctx = cv.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    if (!pts || pts.length < 2) return;
    var vals = pts.map(function (p) { return p[1]; });
    var min = Math.min.apply(null, vals), max = Math.max.apply(null, vals);
    var span = max - min || 1;
    var up = vals[vals.length - 1] >= vals[0];
    ctx.beginPath();
    pts.forEach(function (p, i) {
      var x = (i / (pts.length - 1)) * w;
      var y = h - 3 - ((p[1] - min) / span) * (h - 6);
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.strokeStyle = up ? upColor() : downColor();
    ctx.lineWidth = 1.4;
    ctx.stroke();
  }

  var SERIES_COLORS = { AL: '#4EA8FF', AO: '#FFA63D', AHD: '#B07BFF' };
  var COMPARE_CODES = ['AL', 'AO', 'AHD'];

  function periodByKey(k) {
    var out = PERIODS[0];
    PERIODS.forEach(function (p) { if (p.k === k) out = p; });
    return out;
  }

  function isTick(period) { return period.tick !== undefined; }

  /** 取某品种某周期的序列，统一成 [ts,o,h,l,c,v] 数组。 */
  function loadBars(code, period) {
    // 服务端对照：当前品种 + 当前周期命中快照时，直接复用映射好的服务端 bars，
    // 早返回不追加本地报价，避免把两端数据混在一根曲线上。
    if (S.server.on && S.server.snap && S.server.bars && S.server.bars.length
        && code === S.server.snapCode && period.k === S.server.snapPeriod) {
      return S.server.bars.slice();
    }
    var out = [];
    if (DEMO) return demoSeries(code, period);
    try {
      if (isTick(period)) {
        var tr = JSON.parse(N.tickSeries(code, period.tick, 600));
        (tr && tr.points || []).forEach(function (p) {
          out.push([p[0], p[1], p[2], p[3], p[4], p[5]]);
        });
        return out;
      }
      var raw = JSON.parse(DEMO ? '{"bars":[]}'
        : N.kline(code, period.k, period.k === '1d' ? 4000 : 1400));
      (raw && raw.bars || []).forEach(function (b) {
        out.push([b[0], b[1], b[2], b[3], b[4], b[5]]);
      });
      if (out.length < 2 && period.step > 1) {
        var one = JSON.parse(N.kline(code, '1m', 1400));
        out = aggregate((one && one.bars || []), period.step);
      }
    } catch (e) { out = []; }
    // 把最新报价接到尾部：K 线只到上一根收盘，不接的话曲线总比现价慢一拍
    var q = quoteOf(code);
    if (q && q.last > 0 && out.length) {
      var lastTs = out[out.length - 1][0];
      var qTs = q.ts && q.ts > 0 ? q.ts : Date.now();
      if (qTs > lastTs) out.push([qTs, q.last, q.last, q.last, q.last, 0]);
    }
    return out;
  }

  function quoteOf(code) {
    var ps = (S.state && S.state.products) || [];
    for (var i = 0; i < ps.length; i++) {
      if (ps[i].code === code) return ps[i].quote || null;
    }
    return null;
  }

  function renderChart() {
    var canvas = $('#chart');
    var empty = $('#chartEmpty');
    var period = periodByKey(S.period);
    var compare = !!S.compare;

    var list = compare ? COMPARE_CODES : [S.code];
    var series = [];
    list.forEach(function (code) {
      var bars = loadBars(code, period);
      var limit = (period.k === '60m' || period.k === '1d') ? 1100
        : isTick(period) ? 600 : 260;
      bars = bars.slice(-limit);
      var prod = findProduct(code);
      // 单品种沿用「红涨绿跌」；对比模式用品种固定色区分
      var trendUp = bars.length > 1 && bars[bars.length - 1][4] >= bars[0][4];
      series.push({
        code: code,
        label: (prod && prod.short) || code,
        color: compare ? (SERIES_COLORS[code] || '#4EA8FF')
          : (trendUp ? upColor() : downColor()),
        bars: bars,
        hidden: !!S.hidden[code]
      });
    });

    // 单品种模式只保留一条
    var visible = series.filter(function (s) { return !s.hidden && s.bars.length >= 2; });
    var meta = $('#chartMeta');
    if (!visible.length) {
      if (meta) meta.textContent = isTick(period)
        ? '本机暂未采集到秒级点（打开应用后自动累积）'
        : '—';
      empty.style.display = 'grid';
      if (empty) empty.innerHTML = isTick(period)
        ? '还没有秒级数据<br><span class="muted" style="font-size:11px">秒级点由本机运行累积，公开接口无历史</span>'
        : '等待数据…<br><span class="muted" style="font-size:11px">本地无该周期K线，正在自动同步</span>';
      var c0 = canvas.getContext('2d');
      c0.setTransform(1, 0, 0, 1, 0, 0);
      c0.clearRect(0, 0, canvas.width, canvas.height);
      S.chart = null;
      renderLegend(series, period, compare);
      return;
    }
    empty.style.display = 'none';

    if (meta) {
      var total = visible.reduce(function (a, s) { return Math.max(a, s.bars.length); }, 0);
      var t0 = Math.min.apply(null, visible.map(function (s) { return s.bars[0][0]; }));
      var t1 = Math.max.apply(null, visible.map(function (s) {
        return s.bars[s.bars.length - 1][0];
      }));
      var days = Math.round((t1 - t0) / 86400000);
      var head = esc((period.label || period.k) + ' · ' + total + ' 点'
        + (compare ? ' · 涨跌幅对比' : '') + ' · ' + dateStr(t0)
        + (days > 0 ? '（' + days + ' 天）' : '')
        + (isTick(period) ? ' · 采集点' : ''));
      // 最新一根的时间与滞后情况：超过 2 个采集周期没新数据就标黄
      var lastBar = visible[0].bars[visible[0].bars.length - 1];
      var lagMs = Date.now() - t1;
      var limitMs = Math.max(((S.settings && S.settings.refresh_sec) || 30) * 2000, 120000);
      var stale = period.k !== '1d' && lagMs > limitMs;
      var lagTxt = Math.round(lagMs / 1000);
      var tail = '最新 ' + (compare ? '' : num(lastBar[4], 2) + ' · ')
        + timeTick(t1, period.k, true)
        + (stale ? ' · 滞后 ' + (lagTxt >= 120 ? Math.round(lagTxt / 60) + ' 分' : lagTxt + ' 秒') : '');
      meta.innerHTML = head + '<br><span class="' + (stale ? 'cm-warn' : 'cm-ok') + '">'
        + esc(tail) + '</span>';
    }

    var geo = buildGeo(canvas, series, compare, period);
    S.chart = geo;
    paint(null);
    renderLegend(series, period, compare);
  }

  function buildGeo(canvas, series, compare, period) {
    var w = canvas.clientWidth || 320;
    var h = canvas.clientHeight || 196;
    var dpr = window.devicePixelRatio || 1;
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    var padL = 6, padR = 58, padT = 14, padB = 22;
    var t0 = Infinity, t1 = -Infinity;
    var vMin = Infinity, vMax = -Infinity;
    var items = [];
    series.forEach(function (s) {
      if (s.hidden || s.bars.length < 2) return;
      var base = s.bars[0][4];
      var pts = s.bars.map(function (b) {
        var v = compare ? (base > 0 ? (b[4] - base) / base * 100 : 0) : b[4];
        t0 = Math.min(t0, b[0]);
        t1 = Math.max(t1, b[0]);
        vMin = Math.min(vMin, v);
        vMax = Math.max(vMax, v);
        return [b[0], v, b];
      });
      // 点数过多时抽样，保证滚动顺畅（十字星吸附仍按抽样点）
      if (pts.length > 900) {
        var step = Math.ceil(pts.length / 900), sampled = [];
        for (var i = 0; i < pts.length; i += step) sampled.push(pts[i]);
        if (sampled[sampled.length - 1] !== pts[pts.length - 1]) sampled.push(pts[pts.length - 1]);
        pts = sampled;
      }
      items.push({ code: s.code, label: s.label, color: s.color, pts: pts, bars: s.bars });
    });
    if (!isFinite(t0) || t1 <= t0) { t1 = t0 + 1; }
    if (!isFinite(vMin) || vMax <= vMin) {
      vMin = vMin - 1; vMax = vMax + 1;
    }
    if (compare) {
      var lo = Math.min(vMin, 0), hi = Math.max(vMax, 0);
      vMin = lo; vMax = hi;
    }
    var span = vMax - vMin || Math.max(Math.abs(vMax) * 0.002, 0.01);
    vMin -= span * 0.14; vMax += span * 0.14;
    return {
      canvas: canvas, w: w, h: h, dpr: dpr, items: items, compare: compare,
      period: period, t0: t0, t1: t1, vMin: vMin, vMax: vMax,
      padL: padL, padR: padR, padT: padT, padB: padB
    };
  }

  function Xof(g, ts) {
    return g.padL + (ts - g.t0) / (g.t1 - g.t0) * (g.w - g.padL - g.padR);
  }

  function Yof(g, v) {
    return g.padT + (1 - (v - g.vMin) / (g.vMax - g.vMin)) * (g.h - g.padT - g.padB);
  }

  function Xinv(g, x) {
    var r = (x - g.padL) / Math.max(1, (g.w - g.padL - g.padR));
    return g.t0 + Math.max(0, Math.min(1, r)) * (g.t1 - g.t0);
  }

  function Yinv(g, y) {
    var r = 1 - (y - g.padT) / Math.max(1, (g.h - g.padT - g.padB));
    return g.vMin + Math.max(0, Math.min(1, r)) * (g.vMax - g.vMin);
  }

  function paint(cross) {
    var g = S.chart;
    if (!g) return;
    var ctx = g.canvas.getContext('2d');
    var light = isLight();
    var w = g.w, h = g.h;
    ctx.setTransform(g.dpr, 0, 0, g.dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    // 网格 + 纵轴刻度
    ctx.strokeStyle = light ? 'rgba(15,60,110,.10)' : 'rgba(120,200,255,.07)';
    ctx.lineWidth = 1;
    var span = g.vMax - g.vMin;
    for (var i = 0; i <= 3; i++) {
      var gy = g.padT + (i / 3) * (h - g.padT - g.padB);
      ctx.beginPath();
      ctx.moveTo(g.padL, gy);
      ctx.lineTo(w - g.padR, gy);
      ctx.stroke();
      var gv = g.vMax - (i / 3) * span;
      ctx.fillStyle = light ? 'rgba(70,90,115,.88)' : 'rgba(124,135,152,.75)';
      ctx.font = '10px ui-monospace, monospace';
      ctx.textAlign = 'left';
      ctx.fillText(g.compare
        ? (gv > 0 ? '+' : '') + gv.toFixed(2) + '%'
        : gv.toFixed(g.vMax > 1000 ? 0 : 2), w - g.padR + 6, gy + 3);
    }

    // 横轴时间
    ctx.fillStyle = light ? 'rgba(70,90,115,.72)' : 'rgba(124,135,152,.62)';
    ctx.font = '9.5px ui-monospace, monospace';
    var tMid = g.t0 + (g.t1 - g.t0) / 2;
    ctx.textAlign = 'left';
    ctx.fillText(timeTick(g.t0, g.period.k), g.padL, h - 7);
    ctx.textAlign = 'center';
    ctx.fillText(timeTick(tMid, g.period.k), (g.padL + w - g.padR) / 2, h - 7);
    ctx.textAlign = 'right';
    ctx.fillText(timeTick(g.t1, g.period.k), w - g.padR, h - 7);

    // 序列
    g.items.forEach(function (it) {
      var pts = it.pts;
      if (pts.length < 2) return;
      if (!g.compare) {
        // 单品种保留渐变填充，观感与旧版一致
        var rgb = hexRGB(it.color);
        var grad = ctx.createLinearGradient(0, g.padT, 0, h - g.padB);
        grad.addColorStop(0, 'rgba(' + rgb + ',.28)');
        grad.addColorStop(1, 'rgba(' + rgb + ',0)');
        ctx.beginPath();
        ctx.moveTo(Xof(g, pts[0][0]), Yof(g, pts[0][1]));
        for (var k = 1; k < pts.length; k++) ctx.lineTo(Xof(g, pts[k][0]), Yof(g, pts[k][1]));
        ctx.lineTo(Xof(g, pts[pts.length - 1][0]), h - g.padB);
        ctx.lineTo(Xof(g, pts[0][0]), h - g.padB);
        ctx.closePath();
        ctx.fillStyle = grad;
        ctx.fill();
      }
      ctx.beginPath();
      ctx.moveTo(Xof(g, pts[0][0]), Yof(g, pts[0][1]));
      for (var j = 1; j < pts.length; j++) ctx.lineTo(Xof(g, pts[j][0]), Yof(g, pts[j][1]));
      ctx.strokeStyle = it.color;
      ctx.lineWidth = g.compare ? 1.7 : 1.9;
      ctx.lineJoin = 'round';
      if (!g.compare) {
        ctx.shadowColor = 'rgba(' + hexRGB(it.color) + ',.5)';
        ctx.shadowBlur = 8;
      }
      ctx.stroke();
      ctx.shadowBlur = 0;

      // 末端点
      var last = pts[pts.length - 1];
      var lx = Xof(g, last[0]), ly = Yof(g, last[1]);
      ctx.beginPath();
      ctx.arc(lx, ly, 3, 0, Math.PI * 2);
      ctx.fillStyle = it.color;
      ctx.fill();
      if (!g.compare) {
        ctx.beginPath();
        ctx.arc(lx, ly, 6.5, 0, Math.PI * 2);
        ctx.strokeStyle = 'rgba(' + hexRGB(it.color) + ',.4)';
        ctx.stroke();
        // 现价标签
        var label = last[2][4].toFixed(g.vMax > 1000 ? 2 : 2);
        ctx.fillStyle = it.color;
        ctx.fillRect(w - g.padR + 2, ly - 8, g.padR - 4, 16);
        ctx.fillStyle = '#ffffff';
        ctx.font = 'bold 10px ui-monospace, monospace';
        ctx.textAlign = 'center';
        ctx.fillText(label, w - g.padR / 2, ly + 3.5);
      }
    });

    if (cross) drawCross(ctx, g, cross);
  }

  function drawCross(ctx, g, cross) {
    var light = isLight();
    var x = Xof(g, cross.ts);
    var y = Math.max(g.padT, Math.min(g.h - g.padB, cross.y));
    ctx.save();
    ctx.strokeStyle = light ? 'rgba(40,60,85,.45)' : 'rgba(180,210,240,.45)';
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 3]);
    ctx.beginPath(); ctx.moveTo(x, g.padT); ctx.lineTo(x, g.h - g.padB); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(g.padL, y); ctx.lineTo(g.w - g.padR, y); ctx.stroke();
    ctx.setLineDash([]);

    // 右侧数值气泡（跟随手指）
    var val = Yinv(g, y);
    var txt = g.compare
      ? (val > 0 ? '+' : '') + val.toFixed(2) + '%'
      : val.toFixed(g.vMax > 1000 ? 0 : 2);
    ctx.font = 'bold 10px ui-monospace, monospace';
    var tw = ctx.measureText(txt).width + 10;
    ctx.fillStyle = light ? 'rgba(30,50,75,.92)' : 'rgba(210,232,255,.92)';
    roundRect(ctx, g.w - g.padR + 2, y - 8, Math.max(tw, g.padR - 4), 16, 3);
    ctx.fill();
    ctx.fillStyle = light ? '#ffffff' : '#05070c';
    ctx.textAlign = 'center';
    ctx.fillText(txt, g.w - g.padR + 2 + Math.max(tw, g.padR - 4) / 2, y + 3.5);

    // 底部时间标记
    var timeTxt = timeTick(cross.ts, g.period.k);
    ctx.font = '10px ui-monospace, monospace';
    var tw2 = ctx.measureText(timeTxt).width + 12;
    var bx = Math.max(g.padL, Math.min(g.w - g.padR - tw2, x - tw2 / 2));
    ctx.fillStyle = light ? 'rgba(30,50,75,.92)' : 'rgba(210,232,255,.92)';
    roundRect(ctx, bx, g.h - g.padB + 3, tw2, 15, 3);
    ctx.fill();
    ctx.fillStyle = light ? '#ffffff' : '#05070c';
    ctx.textAlign = 'center';
    ctx.fillText(timeTxt, bx + tw2 / 2, g.h - g.padB + 14);

    // 各序列在该时刻的点 + 顶部浮窗
    var lines = [timeTick(cross.ts, g.period.k, true)];
    g.items.forEach(function (it) {
      var p = nearestPoint(it.pts, cross.ts);
      if (!p) return;
      var px = Xof(g, p[0]), py = Yof(g, p[1]);
      ctx.beginPath();
      ctx.arc(px, py, 3.2, 0, Math.PI * 2);
      ctx.fillStyle = it.color;
      ctx.fill();
      ctx.strokeStyle = light ? '#ffffff' : '#05070c';
      ctx.lineWidth = 1.4;
      ctx.stroke();
      var raw = p[2];
      var pct = (raw[4] - it.pts[0][2][4]) / Math.max(it.pts[0][2][4], 1e-9) * 100;
      lines.push(it.label + ' ' + raw[4].toFixed(g.compare ? 2 : 2)
        + (g.compare ? '  ' + (pct > 0 ? '+' : '') + pct.toFixed(2) + '%' : '')
        + '  高' + raw[2].toFixed(2) + ' 低' + raw[3].toFixed(2));
    });
    ctx.font = '10px ui-monospace, monospace';
    var maxW = 0;
    lines.forEach(function (s) { maxW = Math.max(maxW, ctx.measureText(s).width); });
    var boxW = maxW + 16, boxH = lines.length * 14 + 8;
    var boxX = g.padL + 4, boxY = g.padT + 2;
    ctx.fillStyle = light ? 'rgba(255,255,255,.94)' : 'rgba(8,14,24,.92)';
    ctx.strokeStyle = light ? 'rgba(20,50,90,.15)' : 'rgba(120,200,255,.18)';
    ctx.lineWidth = 1;
    roundRect(ctx, boxX, boxY, boxW, boxH, 5);
    ctx.fill();
    ctx.stroke();
    ctx.textAlign = 'left';
    lines.forEach(function (s, i) {
      ctx.fillStyle = i === 0
        ? (light ? 'rgba(70,90,115,.9)' : 'rgba(160,178,200,.85)')
        : (light ? '#12263f' : '#dce9f7');
      ctx.fillText(s, boxX + 8, boxY + 14 + i * 14);
    });
    ctx.restore();
  }

  function nearestPoint(pts, ts) {
    if (!pts || !pts.length) return null;
    var lo = 0, hi = pts.length - 1;
    while (lo < hi) {
      var mid = (lo + hi) >> 1;
      if (pts[mid][0] < ts) lo = mid + 1; else hi = mid;
    }
    if (lo > 0 && Math.abs(pts[lo - 1][0] - ts) <= Math.abs(pts[lo][0] - ts)) return pts[lo - 1];
    return pts[lo];
  }

  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function hexRGB(hex) {
    var v = hex.replace('#', '');
    if (v.length === 3) v = v[0] + v[0] + v[1] + v[1] + v[2] + v[2];
    var n = parseInt(v, 16);
    return ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255);
  }

  function timeTick(ts, periodKey, full) {
    var d = new Date(ts);
    var p = function (n) { return n < 10 ? '0' + n : '' + n; };
    var hm = p(d.getHours()) + ':' + p(d.getMinutes());
    if (periodKey === '1s' || periodKey === '30s' || full) {
      hm += ':' + p(d.getSeconds());
    }
    if (periodKey === '1d') return (d.getMonth() + 1) + '/' + d.getDate();
    return (full ? p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' : '') + hm;
  }

  function renderLegend(series, period, compare) {
    var box = $('#chartLegend');
    if (!box) return;
    box.innerHTML = series.map(function (s) {
      var last = s.bars.length ? s.bars[s.bars.length - 1][4] : null;
      var first = s.bars.length ? s.bars[0][4] : null;
      var pct = (last !== null && first) ? (last - first) / first * 100 : null;
      return '<button class="lg' + (s.hidden ? ' off' : '') + '" data-code="' + s.code + '"'
        + (compare ? '' : ' disabled') + '>'
        + '<i style="background:' + s.color + '"></i>'
        + '<span class="lg-name">' + esc(s.label) + '</span>'
        + (compare
          ? '<span class="lg-pct ' + chgClass(pct) + '">' + (pct === null ? '—' : (pct > 0 ? '+' : '') + pct.toFixed(2) + '%') + '</span>'
          : '<span class="lg-pct">' + (last === null ? '—' : last.toFixed(2)) + '</span>')
        + '</button>';
    }).join('');
    Array.prototype.forEach.call(box.querySelectorAll('.lg'), function (b) {
      b.addEventListener('click', function () {
        if (!S.compare) return;
        var code = b.getAttribute('data-code');
        S.hidden[code] = !S.hidden[code];
        renderChart();
      });
    });
  }

  /** 按根数聚合（分钟线兜底用）。 */
  function aggregate(bars, step) {
    if (!step || step <= 1) return bars;
    var out = [], cur = null, n = 0;
    for (var i = 0; i < bars.length; i++) {
      var b = bars[i];
      if (!cur) cur = [b[0], b[1], b[2], b[3], b[4], b[5] || 0];
      else {
        cur[2] = Math.max(cur[2], b[2]);
        cur[3] = Math.min(cur[3], b[3]);
        cur[4] = b[4];
        cur[5] += (b[5] || 0);
        cur[0] = b[0];
      }
      if (++n >= step) { out.push(cur); cur = null; n = 0; }
    }
    if (cur) out.push(cur);
    return out;
  }

  // ---------------- 指标 ----------------

  function renderIndicators() {
    var p = findProduct(S.code);
    var ind = (p && p.forecast && p.forecast.indicators) || {};
    var bias = $('#indBias');
    if (bias) {
      bias.textContent = ind.bias || '—';
      bias.style.color = ind.bias === '偏多' ? 'var(--up)'
        : ind.bias === '偏空' ? 'var(--down)' : 'var(--muted)';
    }
    var items = [
      ['RSI14', ind.rsi, 1],
      ['MACD柱', ind.macd_hist, 3],
      ['ATR%', ind.atr_pct, 2],
      ['布林位', ind.boll_pos, 2],
      ['波动率', ind.vol20_pct, 2],
      ['区间位', ind.range_pos, 2]
    ];
    var html = '';
    items.forEach(function (it) {
      var v = it[1];
      var cls = '';
      if (it[0] === 'RSI14' && v >= 70) cls = 'up';
      if (it[0] === 'RSI14' && v <= 30) cls = 'down';
      html += '<div class="ind"><div class="k">' + it[0] + '</div>'
        + '<div class="v ' + cls + '">' + (v === undefined || v === null ? '—' : num(v, it[2])) + '</div></div>';
    });
    $('#indGrid').innerHTML = html || '<div class="empty">数据不足</div>';
  }

  function findProduct(code) {
    var ps = (S.state && S.state.products) || [];
    for (var i = 0; i < ps.length; i++) if (ps[i].code === code) return ps[i];
    return null;
  }

  // ---------------- 预测 ----------------

  function renderForecast() {
    var p = findProduct(S.code);
    var f = (p && p.forecast) || {};
    var overall = f.overall || '—';
    var score = f.score || 0;

    var hero = $('#heroMain');
    hero.textContent = overall;
    hero.className = 'hero-main ' + dirClass(overall);
    $('#heroScore').textContent = '评分 ' + num(score, 2);
    $('#heroPrice').textContent = f.price ? num(f.price, f.price > 1000 ? 0 : 2) : '—';
    var pos = 50 + Math.max(-50, Math.min(50, score * 50));
    $('#heroFill').style.width = pos + '%';
    $('#heroRec').textContent = f.recommendation || '数据积累中，暂无建议';

    var hs = f.horizons || [];
    var html = '';
    hs.forEach(function (h) {
      var pUp = h.p_up === undefined ? 0.5 : h.p_up;
      var dir = h.direction || '—';
      var color = dirClass(dir) === 'up' ? 'var(--up)'
        : dirClass(dir) === 'down' ? 'var(--down)' : 'var(--flat)';
      html += '<div class="hz">'
        + '<div class="h-top"><span class="h-label">' + esc(h.label) + '</span>'
        + '<span class="h-dir" style="color:' + color + '">' + esc(dir) + '</span></div>'
        + '<div class="h-bar"><i style="width:' + (pUp * 100).toFixed(1) + '%;background:' + color + '"></i></div>'
        + '<div class="h-foot"><span>P ' + (pUp * 100).toFixed(0) + '%</span>'
        + '<span>' + (h.target_price ? num(h.target_price, h.target_price > 1000 ? 0 : 2) : '—') + '</span></div>'
        + '</div>';
    });
    $('#hzGrid').innerHTML = html || '<div class="empty">数据不足，先让应用运行一段时间</div>';
    renderForecastMeta();
  }

  // ---------------- 历史 ----------------

  function renderStats() {
    var st = (S.state && S.state.stats) || {};
    // 每次都重取命中率，保证验证结果回填后数值即时刷新（不再只在首次渲染时取一次）
    var row = $('#statRow');
    if (row) {
      try {
        var hs = JSON.parse(DEMO ? '{"verified":0,"hit":0,"rate":0}' : N.hitStats());
        row.innerHTML = statCell('已验证', hs.verified || 0)
          + statCell('命中', hs.hit || 0)
          + statCell('命中率', ((hs.rate || 0) * 100).toFixed(0) + '%');
      } catch (e) { /* ignore */ }
    }
    var ds = $('#dataStats');
    if (ds) {
      var pr = st.periods || {};
      var n = function (k) { return (pr[k] && pr[k].n) || 0; };
      ds.innerHTML = statCell('日线', n('1d') || st.daily || 0)
        + statCell('60分钟', n('60m'))
        + statCell('1分钟', n('1m') || st.minute || 0)
        + statCell('预测条', st.forecasts || 0);
    }
    if (S.state && (S.state.last_sync || S.state.last_sync_msg)) {
      var info = $('#syncInfo');
      if (info && info.getAttribute('data-busy') !== '1') {
        info.textContent = (S.state.last_sync ? '上次同步 ' + timeStr(S.state.last_sync) : '尚未同步')
          + (S.state.last_sync_msg ? '：' + S.state.last_sync_msg : '')
          + '。同步先体检最近 7 天完整性，再增量同步到最新（日线回溯 180 天）。'
          + (S.syncNote ? '\n' + S.syncNote : '');
      }
    }
  }

  function statCell(k, v) {
    return '<div class="stat"><div class="k">' + k + '</div><div class="v">' + v + '</div></div>';
  }

  function loadHistory() {
    var list = [];
    try {
      list = JSON.parse(DEMO ? '[]' : N.listForecasts(S.histCode, 120));
    } catch (e) { list = []; }

    var box = $('#histList');
    if (!list.length) {
      box.innerHTML = '<div class="empty">还没有预测记录<br>应用运行一段时间后会自动留档</div>';
    } else {
      var html = '';
      list.forEach(function (it) {
        var hit = it.hit;
        var badge = hit === 1 ? '<span class="badge hit">命中</span>'
          : hit === 0 ? '<span class="badge miss">未中</span>'
            : '<span class="badge wait">待验证</span>';
        var src = it.source === 'llm' ? '<span class="badge llm">大模型</span>' : '';
        html += '<div class="hitem">'
          + '<div class="h-row1"><div class="h-who">' + esc(shortName(it.code))
          + '<span>' + esc(horizonLabel(it.horizon)) + '</span></div>'
          + '<div class="h-time">' + timeStr(it.created_at) + '</div></div>'
          + '<div class="h-row2">'
          + '<span class="' + dirClass(it.dir) + '">' + esc(it.dir) + '</span>'
          + '<span>预测 ' + num(it.target, it.target > 1000 ? 0 : 2) + '</span>'
          + '<span>基准 ' + num(it.price, 2) + '</span>'
          + (it.actual === null || it.actual === undefined ? '' : '<span>实际 ' + num(it.actual, 2) + '</span>')
          + badge + src
          + '</div>'
          + (it.reason ? '<div class="h-reason">' + esc(it.reason) + '</div>' : '')
          + '</div>';
      });
      box.innerHTML = html;
    }

    // 命中率卡片
    try {
      var hs = JSON.parse(DEMO ? '{"verified":0,"hit":0,"rate":0}' : N.hitStats());
      $('#statRow').innerHTML = statCell('已验证', hs.verified || 0)
        + statCell('命中', hs.hit || 0)
        + statCell('命中率', ((hs.rate || 0) * 100).toFixed(0) + '%');
    } catch (e) { /* ignore */ }
  }

  function horizonLabel(k) {
    var m = { '1m': '1分钟', '5m': '5分钟', '15m': '15分钟', '30m': '30分钟',
      '4h': '4小时', '8h': '8小时', '1d': '1天', '1h': '1小时', '10m': '10分钟' };
    return m[k] || k;
  }

  function shortName(code) {
    var p = findProduct(code);
    return p ? p.short : code;
  }

  // ---------------- 服务端对照 ----------------

  function snapshotPath() {
    return '/api/app/snapshot?code=' + encodeURIComponent(S.code)
      + '&period=' + encodeURIComponent(S.period) + '&limit=400';
  }

  function saveServerPrefs() {
    try {
      localStorage.setItem('ff_server_on', S.server.on ? '1' : '0');
      localStorage.setItem('ff_server_base', S.server.base);
    } catch (e) { /* ignore */ }
  }

  function clearServerSnap() {
    S.server.snap = null;
    S.server.bars = [];
    S.server.lastBarTs = 0;
    S.server.compareText = '';
    var el = $('#serverCompare');
    if (el) el.hidden = true;
  }

  /** 状态行（设置页 #serverState）与行情页对照行（#serverCompare）统一出口。 */
  function paintServerNote(base, cmp, cls) {
    var st = $('#serverState');
    if (st) {
      st.className = 'server-state' + (cls ? ' ' + cls : '');
      st.innerHTML = esc(base) + (cmp ? '<br><span class="server-cmp">' + esc(cmp) + '</span>' : '');
    }
    var el = $('#serverCompare');
    if (el) {
      if (cmp) { el.textContent = cmp; el.hidden = false; }
      else el.hidden = true;
    }
  }

  function productIn(state, code) {
    var ps = (state && state.products) || [];
    for (var i = 0; i < ps.length; i++) if (ps[i].code === code) return ps[i];
    return null;
  }

  /** 服务端 quote 未必含 change_pct，用 last/prev_close 补算，保持 renderQuotes 零改动。 */
  function mapQuote(q) {
    q = q || {};
    var last = (q.last !== undefined && q.last !== null) ? q.last : q.price;
    var prev = (q.prev_close !== undefined && q.prev_close !== null) ? q.prev_close : q.pre_close;
    var chg = q.change_pct;
    if ((chg === undefined || chg === null) && last != null && prev) {
      chg = (last - prev) / prev * 100;
    }
    return {
      last: last, open: q.open, high: q.high, low: q.low,
      prev_close: prev, change_pct: chg
      // 故意不写 ts：避免每变的 server.ts 进入 stateSig 的 qts 段
    };
  }

  /** 把 snapshot 映射成本地同构的 S.state，并缓存服务端 bars 供 loadBars 复用。 */
  function applySnapshot(snap) {
    var st = snap.state || {};
    var kline = snap.kline || {};
    var fc = snap.forecast || {};
    var srv = snap.server || {};

    var bars = [];
    (kline.bars || []).forEach(function (b) {
      bars.push([b.ts, b.o, b.h, b.l, b.c, b.v]);
    });
    S.server.snap = snap;
    S.server.bars = bars;
    S.server.snapCode = S.code;
    S.server.snapPeriod = S.period;
    S.server.lastBarTs = bars.length ? bars[bars.length - 1][0] : 0;
    S.server.ts = srv.ts || 0;
    S.server.version = srv.version || '';

    var products = (st.products || []).map(function (p) {
      var prod = {
        code: p.code, short: p.short || p.code, name: p.name || '', unit: p.unit || '',
        quote: mapQuote(p.quote),
        minute_bars: p.minute_bars, daily_bars: p.daily_bars
      };
      // 服务端 forecast 只有当前品种一份，挂到当前品种即可复用 renderForecast/renderIndicators
      if (p.code === S.code) prod.forecast = fc;
      return prod;
    });
    var periods = st.periods || {};
    S.state = {
      products: products,
      periods: periods,
      stats: {
        periods: periods,
        daily: (periods['1d'] || {}).n || 0,
        minute: (periods['1m'] || {}).n || 0,
        forecasts: 0
      },
      last_sync: st.last_sync || 0,
      last_error: st.last_error || '',
      last_tick: st.last_tick || 0
    };
    buildCompareLine(snap);
    paintServerNote('已连接 · 服务端 v' + (S.server.version || '?'), S.server.compareText, 'ok');
  }

  /** 对照行：服务端模式下再取一次本地 N.state()，生成一行双端对比文本。 */
  function buildCompareLine(snap) {
    var line;
    try {
      var local = N ? JSON.parse(N.state()) : null;
      var lp = productIn(local, S.code);
      var sp = productIn(snap.state, S.code);
      var lq = (lp && lp.quote) || {};
      var sq = (sp && sp.quote) || {};
      var lLast = lq.last;
      var sLast = (sq.last !== undefined && sq.last !== null) ? sq.last
        : ((snap.forecast && snap.forecast.price) || null);
      var lps = (local && local.stats && local.stats.periods) || {};
      var lN = (lps[S.period] && lps[S.period].n) || 0;
      var sN = ((snap.kline && snap.kline.bars) || []).length;
      var lf = lp && lp.forecast;
      var lDir = (lf && (lf.overall || (lf.horizons && lf.horizons[0] && lf.horizons[0].direction))) || '—';
      var sDir = (snap.forecast && snap.forecast.overall) || '—';
      line = '服务端对照 · 报价 本地 ' + (lLast != null ? num(lLast, 2) : '—')
        + ' / 服务端 ' + (sLast != null ? num(sLast, 2) : '—')
        + ' · 当前周期根数 ' + lN + '/' + sN
        + ' · 预测方向 本地' + lDir + '/服务端' + sDir;
    } catch (e) {
      line = '服务端对照 · 本地数据读取失败：' + ((e && e.message) || e);
    }
    S.server.compareText = line;
  }

  /** 服务端不可用：清快照、提示，并把定时轮询冷却 30 秒，避免 5 秒超时反复卡主线程。 */
  function serverFallback(e) {
    clearServerSnap();
    S.server.failUntil = Date.now() + 30000;
    var msg = (e && e.message) ? e.message : String(e || '未知错误');
    paintServerNote('服务端不可用，已回退本地：' + msg, '', 'err');
  }

  function initServer() {
    try {
      var b = localStorage.getItem('ff_server_base');
      if (b) S.server.base = b;
      S.server.on = localStorage.getItem('ff_server_on') === '1';
    } catch (e) { /* ignore */ }
    var bi = $('#serverBase'), ci = $('#serverOn');
    if (bi) bi.value = S.server.base;
    if (ci) ci.checked = S.server.on;
    if (N && typeof N.setServer === 'function') {
      try {
        N.setServer(S.server.base, S.server.on);
      } catch (e) {
        S.server.on = false;
        if (ci) ci.checked = false;
        saveServerPrefs();
        paintServerNote('保存的服务端地址无效，已恢复本地：' + ((e && e.message) || e), '', 'err');
      }
    }
    if (S.server.on) paintServerNote('服务端模式已开启', '', 'ok');
  }

  function onServerToggle() {
    var ci = $('#serverOn'), bi = $('#serverBase');
    if (bi) S.server.base = bi.value.trim() || S.server.base;
    S.server.on = !!(ci && ci.checked);
    S.server.failUntil = 0;
    saveServerPrefs();
    var err = '';
    try {
      if (N && typeof N.setServer === 'function') N.setServer(S.server.base, S.server.on);
    } catch (e) {
      err = (e && e.message) ? e.message : String(e);
      S.server.on = false;
      if (ci) ci.checked = false;
      saveServerPrefs();
    }
    if (err) {
      clearServerSnap();
      paintServerNote('地址无效，已恢复本地：' + err, '', 'err');
    } else if (S.server.on) {
      paintServerNote('服务端模式已开启，正在取数…', '', 'ok');
    } else {
      clearServerSnap();
      paintServerNote('已关闭，使用本地数据', '', '');
    }
    S.lastSig = null;
    pull(true);
  }

  function onServerTest() {
    var bi = $('#serverBase');
    var base = (bi && bi.value.trim()) || S.server.base;
    S.server.base = base;
    saveServerPrefs();
    paintServerNote('正在测试连接…', '', '');
    try {
      if (!N || typeof N.setServer !== 'function' || typeof N.serverGet !== 'function') {
        throw new Error('当前环境不支持 Native');
      }
      N.setServer(base, true);
      var j = JSON.parse(N.serverGet('/api/app/state'));
      if (j && j.error) throw new Error(j.error);
      var prods = (j && j.products) || [];
      var ver = (j && (j.server_version || (j.server && j.server.version))) || '?';
      var per = (j && j.periods) || {};
      var cnt = Object.keys(per).map(function (k) {
        return k + ':' + ((per[k] && per[k].n) || 0);
      }).join(' ');
      paintServerNote('已连接 · 服务端 v' + ver + ' · ' + prods.length + ' 品种 · 各周期根数 '
        + (cnt || '—'), S.server.on ? S.server.compareText : '', 'ok');
    } catch (e) {
      paintServerNote('连接失败：' + ((e && e.message) ? e.message : String(e)), '', 'err');
    }
    // 还原 native 开关到前端当前状态，避免一次测试改变持久化的开关
    try {
      if (N && typeof N.setServer === 'function') N.setServer(base, S.server.on);
    } catch (e2) { /* ignore */ }
  }

  // ---------------- 数据库同步（手机 ↔ 服务端） ----------------

  var DB_CODES = ['AL', 'AO', 'AHD'];
  var DB_DOWN_PERIODS = ['1s', '30s', '1m', '5m', '15m', '30m', '60m', '1d'];
  var DB_UP_PERIODS = ['1m', '5m', '15m', '30m', '60m', '1d'];
  var DB_LIMIT = 300;
  var DB_MAX_BATCH = 30;
  var DB_HINT = '请确认：① 服务端以 0.0.0.0 启动；② Windows 已配置端口转发（netsh interface portproxy）；'
    + '③ 手机与电脑在同一局域网；④ 地址填 http://<电脑局域网IP>:8000'
    + '（WSL 本机或预览用 http://127.0.0.1:8000）。';

  /** 把 Native 抛出的异常/错误串转成可操作提示（地址非法 / 超时 / 拒绝连接）。 */
  function dbHint(e) {
    var m = (e && e.message) ? e.message : String(e == null ? '' : e);
    var low = m.toLowerCase();
    if (/timed?[ -]?out|timeout|超时|socket/.test(low)) {
      return m + '（连接超时：' + DB_HINT + '）';
    }
    if (/refused|unreachable|econn|connect|failed|无法连接|拒绝|host/.test(low)) {
      return m + '（无法连接：' + DB_HINT + '）';
    }
    if (/invalid|illegal|not allowed|非法|地址|scheme/.test(low)) {
      return m + '（地址非法：需为 http://<私有网段IP>:8000 或 http://127.0.0.1:8000）';
    }
    return m;
  }

  /** 统一 JSON 解析：解析失败 / {"error":...} / 空响应都归一成 {__err} 失败对象。 */
  function dbJson(raw, what) {
    var o;
    try { o = JSON.parse(raw); } catch (e) {
      return { __err: (what || '响应') + '不是合法 JSON：' + ((e && e.message) || e) };
    }
    if (!o || typeof o !== 'object') return { __err: (what || '响应') + '为空或格式异常' };
    if (o.error) return { __err: String(o.error) };
    return o;
  }

  function dbGet(path) {
    if (!N || typeof N.serverGet !== 'function') return { __err: '当前环境不支持 Native.serverGet' };
    var raw;
    try { raw = N.serverGet(path); } catch (e) { return { __err: dbHint(e) }; }
    return dbJson(raw, '服务端响应');
  }

  function dbPost(path, body) {
    if (!N || typeof N.serverPost !== 'function') return { __err: '当前环境不支持 Native.serverPost' };
    var raw;
    try { raw = N.serverPost(path, body); } catch (e) { return { __err: dbHint(e) }; }
    return dbJson(raw, '服务端响应');
  }

  function dbLocal() {
    if (!N || typeof N.localDbStatus !== 'function') return { __err: '当前环境不支持 Native.localDbStatus' };
    var raw;
    try { raw = N.localDbStatus(); } catch (e) { return { __err: dbHint(e) }; }
    return dbJson(raw, '本地库状态');
  }

  function dbExport(code, period, since, limit) {
    if (!N || typeof N.exportBars !== 'function') return { __err: '当前环境不支持 Native.exportBars' };
    var raw;
    try { raw = N.exportBars(code, period, since, limit); } catch (e) { return { __err: dbHint(e) }; }
    return dbJson(raw, '本地导出');
  }

  /** 返回净新增根数；-1 表示失败或 Native 返回异常。 */
  function dbImport(code, period, bars) {
    if (!N || typeof N.importBars !== 'function') return -1;
    try {
      var n = N.importBars(code, period, JSON.stringify(bars));
      return (typeof n === 'number' && isFinite(n)) ? n : (Number(n) || 0);
    } catch (e) { return -1; }
  }

  /** 配置 Native 服务端地址；on 绝不为同步把对照开关打开。 */
  function dbApplyBase(base, on) {
    if (!N || typeof N.setServer !== 'function') return '当前环境不支持 Native.setServer';
    try { N.setServer(base, on); return null; } catch (e) { return dbHint(e); }
  }

  /** 同步 / 测试结束后把 Native 的 base、对照开关还原成前端设置，避免污染对照取数。 */
  function dbRestoreServer() {
    if (N && typeof N.setServer === 'function') {
      try { N.setServer(S.server.base, S.server.on); } catch (e) { /* ignore */ }
    }
  }

  function dbBaseValue() {
    var el = $('#dbServerBase');
    var v = el ? String(el.value || '').trim() : '';
    return v || S.db.base;
  }

  function saveDbBase() {
    S.db.base = dbBaseValue();
    try { localStorage.setItem('ff_db_base', S.db.base); } catch (e) { /* ignore */ }
  }

  function dbInfo(text, cls) {
    var el = $('#dbInfo');
    if (!el) return;
    el.className = 'server-state' + (cls ? ' ' + cls : '');
    el.textContent = text;
  }

  function setDbButtons(busy) {
    ['#btnDbSync', '#btnDbTest', '#btnDbRebuild'].forEach(function (sel) {
      var b = $(sel);
      if (b) b.disabled = busy;
    });
    var sync = $('#btnDbSync');
    if (sync) sync.textContent = busy ? '同步中…' : '与服务端同步';
  }

  /** 测试连接：只配地址、不开对照 → 取 /api/db/status → 显示三品种各周期根数。 */
  function dbTest() {
    if (S.db.busy) return;
    if (DEMO || !N) { dbInfo('演示模式（浏览器预览）不支持数据库测试连接', 'err'); return; }
    progHide('#dbProgWrap');
    saveDbBase();
    var base = S.db.base;
    var e0 = dbApplyBase(base, false);
    if (e0) {
      dbRestoreServer();
      dbInfo('地址无效或无法保存：' + e0 + '\n' + DB_HINT, 'err');
      return;
    }
    dbInfo('正在测试连接 ' + base + ' …', '');
    var r = dbGet('/api/db/status');
    dbRestoreServer();
    if (r.__err) { dbInfo('连接失败：' + r.__err + '\n' + DB_HINT, 'err'); return; }
    var periods = r.periods || {};
    var lines = ['已连接 · 服务端 /api/db/status（ts ' + (r.ts || '—') + '）'];
    DB_CODES.forEach(function (code) {
      var parts = [];
      DB_DOWN_PERIODS.forEach(function (p) {
        var v = periods[code + '|' + p] || periods[p];
        parts.push(p + ':' + (v && v.n !== undefined ? v.n : '—'));
      });
      lines.push(code + '  ' + parts.join(' '));
    });
    if (r.app_table) {
      try { lines.push('app_table：' + JSON.stringify(r.app_table)); } catch (e) { /* ignore */ }
    }
    if (r.note) lines.push('note：' + String(r.note));
    dbInfo(lines.join('\n'), 'ok');
  }

  /** 与服务端同步：分片异步（每批一个 setTimeout tick），单批失败跳过且绝不中断整体。 */
  function dbSyncStart() {
    if (S.db.busy) return;
    if (DEMO || !N) { dbInfo('演示模式（浏览器预览）不支持数据库同步', 'err'); return; }
    saveDbBase();
    S.db.busy = true;
    S.db.warns = [];
    S.db.failedBatches = 0;
    setDbButtons(true);

    // 先配地址（不开对照），服务端整体不可达时快速失败并给可操作提示
    var e0 = dbApplyBase(S.db.base, false);
    if (e0) {
      dbSyncEnd('地址无效或无法保存：' + e0 + '\n' + DB_HINT, true);
      return;
    }
    var local = dbLocal();
    if (local.__err) dbWarn('本地库状态读取失败：' + local.__err + '（各组合按首次同步 since=0 处理）');
    var srv = dbGet('/api/db/status');
    if (srv.__err) {
      dbSyncEnd('服务端数据库不可达：' + srv.__err + '\n' + DB_HINT, true);
      return;
    }

    var localPeriods = local.periods || {};
    var combos = [];
    DB_CODES.forEach(function (code) {
      DB_DOWN_PERIODS.forEach(function (p) {
        var v = localPeriods[code + '|' + p];
        var mx = v && v.max !== undefined ? Number(v.max) : 0;
        if (!isFinite(mx) || mx < 0) mx = 0;
        combos.push({ dir: 'down', code: code, period: p, since: mx, page: 0 });
      });
    });
    DB_CODES.forEach(function (code) {
      DB_UP_PERIODS.forEach(function (p) {
        combos.push({ dir: 'up', code: code, period: p, since: 0, page: 0 });
      });
    });
    var M = combos.length;
    var ci = 0, done = 0;
    var stat = { down: 0, up: 0, net: 0 };

    dbInfo('已连接服务端，开始双向增量同步（共 ' + M + ' 个组合）…', '');
    progSet('#dbProgWrap', 0, '准备同步…', 'indet');

    function label(c) {
      return '第 ' + (done + 1) + '/' + M + ' 批 · ' + (c.dir === 'down' ? '下拉' : '上推')
        + ' · ' + c.code + ' ' + c.period + (c.page > 0 ? ' · 第 ' + (c.page + 1) + ' 页' : '');
    }
    function nextCombo() { ci++; done++; }
    function advance() { setTimeout(step, 0); }

    function step() {
      if (ci >= M) { dbSyncEnd(null, false, stat); return; }
      var c = combos[ci];
      var lb = label(c);
      progSet('#dbProgWrap',
        Math.round((done + Math.min(0.9, c.page / DB_MAX_BATCH)) / M * 100), lb, '');
      dbInfo(lb, '');

      if (c.dir === 'down') {
        var path = '/api/db/export?code=' + encodeURIComponent(c.code)
          + '&period=' + encodeURIComponent(c.period)
          + '&since=' + encodeURIComponent(c.since) + '&limit=' + DB_LIMIT;
        var r = dbGet(path);
        if (r.__err) { dbWarn(lb + ' 失败：' + r.__err); nextCombo(); advance(); return; }
        var bars = r.bars || [];
        if (bars.length) {
          var added = dbImport(c.code, c.period, bars);
          if (added === -1) { dbWarn(lb + ' importBars 返回失败'); nextCombo(); advance(); return; }
          stat.down += bars.length;
          if (added > 0) stat.net += added;
        }
        c.page++;
        var ns = Number(r.next_since);
        var more = !!r.has_more && c.page < DB_MAX_BATCH && isFinite(ns) && ns > c.since;
        if (more) c.since = ns;
        else {
          if (r.has_more && c.page >= DB_MAX_BATCH) dbWarn(lb + ' 达到单组合 30 批上限，剩余留待下次');
          else if (r.has_more) dbWarn(lb + ' 分页游标未前进，提前结束该组合');
          nextCombo();
        }
        advance();
        return;
      }

      // 上推：本地 exportBars 翻页 → serverPost /api/db/import
      var ex = dbExport(c.code, c.period, c.since, DB_LIMIT);
      if (ex.__err) { dbWarn(lb + ' 失败：' + ex.__err); nextCombo(); advance(); return; }
      var upBars = ex.bars || [];
      if (upBars.length) {
        var body;
        try {
          body = JSON.stringify({ code: c.code, period: c.period, bars: upBars, source: 'app' });
        } catch (e) { body = null; }
        if (!body) { dbWarn(lb + ' 上推数据序列化失败'); nextCombo(); advance(); return; }
        var pr = dbPost('/api/db/import', body);
        if (pr.__err || pr.ok === false) {
          dbWarn(lb + ' 上推失败：' + (pr.__err || pr.note || '服务端未确认'));
          nextCombo(); advance(); return;
        }
        stat.up += upBars.length;
        var a = Number(pr.added);
        if (isFinite(a) && a > 0) stat.net += a;
      }
      c.page++;
      var ns2 = Number(ex.next_since);
      var more2 = (!!ex.has_more || upBars.length >= DB_LIMIT)
        && c.page < DB_MAX_BATCH && isFinite(ns2) && ns2 > c.since;
      if (more2) c.since = ns2;
      else {
        if ((ex.has_more || upBars.length >= DB_LIMIT) && c.page >= DB_MAX_BATCH)
          dbWarn(lb + ' 达到单组合 30 批上限，剩余留待下次');
        nextCombo();
      }
      advance();
    }

    setTimeout(step, 0);
  }

  function dbWarn(msg) {
    S.db.warns.push(msg);
    if (typeof console !== 'undefined' && console.warn) console.warn('[dbSync] ' + msg);
  }

  /** 统一收尾：还原 Native 配置、解除锁定、写进度条 / 状态行、渲染诊断、刷新行情。 */
  function dbSyncEnd(errMsg, isErr, stat) {
    dbRestoreServer();
    S.db.busy = false;
    S.db.failedBatches = isErr ? 0 : S.db.warns.length;
    setDbButtons(false);
    if (isErr) {
      progSet('#dbProgWrap', 0, errMsg || '同步失败', 'fail');
      dbInfo(errMsg || '同步失败', 'err');
    } else {
      stat = stat || { down: 0, up: 0, net: 0 };
      var msg = '同步完成 · 下拉 ' + stat.down + ' 根 · 上推 ' + stat.up + ' 根'
        + ' · 净新增 ' + stat.net + ' 根'
        + (S.db.warns.length ? ' · 跳过 ' + S.db.warns.length + ' 批（失败已忽略）' : '');
      progSet('#dbProgWrap', 100, msg, 'done');
      var tail = S.db.warns.length
        ? '\n' + S.db.warns.slice(-4).join('\n')
          + (S.db.warns.length > 4 ? '\n…还有 ' + (S.db.warns.length - 4) + ' 条' : '')
        : '';
      dbInfo(msg + tail, S.db.warns.length ? '' : 'ok');
    }
    renderDbDiag();
    pull(true);
  }

  /** 诊断区：本地库每周期 n / 首末时间 / legacy 残留 / 上次同步 / 失败批。 */
  function renderDbDiag() {
    var box = $('#dbDiag');
    if (!box) return;
    if (DEMO) {
      box.innerHTML = '<div class="db-diag-title">本地库诊断</div>'
        + '<div class="db-diag">演示模式（浏览器预览）无本地 SQLite 库。</div>' + diagHint();
      return;
    }
    var st = dbLocal();
    if (st.__err) {
      box.innerHTML = '<div class="db-diag-title">本地库诊断</div>'
        + '<div class="db-diag">读取失败：' + esc(st.__err) + '</div>' + diagHint();
      return;
    }
    var lines = [];
    var periods = st.periods || {};
    var keys = Object.keys(periods).sort();
    if (keys.length) {
      keys.forEach(function (k) {
        var v = periods[k] || {};
        var n = v.n === undefined ? '—' : v.n;
        var t0 = v.min ? dateStr(Number(v.min)) : '—';
        var t1 = v.max ? dateStr(Number(v.max)) : '—';
        var lg = v.legacy === undefined ? '—' : v.legacy;
        lines.push('<b>' + esc(k) + '</b>  ' + esc(n) + ' 根 · 首 ' + esc(t0)
          + ' · 末 ' + esc(t1) + ' · legacy 残留 ' + esc(lg));
      });
    } else {
      lines.push('（本地库暂无周期统计）');
    }
    if (st.ticks) {
      var tk = st.ticks;
      lines.push('<b>ticks</b>  ' + (tk.n === undefined ? '—' : esc(tk.n)) + ' 点'
        + (tk.min ? ' · 首 ' + esc(dateStr(Number(tk.min))) : '')
        + (tk.max ? ' · 末 ' + esc(dateStr(Number(tk.max))) : ''));
    }
    lines.push('—');
    lines.push('上次同步：' + (st.last_sync ? esc(timeStr(Number(st.last_sync))) : '—'));
    lines.push('待同步 pending：' + (st.pending === undefined ? '—' : esc(st.pending)));
    lines.push('seed_version：' + (st.seed_version === undefined ? '—' : esc(st.seed_version))
      + ' · migrated：' + (st.migrated ? '是' : '否'));
    lines.push('失败批：' + (S.db.failedBatches || 0));
    if (st.note) lines.push('note：' + esc(String(st.note)));
    if (S.db.warns && S.db.warns.length) {
      lines.push('—');
      lines.push('<span class="dg-warn">最近失败明细（已跳过，不影响本地）：</span>');
      S.db.warns.slice(-5).forEach(function (w) { lines.push('· ' + esc(w)); });
    }
    box.innerHTML = '<div class="db-diag-title">本地库诊断</div>'
      + '<div class="db-diag">' + lines.join('<br>') + '</div>'
      + diagHint();
  }

  function diagHint() {
    return '<div class="hint">「新增 0 根」属正常：本地已与源站能提供的历史窗口一致，'
      + '更早的历史需由服务端补齐后，再通过本页「与服务端同步」下拉获得。</div>';
  }

  /** 重建本地数据库：二次确认 → Native 后台执行 → 全局 onDbRebuild 回调驱动进度。 */
  function dbRebuildStart() {
    if (S.db.busy) return;
    if (DEMO || !N || typeof N.rebuildLocalDb !== 'function') {
      dbInfo('当前环境不支持重建本地数据库', 'err');
      return;
    }
    if (!confirm('重建本地数据库会清空本地 K 线，并从预置库重新导入（ticks / 预测记录会保留）。\n'
      + '此操作不可撤销，确定继续吗？')) return;
    S.db.busy = true;
    S.db.warns = [];
    setDbButtons(true);
    dbInfo('正在后台重建本地数据库…', '');
    progSet('#dbProgWrap', 0, '重建本地数据库…', 'indet');
    try { N.rebuildLocalDb(); } catch (e) {
      onDbRebuild({ phase: 'error', msg: dbHint(e) });
    }
  }

  /** Native 重建回调（全局）：start / step / done / error 四相。 */
  function onDbRebuild(o) {
    if (typeof o === 'string') o = { phase: 'step', msg: o };
    o = o || {};
    var phase = o.phase || 'step';
    var msg = o.msg || '';
    var pct = Number(o.pct);
    if (!isFinite(pct)) pct = 0;
    var rows = o.rows;
    if (phase === 'done') {
      progSet('#dbProgWrap', 100,
        '重建完成 · 共 ' + (rows === undefined ? '—' : rows) + ' 根', 'done');
      dbInfo('重建完成 · 共 ' + (rows === undefined ? '—' : rows)
        + ' 根（ticks / 预测记录已保留）', 'ok');
      S.db.busy = false;
      setDbButtons(false);
      pull(true);
      renderDbDiag();
      return;
    }
    if (phase === 'error') {
      progSet('#dbProgWrap', pct, '重建失败：' + msg, 'fail');
      dbInfo('重建失败：' + msg, 'err');
      S.db.busy = false;
      setDbButtons(false);
      renderDbDiag();
      pull(true);
      return;
    }
    progSet('#dbProgWrap', pct,
      (phase === 'start' ? '开始重建 · ' : '重建中 · ') + msg
      + (rows === undefined ? '' : '（' + rows + ' 根）'),
      pct > 0 ? '' : 'indet');
    dbInfo('重建中 · ' + msg, '');
  }

  // Native 可能通过 window.onDbRebuild 或 FF.onDbRebuild 回调，两个入口都指向同一处理函数
  window.onDbRebuild = onDbRebuild;

  function initDb() {
    var el = $('#dbServerBase');
    if (el) el.value = S.db.base;
  }

  // ---------------- 设置 ----------------

  function loadSettings() {
    var s = {};
    try {
      s = JSON.parse(DEMO
        ? '{"refresh_sec":30,"llm_base":"https://api.deepseek.com/v1","llm_model":"deepseek-chat","has_llm_key":false,"theme":"dark","version":"2.5.0"}'
        : N.settings());
    } catch (e) { s = {}; }
    S.settings = s;
    if (!S.settings.theme) S.settings.theme = isLight() ? 'light' : 'dark';
    if (FORCED) S.settings.theme = FORCED;
    applyTheme(S.settings.theme, false);
    $('#llmBase').value = s.llm_base || '';
    $('#llmModel').value = s.llm_model || '';
    $('#llmMask').textContent = s.has_llm_key ? s.llm_key_mask : '未配置';
    $('#gtMask').textContent = s.has_gitee_token ? s.gitee_token_mask : '未配置';
    $('#aboutVer').textContent = s.version || '—';
    $('#brandVer').textContent = 'v' + (s.version || '2.1');
    renderIntervalChips();
    renderThemeChips();
  }

  function renderThemeChips() {
    var cur = (S.settings && S.settings.theme) || 'dark';
    var box = $('#themeChips');
    box.innerHTML = THEMES.map(function (t) {
      return '<button class="chip' + (t.v === cur ? ' on' : '') + '" data-theme="' + t.v + '">'
        + t.label + '</button>';
    }).join('');
    Array.prototype.forEach.call(box.querySelectorAll('.chip'), function (b) {
      b.addEventListener('click', function () {
        var v = b.getAttribute('data-theme');
        if (v === S.settings.theme) return;
        S.settings.theme = v;
        applyTheme(v, true);
        renderThemeChips();
        // 重绘画布，让网格与现价标签跟随配色
        renderQuotes();
        renderChart();
        toast(v === 'light' ? '已切换为白天模式' : '已切换为黑夜模式');
      });
    });
  }

  function renderIntervalChips() {
    var cur = (S.settings && S.settings.refresh_sec) || 30;
    var html = '';
    INTERVALS.forEach(function (it) {
      html += '<button class="chip' + (it.v === cur ? ' on' : '') + '" data-sec="' + it.v + '">'
        + it.label + '</button>';
    });
    var box = $('#intervalChips');
    box.innerHTML = html;
    Array.prototype.forEach.call(box.querySelectorAll('.chip'), function (b) {
      b.addEventListener('click', function () {
        var sec = parseInt(b.getAttribute('data-sec'), 10);
        saveSettings({ refresh_sec: sec });
        S.settings.refresh_sec = sec;
        renderIntervalChips();
        toast('已切换为每 ' + (sec >= 60 ? (sec / 60) + ' 分钟' : sec + ' 秒') + ' 刷新');
      });
    });
  }

  function saveSettings(obj) {
    try {
      var r = JSON.parse(DEMO ? '{"ok":true,"message":"已保存"}' : N.saveSettings(JSON.stringify(obj)));
      toast(r.message || '已保存');
      loadSettings();
    } catch (e) {
      toast('保存失败');
    }
  }

  // ---------------- 大模型 ----------------

  function runLlm() {
    if (S.llmBusy) return;
    S.llmBusy = true;
    var btn = $('#btnLlm');
    btn.disabled = true;
    btn.textContent = '分析中…';
    $('#llmBox').innerHTML = '<div class="muted">正在请求模型，通常需要 5–20 秒…</div>';
    if (DEMO) {
      setTimeout(function () { onLlm(demoLlm()); }, 900);
      return;
    }
    try {
      N.llmAnalyze(S.code);
    } catch (e) {
      onLlm({ ok: false, text: '调用失败：' + e.message });
    }
  }

  function onLlm(res) {
    S.llmBusy = false;
    var btn = $('#btnLlm');
    btn.disabled = false;
    btn.textContent = '生成分析';
    var box = $('#llmBox');
    if (!res || !res.ok) {
      box.innerHTML = '<div class="muted">' + esc((res && res.text) || '分析失败') + '</div>';
      return;
    }
    var d = res.data;
    if (!d) {
      box.innerHTML = '<div class="raw">' + esc(res.text) + '</div>';
      return;
    }
    var overall = d.overall || d.direction || '—';
    var html = '<div class="llm-head">'
      + '<span class="llm-dir ' + dirClass(overall) + '">' + esc(overall) + '</span>'
      + '<span class="muted">综合置信度 ' + (d.confidence ? (d.confidence * 100).toFixed(0) + '%' : '—') + '</span>'
      + '</div>';

    var hs = d.horizons || [];
    if (hs.length) {
      html += '<div class="llm-matrix">';
      hs.forEach(function (h) {
        var dir = h.direction || '—';
        var lo = h.target_low, hi = h.target_high;
        var hasRange = lo !== undefined && hi !== undefined && lo !== null && hi !== null;
        html += '<div class="lm-item">'
          + '<div class="lm-line">'
          + '<span class="lm-k">' + esc(h.label || h.key || '') + '</span>'
          + '<span class="lm-d ' + dirClass(dir) + '">' + esc(dir) + '</span>'
          + '<span class="lm-c">' + (h.confidence ? Math.round(h.confidence * 100) + '%' : '—') + '</span>'
          + '<span class="lm-t">' + (hasRange ? num(lo, 2) + ' ~ ' + num(hi, 2) : '—') + '</span>'
          + '</div>'
          + (h.note ? '<div class="lm-note">' + esc(h.note) + '</div>' : '')
          + '</div>';
      });
      html += '</div>';
    }
    if (d.reasons && d.reasons.length) {
      html += '<div class="llm-sec"><b>判断依据</b><ul>';
      d.reasons.forEach(function (r) { html += '<li>' + esc(r) + '</li>'; });
      html += '</ul></div>';
    }
    if (d.risks && d.risks.length) {
      html += '<div class="llm-sec"><b>风险提示</b><ul>';
      d.risks.forEach(function (r) { html += '<li>' + esc(r) + '</li>'; });
      html += '</ul></div>';
    }
    if (d.action) {
      html += '<div class="llm-sec"><b>操作建议</b><div>' + esc(d.action) + '</div></div>';
    }
    html += '<div class="hint">以上为模型观点，与量化结论并列展示，不构成投资建议。</div>';
    box.innerHTML = html;
  }

  // ---------------- 更新 ----------------

  /** 通用进度条：wrap 里 .prog > i 是填充，.prog-meta 是两个文字位。 */
  function progSet(sel, pct, text, state) {
    var wrap = $(sel);
    if (!wrap) return;
    wrap.hidden = false;
    var bar = wrap.querySelector('.prog');
    var fill = wrap.querySelector('.prog > i');
    var label = wrap.querySelector('.prog-meta span:first-child');
    var num = wrap.querySelector('.prog-meta span:last-child');
    if (bar) bar.className = 'prog' + (state ? ' ' + state : '');
    var v = Math.max(0, Math.min(100, pct || 0));
    if (fill) fill.style.width = v + '%';
    if (num) num.textContent = state === 'indet' ? '—' : Math.round(v) + '%';
    if (text != null && label) label.textContent = text;
  }

  function progHide(sel) {
    var wrap = $(sel);
    if (wrap) wrap.hidden = true;
  }

  /** 更新进度条的失败态：保留已下到的位置，右侧百分比改写成「失败」。 */
  function progFail(pct, text) {
    progSet('#updProgWrap', pct || 0, text, 'fail');
    var el = $('#updProgPct');
    if (el) el.textContent = '失败';
  }

  function mb(b) { return ((b || 0) / 1048576).toFixed(1); }

  function speedStr(bps) {
    if (!bps || bps <= 0) return '';
    return bps >= 1048576
      ? (bps / 1048576).toFixed(1) + ' MB/s'
      : Math.max(1, Math.round(bps / 1024)) + ' KB/s';
  }

  function secStr(s) {
    var v = Math.max(1, Math.round(s));
    return v >= 60 ? Math.floor(v / 60) + ' 分 ' + (v % 60) + ' 秒' : v + ' 秒';
  }

  function onSyncStep(msg) {
    var text, pct = 0, hasTotal = false;
    if (msg && typeof msg === 'object') {
      text = msg.msg || '';
      hasTotal = msg.total > 0;
      pct = msg.pct || 0;
      if (hasTotal) text += '（' + Math.min((msg.done || 0) + 1, msg.total) + '/' + msg.total + ' 步）';
    } else {
      text = String(msg == null ? '' : msg);
    }
    // 步骤文案只放进度条里，下面的 hint 在同步过程中保持固定说明，避免重复两行同样的字
    progSet('#syncProgWrap', pct, text, hasTotal ? '' : 'indet');
  }

  function onSync(res) {
    var b = $('#btnSyncHistory');
    if (b) {
      b.disabled = false;
      b.textContent = '同步历史数据';
    }
    var info = $('#syncInfo');
    if (info) info.removeAttribute('data-busy');
    if (!res || !res.ok) {
      var m = (res && res.message) || '同步失败';
      if (info) info.textContent = m;
      progSet('#syncProgWrap', 0, m, 'fail');
      toast(m);
      return;
    }
    var c = res.check || {};
    var t = res.ticks || {};
    var head = '体检 ' + (c.scanned || 0) + ' 组';
    if (c.forced) {
      head += ' · 强制全量重写';
    } else if ((c.missing || 0) > 0 || (c.cleared || 0) > 0) {
      head += ' · 回补 ' + (c.healed_bars || 0) + ' 根 / ' + (c.healed_items || 0) + ' 组';
      if ((c.cleared || 0) > 0) head += '（清空 ' + c.cleared + ' 组）';
    } else {
      head += ' · 无缺失';
    }
    var tickTxt = '秒级 ' + (t.points || 0) + ' 点';
    if (t.age_sec >= 0) tickTxt += '（最新 ' + t.age_sec + ' 秒前' + (t.refilled ? '，已补当前点' : '') + '）';

    progSet('#syncProgWrap', 100,
      '同步完成 · 新增 ' + (res.fetched || 0) + ' 根'
      + ((c.healed_bars || 0) > 0 ? ' · 回补 ' + c.healed_bars + ' 根' : ''), 'done');
    S.syncNote = res.note || '';
    if (info) {
      info.textContent = '同步完成：' + head + ' · ' + tickTxt
        + (res.note ? '\n' + res.note : '')
        + (res.warn ? '\n' + res.warn : '');
    }
    toast('同步完成：' + head);
    pull(true);
  }

  function onPredictStep(step) {
    if (!step) return;
    var txt = step.msg || '';
    if (step.total > 0) txt += '（' + Math.min((step.done || 0) + 1, step.total) + '/' + step.total + ' 步）';
    progSet('#predProgWrap', step.pct || 0, txt, step.total > 0 ? '' : 'indet');
  }

  function onPredict(res) {
    S.predBusy = false;
    var b = $('#btnPredict');
    if (b) {
      b.disabled = false;
      b.textContent = '更新预测';
    }
    // 成功 / 失败都解除 #predInfo 的 busy 锁，避免 renderForecastMeta 永久跳过导致文本冻结
    var info = $('#predInfo');
    if (info) info.removeAttribute('data-busy');
    if (!res || !res.ok) {
      var m = (res && res.message) || '刷新失败';
      progSet('#predProgWrap', 0, m, 'fail');
      toast(m);
      return;
    }
    progSet('#predProgWrap', 100,
      (res.message || '已更新') + '（分钟线 ' + (res.added_minute || 0)
      + ' / 日线 ' + (res.added_daily || 0) + ' 根）', 'done');
    toast(res.message || '预测已更新');
    // 先 pull 再回填：pull 会整体替换 S.state，回填必须放在其后才不会被覆盖；
    // res 里并没有 next_forecast，下次自动时间以 native state 为准。
    pull(true);
    S.state = S.state || {};
    S.state.last_forecast = res.forecast_at || Date.now();
    renderForecastMeta();
  }

  /** 预测时间提示：上次/下次自动刷新。 */
  function renderForecastMeta() {
    var info = $('#predInfo');
    if (!info || !S.state) return;
    if (info.getAttribute('data-busy') === '1') return;
    var last = S.state.last_forecast, next = S.state.next_forecast;
    var txt = last ? '上次更新 ' + timeStr(last) : '尚未更新';
    if (next && next > Date.now()) txt += ' · 下次自动 ' + timeStr(next);
    txt += ' · 整分钟更短线四档，整点全量七档';
    info.textContent = txt;
  }

  /**
   * 检查更新：从点击起进度条就在场上。
   * 查询发行版阶段为不确定态，读到 release 信息后转为确定进度（由 native 的 check 阶段回调驱动），
   * 检查结束不隐藏进度条——有新版直接续成下载进度，无新版落成灰色完成态。
   */
  function checkUpdate() {
    var info = $('#updInfo');
    var btn = $('#btnUpdateInstall');
    S.upd = { phase: 'check' };
    info.textContent = '正在检查更新（Gitee 首要源）…';
    if (btn) { btn.disabled = true; btn.textContent = '立即更新'; }
    progSet('#updProgWrap', 0, '正在读取 Gitee 发行版信息…', 'indet');
    if (DEMO) {
      onUpdate({ ok: true, has_update: false, message: '已是最新版本 v2.6.0（演示）' });
      return;
    }
    try { N.checkUpdate(); } catch (e) {
      S.upd = { phase: 'fail' };
      progFail(0, '检查失败：' + e.message);
      if (btn) { btn.disabled = false; btn.textContent = '重试检查'; }
    }
  }

  function startDownload(o) {
    var btn = $('#btnUpdateInstall');
    if (DEMO) { toast('演示模式不支持安装'); return; }
    S.upd = { phase: 'downloading' };
    S.updT0 = Date.now();
    progSet('#updProgWrap', 0, o.size > 0 ? '正在连接，共 ' + mb(o.size) + ' MB…' : '正在连接…', 'indet');
    if (btn) { btn.disabled = true; btn.textContent = '下载中…'; }
    try {
      N.installUpdate(o.apk_url, o.version, o.size || 0);
    } catch (e) {
      onUpdateProgress({ phase: 'error' });
      toast('下载失败：' + e.message);
    }
  }

  function onUpdateProgress(o) {
    if (!o) return;
    var btn = $('#btnUpdateInstall');
    var phase = o.phase;
    if (phase === 'check') {
      // pct=0 表示还在读发行版（不确定态），之后是解析附件/安装包的确定进度
      progSet('#updProgWrap', o.pct || 0, o.msg || '正在检查更新…',
        (o.pct || 0) > 0 ? '' : 'indet');
      return;
    }
    if (phase === 'error') {
      // 失败文案由 native 脱敏，直接展示；保留失败态红轨、右侧「失败」、按钮「重试更新」
      S.upd = { phase: 'fail' };
      progFail(S.updPct || 0, o.msg || o.message || '下载失败');
      if (btn) { btn.disabled = false; btn.textContent = '重试更新'; }
      return;
    }
    if (phase === 'done') {
      S.upd = { phase: 'done' };
      // 强制 100%：无论 size / 已下载字节为多少，完成态都要满格
      progSet('#updProgWrap', 100, '正在调起系统安装器', 'done');
      if (btn) { btn.disabled = true; btn.textContent = '下载完成'; }
      return;
    }
    if (phase === 'need_permission') {
      // 已下载并通过校验，但未获「安装未知应用」权限：按钮改为可点的「继续安装」
      S.upd = { phase: 'need_permission' };
      progSet('#updProgWrap', 100, '安装包已就绪，点「继续安装」授权后安装', '');
      if (btn) { btn.disabled = false; btn.textContent = '继续安装'; }
      return;
    }
    if (phase === 'start') {
      S.upd = { phase: 'connecting' };
      S.updT0 = Date.now();
      progSet('#updProgWrap', 0,
        o.total > 0 ? '正在连接，共 ' + mb(o.total) + ' MB…' : '正在连接…', 'indet');
      if (btn) { btn.disabled = true; btn.textContent = '下载中…'; }
      return;
    }
    // 其余均为下载进度（含 done=0 的首帧）：显式按 phase 分流，避免 0 字节被 !o.done 误判成重下起点
    var known = o.total > 0;
    var txt = '下载中 ' + mb(o.done) + (known ? ' / ' + mb(o.total) : '') + ' MB';
    var sec = S.updT0 ? (Date.now() - S.updT0) / 1000 : 0;
    if (known && sec >= 1 && o.done > 0) {
      var speed = o.done / sec;
      txt += ' · ' + speedStr(speed);
      var left = (o.total - o.done) / speed;
      if (left > 0 && left < 3600) txt += ' · 剩余 ' + secStr(left);
    }
    S.upd = { phase: 'downloading' };
    S.updPct = o.pct || 0;
    progSet('#updProgWrap', o.pct || 0, txt, known ? '' : 'indet');
    if (btn) {
      btn.disabled = true;
      btn.textContent = known ? '下载中 ' + (o.pct || 0) + '%' : '下载中…';
    }
  }

  function onUpdate(o) {
    S.update = o;
    var info = $('#updInfo');
    var btn = $('#btnUpdateInstall');
    if (!o) {
      S.upd = { phase: 'fail' };
      if (info) info.textContent = '检查失败：没有返回结果';
      progFail(0, '检查失败：没有返回结果');
      if (btn) { btn.disabled = false; btn.textContent = '重试检查'; }
      return;
    }
    if (!o.ok) {
      S.upd = { phase: 'fail' };
      if (info) info.textContent = o.message || '检查失败';
      progFail(0, o.message || '检查失败');
      if (btn) { btn.disabled = false; btn.textContent = '重试检查'; }
      return;
    }
    if (o.has_update) {
      var src = o.source === 'gitee' ? 'Gitee' : (o.source || 'Gitee');
      if (info) {
        info.textContent = '发现新版本 ' + o.version + '（'
          + (o.size ? Math.round(o.size / 1048576 * 10) / 10 + ' MB · ' : '') + src + '）\n'
          + (o.notes || '');
      }
      S.upd = { phase: 'ready' };
      progSet('#updProgWrap', 0, '发现新版本 ' + o.version + ' · 点「立即更新」开始下载', 'idle');
      if (btn) { btn.disabled = false; btn.textContent = '立即更新'; }
    } else {
      S.upd = { phase: 'latest' };
      if (info) info.textContent = o.message || '已是最新版本';
      progSet('#updProgWrap', 100, o.message || '已是最新版本', 'idle');
      if (btn) { btn.disabled = true; btn.textContent = '已是最新'; }
    }
  }

  // ---------------- 事件 ----------------

  function bind() {
    // tab
    Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (t) {
      t.addEventListener('click', function () {
        var tab = t.getAttribute('data-tab');
        S.tab = tab;
        Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (x) {
          x.classList.toggle('active', x.getAttribute('data-tab') === tab);
        });
        Array.prototype.forEach.call(document.querySelectorAll('.view'), function (v) {
          v.classList.toggle('active', v.id === 'v-' + tab);
        });
        window.scrollTo(0, 0);
        if (tab === 'history') loadHistory();
        if (tab === 'settings') loadSettings();
        if (tab === 'forecast') renderForecast();
      });
    });

    // 行情卡片：事件委托只在容器上绑定一次，避免每次 innerHTML 重建都重复 addEventListener
    $('#quoteCards').addEventListener('click', function (e) {
      var el = e.target.closest('.qcard');
      if (!el) return;
      S.code = el.getAttribute('data-code');
      if (S.server.on) { pull(true); return; }   // 服务端模式按新品种重取 snapshot
      renderQuotes();
      renderChart();
      renderIndicators();
      renderForecast();
    });

    // 周期 chips
    var pc = $('#periodChips');
    pc.innerHTML = PERIODS.map(function (p) {
      return '<button class="chip' + (p.k === S.period ? ' on' : '') + '" data-p="' + p.k + '">' + p.label + '</button>';
    }).join('');
    Array.prototype.forEach.call(pc.querySelectorAll('.chip'), function (b) {
      b.addEventListener('click', function () {
        S.period = b.getAttribute('data-p');
        Array.prototype.forEach.call(pc.querySelectorAll('.chip'), function (x) {
          x.classList.toggle('on', x === b);
        });
        if (S.server.on) pull(true);   // 服务端模式按新周期重取 snapshot
        else renderChart();
      });
    });

    // 单品种 / 三品种对比
    var mc = $('#modeChips');
    var MODES = [{ k: 'single', l: '单品种' }, { k: 'compare', l: '三品种对比' }];
    function renderModeChips() {
      mc.innerHTML = MODES.map(function (m) {
        var on = m.k === 'compare' ? !!S.compare : !S.compare;
        return '<button class="chip' + (on ? ' on' : '') + '" data-m="' + m.k + '">' + m.l + '</button>';
      }).join('');
    }
    renderModeChips();
    mc.addEventListener('click', function (e) {
      var b = e.target.closest('.chip');
      if (!b) return;
      S.compare = b.getAttribute('data-m') === 'compare';
      try { localStorage.setItem('ff_compare', S.compare ? '1' : '0'); } catch (err) { }
      renderModeChips();
      renderChart();
      toast(S.compare ? '三品种对比：纵轴为相对窗口首点的涨跌幅' : '已切回单品种');
    });
    try { S.compare = localStorage.getItem('ff_compare') === '1'; } catch (err) { }
    renderModeChips();

    // 十字星：按住拖动查看时间与价格
    var cv = $('#chart');
    var dragging = false;
    var crossOf = function (e) {
      var g = S.chart;
      if (!g) return null;
      var rect = cv.getBoundingClientRect();
      var x = e.clientX - rect.left;
      var y = e.clientY - rect.top;
      if (x < g.padL - 8 || x > g.w - g.padR + 8) return null;
      var ts = Xinv(g, x);
      var primary = g.items.length ? g.items[0] : null;
      var p = primary ? nearestPoint(primary.pts, ts) : null;
      return {
        ts: p ? p[0] : ts,
        y: Math.max(g.padT, Math.min(g.h - g.padB, y))
      };
    };
    var showCross = function (cross) {
      if (S.crossTimer) { clearTimeout(S.crossTimer); S.crossTimer = 0; }
      if (!S.chart) return;
      S.crossActive = true;
      paint(cross);
    };
    var hideCross = function (after) {
      if (S.crossTimer) clearTimeout(S.crossTimer);
      S.crossTimer = setTimeout(function () {
        S.crossTimer = 0;
        S.crossActive = false;
        renderChart();   // 补上冻结期间到达的新数据，并恢复跟随最新
      }, after || 0);
    };
    cv.addEventListener('pointerdown', function (e) {
      var c = crossOf(e);
      if (!c) return;
      dragging = true;
      try { cv.setPointerCapture(e.pointerId); } catch (err) { }
      showCross(c);
      e.preventDefault();
    });
    cv.addEventListener('pointermove', function (e) {
      if (!dragging && e.pointerType === 'mouse') {
        var c0 = crossOf(e);
        if (c0) showCross(c0); else paint(null);
        return;
      }
      if (!dragging) return;
      var c = crossOf(e);
      if (c) showCross(c);
      e.preventDefault();
    });
    cv.addEventListener('pointerup', function () {
      if (!dragging) return;
      dragging = false;
      hideCross(2500);
    });
    cv.addEventListener('pointercancel', function () {
      dragging = false;
      hideCross(0);
    });
    cv.addEventListener('pointerleave', function () {
      if (!dragging) hideCross(400);
    });

    // 品种 chips
    var qc = $('#prodChips');
    qc.innerHTML = ['AL', 'AO', 'AHD'].map(function (c) {
      return '<button class="chip' + (c === S.code ? ' on' : '') + '" data-c="' + c + '">' + c + '</button>';
    }).join('');

    // 历史筛选
    var hc = $('#histChips');
    var opts = [{ k: '', l: '全部' }, { k: 'AL', l: '沪铝' }, { k: 'AO', l: '氧化铝' }, { k: 'AHD', l: '伦铝' }];
    hc.innerHTML = opts.map(function (o) {
      return '<button class="chip' + (o.k === S.histCode ? ' on' : '') + '" data-h="' + o.k + '">' + o.l + '</button>';
    }).join('');
    Array.prototype.forEach.call(hc.querySelectorAll('.chip'), function (b) {
      b.addEventListener('click', function () {
        S.histCode = b.getAttribute('data-h');
        Array.prototype.forEach.call(hc.querySelectorAll('.chip'), function (x) {
          x.classList.toggle('on', x === b);
        });
        loadHistory();
      });
    });

    // 手动刷新
    $('#btnRefresh').addEventListener('click', function () {
      var btn = this;
      btn.classList.add('spin');
      if (DEMO) { setTimeout(function () { btn.classList.remove('spin'); pull(true); }, 500); return; }
      try { N.refresh(); } catch (e) { /* ignore */ }
      setTimeout(function () { btn.classList.remove('spin'); pull(true); }, 1200);
    });

    // 大模型
    $('#btnLlm').addEventListener('click', runLlm);

    // 设置保存
    $('#btnLlmSave').addEventListener('click', function () {
      saveSettings({
        llm_base: $('#llmBase').value.trim(),
        llm_model: $('#llmModel').value.trim(),
        llm_key: $('#llmKey').value.trim()
      });
      $('#llmKey').value = '';
    });
    $('#btnLlmClear').addEventListener('click', function () {
      saveSettings({ llm_key: 'clear' });
      $('#llmKey').value = '';
    });
    $('#giteeToken').addEventListener('change', function () {
      var v = this.value.trim();
      if (v) { saveSettings({ gitee_token: v }); this.value = ''; }
    });
    $('#btnUpdateCheck').addEventListener('click', checkUpdate);
    // 一键续跑：检查失败 → 重试检查；缺安装权限 → 直接装缓存 APK（不重下）；下载失败 → 直接重下
    $('#btnUpdateInstall').addEventListener('click', function () {
      var o = S.update;
      var phase = (S.upd && S.upd.phase) || '';
      var btn = this;
      if (phase === 'fail' && (!o || !o.has_update)) { checkUpdate(); return; }
      if (phase === 'need_permission') {
        // 已下载且校验通过，native 直接用 check 缓存安装，不再重新下载
        if (DEMO || !N || typeof N.installCachedUpdate !== 'function') {
          toast('当前环境不支持继续安装');
          return;
        }
        btn.disabled = true;
        btn.textContent = '正在安装…';
        try {
          N.installCachedUpdate();
        } catch (e) {
          btn.disabled = false;
          btn.textContent = '继续安装';
          toast('安装失败：' + e.message);
        }
        return;
      }
      if (!o || !o.has_update) return;
      startDownload(o);
    });
    // 同步历史数据：先体检最近 7 天，再补齐 / 增量
    $('#btnSyncHistory').addEventListener('click', function () {
      if (DEMO) { toast('演示模式不支持同步'); return; }
      var b = this;
      var forceEl = $('#chkForceResync');
      var force = !!(forceEl && forceEl.checked);
      b.disabled = true;
      b.textContent = '同步中…';
      S.syncNote = '';
      var info = $('#syncInfo');
      if (info) {
        info.setAttribute('data-busy', '1');
        info.textContent = '正在与官方源比对最近 7 天的完整性，进度见上方进度条；中途请勿退出应用。';
      }
      progSet('#syncProgWrap', 0, force ? '强制全量重写近 7 天…' : '正在体检最近 7 天数据…', 'indet');
      try {
        if (force && N.syncHistoryForce) N.syncHistoryForce(true);
        else N.syncHistory();
      } catch (e) {
        b.disabled = false;
        b.textContent = '同步历史数据';
        progSet('#syncProgWrap', 0, '同步失败：' + e.message, 'fail');
        toast('同步失败：' + e.message);
      }
    });

    // 手动刷新预测（先增量补数据，再重算七档）
    $('#btnPredict').addEventListener('click', function () {
      if (DEMO) {
        S.state = S.state || {};
        onPredict({ ok: true, message: '演示模式：已重算七档', added_minute: 0, added_daily: 0 });
        return;
      }
      var b = this;
      b.disabled = true;
      b.textContent = '刷新中…';
      S.predBusy = true;
      var info = $('#predInfo');
      if (info) info.setAttribute('data-busy', '1');
      progSet('#predProgWrap', 0, '正在补齐最近行情…', 'indet');
      try { N.predict(); } catch (e) {
        b.disabled = false;
        b.textContent = '更新预测';
        S.predBusy = false;
        onPredict({ ok: false, message: '刷新失败：' + e.message });
      }
    });

    $('#btnClearFc').addEventListener('click', function () {      if (!confirm('确定清空全部预测记录？预测命中率统计会一起重置。')) return;
      if (DEMO) { toast('演示模式'); return; }
      try { N.clearForecasts(); } catch (e) { /* ignore */ }
      S.state = S.state || {};
      if (S.state.stats) S.state.stats.forecasts = 0;
      loadHistory();
      renderStats();
    });

    // 品种切换（顶部 chips）
    qc.addEventListener('click', function (e) {
      var b = e.target.closest('.chip');
      if (!b) return;
      S.code = b.getAttribute('data-c');
      Array.prototype.forEach.call(qc.querySelectorAll('.chip'), function (x) {
        x.classList.toggle('on', x === b);
      });
      if (S.server.on) { pull(true); return; }   // 服务端模式按新品种重取 snapshot
      renderQuotes();
      renderChart();
      renderIndicators();
      renderForecast();
    });

    // 服务端对照：开关 / 测试连接 / 地址输入
    var sOn = $('#serverOn');
    if (sOn) sOn.addEventListener('change', onServerToggle);
    var sTest = $('#btnServerTest');
    if (sTest) sTest.addEventListener('click', onServerTest);
    var sBase = $('#serverBase');
    if (sBase) sBase.addEventListener('change', function () {
      S.server.base = sBase.value.trim() || S.server.base;
      saveServerPrefs();
    });

    // 数据库同步（手机 ↔ 服务端）：同步 / 测试 / 重建 / 地址持久化
    var dbSyncBtn = $('#btnDbSync');
    if (dbSyncBtn) dbSyncBtn.addEventListener('click', dbSyncStart);
    var dbTestBtn = $('#btnDbTest');
    if (dbTestBtn) dbTestBtn.addEventListener('click', dbTest);
    var dbRebuildBtn = $('#btnDbRebuild');
    if (dbRebuildBtn) dbRebuildBtn.addEventListener('click', dbRebuildStart);
    var dbBaseEl = $('#dbServerBase');
    if (dbBaseEl) dbBaseEl.addEventListener('change', saveDbBase);
  }

  // ---------------- Native 回调 ----------------

  window.FF = {
    onData: function () { pull(true); },
    onLlm: function (res) { onLlm(res); },
    onUpdate: function (o) { onUpdate(o); },
    onUpdateProgress: function (o) { onUpdateProgress(o); },
    onSync: function (res) { onSync(res); },
    onSyncStep: function (msg) { onSyncStep(msg); },
    onPredictStep: function (o) { onPredictStep(o); },
    onPredict: function (res) { onPredict(res); },
    onToast: function (msg) { toast(msg); },
    onDbRebuild: function (o) { onDbRebuild(o); }
  };

  // ---------------- 演示数据（浏览器预览用） ----------------

  function demoState() {
    var mk = function (code, short, name, unit, base, chg) {
      return {
        code: code, short: short, name: name, unit: unit,
        quote: {
          last: base, open: base * 0.996, high: base * 1.004, low: base * 0.992,
          prev_close: base / (1 + chg / 100), change_pct: chg, ts: Date.now()
        },
        minute_bars: 1024, daily_bars: 4800,
        forecast: demoForecast(base)
      };
    };
    return {
      products: [mk('AL', '沪铝', '电解铝（沪铝主力）', '元/吨', 20380, 0.86),
                 mk('AO', '氧化铝', '氧化铝（沪氧化铝主力）', '元/吨', 3126, -0.42),
                 mk('AHD', '伦铝', '伦铝（LME 3M）', '美元/吨', 2612, 0.31)],
      last_tick: Date.now(), last_error: '',
      refresh_sec: 30,
      last_sync: Date.now() - 3 * 3600_000,
      stats: {
        ticks: 8462, minute: 1023, daily: 5313, forecasts: 96,
        periods: {
          '1m': { n: 1023, from: Date.now() - 4 * 86400_000 },
          '5m': { n: 1023, from: Date.now() - 17 * 86400_000 },
          '15m': { n: 1023, from: Date.now() - 56 * 86400_000 },
          '30m': { n: 1023, from: Date.now() - 100 * 86400_000 },
          '60m': { n: 1023, from: Date.now() - 195 * 86400_000 },
          '1d': { n: 5313, from: Date.now() - 7500 * 86400_000 }
        }
      }
    };
  }

  function demoForecast(price) {
    var hs = [['1m', '1分钟', 0.53, 1], ['5m', '5分钟', 0.57, 5], ['15m', '15分钟', 0.61, 15],
              ['30m', '30分钟', 0.54, 30], ['4h', '4小时', 0.47, 240],
              ['8h', '8小时', 0.48, 480], ['1d', '1天', 0.44, 1440]];
    return {
      price: price, score: 0.22, overall: '中性',
      recommendation: '短线偏多：回踩短均线不破可轻仓跟随，跌破 EMA20 止损；多周期方向不一致，优先降低杠杆',
      indicators: {
        rsi: 58.4, macd_hist: 6.21, atr_pct: 0.84, boll_pos: 0.63,
        vol20_pct: 0.42, range_pos: 0.71, bias: '偏多'
      },
      horizons: hs.map(function (h) {
        var d = h[2] >= 0.55 ? '上涨' : h[2] <= 0.45 ? '下跌' : '震荡';
        return {
          key: h[0], label: h[1], minutes: h[3], direction: d, p_up: h[2],
          target_price: price * (1 + (h[2] - 0.5) * 0.02), confidence: Math.abs(h[2] - 0.5) * 2
        };
      })
    };
  }

  function demoSpark(code) {
    var seed = code === 'AL' ? 1 : code === 'AO' ? 2 : 3;
    var v = 100, out = [];
    for (var i = 0; i < 40; i++) {
      v += Math.sin(i / 3 + seed) * 0.7 + (Math.random() - 0.5) * 0.8;
      out.push([Date.now() - (40 - i) * 30000, v]);
    }
    return out;
  }

  function demoSeries(code, period) {
    var base = code === 'AL' ? 20380 : code === 'AO' ? 3126 : 2612;
    var phase = code === 'AL' ? 0 : code === 'AO' ? 1.1 : 2.3;
    var out = [], v = base;
    var tick = period && period.tick !== undefined;
    var n = tick ? 160 : 180;
    var gap = tick ? (period.tick === 0 ? 5000 : 30000) : 60000;
    for (var i = 0; i < n; i++) {
      var drift = Math.sin(i / 14 + phase) * base * 0.0016 + (Math.random() - 0.5) * base * 0.0009;
      v += drift;
      var h = v * 1.0008, l = v * 0.9992;
      out.push([Date.now() - (n - i) * gap, v, h, l, v, tick ? 1 : Math.round(1000 + Math.random() * 500)]);
    }
    return out;
  }

  function demoLlm() {
    var price = 20380;
    var ks = [['1m', '1分钟', '看涨', 0.58], ['5m', '5分钟', '看涨', 0.62],
              ['15m', '15分钟', '看涨', 0.6], ['30m', '30分钟', '震荡', 0.52],
              ['4h', '4小时', '震荡', 0.5], ['8h', '8小时', '看跌', 0.56],
              ['1d', '1天', '看跌', 0.54]];
    return {
      ok: true,
      code: S.code,
      data: {
        overall: '震荡', confidence: 0.55,
        action: '短线回踩 20300 附近轻仓试多，跌破 20180 止损；8 小时以上偏空，多单不隔夜',
        reasons: ['1–15 分钟均线多头排列，短周期动能向上', 'RSI 58 处于强势区但未超买', '日线仍在下降通道内，反弹高度受限'],
        risks: ['8 小时与日线方向向下，存在回调压力', '临近交割月波动可能放大'],
        horizons: ks.map(function (k) {
          var up = k[2] === '看涨', down = k[2] === '看跌';
          return {
            key: k[0], label: k[1], direction: k[2], confidence: k[3],
            target_low: up ? price * 1.001 : down ? price * 0.985 : price * 0.996,
            target_high: up ? price * 1.012 : down ? price * 0.998 : price * 1.004,
            note: k[2] === '震荡' ? '区间内反复，等突破' : '跟随短周期动能'
          };
        })
      }
    };
  }

  // ---------------- 启动 ----------------

  bind();
  initServer();   // 先恢复服务端开关，再让首次 pull 用正确的模式取数
  initDb();       // 恢复数据库同步地址输入框
  pull(true);
  loadSettings();
  renderDbDiag(); // 启动时渲染本地库诊断区
  setInterval(function () {
    if (S.tab === 'market' || S.tab === 'forecast') pull();
  }, 5000);

  // 支持 ?tab=forecast|history|settings 直达（便于调试与分享定位）
  try {
    var q = (location.search.match(/[?&]tab=([a-z]+)/) || [])[1];
    if (q) {
      var t = document.querySelector('.tab[data-tab="' + q + '"]');
      if (t) t.click();
    }
  } catch (e) { /* ignore */ }
})();
