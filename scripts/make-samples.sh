#!/usr/bin/env bash
# Generates a directory of FLAC samples with known provenance for JudgeTest / FlacDecoderTest:
#
#   scripts/make-samples.sh /path/to/samples
#   SPECTROFLAC_SAMPLES=/path/to/samples ./gradlew :app:testDebugUnitTest
#
# real_*  : full-band signals, must come out genuine.
# fake_*  : the same signal round-tripped through a lossy encoder (or padded / upsampled), must not.
# limit_* : encodes whose cutoff is above what a full-band noise signal reveals (AAC 256+, LAME V0,
#           Vorbis q6+): the spectral test cannot see them, so they carry no expectation.
#
# Every stage is checked with ffprobe: a naive pipeline silently resamples or changes the sample
# format, which looks like a Judge bug but is not.
set -euo pipefail

OUT="${1:?usage: make-samples.sh <output dir>}"
mkdir -p "$OUT"
cd "$OUT"

noise() { # name rate seed -> mono pink noise input spec
  echo "anoisesrc=d=20:c=pink:r=$1:a=0.3:s=$2"
}

# True stereo: a shared centre signal plus independent diffuse noise per channel. Mixing a mono
# source up to "stereo" would give a dual-mono file (correlation 1.0) and test nothing.
stereo() { # out rate
  ffmpeg -v error -y -f lavfi -i "$(noise "$2" 1)" -f lavfi -i "$(noise "$2" 2)" -f lavfi -i "$(noise "$2" 3)" \
    -filter_complex "[0][1][2]amerge=inputs=3,pan=stereo|c0=c0+0.5*c1|c1=c0+0.5*c2,aformat=sample_fmts=s16" \
    -c:a flac "$1"
}

check() { # file rate bits channels
  local rate channels bits
  IFS=, read -r _ rate channels bits < <(ffprobe -v error -select_streams a:0 \
    -show_entries stream=sample_fmt,sample_rate,channels,bits_per_raw_sample -of csv=p=0 "$1")
  [[ "${bits:-0}" == "N/A" || -z "${bits:-}" ]] && bits=16   # 16-bit FLAC reports no raw bit depth
  echo "$1: ${rate} Hz, ${bits} bit, ${channels} ch"
  [[ "$rate" == "$2" && "$bits" == "$3" && "$channels" == "$4" ]] || { echo "unexpected format for $1" >&2; exit 1; }
}

roundtrip() { # name rate encoder-args...
  local name=$1 rate=$2; shift 2
  ffmpeg -v error -y -i "real_stereo_${rate}.flac" "$@" enc.tmp
  ffmpeg -v error -y -i enc.tmp -ar "$rate" -sample_fmt s16 -c:a flac "fake_${name}.flac"
  rm -f enc.tmp
  check "fake_${name}.flac" "$rate" 16 2
}

stereo real_stereo_44100.flac 44100; check real_stereo_44100.flac 44100 16 2
stereo real_stereo_48000.flac 48000; check real_stereo_48000.flac 48000 16 2

for br in 64 96 112 128 160 192 224 256 320; do roundtrip "mp3_cbr${br}" 44100 -f mp3 -c:a libmp3lame -b:a "${br}k"; done
for q in 2 4 5 6 7 9; do roundtrip "mp3_v${q}" 44100 -f mp3 -c:a libmp3lame -q:a "$q"; done
for br in 64 96 128 160 192; do roundtrip "aac${br}" 44100 -f ipod -c:a aac -b:a "${br}k"; done
for q in 0 2 3 4 5; do roundtrip "vorbis_q${q}" 44100 -f ogg -c:a libvorbis -q:a "$q"; done
for br in 64 96 128 192; do roundtrip "opus${br}" 44100 -f ogg -c:a libopus -b:a "${br}k"; done
roundtrip mp3_128_48k 48000 -f mp3 -c:a libmp3lame -b:a 128k
roundtrip opus96_48k 48000 -f ogg -c:a libopus -b:a 96k

# Encodes the spectral test cannot see on full-band noise.
roundtrip limit_aac256 44100 -f ipod -c:a aac -b:a 256k && mv fake_limit_aac256.flac limit_aac256.flac
roundtrip limit_mp3_v0 44100 -f mp3 -c:a libmp3lame -q:a 0 && mv fake_limit_mp3_v0.flac limit_mp3_v0.flac
roundtrip limit_vorbis_q6 44100 -f ogg -c:a libvorbis -q:a 6 && mv fake_limit_vorbis_q6.flac limit_vorbis_q6.flac

# Fake hi-res and padded bit depth.
ffmpeg -v error -y -f lavfi -i "$(noise 96000 1)" -af "aformat=sample_fmts=s32:channel_layouts=stereo" \
  -sample_fmt s32 -c:a flac -bits_per_raw_sample 24 real_96000_24.flac
check real_96000_24.flac 96000 24 2
ffmpeg -v error -y -i real_stereo_44100.flac -ar 96000 -sample_fmt s32 -c:a flac -bits_per_raw_sample 24 fake_upsampled_96k.flac
check fake_upsampled_96k.flac 96000 24 2
ffmpeg -v error -y -i real_stereo_44100.flac -sample_fmt s32 -c:a flac -bits_per_raw_sample 24 fake_padded_24.flac
check fake_padded_24.flac 44100 24 2

echo "done: $(ls *.flac | wc -l) files in $OUT"
