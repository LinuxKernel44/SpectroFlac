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
- Delivery: public GitHub repo (github.com/LinuxKernel44/SpectroFlac) via `gh`, with signed APKs attached to releases — not just source.
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
│                   spectrum, per-channel layers, spectrogram preview + full-resolution
│                   spectrogram, stereo sums, clipping, DR, real bit depth), Judge (turns
│                   measurements into a verdict + human-readable reasoning, `findEdge` step
│                   detector, `lossySourceGuess` fingerprint table), StereoAnalysis (stereo
│                   statistics, joint-stereo test, spectrum curves), StereoModels, FlacAnalyzer
│                   (orchestrates decode+analyze for one file), AnalysisReport (the result model)
├── queue/          AnalysisQueue (the scan queue engine, no Android deps: scheduling, parallelism,
│                   pause/cancel/retry/reorder, skip-known, per-file + overall progress),
│                   EtaEstimator, Parallelism (Auto / thermal / battery rules), QueueController
│                   (settings + device state -> queue; history, restore, auto-export), ScanService
│                   (foreground service + notification + wake lock), DeviceMonitor, QueueStore, FileInfo
├── settings/       AppSettings + SettingsRepository (DataStore)
├── export/image/   Spectrogram export to PNG: PngStreamWriter, LevelStore (strip-major temp matrix),
│                   StftEngine (STFT columns, PCM sources), ExportPlanner (sizes, estimates, Maximum),
│                   PcmExtractor, OverlayPainter + ImageComposer (header/axes/legend, strip by strip),
│                   SpectrogramImageExporter (orchestrator), SpectrogramExportJob + ExportService
├── data/           Room entity/DAO for local history, JSON (de)serialization of a report
├── export/         Exporter — text report, CSV, JSON, share intents
└── ui/
    ├── glass/      GlassShaders (AGSL source strings), LiquidGlass.kt (LiquidBackdrop,
    │               GlassPanel composables + frosted fallback for API < 33 or shader-compile
    │               failure)
    ├── theme/      Dark-only color scheme and typography
    ├── screens/    HomeScreen, ResultScreen, SpectrogramScreen (interactive: pinch/pan/cursor),
    │               QueueScreen, SettingsScreen, ListScreens (History/AnalysisOverlay)
    ├── components/ Shared building blocks (buttons, cards, spectrogram strip, verdict badge),
    │               Charts (spectrum chart, correlation timeline, ToggleChip)
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

### Spectrogram export (1.3.0)

It **re-renders from the file** (the in-memory spectrogram is only 2,600 × 1,024): the rows pick the FFT
(`ExportPlanner.fftSize`: next power of two of 2 × rows, so at least one bin per row) and the columns pick
the window spacing, with up to 16 peak-held windows per column when a column spans more audio than a window.
Pipeline: decode the chosen channel to a float temp file (`PcmExtractor`) → columns on all cores in chunks
(`StftColumns`) → `LevelStore` → compose + stream the PNG. Things that are easy to break:

- `LevelStore` layout: strip `s` holds, column after column, that column's bytes for the strip's bands, so a
  strip is one contiguous read (`levelAt = strip[column * rows + band - s * stripRows]`) and a chunk of
  columns is written with one write per strip. Writing a column at a time would be millions of tiny writes.
- The PNG is RGB and streamed row by row (`PngStreamWriter`); memory stays small at any size. Overlays are
  painted on one small bitmap per strip of rows and composited over the level pixels (`ImageComposer`), never
  on a full-size bitmap.
