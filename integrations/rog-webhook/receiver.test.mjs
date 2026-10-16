import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createWebhookServer } from './receiver.mjs';

async function fixture(t) {
  const tempRoot = path.join(process.env.LOCALAPPDATA || os.tmpdir(), process.env.LOCALAPPDATA ? 'Temp/.agents' : '.agents');
  await fs.mkdir(tempRoot, { recursive: true });
  const root = await fs.mkdtemp(path.join(tempRoot, 'uptimer-webhook-test-'));
  const config = {
    notesDir: path.join(root, 'notes'), uptimerDir: path.join(root, 'uptimer'),
    activitywatchDir: path.join(root, 'activitywatch'), activitywatchSecret: 'activitywatch-secret-'.repeat(3),
    secret: 'legacy-secret-'.repeat(4), uptimerSecret: 'uptimer-secret-'.repeat(4),
    newline: '\n', maxBodyBytes: 1024,
  };
  const server = createWebhookServer(config, { logger: () => {} });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    await new Promise(resolve => server.close(resolve));
    await fs.rm(root, { recursive: true, force: true });
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (url, body, type = 'application/json') => fetch(base + url, {
    method: 'POST', headers: { 'Content-Type': type }, body,
  });
  return { config, post, base };
}

test('dedicated UpTimer route accepts pretty JSON as one line and fixes the destination', async t => {
  const { config, post } = await fixture(t);
  const event = { hook_type: 'timing_log', content: '', path: '../../escape.md', start_time: 1, end_time: 2 };
  const response = await post(`/uptimer/${config.uptimerSecret}?path=../../escape.md`, JSON.stringify(event, null, 2));
  assert.equal(response.status, 201);
  assert.equal((await response.json()).path, 'UpTimer.md');
  const data = await fs.readFile(path.join(config.uptimerDir, 'UpTimer.md'), 'utf8');
  assert.equal(data.split('\n').filter(Boolean).length, 1);
  assert.deepEqual(JSON.parse(data), event);
  await assert.rejects(fs.stat(config.notesDir), { code: 'ENOENT' });
});

test('dedicated route rejects the legacy secret, text, arrays, empty hooks and malformed JSON', async t => {
  const { config, post } = await fixture(t);
  assert.equal((await post(`/uptimer/${config.secret}`, '{"hook_type":"timing_log"}')).status, 401);
  for (const [body, type] of [['hello', 'text/plain'], ['[]', 'application/json'], ['{"hook_type":""}', 'application/json'], ['{', 'application/json']]) {
    assert.equal((await post(`/uptimer/${config.uptimerSecret}`, body, type)).status, 400);
  }
  await assert.rejects(fs.stat(config.uptimerDir), { code: 'ENOENT' });
});

test('legacy UpTimer senders now store in the service while other note senders keep their destination', async t => {
  const { config, post } = await fixture(t);
  assert.equal((await post(`/webhook/${config.secret}?path=${encodeURIComponent('06记录/UpTimer/UpTimer.md')}`, '{"hook_type":"timing_log","content":""}')).status, 201);
  assert.equal((await post(`/webhook/${config.secret}?path=inbox/note.md`, '{"data":"note"}')).status, 201);
  assert.equal(await fs.readFile(path.join(config.notesDir, 'inbox/note.md'), 'utf8'), 'note\n');
  await assert.rejects(fs.stat(path.join(config.notesDir, '06记录/UpTimer/UpTimer.md')), { code: 'ENOENT' });
  assert.equal(JSON.parse(await fs.readFile(path.join(config.uptimerDir, 'UpTimer.md'), 'utf8')).hook_type, 'timing_log');
});

test('notes traversal and oversized UpTimer bodies are rejected before writing', async t => {
  const { config, post } = await fixture(t);
  assert.equal((await post(`/webhook/${config.secret}?path=../escape.md`, '{"data":"bad"}')).status, 400);
  assert.equal((await post(`/uptimer/${config.uptimerSecret}`, JSON.stringify({ hook_type: 'timing_log', content: 'x'.repeat(1500) }))).status, 413);
});

test('concurrent UpTimer requests append complete JSON lines without changing notes', async t => {
  const { config, post } = await fixture(t);
  const replies = await Promise.all(Array.from({ length: 15 }, (_, id) => post(`/uptimer/${config.uptimerSecret}`, JSON.stringify({ hook_type: 'timing_log', id }))));
  assert.ok(replies.every(r => r.status === 201));
  const records = (await fs.readFile(path.join(config.uptimerDir, 'UpTimer.md'), 'utf8')).trim().split('\n').map(JSON.parse);
  assert.equal(records.length, 15);
  assert.equal(new Set(records.map(r => r.id)).size, 15);
});

test('ActivityWatch uses independent bearer auth and acknowledges only stored batches', async t => {
  const { config, base } = await fixture(t);
  const payload = { schema_version: 1, device_id: 'phone-1', batch_id: 'batch-1', sent_at: '2026-10-16T00:00:00Z',
    bucket: { id: 'aw-watcher-android-web', type: 'web.tab.current', hostname: 'K70' },
    events: [{ id: 1, timestamp: '2026-10-16T00:00:00Z', duration: 5, data: { url: 'example.com', browser: 'mark.via.gp' } }] };
  const post = token => fetch(base + '/activitywatch', { method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: JSON.stringify(payload) });
  assert.equal((await post(config.uptimerSecret)).status, 401);
  const first = await post(config.activitywatchSecret);
  assert.equal(first.status, 201);
  assert.equal((await first.json()).batch_id, 'batch-1');
  assert.equal((await post(config.activitywatchSecret)).status, 200);
  const stored = JSON.parse(await fs.readFile(path.join(config.activitywatchDir, 'phone-1', 'batches', 'batch-1.json'), 'utf8'));
  assert.equal(stored.events[0].data.browser, 'mark.via.gp');
  await assert.rejects(fs.stat(config.uptimerDir), { code: 'ENOENT' });
  const bad = await fetch(base + '/activitywatch', { method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + config.activitywatchSecret }, body: '{' });
  assert.equal(bad.status, 400);
});
