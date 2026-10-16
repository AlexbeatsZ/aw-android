// Pure data shaping for the dashboard: no DOM, no bridge. Loaded in the WebView and,
// for tests, in Node (module.exports at the bottom).
(function (root) {
  'use strict';

  var MIN = 60 * 1000;
  var HOUR = 60 * MIN;
  // Sessions of the same app this close together read as one visit on the timeline.
  var MERGE_GAP_MS = 60 * 1000;
  // Visits are matched to the browser session that contains them, with this slack.
  var ATTACH_SLACK_MS = 5 * 1000;
  var SLOTS = 6;

  function hostOf(url) {
    if (!url) return '';
    var rest = String(url).trim();
    var scheme = rest.indexOf('://');
    if (scheme > 0 && scheme <= 16) rest = rest.slice(scheme + 3);
    rest = rest.split(/[\/?#]/)[0];
    rest = rest.slice(rest.lastIndexOf('@') + 1);
    if (rest.charAt(0) !== '[') rest = rest.split(':')[0];
    return rest.toLowerCase().replace(/^www\./, '').replace(/\.+$/, '');
  }

  /** Clip `{t, d}` events (ms start, seconds duration) to [start, end). */
  function clip(events, start, end) {
    var out = [];
    (events || []).forEach(function (e) {
      var s = Math.max(e.t, start);
      var f = Math.min(e.t + Math.max(0, e.d) * 1000, end);
      if (f > s) out.push(Object.assign({}, e, { start: s, end: f }));
    });
    out.sort(function (a, b) { return a.start - b.start; });
    return out;
  }

  function appKey(e) {
    if (e.private) return 'private:' + e.app;
    return e.pkg || e.app || '?';
  }

  function siteKey(v) {
    if (v.private) return 'private:' + v.url;
    return hostOf(v.url) || v.url || '?';
  }

  function siteName(v) {
    if (v.private) return v.url;
    return hostOf(v.url) || v.url || '未知网址';
  }

  /** Add the part of [s, e) that falls in each local hour of the day to `hours`. */
  function spreadHours(hours, dayStart, s, e) {
    while (s < e) {
      var d = new Date(s);
      d.setMinutes(0, 0, 0);
      var next = d.getTime() + HOUR;
      // Index by wall-clock hour, so DST days still map to 0–23.
      var idx = new Date(s).getHours();
      hours[idx] += Math.min(e, next) - s;
      s = Math.min(e, next);
    }
  }

  function aggregate(items, keyOf, nameOf) {
    var map = Object.create(null);
    var list = [];
    items.forEach(function (it) {
      var key = keyOf(it);
      var entry = map[key];
      if (!entry) {
        entry = map[key] = { key: key, name: nameOf(it), private: !!it.private, ms: 0, count: 0 };
        list.push(entry);
      }
      entry.ms += it.end - it.start;
      entry.count += 1;
    });
    list.sort(function (a, b) { return b.ms - a.ms; });
    return list;
  }

  /**
   * Shape one day's raw bridge payload for display.
   * opts.minSeconds hides shorter timeline entries (they still count in totals);
   * opts.hideLaunchers drops the home screen from everything.
   */
  function buildDay(raw, opts) {
    opts = opts || {};
    var start = raw.start;
    var end = raw.end;
    var launchers = {};
    (raw.launchers || []).forEach(function (p) { launchers[p] = true; });

    var apps = clip(raw.apps, start, end).filter(function (e) {
      return !(opts.hideLaunchers && !e.private && launchers[e.pkg]);
    });
    var visits = clip(raw.web, start, end);

    var hours = [];
    for (var h = 0; h < 24; h++) hours.push(0);
    var total = 0;
    apps.forEach(function (e) {
      total += e.end - e.start;
      spreadHours(hours, start, e.start, e.end);
    });

    var appTotals = aggregate(apps, appKey, function (e) { return e.app || e.pkg || '未知应用'; });
    var siteTotals = aggregate(visits, siteKey, siteName);

    // Stable color identity for the day: the top apps own a slot each.
    var slotOf = Object.create(null);
    appTotals.slice(0, SLOTS).forEach(function (a, i) { slotOf[a.key] = i; });

    // Merge consecutive sessions of the same app into timeline entries.
    var sessions = [];
    apps.forEach(function (e) {
      var key = appKey(e);
      var last = sessions[sessions.length - 1];
      if (last && last.key === key && e.start - last.end <= MERGE_GAP_MS) {
        last.end = Math.max(last.end, e.end);
        last.ms += e.end - e.start;
        last.parts += 1;
        return;
      }
      sessions.push({
        key: key, name: e.app || e.pkg || '未知应用', pkg: e.private ? '' : (e.pkg || ''),
        private: !!e.private, start: e.start, end: e.end, ms: e.end - e.start, parts: 1,
        slot: key in slotOf ? slotOf[key] : -1, visits: []
      });
    });

    // Attach web visits to the session they happened in.
    var i = 0;
    visits.forEach(function (v) {
      while (i < sessions.length && sessions[i].end + ATTACH_SLACK_MS < v.start) i++;
      var s = sessions[i];
      if (s && s.start - ATTACH_SLACK_MS <= v.start) {
        s.visits.push({ start: v.start, ms: v.end - v.start, name: siteName(v), title: v.private ? '' : (v.title || ''), private: !!v.private });
      }
    });

    var minMs = Math.max(0, (opts.minSeconds || 0) * 1000);
    var shown = [];
    var hidden = { count: 0, ms: 0 };
    sessions.forEach(function (s) {
      if (s.ms >= minMs || s.visits.length) shown.push(s);
      else { hidden.count++; hidden.ms += s.ms; }
    });

    var unlocks = (raw.unlocks || []).filter(function (t) { return t >= start && t < end; });

    return {
      start: start, end: end, total: total,
      unlocks: unlocks.length,
      firstUse: apps.length ? apps[0].start : null,
      lastUse: apps.length ? apps.reduce(function (m, e) { return Math.max(m, e.end); }, 0) : null,
      apps: appTotals, sites: siteTotals, hours: hours,
      sessions: shown, hidden: hidden, slotOf: slotOf,
      longest: sessions.reduce(function (m, s) { return s.ms > m.ms ? s : m; }, { ms: 0 })
    };
  }

  /** Group timeline entries by the local hour they start in. */
  function groupByHour(sessions) {
    var groups = [];
    sessions.forEach(function (s) {
      var hour = new Date(s.start).getHours();
      var g = groups[groups.length - 1];
      if (!g || g.hour !== hour) {
        g = { hour: hour, items: [], ms: 0 };
        groups.push(g);
      }
      g.items.push(s);
      g.ms += s.ms;
    });
    return groups;
  }

  /** Top `n` entries plus an "其他" remainder, for the donut. */
  function topWithOther(list, n) {
    var top = list.slice(0, n);
    var rest = list.slice(n);
    if (rest.length) {
      top = top.concat([{
        key: '__other', name: '其他 ' + rest.length + ' 项', other: true,
        ms: rest.reduce(function (m, x) { return m + x.ms; }, 0), count: rest.length
      }]);
    }
    return top;
  }

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  function clock(ms) {
    var d = new Date(ms);
    return pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  /** 1小时 05分 / 12分 / 40秒 */
  function duration(ms) {
    var s = Math.round(ms / 1000);
    if (s < 60) return s + '秒';
    var m = Math.round(s / 60);
    if (m < 60) return m + '分';
    var h = Math.floor(m / 60);
    return h + '小时 ' + pad(m % 60) + '分';
  }

  /** Parts for the large figure: [{v: '5', u: '小时'}, {v: '07', u: '分'}] */
  function durationParts(ms) {
    var m = Math.round(ms / MIN);
    var h = Math.floor(m / 60);
    if (h === 0) return [{ v: String(m), u: '分钟' }];
    return [{ v: String(h), u: '小时' }, { v: pad(m % 60), u: '分' }];
  }

  function dayStart(ms) {
    var d = new Date(ms);
    d.setHours(0, 0, 0, 0);
    return d.getTime();
  }

  function addDays(ms, n) {
    var d = new Date(ms);
    d.setDate(d.getDate() + n);
    d.setHours(0, 0, 0, 0);
    return d.getTime();
  }

  var api = {
    hostOf: hostOf, clip: clip, buildDay: buildDay, groupByHour: groupByHour,
    topWithOther: topWithOther, clock: clock, duration: duration, durationParts: durationParts,
    dayStart: dayStart, addDays: addDays, pad: pad, SLOTS: SLOTS
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.AWModel = api;
})(this);
