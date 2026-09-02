#!/bin/sh
set -eu
. "$(dirname "$0")/../env.sh"
exec emulator -avd codex_api36_arm64 -port 5554 -no-snapshot -no-boot-anim -gpu auto -camera-back none -camera-front none "$@"
