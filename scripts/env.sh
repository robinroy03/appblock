# Shared setup, sourced by the other scripts. Not executable on its own.
#
# Gradle needs JDK 17 (the default java on this machine may be older); adb
# lives inside the SDK named by local.properties and isn't on PATH.

[ -d /opt/homebrew/opt/openjdk@17 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17

ADB="$(sed -n 's/^sdk.dir=//p' local.properties)/platform-tools/adb"
