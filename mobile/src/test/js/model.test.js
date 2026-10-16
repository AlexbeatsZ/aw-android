// Run with: node --test mobile/src/test/js/model.test.js
'use strict';
process.env.TZ = 'Asia/Shanghai';
const test = require('node:test');
const assert = require('node:assert');
const M = require('../../main/assets/dashboard/model.js');

const day = new Date(2026, 9, 15).getTime();
const at = (h, m = 0, s = 0) => day + ((h * 60 + m) * 60 + s) * 1000;
const app = (t, d, name, pkg, extra) => Object.assign({ t, d, app: name, pkg }, extra);

test('clips events to the day and drops ones outside it', () => {
  const out = M.clip([{ t: day - 60000, d: 120 }, { t: day - 120000, d: 30 }], day, day + 86400000);
  assert.strictEqual(out.length, 1);
  assert.strictEqual(out[0].start, day);
  assert.strictEqual(out[0].end, day + 60000);
});

test('merges nearby sessions of one app and attaches visits', () => {
  const raw = {
    start: day, end: day + 86400000, launchers: ['com.miui.home'],
    apps: [
      app(at(9), 300, 'Via', 'mark.via.gp'),
      app(at(9, 5, 30), 120, 'Via', 'mark.via.gp'),
      app(at(9, 8), 20, '系统桌面', 'com.miui.home'),
      app(at(9, 9), 5, '设置', 'com.android.settings'),
      app(at(9, 10), 600, '娱乐', '', { private: true }),
    ],
    web: [{ t: at(9, 1), d: 60, url: 'zhihu.com/question/1', title: 'Q' }, { t: at(9, 6), d: 30, url: '娱乐', title: '娱乐', private: true }],
    unlocks: [at(8, 59), day - 1000],
  };
  const m = M.buildDay(raw, { minSeconds: 10, hideLaunchers: true });
  assert.strictEqual(m.total, (300 + 120 + 5 + 600) * 1000);
  assert.strictEqual(m.unlocks, 1);
  const via = m.sessions[0];
  assert.strictEqual(via.parts, 2);
  assert.strictEqual(via.visits.length, 2);
  assert.strictEqual(via.visits[0].name, 'zhihu.com');
  assert.strictEqual(via.visits[1].title, '');
  assert.deepStrictEqual(m.sessions.map(s => s.name), ['Via', '娱乐']);
  assert.deepStrictEqual(m.hidden, { count: 1, ms: 5000 });
  assert.strictEqual(m.apps[0].name, '娱乐');
  assert.strictEqual(m.apps[0].key, 'private:娱乐');
});

test('spreads a session across the hours it covers', () => {
  const m = M.buildDay({ start: day, end: day + 86400000, apps: [app(at(10, 50), 1200, 'A', 'a')] }, {});
  assert.strictEqual(m.hours[10], 10 * 60000);
  assert.strictEqual(m.hours[11], 10 * 60000);
});

test('folds the tail into one "other" slice', () => {
  const list = [1, 2, 3, 4].map(i => ({ key: 'k' + i, name: 'n' + i, ms: 10 - i }));
  const top = M.topWithOther(list, 2);
  assert.strictEqual(top.length, 3);
  assert.strictEqual(top[2].ms, 7 + 6);
  assert.ok(top[2].other);
});

test('formats durations', () => {
  assert.strictEqual(M.duration(40000), '40秒');
  assert.strictEqual(M.duration(12 * 60000), '12分');
  assert.strictEqual(M.duration(65 * 60000), '1小时 05分');
  assert.strictEqual(M.hostOf('https://www.Example.com:8080/a?b'), 'example.com');
});
