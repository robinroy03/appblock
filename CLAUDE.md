# CLAUDE.md

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

Build:

```sh
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JUnit tests (also run by the pre-commit hook)
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
permission grants.

Install via `adb`, not by copying the APK to the phone — adb installs skip
Google Play Protect's block. One-time phone setup: enable Developer options and
USB debugging, then accept the USB-debugging prompt.

To verify a change, open AppBlock on the phone; the permission grants and
blocked-app rules survive an `-r` reinstall, and the blocker service restarts
itself after the update, so no re-setup is needed.

## Conventions

- Decision logic goes in `Storage.kt` as pure functions with JUnit tests in
  `app/src/test/` — every feature. UI stays in the Activities/Service.
- No build artifacts are committed: `*.apk`, `build/`, and `local.properties`
  are gitignored. Keep it that way.
