# Shared setup, sourced by the other scripts. Not executable on its own.
#
# Gradle needs JDK 17 (the default java on this machine may be older); adb
# lives inside the SDK named by local.properties and isn't on PATH.

[ -d /opt/homebrew/opt/openjdk@17 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17

SDK="$(sed -n 's/^sdk.dir=//p' local.properties)"
ADB="$SDK/platform-tools/adb"

# With the emulator running too, adb needs telling which device. Unless
# ANDROID_SERIAL says otherwise, scripts target the phone: the connected
# device that isn't an emulator.
if [ -z "$ANDROID_SERIAL" ]; then
  PHONE=$("$ADB" devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }')
  [ -n "$PHONE" ] && export ANDROID_SERIAL="$PHONE"
fi
