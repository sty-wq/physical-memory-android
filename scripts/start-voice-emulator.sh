#!/bin/sh
# Mac voice testing: retain normal per-utterance AudioRecord release in the app.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$project_dir/env.sh"

if [ "$(uname -s)" != Darwin ] || [ "$(uname -m)" != arm64 ]; then
    echo 'This voice-test workaround is verified only on Apple Silicon macOS.' >&2
    exit 1
fi
if ! grep -q '^Pkg.Revision=37.1.11$' "$ANDROID_SDK_ROOT/emulator/source.properties" ||
   ! grep -q '^Pkg.BuildId=15917651$' "$ANDROID_SDK_ROOT/emulator/source.properties"; then
    echo 'The CoreAudio workaround must be revalidated for this Emulator version. See docs/emulator_microphone_fix.md.' >&2
    exit 1
fi
if adb devices | grep -q '^emulator-5554[[:space:]]'; then
    echo 'emulator-5554 is already running. Close its window before starting the voice-test emulator.' >&2
    exit 1
fi

native_dir="$project_dir/build/native/macos"
mkdir -p "$native_dir"
xcrun clang -std=c11 -Wall -Wextra -Werror -dynamiclib -framework CoreAudio \
    "$project_dir/tools/macos/coreaudio_listener_cleanup.c" -o "$native_dir/coreaudio_listener_cleanup.dylib"
# Set this AFTER entering the shell: macOS strips DYLD variables passed to /bin/sh.
# Emulator already permits process-local libraries; do not alter signatures or OS settings.
export DYLD_INSERT_LIBRARIES="$native_dir/coreaudio_listener_cleanup.dylib${DYLD_INSERT_LIBRARIES:+:$DYLD_INSERT_LIBRARIES}"
exec "$ANDROID_SDK_ROOT/emulator/emulator" -avd codex_api36_arm64 -port 5554 \
    -no-snapshot -no-boot-anim -gpu auto -camera-back none -camera-front none -allow-host-audio "$@"
