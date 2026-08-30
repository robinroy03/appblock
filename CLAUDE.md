# CLAUDE.md

AppBlock is a small Android app-blocker: an accessibility service watches the
foreground app and draws a block wall when a blocked app exceeds its time budget.
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

After every successful build:

```sh
cp app/build/outputs/apk/debug/app-debug.apk appblock.apk   # root copy, gitignored
adb install -r appblock.apk                                 # -r keeps data + accessibility grant
```

Install via `adb`, not by copying the APK to the phone — adb installs skip
Google Play Protect's block and the Android 13+ "restricted setting" guard on
accessibility (see README for details). One-time phone setup: enable Developer
options and USB debugging, then accept the USB-debugging prompt.

To verify a change, open AppBlock on the phone; the accessibility service and
blocked-app rules survive an `-r` reinstall, so no re-setup is needed.

## Conventions

- Decision logic goes in `Storage.kt` as pure functions with JUnit tests in
  `app/src/test/` — every feature. UI stays in the Activities/Service.
- No build artifacts are committed: `*.apk`, `build/`, and `local.properties`
  are gitignored. Keep it that way.
