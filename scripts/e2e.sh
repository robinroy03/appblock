#!/bin/sh -e
# End-to-end tests (app/src/androidTest) on the emulator: boots it if needed,
# then drives the real app with UI Automator. Report on failure:
# app/build/reports/androidTests/connected/debug/index.html
cd "$(dirname "$0")/.."
. scripts/env.sh
export ANDROID_SERIAL=emulator-5554

scripts/emulator.sh
./gradlew connectedDebugAndroidTest "$@"
