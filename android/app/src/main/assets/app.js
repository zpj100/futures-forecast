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
    llmBusy: false
  };

  var PERIODS = [
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
    renderChart();
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

  function renderChart() {
    var canvas = $('#chart');
    var empty = $('#chartEmpty');
    var period = PERIODS.filter(function (p) { return p.k === S.period; })[0] || PERIODS[0];
    var raw = null;
    var bars = [];
    try {
      raw = JSON.parse(DEMO
        ? JSON.stringify(demoBars(S.code, period.step))
        : N.kline(S.code, period.k, period.k === '1d' ? 4000 : 1400));
      bars = (raw && raw.bars) || [];
    } catch (e) { bars = []; }

    // 该周期没有独立数据时，用 1 分钟线现场聚合兜底
    if (bars.length < 2 && !DEMO && period.step > 1) {
      try {
        var one = JSON.parse(N.kline(S.code, '1m', 1400));
        bars = aggregate((one && one.bars) || [], period.step);
      } catch (e) { bars = []; }
    }

    var limit = (period.k === '60m' || period.k === '1d') ? 1100 : 260;
    bars = bars.slice(-limit);

    var meta = $('#chartMeta');
    if (meta) {
      if (!bars.length) meta.textContent = '—';
      else {
        var span = Math.round((bars[bars.length - 1][0] - bars[0][0]) / 86400000);
        meta.textContent = bars.length + ' 根 · ' + period.label
          + ' · ' + dateStr(bars[0][0]) + (span > 0 ? '（' + span + ' 天）' : '');
      }
    }
    if (bars.length < 2) {
      empty.style.display = 'grid';
      var ctx0 = canvas.getContext('2d');
      ctx0.clearRect(0, 0, canvas.width, canvas.height);
      return;
    }
    empty.style.display = 'none';
    drawLine(canvas, bars);
  }

  function aggregate(bars, step) {
    if (!step || step <= 1) return bars;
    var out = [], cur = null;
    for (var i = 0; i < bars.length; i++) {
      var b = bars[i];
      if (!cur) cur = { ts: b[0], o: b[1], h: b[2], l: b[3], c: b[4], v: b[5], n: 1 };
      else {
        cur.h = Math.max(cur.h, b[2]);
        cur.l = Math.min(cur.l, b[3]);
        cur.c = b[4];
        cur.v += b[5];
        cur.ts = b[0];
        cur.n++;
      }
      if (cur.n >= step) { out.push([cur.ts, cur.o, cur.h, cur.l, cur.c, cur.v]); cur = null; }
    }
    if (cur) out.push([cur.ts, cur.o, cur.h, cur.l, cur.c, cur.v]);
    return out;
  }

  function drawLine(canvas, bars) {
    var w = canvas.clientWidth || 320;
    var h = canvas.clientHeight || 178;
    var light = isLight();
    var dpr = window.devicePixelRatio || 1;
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    var ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    var padL = 6, padR = 52, padT = 12, padB = 16;
    var closes = bars.map(function (b) { return b[4]; });
    var min = Math.min.apply(null, closes), max = Math.max.apply(null, closes);
    var span = max - min || Math.max(max * 0.002, 0.01);
    min -= span * 0.12; max += span * 0.12;
    span = max - min;

    var X = function (i) { return padL + (i / (bars.length - 1)) * (w - padL - padR); };
    var Y = function (v) { return padT + (1 - (v - min) / span) * (h - padT - padB); };

    // 网格
    ctx.strokeStyle = light ? 'rgba(15,60,110,.10)' : 'rgba(120,200,255,.07)';
    ctx.lineWidth = 1;
    for (var g = 0; g <= 3; g++) {
      var gy = padT + (g / 3) * (h - padT - padB);
      ctx.beginPath();
      ctx.moveTo(padL, gy);
      ctx.lineTo(w - padR, gy);
      ctx.stroke();
      var gv = max - (g / 3) * span;
      ctx.fillStyle = light ? 'rgba(70,90,115,.88)' : 'rgba(124,135,152,.75)';
      ctx.font = '10px ui-monospace, monospace';
      ctx.textAlign = 'left';
      ctx.fillText(gv.toFixed(max > 1000 ? 0 : 2), w - padR + 6, gy + 3);
    }

    var up = closes[closes.length - 1] >= closes[0];
    var lineColor = up ? upColor() : downColor();
    var rgb = up ? upRGB() : downRGB();

    // 填充
    var grad = ctx.createLinearGradient(0, padT, 0, h - padB);
    grad.addColorStop(0, 'rgba(' + rgb + ',.30)');
    grad.addColorStop(1, 'rgba(' + rgb + ',0)');
    ctx.beginPath();
    ctx.moveTo(X(0), Y(closes[0]));
    for (var i = 1; i < closes.length; i++) ctx.lineTo(X(i), Y(closes[i]));
    ctx.lineTo(X(closes.length - 1), h - padB);
    ctx.lineTo(X(0), h - padB);
    ctx.closePath();
    ctx.fillStyle = grad;
    ctx.fill();

    // 折线
    ctx.beginPath();
    ctx.moveTo(X(0), Y(closes[0]));
    for (var j = 1; j < closes.length; j++) ctx.lineTo(X(j), Y(closes[j]));
    ctx.strokeStyle = lineColor;
    ctx.lineWidth = 1.8;
    ctx.lineJoin = 'round';
    ctx.shadowColor = 'rgba(' + rgb + ',.55)';
    ctx.shadowBlur = 8;
    ctx.stroke();
    ctx.shadowBlur = 0;

    // 最后一个点
    var lx = X(closes.length - 1), ly = Y(closes[closes.length - 1]);
    ctx.beginPath();
    ctx.arc(lx, ly, 3, 0, Math.PI * 2);
    ctx.fillStyle = lineColor;
    ctx.fill();
    ctx.beginPath();
    ctx.arc(lx, ly, 6.5, 0, Math.PI * 2);
    ctx.strokeStyle = 'rgba(' + rgb + ',.4)';
    ctx.stroke();

    // 现价标签
    ctx.fillStyle = lineColor;
    ctx.fillRect(w - padR + 2, ly - 8, padR - 4, 16);
    ctx.fillStyle = isLight() ? '#ffffff' : '#05070c';
    ctx.font = 'bold 10px ui-monospace, monospace';
    ctx.textAlign = 'center';
    ctx.fillText(closes[closes.length - 1].toFixed(max > 1000 ? 0 : 2), w - padR / 2, ly + 3.5);
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
          + '<span>' + esc(it.horizon) + '</span></div>'
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

  function shortName(code) {
    var p = findProduct(code);
    return p ? p.short : code;
  }

  // ---------------- 设置 ----------------

  function loadSettings() {
    var s = {};
    try {
      s = JSON.parse(DEMO
        ? '{"refresh_sec":30,"llm_base":"https://api.deepseek.com/v1","llm_model":"deepseek-chat","has_llm_key":false,"theme":"dark","version":"2.1.0"}'
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
    var html = '<div class="llm-head">'
      + '<span class="llm-dir ' + dirClass(d.direction) + '">' + esc(d.direction || '—') + '</span>'
      + '<span class="muted">置信度 ' + (d.confidence ? (d.confidence * 100).toFixed(0) + '%' : '—') + '</span>'
      + '</div>';
    if (d.target_low || d.target_high) {
      html += '<div class="muted">目标区间 ' + num(d.target_low, 1) + ' ~ ' + num(d.target_high, 1) + '</div>';
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

  function onSyncStep(msg) {
    var info = $('#syncInfo');
    if (info) info.textContent = msg;
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
      toast(m);
      return;
    }
    if (info) {
      info.textContent = '同步完成：' + res.message
        + (res.warn ? '\n' + res.warn : '');
    }
    toast('同步完成：' + res.message);
    pull();
  }

  function checkUpdate() {
    var info = $('#updInfo');
    info.textContent = '正在检查…';
    $('#btnUpdateInstall').disabled = true;
    if (DEMO) {
      onUpdate({ ok: true, has_update: false, message: '已是最新版本 v2.0.0（演示）' });
      return;
    }
    try { N.checkUpdate(); } catch (e) { info.textContent = '检查失败：' + e.message; }
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
      try { N.installUpdate(o.apk_url, o.version); } catch (e) { toast('下载失败'); }
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
        info.textContent = '正在同步最近 180 天数据…';
      }
      try { N.syncHistory(); } catch (e) {
        b.disabled = false;
        b.textContent = '同步历史数据';
        toast('同步失败：' + e.message);
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
    onSync: function (res) { onSync(res); },
    onSyncStep: function (msg) { onSyncStep(msg); },
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
    var hs = [['1m', '1分钟', 0.53], ['5m', '5分钟', 0.57], ['10m', '10分钟', 0.61],
              ['1h', '1小时', 0.64], ['8h', '8小时', 0.48], ['1d', '1天', 0.44]];
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
          key: h[0], label: h[1], minutes: 1, direction: d, p_up: h[2],
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

  function demoBars(code, step) {
    var base = code === 'AL' ? 20380 : code === 'AO' ? 3126 : 2612;
    var n = 180, out = [], v = base;
    for (var i = 0; i < n; i++) {
      var drift = Math.sin(i / 14) * base * 0.0016 + (Math.random() - 0.5) * base * 0.0009;
      v += drift;
      out.push([Date.now() - (n - i) * 60000, v, v * 1.0008, v * 0.9992, v, 1000 + Math.random() * 500]);
    }
    return { code: code, period: '1m', bars: out };
  }

  function demoLlm() {
    return {
      ok: true,
      code: S.code,
      data: {
        direction: '看涨', confidence: 0.62,
        target_low: 20420, target_high: 20560,
        reasons: ['短周期均线呈多头排列，EMA5 上穿 EMA20', 'RSI 58 处于强势区但未超买', '成交量温和放大，价格站上布林中轨'],
        risks: ['8 小时与日线周期方向不一致，存在回调压力', '临近交割月波动可能放大'],
        action: '回踩 20300 附近轻仓试多，跌破 20180 止损'
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
