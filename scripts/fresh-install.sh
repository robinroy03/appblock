#!/bin/sh -e
# From-scratch install for testing the first-run experience: uninstall
# (wiping rules, onboarding state, and the accessibility grant), then
# install the freshly built APK.
cd "$(dirname "$0")/.."
. scripts/env.sh

./gradlew testDebugUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk appblock.apk
"$ADB" uninstall com.robin.appblock || true   # fine if it wasn't installed
"$ADB" install appblock.apk
