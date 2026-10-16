# ROG webhook receiver

The receiver is the user's existing Node webhook service, extended with an independently authenticated `POST /activitywatch` route. Existing note and UpTimer routes remain covered by their original tests. Tailscale Funnel continues forwarding HTTPS to loopback port 8788; this integration requires no new global network setting or listening port.

Copy `activitywatch.mjs` and the updated `receiver.mjs` to the existing receiver project. Preserve its private state directory, notes path, UpTimer path, startup task and authentication files. Run `node --test activitywatch.test.mjs receiver.test.mjs` in an isolated staging directory before deployment.

Set `ACTIVITYWATCH_DATA_DIR` **in the receiver process launcher** to the desired data sibling. On the user's ROG, Work Review's verified hub is `C:/Users/Meta/Project/Workspaces/work-review/data/hub`, and the chosen phone activity destination is `C:/Users/Meta/Project/Workspaces/work-review/data/activitywatch`. This is a new dataset; it does not open or change the hub's SQLite database or ingest phone records as desktop samples.

The independent token defaults to `%LOCALAPPDATA%/obsidian-webhook-receiver/activitywatch-secret.txt`. Configure the Android app with the existing HTTPS origin plus `/activitywatch`, and supply this token in its separate token field. Never put the token in Git, logs, a URL, or an Android build.

The wire format is JSON:

```json
{
  "schema_version": 1,
  "device_id": "stable-installation-uuid",
  "batch_id": "durable-outbox-uuid",
  "sent_at": "2026-10-16T00:00:00Z",
  "bucket": {"id": "aw-watcher-android-web", "type": "web.tab.current", "hostname": "K70", "created": "2026-10-16T00:00:00Z"},
  "events": [{"id": 1, "timestamp": "2026-10-16T00:00:00Z", "duration": 30, "data": {"url": "example.com", "title": "Example", "browser": "mark.via.gp"}}]
}
```

Use `Authorization: Bearer <token>`. The receiver validates bounded batches and writes an immutable file to `<data-dir>/<device-id>/batches/<batch-id>.json`. Only after writing, syncing and renaming it does the receiver return `{ "ok": true, "batch_id": "...", "events": 1, "duplicate": false }`. An identical replay returns 200 with `duplicate: true`; a reused batch id with different contents returns 409.

Raw batches preserve activity edits. For analysis, `readActivityWatchEvents(root, deviceId)` resolves the latest received event by `(device_id, bucket_id, bucket_created, event_id)`, so retries and recent duration corrections do not inflate totals. Bucket creation time preserves separate histories when a bucket is recreated. Missing periods remain missing; these files do not claim exact equivalence to Xiaomi's private screen-time totals.

Android persists the pending batch before HTTP transmission. It keeps that exact batch until the receiver acknowledges its id and count, and retries transient failures through WorkManager. Routine scans replay seven days to detect edited/extended events; a long outage starts again at the previous completed scan's replay boundary. First enable includes up to fourteen days of locally available records. These ranges concern records already saved by ActivityWatch, not an Android promise to retain arbitrary historical events.

To export a merged analysis view, use `node export.mjs <data-dir> <device-id> [json|csv] <output-file>`. The optional output filename writes UTF-8 directly, including on Windows PowerShell 5, whose shell redirection otherwise changes encoding. Omitting it prints data to stdout. The CSV carries raw event data as a JSON column so app and browser fields remain available.
