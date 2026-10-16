# Goal

Collect Android application, unlock and Via browser activity without root or daily screenshots, and synchronize exportable records to ROG alongside Work Review data.

# Current State

- Upstream v0.14.3, commit 968319fe5469c0120cd1fc8544e844cedac60ae7; branch feat/via-browser-sync; fork https://github.com/AlexbeatsZ/aw-android.
- Added Via support for mark.via and mark.via.gp, separate webhook settings and automatic WorkManager delivery, a durable outbox with acknowledged retries, recent event correction detection, and the ROG receiver/exporter integration.
- K70: Android 16 / HyperOS OS3.0.307.0.WNKCNXM. Installed and authorized for usage/browser capture and automatic sync. User enabled autostart. Phone UI released for other tasks after direct uploads succeeded.
- Xiaomi Pad 6 Pro: Android 15 / HyperOS 3. Installed and configured with a separate device identity, usage/browser permissions, autostart and automatic sync. ROG received 361 merged records, including two real Via visits; JSON/CSV exports passed.
- ROG receiver is C:/Users/Meta/Project/Scripts/JavaScript/webhooks, existing ObsidianWebhookReceiver task and Tailscale HTTPS ingress. Dataset is C:/Users/Meta/Project/Workspaces/work-review/data/activitywatch, sibling of data/hub. Independent runtime Bearer credential; no credentials in source or APK.
- K70 live acceptance: ROG received 8,607 merged events (8,106 app sessions, 499 unlocks, two real Via fixture visits with 5s/7s duration). This device exposes no page title; title stays empty. Do not substitute emulator title acceptance for device evidence.
- AI Decision's automatic reader has not been extended for this dataset. JSON/CSV export is available through the receiver integration.
- 2026-10-16 personal UI: the upstream aw-webui WebView, drawer, widget, aw-notify alerts, sync/auth settings screens and welcome page are removed. MainActivity hosts dashboard/DashboardFragment, which loads the bundled dark dashboard (assets/dashboard: timeline, donut + hourly stats, privacy rules, settings) and talks to dashboard/DashboardBridge (window.AWNative). The Rust server still embeds aw-webui but nothing opens it.
- Privacy rules (privacy/PrivacyRules.kt, stored by PrivacyStore) rename matching apps/domains/keywords to a label and optionally keep only URL hosts. They apply to the dashboard payload and, through WebhookSyncEngine's transform, to every webhook upload. A rule change re-sends the 7-day replay window; older raw batches on ROG are not rewritten.

# Active Work

- [x] Inspect actual ROG datasets and select Work Review's data sibling.
- [x] Add and test Via extraction and robust webhook delivery.
- [x] Install/configure K70 and verify direct transfer plus ROG JSON/CSV exports.
- [x] Complete tablet autostart, Via and direct-transfer acceptance.
- [x] Finish acceptance notes and checks.
- Delivery branch: feat/via-browser-sync on the user fork.

# Build / Run / Test

- CI: .github/workflows/personal.yml builds the arm64 debug APK from upstream v0.14.3 native libraries on pushes to claude/**, feat/**, personal/**.
- node --test mobile/src/test/js/model.test.js covers the dashboard's data shaping.

- Read docs/build-via-webhook.md for matching upstream native library preparation and reproducible commands.
- Existing per-process JDK 17: /opt/homebrew/opt/openjdk@17; SDK: /Users/meta/Library/Android/sdk.
- Gradle :mobile:testStandardDebugUnitTest :mobile:assembleStandardDebug :mobile:assembleStandardDebugAndroidTest: 234 tests pass, complete arm64 APK builds.
- node --test integrations/rog-webhook/activitywatch.test.mjs integrations/rog-webhook/receiver.test.mjs: nine tests pass, including existing UpTimer behavior; also passed Windows staging before deployment.
- ViaBrowserAcceptanceTest passes on isolated API36 emulator using the real native datastore. Only run there; preserve existing accessibility services.
- Lint has six verified errors inherited from upstream (four API-version exception diagnostics and two widget tint attributes); this change adds none.
- Temporary files: /tmp/.agents/ locally, Windows %LOCALAPPDATA%/Temp/.agents/. Package is net.activitywatch.android.debug; preserve the signing key and app data for updates.

# Design Index

- docs/design/via-and-personal-sync.md: browser extraction, retry/state contract, device evidence and Funnel networking. Read before modifying these modules.
- integrations/rog-webhook/README.md: receiver deployment, storage and export protocol.
- docs/build-via-webhook.md: native backend preparation, build and acceptance commands.
- mobile/src/main/assets/dashboard: the whole UI (no build step; plain JS). Bridge methods: day, candidates, privacy.get/set, status, open, webhook.syncNow, ui.get/set.

# Durable Lessons

- Via Play is mark.via.gp. Its compressed service tree can omit the toolbar id visible to UiAutomator; only a structurally verified committed URL should count as a visit.
- The K70 VPN advertises an HTTP proxy as well as fake DNS. DNS override alone still went through CONNECT's failed resolver. Public-Funnel fallback must retain original-host TLS validation and bypass that advertised proxy for the fallback connection only; change no global VPN configuration.
- A completed local export is not evidence of network receipt. Validate Android transmission, immutable remote storage and merged exports separately.
- Windows PowerShell 5 redirection changes encoding; the export CLI's output-file argument writes UTF-8 directly.
- In GitHub Actions, android-actions/setup-android@v3 defaults to installing the legacy 'tools' package, which fails on modern runner images; explicitly pass 'packages: platform-tools'.
