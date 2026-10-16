import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import test from 'node:test';
import { storeActivityWatchBatch, readActivityWatchEvents, validateActivityWatchBatch } from './activitywatch.mjs';

const batch = (id = 'batch-1', duration = 30) => ({ schema_version: 1, device_id: 'phone-1', batch_id: id,
  sent_at: '2026-10-16T00:00:00Z', bucket: { id: 'aw-watcher-android-web', type: 'web.tab.current', hostname: 'K70' },
  events: [{ id: 1, timestamp: '2026-10-16T00:00:00Z', duration, data: { url: 'example.com', browser: 'mark.via.gp' } }] });
async function fixture(t) {
  const base = path.join(os.tmpdir(), '.agents');
  await fs.mkdir(base, { recursive: true });
  const root = await fs.mkdtemp(path.join(base, 'aw-webhook-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  return root;
}
test('a lost receipt can be retried without appending a second batch', async t => {
  const root = await fixture(t);
  assert.equal((await storeActivityWatchBatch(root, batch())).duplicate, false);
  assert.equal((await storeActivityWatchBatch(root, batch())).duplicate, true);
  assert.equal((await fs.readdir(path.join(root, 'phone-1', 'batches'))).length, 1);
  assert.equal((await readActivityWatchEvents(root, 'phone-1')).length, 1);
  await assert.rejects(storeActivityWatchBatch(root, batch('batch-1', 40)), { code: 'batch_id_conflict' });
});
test('edits update the analysis view while preserving the received raw batches', async t => {
  const root = await fixture(t);
  await storeActivityWatchBatch(root, batch());
  await new Promise(resolve => setTimeout(resolve, 5));
  await storeActivityWatchBatch(root, batch('batch-2', 25));
  const view = await readActivityWatchEvents(root, 'phone-1');
  assert.equal(view.length, 1);
  assert.equal(view[0].duration, 25);
  assert.equal(view[0].data.browser, 'mark.via.gp');
});
test('malformed events and destination traversal are rejected', () => {
  for (const invalid of [ { ...batch(), device_id: '../escape' }, { ...batch(), batch_id: 'a/b' },
    { ...batch(), events: [ { ...batch().events[0], duration: -1 } ] },
    { ...batch(), events: [batch().events[0], batch().events[0]] },
    { ...batch(), bucket: { ...batch().bucket, id: '../../elsewhere' } },
    { ...batch(), events: [ { ...batch().events[0], timestamp: 'not-a-date' } ] } ]) {
    assert.throws(() => validateActivityWatchBatch(invalid), { status: 400 });
  }
});
