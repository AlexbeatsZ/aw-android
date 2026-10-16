// Dashboard UI. Data comes from the native bridge (bridge.js), shaping from model.js.
(function () {
  'use strict';
  var M = window.AWModel;
  var B = window.AWBridge;
  var view = document.getElementById('view');
  var WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  var LABEL_PRESETS = ['娱乐', '社交', '学习', '工作', '购物', '资讯', '私人'];
  var KIND_NAMES = { app: '应用', domain: '网站', keyword: '关键词' };

  var state = {
    tab: 'timeline',
    day: M.dayStart(Date.now()),
    statsMode: 'apps',
    focus: null,
    ui: { minSeconds: 30, hideLaunchers: true },
    raw: null,
    rawDay: null,
    loading: false,
    error: null,
    expanded: Object.create(null),
    privacy: null,
    candidates: null,
    status: null,
    form: { match: 'app', pattern: '', label: '娱乐' }
  };

  // ---------- DOM helpers ----------
  function h(tag, attrs) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === null || v === undefined || v === false) return;
        if (k === 'class') el.className = v;
        else if (k === 'text') el.textContent = v;
        else if (k === 'style') el.setAttribute('style', v);
        else if (k.slice(0, 2) === 'on') el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v === true ? '' : v);
      });
    }
    for (var i = 2; i < arguments.length; i++) append(el, arguments[i]);
    return el;
  }
  function append(el, child) {
    if (child === null || child === undefined || child === false) return;
    if (Array.isArray(child)) { child.forEach(function (c) { append(el, c); }); return; }
    el.appendChild(typeof child === 'string' || typeof child === 'number' ? document.createTextNode(String(child)) : child);
  }
  function svg(tag, attrs) {
    var el = document.createElementNS('http://www.w3.org/2000/svg', tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k.slice(0, 2) === 'on') el.addEventListener(k.slice(2), attrs[k]);
      else el.setAttribute(k, attrs[k]);
    });
    return el;
  }
  function slotColor(slot) { return slot >= 0 ? 'var(--s' + slot + ')' : 'var(--other)'; }

  var toastTimer = null;
  function toast(msg) {
    var el = document.getElementById('toast');
    el.textContent = msg;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 2600);
  }

  var tip = document.getElementById('tooltip');
  function showTip(text, x, y) {
    tip.textContent = text;
    tip.hidden = false;
    var w = tip.offsetWidth;
    var left = Math.min(window.innerWidth - w - 8, Math.max(8, x - w / 2));
    tip.style.left = left + 'px';
    tip.style.top = Math.max(8, y - tip.offsetHeight - 12) + 'px';
  }
  function hideTip() { tip.hidden = true; }
  document.addEventListener('scroll', hideTip, { passive: true });

  // ---------- Data ----------
  function isToday() { return state.day === M.dayStart(Date.now()); }

  function loadDay(force) {
    var day = state.day;
    if (!force && state.rawDay === day && state.raw) return Promise.resolve();
    state.loading = true;
    state.error = null;
    render();
    return B.call('day', { start: day, end: M.addDays(day, 1) }).then(function (raw) {
      if (state.day !== day) return;
      state.raw = raw;
      state.rawDay = day;
      state.loading = false;
      render();
    }, function (err) {
      if (state.day !== day) return;
      state.loading = false;
      state.error = err;
      render();
      // The local server can still be starting right after launch.
      if (err.code === 'server_unavailable') setTimeout(function () { if (state.error) loadDay(true); }, 2000);
    });
  }

  function dayModel() {
    if (!state.raw || state.rawDay !== state.day) return null;
    return M.buildDay(state.raw, state.ui);
  }

  function saveUi() {
    B.call('ui.set', state.ui).catch(function () {});
  }

  // ---------- Masthead ----------
  function masthead(label, model) {
    var d = new Date(state.day);
    var today = isToday();
    var dateInput = h('input', {
      type: 'date', 'aria-label': '选择日期',
      value: d.getFullYear() + '-' + M.pad(d.getMonth() + 1) + '-' + M.pad(d.getDate()),
      max: (function () { var n = new Date(); return n.getFullYear() + '-' + M.pad(n.getMonth() + 1) + '-' + M.pad(n.getDate()); })(),
      onchange: function (e) {
        var p = e.target.value.split('-');
        if (p.length !== 3) return;
        var t = new Date(+p[0], +p[1] - 1, +p[2]).getTime();
        if (!isNaN(t)) setDay(Math.min(t, M.dayStart(Date.now())));
      }
    });
    var children = [
      h('div', { class: 'eyebrow' },
        h('span', null, h('b', null, '■ '), label),
        h('span', { class: 'num' }, today ? '今天 · ' + M.clock(Date.now()) : WEEK[d.getDay()])),
      h('div', { class: 'datebar' },
        h('div', { class: 'date' }, (d.getMonth() + 1) + '月' + d.getDate() + '日',
          h('small', null, WEEK[d.getDay()] + ' ▾'), dateInput),
        h('button', { class: 'iconbtn', 'aria-label': '前一天', onclick: function () { setDay(M.addDays(state.day, -1)); } }, '‹'),
        h('button', { class: 'iconbtn', 'aria-label': '后一天', disabled: today, onclick: function () { setDay(M.addDays(state.day, 1)); } }, '›'))
    ];
    if (model) {
      var hero = h('div', { class: 'hero num', 'aria-label': '屏幕时间 ' + M.duration(model.total) });
      M.durationParts(model.total).forEach(function (p) {
        hero.appendChild(h('span', { class: 'v' }, p.v));
        hero.appendChild(h('span', { class: 'u' }, p.u));
      });
      children.push(hero);
      children.push(h('dl', { class: 'facts num' },
        h('div', null, h('dt', null, '解锁'), h('dd', null, model.unlocks + ' 次')),
        h('div', null, h('dt', null, '首次使用'), h('dd', null, model.firstUse ? M.clock(model.firstUse) : '—')),
        h('div', null, h('dt', null, '最长连续'), h('dd', null, model.longest.ms ? M.duration(model.longest.ms) : '—'))));
    }
    return h('header', { class: 'mast' }, children);
  }

  function setDay(day) {
    if (day === state.day) return;
    state.day = day;
    state.focus = null;
    state.expanded = Object.create(null);
    window.scrollTo(0, 0);
    loadDay();
  }

  function loadingOrError() {
    if (state.error) {
      var starting = state.error.code === 'server_unavailable';
      return h('div', { class: 'state' },
        h('b', null, starting ? '本地服务启动中' : '读取失败'),
        starting ? '记录服务正在启动，稍后自动重试。' : String(state.error.message || state.error),
        starting ? null : h('div', { style: 'margin-top:16px' }, h('button', { class: 'btn', onclick: function () { loadDay(true); } }, '重试')));
    }
    return h('div', { class: 'state' }, '读取中…');
  }

  // ---------- Timeline ----------
  function renderTimeline() {
    var model = dayModel();
    var out = [masthead('Timeline / 时间线', model)];
    if (!model) { out.push(loadingOrError()); return out; }
    if (!model.sessions.length) {
      out.push(h('div', { class: 'empty' }, '这一天没有记录。'));
      return out;
    }

    out.push(h('div', { class: 'sect' },
      h('h2', null, '使用记录'),
      h('span', { class: 'aside num' }, model.sessions.length + ' 段 · 隐藏短于 ' + state.ui.minSeconds + ' 秒')));

    var tl = h('div', { class: 'tl' });
    M.groupByHour(model.sessions).forEach(function (g) {
      tl.appendChild(h('div', { class: 'hour num' },
        h('div', { class: 'h' }, M.pad(g.hour)),
        h('div', { class: 'meta' },
          h('div', { class: 'bar' }, h('i', { style: 'width:' + Math.min(100, model.hours[g.hour] / 36000) + '%' })),
          h('span', null, M.duration(model.hours[g.hour])))));
      g.items.forEach(function (s) { tl.appendChild(entry(s)); });
    });
    out.push(tl);
    if (model.hidden.count) {
      out.push(h('div', { class: 'hidden-note num' },
        '另有 ' + model.hidden.count + ' 段短暂使用，共 ' + M.duration(model.hidden.ms) + '，已计入总时长。'));
    }
    return out;
  }

  function entry(s) {
    var key = s.key + '@' + s.start;
    var major = s.ms >= 15 * 60 * 1000;
    var sub = M.clock(s.start) + '–' + M.clock(s.end);
    if (s.parts > 1) sub += ' · ' + s.parts + ' 次切换';
    var body = h('div', { class: 'body' },
      h('div', { class: 'row' },
        h('div', { class: 'name' }, s.name, s.private ? h('span', { class: 'tag' }, '已隐藏') : null),
        h('div', { class: 'dur num' }, M.duration(s.ms))),
      h('div', { class: 'sub num' }, sub),
      h('div', { class: 'len', 'aria-hidden': 'true' }, h('i', { style: 'width:' + Math.max(1, Math.min(100, s.ms / 36000)) + '%' })));

    if (s.visits.length) {
      var open = state.expanded[key];
      var list = open ? s.visits : s.visits.slice(0, 3);
      var ul = h('ul', { class: 'visits' });
      list.forEach(function (v) {
        ul.appendChild(h('li', null,
          h('span', { class: 'host' }, v.name, v.private ? h('span', { class: 'tag' }, '已隐藏') : null),
          h('span', { class: 'vt num' }, M.clock(v.start) + ' · ' + M.duration(v.ms)),
          v.title ? h('span', { class: 'title' }, v.title) : null));
      });
      body.appendChild(ul);
      if (s.visits.length > 3) {
        body.appendChild(h('button', {
          class: 'more', onclick: function () { state.expanded[key] = !open; render(); }
        }, open ? '收起' : '展开全部 ' + s.visits.length + ' 个网页'));
      }
    }

    return h('div', { class: 'entry' + (major ? ' major' : '') },
      h('div', { class: 't num' }, M.clock(s.start)),
      h('div', { class: 'rail' }, h('i', { style: 'background:' + slotColor(s.slot) })),
      body);
  }

  // ---------- Stats ----------
  function renderStats() {
    var model = dayModel();
    var out = [masthead('Overview / 统计', model)];
    if (!model) { out.push(loadingOrError()); return out; }
    if (!model.total && !model.sites.length) {
      out.push(h('div', { class: 'empty' }, '这一天没有记录。'));
      return out;
    }

    var mode = state.statsMode;
    var list = mode === 'apps' ? model.apps : model.sites;
    var total = list.reduce(function (m, x) { return m + x.ms; }, 0);

    out.push(h('div', { class: 'sect' },
      h('h2', null, mode === 'apps' ? '应用占比' : '网站占比'),
      h('div', { class: 'seg', role: 'group', 'aria-label': '统计对象' },
        h('button', { 'aria-pressed': String(mode === 'apps'), onclick: function () { state.statsMode = 'apps'; state.focus = null; render(); } }, '应用'),
        h('button', { 'aria-pressed': String(mode === 'sites'), onclick: function () { state.statsMode = 'sites'; state.focus = null; render(); } }, '网站'))));

    if (!total) {
      out.push(h('div', { class: 'empty' }, mode === 'sites' ? '这一天没有网页记录。需要在设置中开启无障碍网页采集。' : '没有应用记录。'));
    } else {
      var slices = M.topWithOther(list, M.SLOTS);
      out.push(donut(slices, total));
      out.push(legend(slices, total));
    }

    out.push(h('div', { class: 'sect' }, h('h2', null, '时段分布'), h('span', { class: 'aside' }, '每小时屏幕时间')));
    out.push(hourChart(model.hours));
    return out;
  }

  function sliceColor(i, s) { return s.other ? 'var(--other)' : 'var(--s' + i + ')'; }

  function arcPath(a0, a1, R, r) {
    // Angles in radians from 12 o'clock, clockwise.
    function pt(a, rad) { return [Math.sin(a) * rad, -Math.cos(a) * rad]; }
    var large = a1 - a0 > Math.PI ? 1 : 0;
    var p0 = pt(a0, R), p1 = pt(a1, R), p2 = pt(a1, r), p3 = pt(a0, r);
    return 'M' + p0[0].toFixed(3) + ' ' + p0[1].toFixed(3) +
      'A' + R + ' ' + R + ' 0 ' + large + ' 1 ' + p1[0].toFixed(3) + ' ' + p1[1].toFixed(3) +
      'L' + p2[0].toFixed(3) + ' ' + p2[1].toFixed(3) +
      'A' + r + ' ' + r + ' 0 ' + large + ' 0 ' + p3[0].toFixed(3) + ' ' + p3[1].toFixed(3) + 'Z';
  }

  function donut(slices, total) {
    var R = 100, r = 64;
    var root = svg('svg', { viewBox: '-104 -104 208 208', class: 'donut' + (state.focus ? ' focus' : ''), role: 'img', 'aria-label': '占比环形图' });
    var a = 0;
    slices.forEach(function (s, i) {
      var frac = s.ms / total;
      var a1 = a + frac * Math.PI * 2;
      // A single full slice cannot be drawn as one arc; split it in two.
      var segs = frac >= 0.9999 ? [[0, Math.PI], [Math.PI, Math.PI * 2]] : [[a, a1]];
      segs.forEach(function (seg) {
        var p = svg('path', {
          d: arcPath(seg[0], seg[1], R, r),
          fill: sliceColor(i, s),
          stroke: 'var(--bg)', 'stroke-width': '2', 'stroke-linejoin': 'round',
          class: state.focus === s.key ? 'on' : '',
          onclick: function () { toggleFocus(s.key); },
          onmousemove: function (e) { showTip(s.name + ' · ' + M.duration(s.ms) + ' · ' + pct(s.ms, total), e.clientX, e.clientY); },
          onmouseleave: hideTip
        });
        root.appendChild(p);
      });
      a = a1;
    });
    var f = slices.filter(function (s) { return s.key === state.focus; })[0];
    var center = h('div', { class: 'donut-center num' },
      h('div', { class: 'c-name' }, f ? f.name : (state.statsMode === 'apps' ? '屏幕时间' : '网页时间')),
      h('div', { class: 'c-val' }, M.duration(f ? f.ms : total)),
      h('div', { class: 'c-pct' }, f ? pct(f.ms, total) : (state.statsMode === 'apps' ? slices.length + ' 组' : '')));
    return h('div', { class: 'donut-wrap' }, root, center);
  }

  function pct(v, total) {
    var p = v / total * 100;
    return (p < 1 ? '<1' : Math.round(p)) + '%';
  }

  function toggleFocus(key) {
    state.focus = state.focus === key ? null : key;
    render();
  }

  function legend(slices, total) {
    var ul = h('ul', { class: 'legend num' });
    slices.forEach(function (s, i) {
      ul.appendChild(h('li', { class: state.focus === s.key ? 'on' : '', onclick: function () { toggleFocus(s.key); } },
        h('span', { class: 'rank' }, s.other ? '··' : M.pad(i + 1)),
        h('span', { class: 'sw', style: 'background:' + sliceColor(i, s) }),
        h('span', { class: 'lname' }, s.name, s.private ? h('span', { class: 'tag' }, '已隐藏') : null),
        h('span', { class: 'lval' }, M.duration(s.ms)),
        h('span', { class: 'lpct' }, pct(s.ms, total))));
    });
    return ul;
  }

  function hourChart(hours) {
    var max = Math.max.apply(null, hours.concat([1]));
    var peak = hours.indexOf(max);
    var grid = h('div', { class: 'hours', role: 'img', 'aria-label': '每小时屏幕时间' });
    hours.forEach(function (ms, hr) {
      var label = M.pad(hr) + ':00–' + M.pad((hr + 1) % 24) + ':00 · ' + M.duration(ms);
      var col = h('div', {
        class: 'col', title: label,
        onclick: function (e) { showTip(label, e.clientX, e.clientY); setTimeout(hideTip, 1800); },
        onmousemove: function (e) { showTip(label, e.clientX, e.clientY); },
        onmouseleave: hideTip
      }, h('div', { class: 'b' + (hr === peak && ms > 0 ? ' peak' : ''), style: 'height:' + (ms / max * 100) + '%' }));
      grid.appendChild(col);
    });
    return h('div', null, grid,
      h('div', { class: 'hours-axis' }, h('span', null, '00'), h('span', null, '06'), h('span', null, '12'), h('span', null, '18')));
  }

  // ---------- Privacy ----------
  function loadPrivacy() {
    return Promise.all([
      B.call('privacy.get'),
      B.call('candidates', { days: 7 }).catch(function () { return { apps: [], sites: [] }; })
    ]).then(function (res) {
      state.privacy = res[0];
      state.candidates = res[1];
      render();
    }, function (err) { toast('读取隐私设置失败：' + err.message); });
  }

  function savePrivacy(next, msg) {
    return B.call('privacy.set', next).then(function (saved) {
      state.privacy = saved;
      state.raw = null; // Reload so the timeline shows the new labels.
      render();
      if (msg) toast(msg);
    }, function (err) { toast('保存失败：' + err.message); });
  }

  function ruleCovers(rule, item) {
    var p = rule.pattern.toLowerCase();
    if (item.kind === 'app') {
      if (rule.match === 'app') return p === (item.pkg || '').toLowerCase() || p === (item.name || '').toLowerCase();
      if (rule.match === 'keyword') return (item.pkg || '').toLowerCase().indexOf(p) >= 0 || (item.name || '').toLowerCase().indexOf(p) >= 0;
      return false;
    }
    if (rule.match === 'domain') return item.host === p || item.host.slice(-(p.length + 1)) === '.' + p;
    if (rule.match === 'keyword') return item.host.indexOf(p) >= 0;
    return false;
  }

  function coveredBy(item) {
    var rules = (state.privacy && state.privacy.rules) || [];
    for (var i = 0; i < rules.length; i++) if (ruleCovers(rules[i], item)) return rules[i];
    return null;
  }

  function renderPrivacy() {
    var out = [h('header', { class: 'mast' },
      h('div', { class: 'eyebrow' }, h('span', null, h('b', null, '■ '), 'Privacy / 隐私')),
      h('div', { class: 'datebar' }, h('div', { class: 'date' }, '隐私规则')),
      h('p', { class: 'note' }, '命中规则的应用或网站会被替换成你设定的名称（例如“娱乐”），只保留时间和时长。规则同时作用于本页面和 Webhook 导出，按顺序匹配，先命中者生效。'))];
    var p = state.privacy;
    if (!p) { out.push(h('div', { class: 'state' }, '读取中…')); return out; }

    out.push(h('div', { class: 'sect' }, h('h2', null, '全局')));
    out.push(h('div', { class: 'row-line' },
      h('div', { class: 'grow' },
        h('div', { class: 'label' }, '网址只保留域名'),
        h('div', { class: 'hint' }, '去掉所有网页的路径、参数和标题，例如 zhihu.com/question/… → zhihu.com')),
      h('label', { class: 'switch' },
        h('input', {
          type: 'checkbox', checked: p.domainOnly, 'aria-label': '网址只保留域名',
          onchange: function (e) { savePrivacy({ domainOnly: e.target.checked, rules: p.rules }, e.target.checked ? '已开启：只保留域名' : '已关闭：保留完整网址'); }
        }),
        h('span'))));

    out.push(h('div', { class: 'sect' }, h('h2', null, '规则'), h('span', { class: 'aside num' }, p.rules.length + ' 条')));
    if (!p.rules.length) out.push(h('p', { class: 'note' }, '还没有规则。在下方添加，或从“最近 7 天”里直接选择。'));
    var ul = h('ul', { class: 'rules' });
    p.rules.forEach(function (rule, i) {
      ul.appendChild(h('li', null,
        h('span', { class: 'kind' }, KIND_NAMES[rule.match] || rule.match),
        h('span', { class: 'acts' },
          h('button', { class: 'iconbtn', 'aria-label': '上移', disabled: i === 0, onclick: function () { moveRule(i, -1); } }, '↑'),
          h('button', { class: 'iconbtn', 'aria-label': '删除规则', onclick: function () { removeRule(i); } }, '×')),
        h('span', { class: 'map' }, rule.pattern + '  →  ', h('b', null, rule.label))));
    });
    out.push(ul);

    out.push(ruleForm());
    out.push(candidates());
    out.push(h('p', { class: 'note' },
      '修改规则后，最近 7 天的数据会在下一次 Webhook 同步时以新名称重新发送，接收端按事件 ID 保留最新版本。更早已经发送到电脑上的原始记录不会被改写，需要的话请在接收端清理。'));
    return out;
  }

  function ruleForm() {
    var f = state.form;
    var placeholder = { app: '包名或应用名，例如 com.tencent.mm', domain: '域名，例如 bilibili.com', keyword: '出现在名称、网址或标题中的文字' }[f.match];
    var patternInput = h('input', {
      class: 'input', id: 'rule-pattern', value: f.pattern, placeholder: placeholder, autocomplete: 'off', autocapitalize: 'off',
      oninput: function (e) { f.pattern = e.target.value; }
    });
    var labelInput = h('input', {
      class: 'input', id: 'rule-label', value: f.label, placeholder: '显示为…',
      oninput: function (e) { f.label = e.target.value; syncChips(); }
    });
    var chips = h('div', { class: 'chips' });
    function syncChips() {
      Array.prototype.forEach.call(chips.children, function (c) { c.setAttribute('aria-pressed', String(c.textContent === f.label.trim())); });
    }
    LABEL_PRESETS.forEach(function (name) {
      chips.appendChild(h('button', {
        class: 'chip', type: 'button', 'aria-pressed': String(name === f.label),
        onclick: function () { f.label = name; labelInput.value = name; syncChips(); }
      }, name));
    });
    var seg = h('div', { class: 'seg', role: 'group', 'aria-label': '匹配方式' });
    ['app', 'domain', 'keyword'].forEach(function (k) {
      seg.appendChild(h('button', {
        type: 'button', 'aria-pressed': String(f.match === k),
        onclick: function () { f.match = k; render(); }
      }, KIND_NAMES[k]));
    });
    return h('form', {
      id: 'rule-form',
      onsubmit: function (e) {
        e.preventDefault();
        var pattern = f.pattern.trim();
        var label = f.label.trim();
        if (!pattern || !label) { toast('请填写匹配内容和显示名称'); return; }
        var rules = state.privacy.rules.concat([{ match: f.match, pattern: pattern, label: label }]);
        savePrivacy({ domainOnly: state.privacy.domainOnly, rules: rules }, '已添加：' + pattern + ' → ' + label).then(function () {
          f.pattern = '';
          render();
        });
      }
    },
      h('div', { class: 'sect' }, h('h2', null, '添加规则'), seg),
      h('label', { class: 'field' }, h('span', null, '匹配'), patternInput),
      h('label', { class: 'field' }, h('span', null, '显示为'), labelInput),
      chips,
      h('div', { style: 'margin-top:16px' }, h('button', { class: 'btn primary', type: 'submit' }, '添加规则')));
  }

  function candidates() {
    var c = state.candidates;
    var wrap = h('div', null, h('div', { class: 'sect' }, h('h2', null, '最近 7 天'), h('span', { class: 'aside' }, '点“设置”快速填入')));
    if (!c) { wrap.appendChild(h('p', { class: 'note' }, '读取中…')); return wrap; }
    var items = c.apps.slice(0, 30).map(function (a) { return { kind: 'app', name: a.name, pkg: a.pkg, ms: a.ms }; })
      .concat(c.sites.slice(0, 30).map(function (s) { return { kind: 'site', name: s.host, host: s.host, ms: s.ms }; }));
    items.sort(function (a, b) { return b.ms - a.ms; });
    if (!items.length) { wrap.appendChild(h('p', { class: 'note' }, '暂无记录。')); return wrap; }
    var ul = h('ul', { class: 'cands' });
    items.forEach(function (it) {
      var rule = coveredBy(it);
      ul.appendChild(h('li', null,
        h('span', { class: 'cn' }, it.name, h('small', null, it.kind === 'app' ? (it.pkg || '应用') : '网站')),
        rule ? h('span', { class: 'covered' }, '→ ' + rule.label) : h('span', { class: 'cv num' }, M.duration(it.ms)),
        h('button', {
          class: 'btn', disabled: !!rule,
          onclick: function () {
            state.form.match = it.kind === 'app' ? 'app' : 'domain';
            state.form.pattern = it.kind === 'app' ? (it.pkg || it.name) : it.host;
            render();
            var form = document.getElementById('rule-form');
            if (form) form.scrollIntoView({ behavior: 'smooth', block: 'start' });
            var label = document.getElementById('rule-label');
            if (label) setTimeout(function () { label.focus(); label.select(); }, 300);
          }
        }, '设置')));
    });
    wrap.appendChild(ul);
    return wrap;
  }

  function moveRule(i, delta) {
    var rules = state.privacy.rules.slice();
    var j = i + delta;
    if (j < 0 || j >= rules.length) return;
    var t = rules[i]; rules[i] = rules[j]; rules[j] = t;
    savePrivacy({ domainOnly: state.privacy.domainOnly, rules: rules });
  }

  function removeRule(i) {
    var rules = state.privacy.rules.slice();
    var removed = rules.splice(i, 1)[0];
    savePrivacy({ domainOnly: state.privacy.domainOnly, rules: rules }, '已删除：' + removed.pattern);
  }

  // ---------- Settings ----------
  function loadStatus() {
    return B.call('status').then(function (s) { state.status = s; render(); }, function (err) { toast('读取状态失败：' + err.message); });
  }

  function renderSettings() {
    var s = state.status;
    var out = [h('header', { class: 'mast' },
      h('div', { class: 'eyebrow' }, h('span', null, h('b', null, '■ '), 'Settings / 设置')),
      h('div', { class: 'datebar' }, h('div', { class: 'date' }, '设置')))];

    out.push(h('div', { class: 'sect' }, h('h2', null, '显示')));
    var minSel = h('select', {
      class: 'input', style: 'width:auto', 'aria-label': '隐藏短会话',
      onchange: function (e) { state.ui.minSeconds = +e.target.value; saveUi(); render(); }
    });
    [0, 10, 30, 60, 120, 300].forEach(function (v) {
      var o = h('option', { value: v }, v === 0 ? '全部显示' : v < 60 ? v + ' 秒' : (v / 60) + ' 分钟');
      if (v === state.ui.minSeconds) o.selected = true;
      minSel.appendChild(o);
    });
    out.push(h('div', { class: 'row-line' },
      h('div', { class: 'grow' }, h('div', { class: 'label' }, '时间线隐藏短于'), h('div', { class: 'hint' }, '只影响时间线显示，总时长和统计照常计算')),
      minSel));
    out.push(h('div', { class: 'row-line' },
      h('div', { class: 'grow' }, h('div', { class: 'label' }, '不计入桌面'), h('div', { class: 'hint' }, '停留在桌面启动器的时间不算屏幕时间')),
      h('label', { class: 'switch' },
        h('input', { type: 'checkbox', checked: state.ui.hideLaunchers, 'aria-label': '不计入桌面', onchange: function (e) { state.ui.hideLaunchers = e.target.checked; saveUi(); } }),
        h('span'))));

    out.push(h('div', { class: 'sect' }, h('h2', null, 'Webhook 导出')));
    if (!s) { out.push(h('div', { class: 'state' }, '读取中…')); return out; }
    var wh = s.webhook || {};
    out.push(h('div', { class: 'row-line' },
      h('div', { class: 'grow' },
        h('div', { class: 'label' }, h('span', { class: 'status-dot ' + (wh.enabled ? 'ok' : 'bad') }), wh.enabled ? '自动同步已开启' : '自动同步未开启'),
        h('div', { class: 'hint' }, wh.status || '—')),
      h('button', { class: 'btn', onclick: function () { B.call('open', { screen: 'webhook' }); } }, '配置')));
    out.push(h('div', { class: 'row-line' },
      h('div', { class: 'grow' }, h('div', { class: 'label' }, '立即同步'), h('div', { class: 'hint' }, '约每 15 分钟自动同步一次；系统省电策略可能推迟')),
      h('button', {
        class: 'btn', disabled: !wh.enabled,
        onclick: function () { B.call('webhook.syncNow').then(function () { toast('已开始同步'); setTimeout(loadStatus, 4000); }, function (e) { toast(e.message); }); }
      }, '同步')));

    out.push(h('div', { class: 'sect' }, h('h2', null, '采集权限')));
    var perms = s.permissions || {};
    [
      ['usage', '使用情况访问', '记录应用使用和解锁，必需'],
      ['accessibility', '无障碍（网页采集）', 'Via 需在“工具栏设置 → 地址栏内容”中选择“网址”']
    ].forEach(function (p) {
      var ok = !!perms[p[0]];
      out.push(h('div', { class: 'row-line' },
        h('div', { class: 'grow' },
          h('div', { class: 'label' }, h('span', { class: 'status-dot ' + (ok ? 'ok' : 'bad') }), p[1]),
          h('div', { class: 'hint' }, ok ? '已授权' : p[2])),
        h('button', { class: 'btn', onclick: function () { B.call('open', { screen: p[0] }); } }, ok ? '查看' : '去授权')));
    });

    out.push(h('div', { class: 'sect' }, h('h2', null, '关于')));
    out.push(h('div', { class: 'row-line' },
      h('div', { class: 'grow' },
        h('div', { class: 'label' }, '手机使用记录'),
        h('div', { class: 'hint num' }, (s.version || '') + (s.device ? ' · ' + s.device : '') + ' · 数据只保存在本机，并通过 Webhook 发送到你的接收端')),
      null));
    return out;
  }

  // ---------- Shell ----------
  function render() {
    hideTip();
    var nodes;
    if (state.tab === 'timeline') nodes = renderTimeline();
    else if (state.tab === 'stats') nodes = renderStats();
    else if (state.tab === 'privacy') nodes = renderPrivacy();
    else nodes = renderSettings();
    view.replaceChildren.apply(view, [].concat(nodes).filter(Boolean));
    document.querySelectorAll('.tabs button').forEach(function (b) {
      b.setAttribute('aria-selected', String(b.getAttribute('data-tab') === state.tab));
    });
  }

  function selectTab(tab) {
    if (state.tab === tab) { window.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    state.tab = tab;
    state.focus = null;
    window.scrollTo(0, 0);
    render();
    if (tab === 'timeline' || tab === 'stats') loadDay();
    if (tab === 'privacy') loadPrivacy();
    if (tab === 'settings') loadStatus();
  }

  document.querySelectorAll('.tabs button').forEach(function (b) {
    b.addEventListener('click', function () { selectTab(b.getAttribute('data-tab')); });
  });

  // Called by the native host.
  window.AWApp = {
    /** Fresh data after the app returns to the foreground or finishes importing. */
    refresh: function () {
      if (state.tab === 'timeline' || state.tab === 'stats') {
        if (isToday() || state.rawDay !== state.day) loadDay(true);
      } else if (state.tab === 'settings') loadStatus();
      else if (state.tab === 'privacy') loadPrivacy();
    },
    /** Returns true when the page handled the back gesture. */
    back: function () {
      if (state.focus) { state.focus = null; render(); return true; }
      if (state.tab !== 'timeline') { selectTab('timeline'); return true; }
      if (!isToday()) { setDay(M.dayStart(Date.now())); return true; }
      return false;
    }
  };

  // Keep "today" current while the page is visible.
  setInterval(function () {
    if (document.hidden || !isToday()) return;
    if (state.tab === 'timeline' || state.tab === 'stats') loadDay(true);
  }, 60 * 1000);

  B.call('ui.get').then(function (ui) {
    if (ui && typeof ui === 'object') {
      if (typeof ui.minSeconds === 'number') state.ui.minSeconds = ui.minSeconds;
      if (typeof ui.hideLaunchers === 'boolean') state.ui.hideLaunchers = ui.hideLaunchers;
    }
  }).catch(function () {}).then(function () {
    render();
    loadDay();
  });
})();
