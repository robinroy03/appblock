# AGENTS.md

AppBlock is a small Android app-blocker: a foreground service polls the system
usage-event log for the foreground app (via "Usage access") and draws a block
wall over it ("Display over other apps") when a blocked app exceeds its time
budget. No accessibility service.
Plain Kotlin + Gradle, no Android Studio required.

## Setup

Requirements:

- **JDK 17** — the Android Gradle plugin refuses Java 11 and older. If the
  default `java` on the machine is wrong, point `JAVA_HOME` at a JDK 17 before
  building, or set `org.gradle.java.home` in `gradle.properties`.
- **Android SDK** — create `local.properties` (gitignored) with the SDK path:

  ```properties
  sdk.dir=/path/to/android-sdk
  ```

  `adb` lives at `<sdk.dir>/platform-tools/adb` and may not be on `PATH`.

First time in a clone, before the first commit (agents: do this yourself,
don't wait to be asked; check with `git config core.hooksPath`):

1. **Enable the pre-commit hook** — it runs the unit tests and blocks a
   commit that breaks them. Git doesn't turn on in-repo hooks by itself:

   ```sh
   git config core.hooksPath .githooks
   ```

2. **Install the emulator** for the end-to-end tests (one-time SDK
   download; `scripts/emulator.sh` creates the virtual device itself):

   ```sh
   sdkmanager "emulator" "system-images;android-34;google_apis;arm64-v8a"
   ```

   That's the Apple-silicon image; on an Intel machine use `x86_64` here
   and in `scripts/emulator.sh`.

3. **Run `scripts/e2e.sh` before pushing** any change to the app or its
   tests. It's the same suite CI's `e2e` job runs on every push, so a red
   suite here means a red CI. When a change renames or moves something the
   e2e tests look for (button text, a dialog), update the tests in
   `app/src/androidTest/` in the same commit.

CI (`.github/workflows/ci.yml`) runs two jobs on every branch push: `test`
(unit tests + compile) and `e2e` (the suite above on a fresh emulator). Check
a push with `gh run list`; a failing e2e run uploads an `e2e-report`
artifact with the HTML report, plus a screenshot and the device log for
each failed test.

Build:

```sh
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JUnit tests (also run by the pre-commit hook)
scripts/e2e.sh                   # end-to-end tests on the emulator (also in CI)
```

## Build → phone loop (phone connected over USB)

```sh
scripts/deploy.sh          # tests + release APK + adb install -r (keeps data/grants)
scripts/fresh-install.sh   # same build, but uninstall first: wipes all app state
                           # to test the first-run flow
```

Both handle JDK 17 and the adb path (via `scripts/env.sh`) themselves. Use
`deploy.sh` after every change; `fresh-install.sh` only when the fresh-install
experience itself is being tested — it deletes the user's rules and the
permission grants. A bare uninstall + install is not enough: Android's Auto
Backup restores the old rules on install, so the script turns restore off
for its install (`bmgr autorestore`). The phone also refuses
`adb shell pm clear` (OxygenOS), so reinstalling is the only way to wipe.

Install via `adb`, not by copying the APK to the phone — adb installs skip
Google Play Protect's block. One-time phone setup: enable Developer options and
USB debugging, then accept the USB-debugging prompt.

To verify a change, open AppBlock on the phone; the permission grants and
blocked-app rules survive an `-r` reinstall, and the blocker service restarts
itself after the update, so no re-setup is needed.

## Emulator loop (no phone needed)

```sh
scripts/emulator.sh            # boot (creating it on first run), install, grant settings
scripts/emulator.sh --window   # same, with the emulator window visible
```

An Android 14 emulator (a Pixel 7, like the test phone's screen) whose
virtual device lives in `.avd/` (gitignored). It installs the current debug
build and grants usage access, display-over-apps, the battery exemption and
notifications via adb, so the home list is live straight away. Prefer it for
checking UI changes; use the phone for what the emulator can't tell you
(haptics, OxygenOS behaviour). With both connected, the scripts target the
phone; set `ANDROID_SERIAL=emulator-5554` to point one at the emulator.

## Conventions

- Decision logic goes in `Storage.kt` as pure functions with JUnit tests in
  `app/src/test/` — every feature. UI stays in the Activities/Service.
- User-visible flows get an end-to-end test in `app/src/androidTest/`:
  UI Automator driving the real app (and the apps it blocks) on the
  emulator. `E2e.freshState()` resets the app before each test, and
  `seedUsage()` starts a test partway into a budget instead of waiting.
  Wheel steps use shell taps (`device.click` is too long a press for them).
- No build artifacts are committed: `*.apk`, `build/`, and `local.properties`
  are gitignored. Keep it that way.
