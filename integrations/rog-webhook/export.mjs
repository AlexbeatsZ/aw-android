import { readActivityWatchEvents } from './activitywatch.mjs';
import fs from 'node:fs/promises';
import path from 'node:path';
const [root, device, format = 'json', output] = process.argv.slice(2);
if (!root || !device || !['json', 'csv'].includes(format)) {
  process.stderr.write('Usage: node export.mjs <data-dir> <device-id> [json|csv] [output-file]\n');
  process.exit(2);
}
const events = await readActivityWatchEvents(root, device);
let content;
if (format === 'json') content = JSON.stringify(events, null, 2) + '\n';
else {
  const columns = ['device_id', 'bucket_id', 'bucket_created', 'bucket_type', 'hostname', 'id', 'timestamp', 'duration', 'data'];
  const csv = value => '"' + String(value ?? '').replaceAll('"', '""') + '"';
  content = columns.join(',') + '\n';
  for (const event of events) content += columns.map(key => csv(key === 'data' ? JSON.stringify(event.data) : event[key])).join(',') + '\n';
}
if (output) {
  await fs.mkdir(path.dirname(output), { recursive: true });
  await fs.writeFile(output, content, 'utf8');
  process.stdout.write(`Exported ${events.length} events as UTF-8 ${format}\n`);
} else process.stdout.write(content);
