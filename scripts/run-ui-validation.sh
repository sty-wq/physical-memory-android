#!/bin/sh
# Run selected isolated tests. Never invoke AGP connected-test teardown on a phone with user data.
set -eu
. ./env.sh
if [ -n "${1:-}" ]; then
    serial=$(python3 scripts/select-primary-device.py --serial "$1")
else
    serial=$(python3 scripts/select-primary-device.py)
fi
out="${2:-../physical-memory-ui-validation/recheck}"
mkdir -p "$out"
adb -s "$serial" get-state
./gradlew clean test lint assembleDebug build assembleDebugAndroidTest
adb -s "$serial" shell am force-stop dev.local.physicalmemory
adb -s "$serial" exec-out run-as dev.local.physicalmemory tar cf - databases > "$out/pre-test-databases.tar"
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_case() {
    name="$1"
    shift
    adb -s "$serial" shell am instrument -w -r "$@" dev.local.physicalmemory.test/androidx.test.runner.AndroidJUnitRunner > "$out/$name.log"
    if ! rg -q '^OK \(' "$out/$name.log"; then
        cat "$out/$name.log"
        exit 1
    fi
}
run_case ui-room -e class dev.local.physicalmemory.UiUxRefactorTest,dev.local.physicalmemory.HistoryStoreTest,dev.local.physicalmemory.InventoryRepositoryTest
run_case real-nlu -e class dev.local.physicalmemory.InventoryUiTest -e realNlu true
# Current OPPO microphone checks; historical device-specific WAV replay is not part of this phase.
run_case speech-cancel -e class dev.local.physicalmemory.UiSpeechDeviceTest#realMicrophoneCancelPauseBackgroundAndScreenOff -e uiRealMicrophone true
run_case oppo-probe -e class dev.local.physicalmemory.OppoPrimaryProbeTest -e oppoProbe true
adb -s "$serial" shell am start -W -n dev.local.physicalmemory/.MainActivity
