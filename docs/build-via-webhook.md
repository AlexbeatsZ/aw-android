# Build this personal branch

Use JDK 17 and the existing Android SDK per process. The Java/Kotlin fork keeps the upstream v0.14.3 Rust backend and embedded WebUI unchanged. For an arm64 debug APK, supply both native libraries from the matching official release:

```sh
mkdir -p /tmp/.agents/aw-android-native
gh release download v0.14.3 --repo ActivityWatch/aw-android \
  --pattern aw-android-arm64-v8a.apk --dir /tmp/.agents/aw-android-native
uv run --no-project python scripts/prepare-upstream-native.py \
  /tmp/.agents/aw-android-native/aw-android-arm64-v8a.apk
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/Users/meta/Library/Android/sdk \
./gradlew :mobile:testStandardDebugUnitTest :mobile:assembleStandardDebug
node --test integrations/rog-webhook/activitywatch.test.mjs integrations/rog-webhook/receiver.test.mjs
```

The prepared libraries and APK output are ignored by Git. Building the backend from source remains possible through the upstream native build workflow. Do not mix a different release's JNI libraries with this source.

Gradle may require explicit Java `http.proxyHost` / `https.proxyHost` system properties on a proxy network; Java does not automatically apply shell HTTP_PROXY variables. Keep those options per process.

The package is `net.activitywatch.android.debug`. Preserve the debug signing key for future updates; a differently signed APK cannot update the installed package without removing its local data. Credentials are configured at runtime and are never compiled into the APK.

Run `ViaBrowserAcceptanceTest` only on an isolated emulator with Via installed, its URL field set to URL, and a host HTTP fixture on port 18987 serving distinct `/one` and `/two` page titles. It exercises the native datastore and restores existing accessibility services. Do not run the upstream accessibility tests on a user's device: several replace the entire enabled-service list.

Lint currently reports six errors already present in upstream v0.14.3: API-31 exception catches in `AlarmReceiver.kt` and `BackgroundService.kt`, plus two widget `android:tint` attributes. The fork's newly added translation/transport/settings code must not introduce additional errors. Unit tests and APK assembly are separate checks.
