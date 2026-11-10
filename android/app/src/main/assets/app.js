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
    predBusy: false
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

  function pull() {
    var raw = null;
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
    // 选中品种保持有效
    var codes = (S.state.products || []).map(function (p) { return p.code; });
    if (codes.length && codes.indexOf(S.code) < 0) S.code = codes[0];
    renderQuotes();
    if (!S.crossActive) renderChart();   // 按住十字星时先冻结，松手后再跟最新
    renderIndicators();
    renderForecast();
    renderStats();
    var err = S.state.last_error;
    if (err) setLive('数据源异常', 'err');
    else setLive('已更新 ' + nowStr(), 'ok');
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
    Array.prototype.forEach.call(box.querySelectorAll('.qcard'), function (el) {
      el.addEventListener('click', function () {
        S.code = el.getAttribute('data-code');
        renderQuotes();
        renderChart();
        renderIndicators();
        renderForecast();
      });
    });
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
    var row = $('#statRow');
    if (row && row.children.length === 0) {
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
          + '。源站每周期最多 1023 根（60 分钟线约 195 天），重复同步只补增量。';
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

  // ---------------- 设置 ----------------

  function loadSettings() {
    var s = {};
    try {
      s = JSON.parse(DEMO
        ? '{"refresh_sec":30,"llm_base":"https://api.deepseek.com/v1","llm_model":"deepseek-chat","has_llm_key":false,"theme":"dark","version":"2.3.0"}'
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

  function mb(b) { return ((b || 0) / 1048576).toFixed(1); }

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
    progSet('#syncProgWrap', 100, '同步完成 · 新增 ' + (res.fetched || 0) + ' 根', 'done');
    if (info) {
      info.textContent = '同步完成：' + res.message
        + (res.warn ? '\n' + res.warn : '');
    }
    toast('同步完成：' + res.message);
    pull();
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
    if (!res || !res.ok) {
      var m = (res && res.message) || '刷新失败';
      progSet('#predProgWrap', 0, m, 'fail');
      toast(m);
      return;
    }
    progSet('#predProgWrap', 100,
      (res.message || '已更新') + '（分钟线 ' + (res.added_minute || 0)
      + ' / 日线 ' + (res.added_daily || 0) + ' 根）', 'done');
    // 立刻回填时间戳，避免下面的 pull → renderForecastMeta 又显示「尚未更新」
    S.state = S.state || {};
    S.state.last_forecast = res.forecast_at || Date.now();
    if (res.next_forecast) S.state.next_forecast = res.next_forecast;
    toast(res.message || '预测已更新');
    pull();
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

  function checkUpdate() {
    var info = $('#updInfo');
    info.textContent = '正在检查…';
    $('#btnUpdateInstall').disabled = true;
    progHide('#updProgWrap');
    if (DEMO) {
      onUpdate({ ok: true, has_update: false, message: '已是最新版本 v2.0.0（演示）' });
      return;
    }
    try { N.checkUpdate(); } catch (e) { info.textContent = '检查失败：' + e.message; }
  }

  function onUpdateProgress(o) {
    if (!o) return;
    var btn = $('#btnUpdateInstall');
    if (o.phase === 'error') {
      progSet('#updProgWrap', 0, '下载失败，可重新点「重试更新」', 'fail');
      if (btn) { btn.disabled = false; btn.textContent = '重试更新'; }
      return;
    }
    if (o.phase === 'done') {
      progSet('#updProgWrap', 100, '下载完成，正在调起系统安装…', 'done');
      if (btn) { btn.disabled = true; btn.textContent = '下载完成'; }
      return;
    }
    var known = o.total > 0;
    var txt = known
      ? '下载中 ' + mb(o.done) + ' / ' + mb(o.total) + ' MB'
      : '下载中 ' + mb(o.done) + ' MB';
    progSet('#updProgWrap', o.pct || 0, txt, known ? '' : 'indet');
    if (btn) {
      btn.disabled = true;
      btn.textContent = known ? '下载中 ' + (o.pct || 0) + '%' : '下载中…';
    }
  }

  function onUpdate(o) {
    S.update = o;
    var info = $('#updInfo');
    if (!o) { info.textContent = '检查失败'; return; }
    if (!o.ok) { info.textContent = o.message || '检查失败'; return; }
    if (o.has_update) {
      var src = o.source === 'gitee' ? 'Gitee' : (o.source || 'Gitee');
      info.textContent = '发现新版本 ' + o.version + '（' + (o.size ? Math.round(o.size / 1048576 * 10) / 10 + ' MB · ' : '') + src + '）\n'
        + (o.notes || '');
      $('#btnUpdateInstall').disabled = false;
    } else {
      info.textContent = o.message || '已是最新版本';
      $('#btnUpdateInstall').disabled = true;
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
        renderChart();
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
      if (DEMO) { setTimeout(function () { btn.classList.remove('spin'); pull(); }, 500); return; }
      try { N.refresh(); } catch (e) { /* ignore */ }
      setTimeout(function () { btn.classList.remove('spin'); pull(); }, 1200);
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
    $('#btnUpdateInstall').addEventListener('click', function () {
      var o = S.update;
      if (!o || !o.has_update) return;
      if (DEMO) { toast('演示模式不支持安装'); return; }
      progSet('#updProgWrap', 0, o.size > 0 ? '正在连接，共 ' + mb(o.size) + ' MB…' : '正在连接…', 'indet');
      this.disabled = true;
      this.textContent = '下载中…';
      try {
        N.installUpdate(o.apk_url, o.version, o.size || 0);
      } catch (e) {
        onUpdateProgress({ phase: 'error' });
        toast('下载失败：' + e.message);
      }
    });
    // 同步历史数据（最近 180 天）
    $('#btnSyncHistory').addEventListener('click', function () {
      if (DEMO) { toast('演示模式不支持同步'); return; }
      var b = this;
      b.disabled = true;
      b.textContent = '同步中…';
      var info = $('#syncInfo');
      if (info) {
        info.setAttribute('data-busy', '1');
        info.textContent = '正在与官方源做增量同步，进度见上方进度条；中途请勿退出应用。';
      }
      progSet('#syncProgWrap', 0, '正在连接数据源…', 'indet');
      try { N.syncHistory(); } catch (e) {
        b.disabled = false;
        b.textContent = '同步历史数据';
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
      renderQuotes();
      renderChart();
      renderIndicators();
      renderForecast();
    });
  }

  // ---------------- Native 回调 ----------------

  window.FF = {
    onData: function () { pull(); },
    onLlm: function (res) { onLlm(res); },
    onUpdate: function (o) { onUpdate(o); },
    onUpdateProgress: function (o) { onUpdateProgress(o); },
    onSync: function (res) { onSync(res); },
    onSyncStep: function (msg) { onSyncStep(msg); },
    onPredictStep: function (o) { onPredictStep(o); },
    onPredict: function (res) { onPredict(res); },
    onToast: function (msg) { toast(msg); }
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
  pull();
  loadSettings();
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
