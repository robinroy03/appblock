#!/bin/sh -e
# Test on an emulator instead of the phone: boots an Android 14 emulator
# (creating it on first run), installs the current debug build on it (the
# build the e2e tests use), and grants AppBlock's required settings so the
# home list is live straight away.
#
#   scripts/emulator.sh            headless; drive it with adb
#   scripts/emulator.sh --window   with the emulator window on screen
#
# The virtual device lives in .avd/ (gitignored). One-time SDK download:
#   sdkmanager "emulator" "system-images;android-34;google_apis;arm64-v8a"
cd "$(dirname "$0")/.."
. scripts/env.sh

export ANDROID_AVD_HOME="$PWD/.avd"
export ANDROID_SERIAL=emulator-5554   # the first emulator's serial
WINDOW=-no-window
[ "$1" = "--window" ] && WINDOW=

if [ ! -d .avd/appblock.avd ]; then
  mkdir -p .avd
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n appblock \
    -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_7
fi

if ! "$ADB" devices | grep -q "^$ANDROID_SERIAL"; then
  nohup "$SDK/emulator/emulator" -avd appblock $WINDOW -no-audio -no-boot-anim \
    > .avd/emulator.log 2>&1 &
  echo "Booting the emulator (log: .avd/emulator.log)..."
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
fi

./gradlew assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk
# A release build there (signed differently) refuses the update: replace it.
"$ADB" install -r "$APK" || { "$ADB" uninstall com.robin.appblock; "$ADB" install "$APK"; }

# The required settings, granted directly instead of through their pages.
"$ADB" shell appops set com.robin.appblock GET_USAGE_STATS allow
"$ADB" shell appops set com.robin.appblock SYSTEM_ALERT_WINDOW allow
"$ADB" shell dumpsys deviceidle whitelist +com.robin.appblock > /dev/null
"$ADB" shell pm grant com.robin.appblock android.permission.POST_NOTIFICATIONS
echo "Ready: AppBlock is installed on $ANDROID_SERIAL."
