> [!IMPORTANT]
> This was written entirely with AI, and I haven't verified any of the code. Please use it with caution — though I do trust [Claude's Plan](https://www.youtube.com/watch?v=gFx-NjTw3sM).

# appblock

Personal Android app blocker — like [siteblock](https://github.com/csapuntz/siteblock), but for apps.
Pick apps and give each a budget: **allow X minutes per rolling Y minutes**. When the budget
is spent, opening the app bounces you to a "blocked, try again in N min" screen.

No dependencies, no network, no analytics. Four Kotlin files:

| File                 | What it does                                                               |
| -------------------- | -------------------------------------------------------------------------- |
| `MainActivity.kt`      | Home screen: blocked apps with editable budgets (auto-saved)               |
| `AppPickerActivity.kt` | "Block an app" picker: all installed apps with checkboxes                  |
| `BlockerService.kt`    | Accessibility service that watches the foreground app and enforces budgets |
| `BlockedActivity.kt`   | The full-screen "blocked" wall                                             |
| `Storage.kt`           | Rules + usage log as JSON in SharedPreferences                             |

## Build

Needs Java 17 and the Android SDK (`sdk.dir` in `local.properties`).

```sh
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

./gradlew test   # unit tests for the rolling-window math
```

## Install (via adb — read this, it saves you two roadblocks)

Don't install by copying the APK to the phone and tapping it. Two Android guards
will fight you, because this is a sideloaded, debug-signed APK that requests an
accessibility service:

1. **Google Play Protect** blocks the install ("app blocked to protect your device") —
   it flags any APK from an unrecognized developer, especially ones using accessibility.
2. Even after installing, **Android 13+ "Restricted setting"** refuses to let a
   file-manager-installed app have accessibility access.

Installing over USB with `adb` avoids **both**: Play Protect doesn't gate adb installs,
and adb-installed apps are exempt from the restricted-settings block.

One-time phone setup: Settings → About device → tap **Build number** 7 times to unlock
Developer options, then Settings → System → **Developer options** → enable
**USB debugging**. Plug the phone into the computer and accept the
"Allow USB debugging?" prompt on the phone.

```sh
adb install appblock.apk   # adb ships with the Android SDK platform-tools
```

## Setup (once)

1. Open AppBlock → tap **Enable accessibility service** → turn on AppBlock.
   (The button disappears once granted.)
2. Tap **+ Block an app**, check the apps you want, hit **Block selected apps**.
3. Adjust each app's budget on the home screen — changes save automatically.
4. OnePlus/OxygenOS kills background services: Settings → Apps → AppBlock →
   Battery → set to **Unrestricted / Don't optimize**.

## How the rolling window works

Every usage session is logged as a `[start, end]` interval. An app is blocked when the
summed usage inside the trailing window (e.g. last 120 min) reaches the allowance
(e.g. 5 min). Old usage "ages out" of the window continuously — no fixed reset times.
While a tracked app is open, the service re-checks every 5 seconds so a session is cut
off mid-use the moment the budget runs out.
