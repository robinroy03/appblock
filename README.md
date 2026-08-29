> [!IMPORTANT]
> This was written entirely with AI, and I haven't verified any of the code. Please use it with caution — though I do trust [Claude's Plan](https://www.youtube.com/watch?v=gFx-NjTw3sM).

# appblock

Personal Android app blocker — like [siteblock](https://github.com/csapuntz/siteblock), but for apps.
Pick apps and give each a budget: **allow X minutes per rolling Y minutes**. When the budget
is spent, opening the app bounces you to a "blocked, try again in N min" screen.

No dependencies, no network, no analytics. Four Kotlin files:

| File                 | What it does                                                               |
| -------------------- | -------------------------------------------------------------------------- |
| `MainActivity.kt`    | Settings screen: app list + budget fields + save                           |
| `BlockerService.kt`  | Accessibility service that watches the foreground app and enforces budgets |
| `BlockedActivity.kt` | The full-screen "blocked" wall                                             |
| `Storage.kt`         | Rules + usage log as JSON in SharedPreferences                             |

## Build

Needs Java 17 and the Android SDK (`sdk.dir` in `local.properties`).

```sh
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

./gradlew test   # unit tests for the rolling-window math
```

## Install & setup (once)

1. Copy the APK to the phone and tap it (allow "install unknown apps" when prompted).
2. Open AppBlock → tap **Enable accessibility service** → turn on AppBlock.
3. Check apps, set budgets, hit **Save rules**.
4. OnePlus/OxygenOS kills background services: Settings → Apps → AppBlock →
   Battery → set to **Unrestricted / Don't optimize**.

## How the rolling window works

Every usage session is logged as a `[start, end]` interval. An app is blocked when the
summed usage inside the trailing window (e.g. last 120 min) reaches the allowance
(e.g. 5 min). Old usage "ages out" of the window continuously — no fixed reset times.
While a tracked app is open, the service re-checks every 5 seconds so a session is cut
off mid-use the moment the budget runs out.
