import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

export class ActivityWatchError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
const fail = (code) => { throw new ActivityWatchError(400, code); };
const safeId = (value) => typeof value === 'string' && /^[a-zA-Z0-9_-]{1,128}$/.test(value);

export function validateActivityWatchBatch(value) {
  if (!value || value.schema_version !== 1 || !safeId(value.device_id) || !safeId(value.batch_id)) fail('invalid_batch_identity');
  if (typeof value.sent_at !== 'string' || !Number.isFinite(Date.parse(value.sent_at))) fail('invalid_sent_at');
  const bucket = value.bucket;
  if (!bucket || typeof bucket.id !== 'string' || !/^aw-watcher-android[a-zA-Z0-9_.-]*$/.test(bucket.id) || bucket.id.length > 256) fail('invalid_bucket');
  if (typeof bucket.type !== 'string' || bucket.type.length > 128 || typeof bucket.hostname !== 'string' || bucket.hostname.length > 256) fail('invalid_bucket_metadata');
  if (bucket.created && (typeof bucket.created !== 'string' || !Number.isFinite(Date.parse(bucket.created)))) fail('invalid_bucket_created');
  if (!Array.isArray(value.events) || value.events.length < 1 || value.events.length > 200) fail('invalid_events');
  const ids = new Set();
  for (const event of value.events) {
    if (!event || !Number.isSafeInteger(event.id) || event.id < 0 || ids.has(event.id)) fail('invalid_event_id');
    ids.add(event.id);
    if (typeof event.timestamp !== 'string' || !Number.isFinite(Date.parse(event.timestamp))) fail('invalid_event_timestamp');
    if (!Number.isFinite(event.duration) || event.duration < 0 || event.duration > 366 * 86400) fail('invalid_event_duration');
    if (!event.data || typeof event.data !== 'object' || Array.isArray(event.data)) fail('invalid_event_data');
  }
  // Explicit field selection prevents request paths/extra properties changing storage.
  return { schema_version: 1, device_id: value.device_id, batch_id: value.batch_id,
    sent_at: value.sent_at, bucket: { id: bucket.id, type: bucket.type, hostname: bucket.hostname, created: bucket.created || '' },
    events: value.events.map(({ id, timestamp, duration, data }) => ({ id, timestamp, duration, data })) };
}

/** Called inside the receiver's existing serialized write queue. */
export async function storeActivityWatchBatch(root, input) {
  const batch = validateActivityWatchBatch(input);
  const directory = path.join(root, batch.device_id, 'batches');
  await fs.mkdir(directory, { recursive: true });
  const target = path.join(directory, batch.batch_id + '.json');
  const digest = crypto.createHash('sha256').update(JSON.stringify(batch)).digest('hex');
  const existing = await fs.readFile(target, 'utf8').catch(error => {
    if (error.code === 'ENOENT') return null;
    throw error;
  });
  if (existing !== null) {
    if (JSON.parse(existing).digest !== digest) throw new ActivityWatchError(409, 'batch_id_conflict');
    return { ok: true, batch_id: batch.batch_id, events: batch.events.length, duplicate: true };
  }
  const temporary = target + '.' + crypto.randomUUID() + '.tmp';
  const handle = await fs.open(temporary, 'wx', 0o600);
  try {
    await handle.writeFile(JSON.stringify({ received_at: new Date().toISOString(), digest, ...batch }) + '\n');
    await handle.sync();
  } finally { await handle.close(); }
  try { await fs.rename(temporary, target); }
  catch (error) { await fs.rm(temporary, { force: true }); throw error; }
  return { ok: true, batch_id: batch.batch_id, events: batch.events.length, duplicate: false };
}

/** Materialize the latest received version of each event for CSV/JSON analysis. */
export async function readActivityWatchEvents(root, deviceId) {
  if (!safeId(deviceId)) fail('invalid_device_id');
  const directory = path.join(root, deviceId, 'batches');
  const names = await fs.readdir(directory).catch(error => {
    if (error.code === 'ENOENT') return [];
    throw error;
  });
  const latest = new Map();
  for (const name of names.filter(name => /^[a-zA-Z0-9_-]+\.json$/.test(name)).sort()) {
    const batch = JSON.parse(await fs.readFile(path.join(directory, name), 'utf8'));
    for (const event of batch.events) {
      const key = batch.bucket.id + ':' + (batch.bucket.created || '') + ':' + event.id;
      const previous = latest.get(key);
      if (!previous || previous.received_at <= batch.received_at) latest.set(key, {
        device_id: deviceId, bucket_id: batch.bucket.id, bucket_type: batch.bucket.type,
        hostname: batch.bucket.hostname, bucket_created: batch.bucket.created || '', received_at: batch.received_at, ...event,
      });
    }
  }
  return [...latest.values()].sort((a, b) => a.timestamp.localeCompare(b.timestamp) || a.id - b.id);
}