- Windows are **clamped inside the file** (`StftColumns.column`): zero-padding past the start made the first
  column splatter energy over every frequency (a visible line on the left of a lossy file's spectrogram).
- The size estimate (`ExportPlanner.estimate`) uses a measured FFT time (`ExportBenchmark`), a PNG ratio of
  0.55 and a decode speed of 40x real time; they are rough. `maximum()` keeps temp + PNG under 40 % of free space.
- Dialog windows are separate windows, so glass has nothing to refract and the screen behind shows through:
  use `DialogSurface` (opaque dark layer under the glass), never a bare `GlassPanel` inside a `Dialog`.
- A cancelled or failed export deletes its half-written output (`SpectrogramExportJob.discard`).
- Measured on a 4-core emulator: 8192 x 4096 plot in 6.3 s (12 MB), 16384 x 16384 plot (a 20275 x 19695 image,
  FFT 32768) in 47 s (45 MB PNG), Java heap ~40 MB, temp files ~260 MB.

### Permissions and notifications (1.3.0)

On the first scan a dialog explains why, then the notification permission and
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are asked in turn (`MainActivity`, flag `permissions_prompted`).
It is asked once; Settings > "Notifications and battery" shows both statuses with an *Allow* button (a refused
notification permission cannot be asked again by Android, so the button then opens the app's notification
settings: flag `notifications_denied`). The battery exemption is a sideloading/GitHub-distribution feature —
Google Play restricts it. The finish notification (`AppNotifications.scanFinished`, setting `notifyOnFinish`,
default on) is shown **always**, even with the app open, as the user chose. Thread presets: Quiet = cores/4 at
low priority, Balanced = Auto, Fast = cores-1 at normal priority (`Parallelism.applyPreset`).

### The scan queue and multi-threading (1.2.0)

The queue lives in `SpectroFlacApp` (not a ViewModel) so a scan survives screen changes and the foreground
service keeps the process alive. `AnalysisQueue` has no Android dependencies on purpose: the analysis, the
worker dispatcher and every device rule are injected, and `AnalysisQueueTest` drives it with gate-controlled
fake analysers (deterministic, no sleeps for correctness). Keep it that way.

Decisions worth knowing:

- Parallelism = several *files* at once; one file stays on one thread (a FLAC stream decodes sequentially).
  Auto = half the cores. Thermal protection lowers both Auto and a fixed number; Battery Saver only lowers Auto.
  A queue holds new files back at critical heat (`setHold`), never kills running ones.
- Finished items keep a `light()` report (no cover, spectrograms or curves): hundreds of full reports would
  run out of memory. Opening one re-analyses it (`MainViewModel.openQueueItem`).
- ETA = bytes processed over a 20 s sliding window (`EtaEstimator`); it resets on pause/idle and when the
  processed total drops (a cancelled file), and is null ("Estimating…") for the first 3 s.
- A cancelled decode returns a truncated report instead of throwing: `AnalysisQueue.run` calls `ensureActive()`
  after `analyze` and drops it. Do not remove that.
- List rows use `FlatPanel`, not `GlassPanel`: a refraction shader per row is wasted work in long lists.
- `QueueController` saves the unfinished files **after** the delay from the *latest* snapshot; saving the
  snapshot captured before the delay never fired during a busy scan (found on the emulator).
- History keys live in the JSON blob (`sourceModified`, `analyzerVersion`), not in new Room columns: the
  database uses `fallbackToDestructiveMigration`, so a schema change would wipe the user's history.
- The foreground service is `dataSync` (Android 15 gives it a 6 h limit; `onTimeout` just stops it, the queue
  continues in-process). Starting it is wrapped in `runCatching` (background-start restrictions).
- Testing on the emulator: the animated home backdrop never lets the UI thread idle, which hangs
  `ActivityScenario`/Espresso — `ScanDemoDriver` turns it off in settings first. The emulator's folder picker
  refuses the storage root and the Download folder itself ("choose another folder"); Documents works.
  `scripts/e2e-queue.sh` pushes real FLAC files into the debug app and runs `QueueEndToEndTest`.
- On a 4-core emulator, 12 files: 7.9 s with 1 thread, 2.8 s with 4 (x2.8). Nothing here has run on a real
  phone (the user's OnePlus 15 was not available), so the Auto default and the heat thresholds are untested on
  real hardware.

### Calibration on real music (2026-10-03)

The thresholds were first calibrated on synthetic noise and then checked against a real 17-track lossless
album (Bandcamp 16-bit/44.1 kHz FLAC, the user's own purchase — not in the repo) plus 21 lossy transcodes
made from 60 s excerpts of three of its tracks (MP3 128/192/320/V2, AAC 128, Vorbis q4, Opus 128). Findings
that changed the code:

- Genuine music: peak-hold top-of-spectrum step 7.6–12.4 dB (one gradual roll-off at 18.8 dB, track 16),
  average-spectrum step ≤ 12.6 dB, stereo width collapse up to 12.2 dB. `WALL_DB` = 26 has ample margin.
- **Stereo collapse alone is weak evidence**: the first threshold (12 dB) raised a false alarm on a genuine
  track, so `COLLAPSE_DB` is now 20 and a collapse-only finding is INFO, never WARNING. A side-channel brick
  wall below the mid channel's edge is the specific symptom (seen on a real MP3 transcode: side 16.0 kHz vs mid
  18.6 kHz) and is the only joint-stereo case that rates WARNING.
- **Peak-hold can be blurred**: an Opus 128 kbps transcode of a loud track had a 23.9 dB peak-hold step because
  sparse broadband bursts (clipping / decoder overshoot) filled the dead zone, and was rated GENUINE at 96 % —
  the worst kind of error. `Judge.spectral` now falls back to the *average* spectrum (`MEAN_WALL_DB` = 20,
  genuine ≤ 12.6, fakes ≥ 25.7) when peak-hold finds no wall. Peak-hold results are unchanged when it does.
- Vorbis q4 of real music lands at 19.1–19.45 kHz (19.0 kHz on noise); the 19.25–19.9 kHz band lists it.

Caveat: that is **one album** (one artist, one mastering chain) plus transcodes of three tracks. Say so
rather than overclaiming, and re-check against other genuine material (classical, hi-res, old masters with
steep low-passes) before loosening anything. Never commit the user's music; keep such tests in the scratchpad.

### Encoder fingerprints and their limits

`Judge.lossySourceGuess` is a table of measured cut frequencies, not folklore: each band comes from
round-tripping full-band stereo noise through libmp3lame (CBR/VBR), FFmpeg's native AAC, libvorbis and
libopus at 44.1 and 48 kHz (`scripts/make-samples.sh` rebuilds that corpus; `FingerprintTest` pins the
table to the measured values). Several encoders share a cutoff, so the guess lists candidates. Very high
bitrates (AAC 256+, LAME V0, Vorbis q6+) leave **no** cut on full-band noise — those are the `limit_*`
samples and carry no expectation. The table has not been checked against real music or against Apple /
FDK / Fraunhofer encoders; say so rather than overclaiming.

### Stereo and joint-stereo analysis

`AudioAnalyzer` keeps one spectral *layer* per view: the mono mix (layer 0, drives the verdict and must
stay numerically identical to the old single-spectrum behaviour) and, for stereo, Left/Right/Side. Left
and right go through **one** complex FFT (L as real part, R as imaginary) and mid/side are derived from the
separated spectra, which keeps the cost at one FFT per window — the first four-FFT version cost ~40 % of
throughput. Peak-hold is compared in the power domain and converted to dB once at the end; the 8-bit
spectrogram levels use a fast log2 (±0.02 dB), so don't "simplify" those back to per-bin `log10`.

Joint-stereo evidence (`StereoAnalysis.jointStereo`) is informational only: it adds a finding but never
changes the verdict. Test material must be **true stereo** — an ffmpeg `aformat=channel_layouts=stereo` on a
mono noise source gives a dual-mono file (correlation 1.0) that exercises nothing.

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

Synthetic unit tests (`StereoAnalysisTest`, `FingerprintTest`) need no samples. Real-encoder samples:
`scripts/make-samples.sh <dir>`, then `SPECTROFLAC_SAMPLES=<dir>`.

Instrumented Compose tests live in `app/src/androidTest/` (`SpectrogramScreenTest`: horizontal / vertical /
diagonal pinch, pan, double-tap, cursor, layer chips). They read the zoom state back through the canvas's
`stateDescription`. Run them on the KVM emulator. **RAM is tight**: a Gradle build running next to the
emulator has crashed it (segfault) — build the APKs first, `./gradlew --stop`, boot the emulator, then
install both APKs and run
`adb shell am instrument -w -e class com.spectroflac.ui.SpectrogramScreenTest com.spectroflac.debug.test/androidx.test.runner.AndroidJUnitRunner`.
Other screens were verified by hand on the emulator (see the `android-dev-environment` memory file).

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

**Two signing keys exist.** v1.0.0 was signed with `/home/david/dev/android/.spectroflac/spectroflac.jks` (password not on this
machine any more, never recovered). v1.1.0 onward is signed with a **new** key,
`/home/david/dev/android/.spectroflac/spectroflac-v2.jks` (alias `spectroflac`, created 2026-10-03 at the user's explicit request;
its password is in `/home/david/dev/android/.spectroflac/spectroflac-keystore-password.txt`, outside the repo, and `keystore.properties` points at
it). Android refuses to update a v1.0.0 install with a v1.1.0 APK, so v1.0.0 must be uninstalled first — the
release notes say so. Keep both keystores; releases from now on must use the v2 key.

**A signing keystore is the one thing in this project that cannot be regenerated.** If it's
lost, no future release can update an existing SpectroFlac install on a device — users would have
to uninstall and reinstall. It intentionally lives outside the repo; don't try to "fix" that by
committing it or copying it into a tracked path.

Repo is **public** (it was created private, later made public). It is licensed under PolyForm Noncommercial 1.0.0 (see `LICENSE`): noncommercial use only, and the `Required Notice:` credit line must be kept — do not swap in a different license without asking. Releases carry the signed APK as a
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
