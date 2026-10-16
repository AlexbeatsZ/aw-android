# Via browser and personal data synchronization

## Requirements

Collect future phone app/browser usage without screenshots or root. Capture Via URL, page title and visit duration while retaining existing browser support. Transfer data to the user's ROG machine and place it alongside the comparable AI Decision input datasets, using its current documented layout.

## Existing architecture

WebWatcher receives accessibility events and BrowserSessionTracker closes a visit on URL/browser/window changes. RustInterface persists events to the local ActivityWatch server. SyncInterface runs the native aw-sync exporter and mirrors its files into an explicitly selected SAF directory; SyncScheduler and SyncWorker provide recurring and fallback execution.

## Implementation decisions

- Reuse existing collection, persistence and scheduling components.
- Support both Via package variants, including the installed mark.via.gp.
- Determine URL/title extraction from the installed browser's actual accessibility tree; never treat arbitrary page text or typed search text as a committed URL.
- Keep URL extraction scoped to the expected browser window and preserve existing accessibility node ownership/recycling rules.
- ROG AI Decision currently reads UpTimer from the Node webhook service. Reuse its Tailscale HTTPS ingress and loopback receiver, with an independent Bearer token and ActivityWatch endpoint.
- The user then selected Work Review as the storage neighbor. Its hub is under `C:/Users/Meta/Project/Workspaces/work-review/data/hub`; use the sibling `data/activitywatch` for raw phone batches. Keep this destination in the receiver process configuration, not compiled into the Android app.
- Webhook work is independent of aw-sync directory mirroring. WorkManager provides network-aware 15-minute recurrence and exponential retries. A durable pending batch is replayed unchanged until the receiver acknowledges its id and event count. Per-event fingerprints detect recent edits; day-window scans replay seven days and cover gaps since the last completed scan. First enable backfills up to 14 days of local records.
- The actual Via Play build uses a programmatic, id-less address TextView under `mark.via.gp:id/fm`. The default field content is Title. Configure Via General / Toolbars settings / URL field content to URL, then read only the committed clickable TextView in that toolbar. Unknown/title/editing states end the known visit. Page title comes from the WebView accessibility node, when exposed.
- The receiver stores immutable JSON batches and treats the same batch id/body as an idempotent retry. Analysis resolves multiple versions by `(device_id, bucket_id, bucket_created, event_id)`; receiver confirmation is sent only after storage. Logs exclude URLs and payloads.
- A successful local export is separate from successful network transfer and analysis ingestion. Report the stages accurately.
- Device test setup must preserve other accessibility services. Deployment requires explicit installation authorization under the user's environment rules.

## Verification

Exercise URL extraction against recorded structural cases; cover browser/session transitions and meaningful sync failure/retry behavior. Run the existing relevant unit tests and produce a complete APK. Device acceptance uses real Via navigation and received, parseable data on ROG when installation is authorized.

## Verified device and network details

- Via's AccessibilityService tree can omit the toolbar container/id found by UiAutomator. The fallback requires a real WebView plus a clickable native URL label with ImageView siblings; page/list ancestry is rejected.
- K70 real navigation stored two fixture URLs with positive durations. This device's Via WebView does not expose page title text, so title remains empty. The isolated API-36 emulator exposes titles and the full URL/title/duration test passes. Do not infer real-phone title availability from emulator acceptance.
- The K70 VPN supplies both synthetic DNS answers and an HTTP proxy. The configured public Funnel hostname failed TLS through that proxy; `curl --resolve` to a fresh public DNS answer succeeded with normal certificate verification. A DNS override alone still went through the HTTP proxy's CONNECT resolver and failed.
- The webhook transport now tries normal system networking first. Only for a `.ts.net` destination after an I/O failure, it resolves public A records through HTTPS DNS, rejects private/fake-IP answers, and opens the same HTTPS origin without the advertised HTTP proxy. SNI, hostname verification and normal certificate validation remain enabled. Public answers are cached five minutes; there is no pinned IP or global VPN/DNS change. Private-only tailnet names without public Funnel ingress keep their normal route and will not gain a usable public fallback.
- Native settings have an explicit floating menu button because Android system back gestures can consume edge swipes intended for the drawer.
- A running worker snapshots the destination and credential together for its delivery, and stops between batches if configuration changes. Manual sync replaces a pending manual retry rather than retaining its old backoff delay. Each destination/credential pair retains its own durable outbox.

## Deployment acceptance, 2026-10-16

Both devices run the configured debug package with separate installation UUIDs. K70 and Xiaomi Pad 6 Pro retain the existing MiLink accessibility service. Usage access, browser accessibility and automatic webhook sync are enabled; autostart is enabled on both. No root or Shizuku is required by the app.

ROG received and exported these merged records at the acceptance snapshot:

| Device | Application sessions | Unlocks | Via visits | Total |
| --- | ---: | ---: | ---: | ---: |
| K70 | 8,106 | 499 | 2 | 8,607 |
| Xiaomi Pad 6 Pro | 333 | 26 | 2 | 361 |

The real Via fixture visits arrived as distinct URLs with positive durations: K70 5s/7s; tablet 3.078s/2.087s. Both real devices leave titles empty. The isolated API36 emulator also verifies title capture when its WebView exposes one. K70's exported history starts 2026-10-09 UTC; tablet history starts 2026-10-06 UTC. These are the actual received coverage windows, not a guarantee of Xiaomi dashboard parity or complete old system history.

The receiver's `export.mjs` directly produced parseable UTF-8 JSON and matching-row-count CSV for both device IDs under `data/activitywatch/exports`. Raw records continue accumulating independently of these point-in-time exports. Automatic collection and transfer need no ADB connection. WorkManager targets a 15-minute interval; Android power management and connectivity can delay a run. Public-Funnel fallback was verified on the K70's real VPN path.

The fork's 234 Android unit tests, nine Node receiver tests and real Via/native-datastore instrumentation pass. Lint still reports the six documented upstream baseline errors and no additional fork errors. The ROG receiver preserves existing UpTimer/note behavior, backed up before deployment and covered by receiver tests. AI Decision's automatic reader is not yet wired to this new dataset.

The inherited `audible` and `incognito` boolean fields are placeholders; they do not establish actual audio playback or private-browsing state.
