#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
. "$PROJECT_DIR/env.sh"
if [ -n "${ANDROID_SERIAL:-}" ]; then
    target_serial=$(python3 "$PROJECT_DIR/scripts/select-primary-device.py" --serial "$ANDROID_SERIAL")
else
    target_serial=$(python3 "$PROJECT_DIR/scripts/select-primary-device.py")
fi
apk="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
package=$("$ANDROID_HOME/cmdline-tools/22.0/bin/apkanalyzer" manifest application-id "$apk")
adb -s "$target_serial" install -r "$apk"
activity=$(adb -s "$target_serial" shell cmd package resolve-activity --brief "$package" | tr -d '\r' | tail -n 1)
adb -s "$target_serial" shell am start -W -n "$activity"
