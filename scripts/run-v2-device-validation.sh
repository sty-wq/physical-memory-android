#!/bin/sh
# Intentionally selects only isolated V2 tests. Never use AGP connected-test teardown on user data.
set -eu
. ./env.sh
if [ -n "${1:-}" ]; then
    serial=$(python3 scripts/select-primary-device.py --serial "$1")
else
    serial=$(python3 scripts/select-primary-device.py)
fi
adb -s "$serial" get-state
./gradlew assembleDebug assembleDebugAndroidTest
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$serial" shell am instrument -w -r -e class dev.local.physicalmemory.InventoryRepositoryTest dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$serial" shell am instrument -w -r -e class dev.local.physicalmemory.InventoryUiTest -e realNlu true dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$serial" shell am instrument -w -r -e class dev.local.physicalmemory.NluLifecycleTest -e nluLifecycle true dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$serial" shell am instrument -w -r -e class dev.local.physicalmemory.NluNativeTest#coexist -e nluCoexist true dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner
# Instrumentation can return shell exit 0 on assertion failure: inspect each OK/FAILURES summary.
