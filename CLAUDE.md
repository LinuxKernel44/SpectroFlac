# CLAUDE.md

Context for Claude Code (or any future agent) working in this repository.

## What this project is

SpectroFlac is an Android app (Kotlin, Jetpack Compose, no Flutter) that decodes a `.flac` file
end to end on device and reports whether the audio is genuinely lossless — catching lossy
transcodes, fake hi-res upsampling, padded bit depth, renamed non-FLAC files, and stream damage.
Full detail and screenshots are in `README.md`; this file is about *how to work on it*, not what
it does.

## Origin and constraints that shaped the design

- The user originally asked for the UI to use the `AndroidLiquidGlassView` library
  (github.com/QmDeve/AndroidLiquidGlassView). Investigation showed it's a Java/Kotlin **View**
  library (not Compose, not Flutter-compatible) requiring Android 13+ to render at all. The user
  then explicitly said to drop it entirely ("Laisse totalement tomber
  https://github.com/QmDeve/AndroidLiquidGlassView"). The liquid glass look in `ui/glass/` is a
  from-scratch AGSL reimplementation — do not reintroduce the QmDeve dependency.
- Stack decision (user-confirmed): Kotlin + Jetpack Compose, not Flutter. Reasoning: native NDK
  access matters for a future fast spectrogram, and the glass effect needs AGSL/RuntimeShader
  either way since the original lib is gone.
- `minSdk = 29` (Android 10), user-confirmed, accepting that liquid glass refraction only renders
  on API 33+ (AGSL/RuntimeShader requirement) and everything below gets the frosted-glass
  fallback with identical layout. Do not raise minSdk without checking with the user — it was a
  deliberate compatibility trade-off.
- English UI (user-confirmed), even though the requesting conversation was in French.
- No audio player, ever — user was explicit ("Non, jamais"). Don't add playback controls.
- `.flac` extension only, by design: a non-FLAC file (e.g. an MP3 renamed to `.flac`) must be
  *detected and reported as such* rather than silently accepted or rejected — that's one of the
  fraud cases the app exists to catch. See `ContainerSniffer`.
- Detection scope the user asked for, all implemented: lossy transcode, upsampled/fake hi-res,
  padded bit depth ("fake 24-bit"), integrity/corruption (MD5 + CRC), clipping/dynamic range.
- Export: text share + CSV/JSON (user-confirmed "yes" over "text only" or "no export").
- Delivery: private GitHub repo via `gh`, with signed APKs attached to releases — not just source.
  See "Releasing" below.

## Why the FLAC decoder is hand-written

`flac/FlacDecoder.kt` is a complete FLAC bitstream decoder written from scratch in Kotlin — it
does **not** delegate to `MediaCodec`. This was a deliberate choice, not an oversight: the
platform decoder returns 16-bit PCM on a lot of Android devices, which would make both the
STREAMINFO-MD5 verification and the "is this file's 24-bit depth real?" test meaningless. The
decoder is validated by recomputing the MD5 the encoder stored in STREAMINFO from the actually
decoded PCM (see Testing below) — that's the strongest correctness check a decoder can get, and
any change to `flac/` must keep passing it.

## Architecture

```
app/src/main/java/com/spectroflac/
├── flac/          FLAC bitstream: BitInput (bit reader + CRC-8/16), ContainerSniffer
│                   (identifies the real format from magic bytes, incl. ID3-prefixed files),
│                   FlacDecoder (metadata blocks + full frame/subframe decode), FlacModels
├── analysis/       Fft (radix-2), AudioAnalyzer (single-pass streaming measurements: peak-hold
│                   spectrum, spectrogram preview, clipping, DR, real bit depth), Judge (turns
│                   measurements into a verdict + human-readable reasoning), FlacAnalyzer
│                   (orchestrates decode+analyze for one file), AnalysisReport (the result model)
├── data/           Room entity/DAO for local history, JSON (de)serialization of a report
├── export/         Exporter — text report, CSV, JSON, share intents
└── ui/
    ├── glass/      GlassShaders (AGSL source strings), LiquidGlass.kt (LiquidBackdrop,
    │               GlassPanel composables + frosted fallback for API < 33 or shader-compile
    │               failure)
    ├── theme/      Dark-only color scheme and typography
    ├── screens/    HomeScreen, ResultScreen, ListScreens (Batch/History/AnalysisOverlay)
    ├── components/ Shared building blocks (buttons, cards, spectrogram strip, verdict badge)
    ├── MainActivity.kt   intent handling (VIEW/SEND), screen routing
    └── MainViewModel.kt  state, coroutine-driven analysis, Room wiring
```

### How detection actually works (`analysis/Judge.kt`)

The core idea: a lossy encoder throws away everything above its cutoff frequency in *every* frame,
so the peak-hold spectrum (not the average — peak-hold) of a transcode shows a sharp step ("brick
wall") of ≥26 dB (`WALL_DB`) followed by near-silence. Genuine lossless audio that's simply dull
rolls off gradually and still has *something* above the roll-off somewhere in the track. The step
detector (`Judge.spectral`) scans for the steepest ~1.2 kHz-wide drop in the smoothed spectrum;
if it clears the threshold, the edge position is matched against known encoder cutoff profiles
(`lossySourceGuess`) to name a likely bitrate/codec.

Fake hi-res detection layers on top: if the sample rate is >50 kHz and the measured cutoff lands
near the Nyquist of a standard lower rate (44.1/48/88.2/96 kHz, ±20% margin for resampler
transition bands), it's flagged as upsampled regardless of whether a "wall" name-matches a codec.

Real bit depth: `AudioAnalyzer` ORs every sample together; trailing zero bits across the whole
file mean those low bits never carried signal — that's `effectiveBitDepth` /
`unusedLowBits` in `DynamicsInfo`.

If you touch the thresholds in `Judge.kt`, **you must re-run `JudgeTest` against real encoder
output**, not just synthetic signals — see Testing.

## Testing

Unit tests live in `app/src/test/java/com/spectroflac/`. Two of them are driven by environment
variables pointing at directories of real `.flac` files, because meaningful FLAC decoder/detector
testing needs actual encoder output, not fixtures:

```bash
# Decoder correctness: recomputes MD5 from decoded PCM and compares to STREAMINFO's stored MD5,
# checks CRC-16 per frame, checks sample counts. This is the strongest signal that the decoder
# is right.
SPECTROFLAC_SAMPLES=/path/to/flacs ./gradlew :app:testDebugUnitTest --tests '*FlacDecoderTest*'

# Detection correctness: files named real_*.flac must come out AUTHENTIC, fake_*.flac must come
# out FAKE. Fails loudly (with per-file measurements printed) if the Judge thresholds regress.
SPECTROFLAC_SAMPLES=/path/to/flacs ./gradlew :app:testDebugUnitTest --tests '*JudgeTest*'

# Throughput sanity check (large files, prints x-realtime).
SPECTROFLAC_PERF=/path/to/large/flacs ./gradlew :app:testDebugUnitTest --tests '*PerformanceTest*'
```

Sample generation recipe used during development (ffmpeg): build a `real_*.flac` from
`anoisesrc`/`sine` at the target rate/depth, then derive `fake_*.flac` variants by transcoding
through `libmp3lame`/`aac` and back to FLAC, or by resampling up, or by re-encoding at a higher
declared bit depth without adding real resolution. **Do not derive fake/real pairs through a
naive ffmpeg pipeline without checking each stage's actual sample rate/depth** — an earlier round
of sample generation silently resampled the "real" files too, which produced false test failures
that looked like Judge bugs but weren't. Verify with `ffprobe` before trusting a sample.

No instrumented/UI tests exist yet; screen changes were manually verified on a local headless
emulator during development (see the `android-dev-environment` memory file for the recipe —
worth checking if it still exists and is current before repeating that setup work).

## Releasing

```bash
scripts/release.sh                    # build, verify signature, create/update the vX.Y.Z release
scripts/release.sh --notes "…"         # custom release notes
```

Reads `versionName` from `app/build.gradle.kts`, so bump `versionCode`/`versionName` there first.
Requires `keystore.properties` at the project root (gitignored, never committed — see
`app/build.gradle.kts` for the exact keys it reads: `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). Without it, `assembleRelease` still produces an APK, just unsigned — the script
refuses to publish that (`apksigner verify` gate).

**The signing keystore is the one thing in this project that cannot be regenerated.** If it's
lost, no future release can update an existing SpectroFlac install on a device — users would have
to uninstall and reinstall. It intentionally lives outside the repo; don't try to "fix" that by
committing it or copying it into a tracked path.

Repo is private (`gh repo create SpectroFlac --private`). Releases carry the signed APK as a
binary asset via `gh release create`/`gh release upload`, not just source — that was an explicit
delivery requirement, not a nice-to-have.

## Conventions worth preserving

- Every user-facing numeric string goes through `Locale.US` formatting (`analysis/Format.kt`'s
  `fmt()` extension, `util/Formatting.kt`). This is deliberate: "44.1KHZ" must never become
  "44,1KHZ" on a device set to a comma-decimal locale. Watch for raw `"%.1f".format(...)` or
  string concatenation that accidentally drops a `.fmt()` call and prints a literal `%.0f` —
  this exact bug happened once during development (a `.fmt()` was chained onto only the first
  half of a `+`-concatenated string) and only showed up visually on-device, not in unit tests.
- The liquid glass shaders must never crash the app if AGSL compilation fails on some GPU driver:
  `LiquidGlass.kt` wraps `RuntimeShader` creation in `runCatching` and falls back to
  `drawFallbackBackdrop`/`drawFallbackGlass`. Keep that guard if you touch `GlassShaders.kt`.
- The backdrop only animates on the home screen (`animated = viewModel.screen is Screen.Home`) —
  redrawing a full-screen shader every frame behind a long scrolling result list was wasted GPU
  work with no visual benefit; preserve that gating if you add screens.
- The spectrogram preview fills gaps between analysis windows and result-strip columns
  (`AudioAnalyzer.fillSpectrogramGaps`) so short tracks don't show black bars — this was a
  visible bug caught on-device, not something a unit test would surface, because unit tests don't
  render the bitmap.
