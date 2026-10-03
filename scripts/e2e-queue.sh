#!/usr/bin/env bash
# Builds a small corpus of real_* / fake_* FLAC files and runs the on-device queue tests on a running
# emulator or phone:
#
#   scripts/e2e-queue.sh            # needs adb, ffmpeg, a connected device, and the debug app installed
#
# The files go to the debug app's private storage (files/e2e); QueueEndToEndTest skips itself when they
# are missing. Build and install the APKs first (assembleDebug assembleDebugAndroidTest).
set -euo pipefail

PKG=com.spectroflac.debug
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK:?}"' EXIT

stereo() { # out seed
  ffmpeg -v error -y -f lavfi -i "anoisesrc=d=14:c=pink:r=44100:a=0.3:s=$2" -f lavfi -i "anoisesrc=d=14:c=pink:r=44100:a=0.3:s=$(($2 + 100))" \
    -f lavfi -i "anoisesrc=d=14:c=pink:r=44100:a=0.3:s=$(($2 + 200))" \
    -filter_complex "[0][1][2]amerge=inputs=3,pan=stereo|c0=c0+0.5*c1|c1=c0+0.5*c2,aformat=sample_fmts=s16" -c:a flac "$1"
}

for i in 1 2 3 4 5 6; do stereo "$WORK/real_$i.flac" "$i"; done
for i in 1 2 3 4 5 6; do
  br=$((96 + i * 32))
  ffmpeg -v error -y -i "$WORK/real_$i.flac" -f mp3 -c:a libmp3lame -b:a "${br}k" "$WORK/t.mp3"
  ffmpeg -v error -y -i "$WORK/t.mp3" -ar 44100 -sample_fmt s16 -c:a flac "$WORK/fake_mp3_${br}_$i.flac"
done
rm -f "$WORK/t.mp3"

adb shell rm -rf /data/local/tmp/e2e
adb push "$WORK" /data/local/tmp/e2e >/dev/null
adb shell "run-as $PKG sh -c 'rm -rf files/e2e && mkdir -p files/e2e && cp /data/local/tmp/e2e/*.flac files/e2e/'"
adb shell rm -rf /data/local/tmp/e2e
echo "pushed $(ls "$WORK"/*.flac | wc -l) files to $PKG/files/e2e"

adb shell am instrument -w -e class com.spectroflac.ui.QueueEndToEndTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner"
