# SPEC — Match display refresh rate (delta spec on top of upstream NuvioDesktop)

Describes ONLY what this patch adds or changes. It's the reference for re-applying and
re-verifying the patch after an upstream update. Keep it in sync with the code.

Upstream base: NuvioMedia/NuvioDesktop `Dev` @ `083921cf` (2026-09-27).

## 1. Behaviour
State after Phase 5 (details in §4 per phase; the toggle is Phase 6, until then `NUVIO_RR_ENABLE=1`):
- At mpv's `on_preloaded` hook (before VO init) the glue reads the fps and the NVIDIA driver's Max Frame Rate, Kotlin
  decides (`decide()`: highest k·fps mode, 1000/1001 tolerance, same resolution + bpc; none ⇒ no switch; a frame cap
  below 1.05 × target ⇒ no switch), switches with `CDS_FULLSCREEN`, settles, verifies.
- Switched (or already at the target) ⇒ `video-sync=display-resample`, `interpolation=no`,
  `display-fps-override=<exact rate>` on that player before the hook continues; otherwise no mpv option is touched.
- Mid-playback: monitor off/on ⇒ re-switch once (HDR toggles: up to 3), timing re-applied; given up / window moved ⇒
  restore and put the saved mpv values back (playback continues); display-resample clearly broken (health check) ⇒
  mpv's own timing, the mode stays.
- Restore on player screen gone, window close, JVM exit; Windows reverts on crash/kill (D7).

Original summary of intent:
- Opt-in setting "Match display refresh rate" (Playback settings, Windows), default OFF.
  OFF ⇒ identical to upstream.
- On playback start: fps → target mode (integer multiple, 1000/1001 tolerance, same
  resolution/bit depth, monitor hosting the player) → switch while held → settle →
  display-synced mpv timing → play. Rate constant for the session.
- Restore on end/close/exit/crash/kill. Never persisted as the Windows default.

## 2. Upstream files touched (hooks)
Every hook line ends with a `nuvio-rr fork hook Hn` comment (grep for it after a rebase).
| File | Hook | Lines |
|---|---|---|
| `composeApp/src/desktopMain/native/windows/player_bridge.cpp` | H1 `#include "display_mode_matcher.cpp"` after `mpvApi()` | 1 |
| same | H2 `nuvio_rr::onMpvInitialized(this, mpv, containerHwnd)` in `startMpv()`, after the `mpv_initialize` check, before `loadfile` | 1 |
| same | H4 `nuvio_rr::onMpvEvent(...)` in `drainMpvEvents()`, after `waitEvent` | 1 |
| same | H5 `nuvio_rr::onPlayerShutdown(this)` first line of `shutdown()` | 1 |
| `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/PlayerEngine.desktop.kt` | H6 `RefreshRateMatch.onScreenGone()` (fully qualified, no import) first line of `DisposableEffect(host).onDispose` | 1 |
| `composeApp/src/desktopMain/kotlin/com/nuvio/app/Main.kt` | H8 `RefreshRateMatch.onAppExit()` (fully qualified) first line of `onCloseRequest` | 1 |

## 3. New files
| File | Purpose |
|---|---|
| `composeApp/src/desktopMain/native/windows/display_mode_matcher.cpp` | Native side (`namespace nuvio_rr`, `#include`d by `player_bridge.cpp`; closes/reopens its anonymous namespace for `<dxgi1_2.h>` and the JNI exports; `#pragma comment(lib, "dxgi.lib")`). Phase 2/2b: measure-only sampler + knobs. Phase 4: feature config (`NUVIO_RR_ENABLE`, `NUVIO_RR_FAULT`), `refresh-rate.log` sink, player registry, `on_preloaded` hook worker + JNI upcall, Win32 port (QDC query, DXGI modes, CDS switch + settle, restore), JNI exports for `NativeDisplayPort`. Phase 5: timing apply/revert with saved values (`applyDisplaySyncLocked`, `setTiming`), `timingStats`, read-only NVAPI DRS read (`readDriverSettings`), fault kinds `timing-set`/`drop-mode`, query-failure log once per change |
| `.../refreshrate/ResampleHealth.kt` | pure health rule for display-synced timing (P5-11) |
| `composeApp/src/desktopTest/kotlin/.../refreshrate/ResampleHealthTest.kt`, `.../runtime/RefreshRateTimingTest.kt` | Phase 5 tests (P5-7, P5-10, P5-11) |
| `.../refreshrate/runtime/DisplayPort.kt` | `DisplayPort` interface (the Win32 side) + `StartInput` (P4-5..P4-9) |
| `.../refreshrate/runtime/RefreshRateController.kt` | runs `decide()`/`step()` against a `DisplayPort`, every command to its result; fail-safe; watcher with two-read debounce; monitor move (Q15) (P4-13..P4-17) |
| `.../refreshrate/runtime/RefreshRateDispatcher.kt` | one daemon thread `nuvio-rr`, bounded waits, 1 s watcher (P4-13, P4-16) |
| `.../refreshrate/runtime/NativeCodec.kt` | `long[]` layouts across JNI |
| `.../refreshrate/runtime/NativeDisplayPort.kt` | `external` functions implemented in `display_mode_matcher.cpp` |
| `.../refreshrate/runtime/RefreshRateMatch.kt` | process-global entry points: native upcall, H6, H8, JVM shutdown hook; Kermit tag `RefreshRateMatch` |
| `composeApp/src/desktopTest/kotlin/.../refreshrate/runtime/*Test.kt`, `FakeDisplayPort.kt` | Phase 4 controller, dispatcher and codec tests |
| `composeApp/src/desktopMain/kotlin/.../player/desktop/refreshrate/RefreshRateModels.kt` | `Rational` (exact rates), `DisplayMode`, `DisplayState` |
| `.../refreshrate/FpsSnapper.kt` | fps → standard rate, container first / estimate fallback, cross-check (P3-6..P3-9) |
| `.../refreshrate/ModeSelector.kt` | target-mode selection and the per-start decision `decide()` (P3-10..P3-15) |
| `.../refreshrate/FailSafePolicy.kt` | `FailureKind` codes and the failure → action rule (P3-23) |
| `.../refreshrate/RefreshRateSession.kt` | pure session state machine: `step(session, event)` → state + commands + timing (P3-16..P3-24) |
| `composeApp/src/desktopTest/kotlin/.../refreshrate/*Test.kt`, `RefreshRateFixtures.kt` | Phase 3 unit, table and seeded fuzz tests |

## 4. Acceptance criteria (per step; written before each step's code)
Format: `ID — statement — how checked (auto / [HUMAN])`.

### Phase 0
- P0-1 — Unmodified fork builds `player_bridge.dll` and compiles desktop Kotlin — auto (`verify.ps1 -Full`)
- P0-2 — Existing `desktopTest` suite passes on the unmodified fork — auto
- P0-3 — `verify.ps1 -Fast` and `-Full` exit 0 on the unmodified fork and non-zero on an induced failure — auto
- P0-4 — The app launches and plays a video on the owner's PC — [HUMAN]
- P0-5 — A dev run (`scripts/run-dev.ps1`) writes nothing to the official `%APPDATA%\Nuvio` — auto (file timestamps; verified 2026-09-27: 0 files)

Phase 0 results (2026-09-27): P0-1 PASS, P0-2 PASS (1345 tests; 6 known upstream failures,
`scripts/known-upstream-test-failures.txt`), P0-3 PASS (green clean; exit 1 on an induced test
failure and an induced compile error), P0-5 PASS, P0-4 PASS (owner, 2026-09-28: picture, sound, seek, fullscreen OK).

### Phase 2 — Measure only (written 2026-09-28, before code)
Scope: measure, no behaviour change. Upstream base after rebase: `Dev` @ `fe92d414`.
Everything new is gated by env `NUVIO_RR_MEASURE=1` (set only by `scripts/measure.ps1`).

**Rebase**
- P2-0 — Branch rebased (not merged) onto `upstream/Dev` `fe92d414`; `verify.ps1 -Full` green with only the
  known upstream failures (7: the original 6 + `HomeHeroSectionTest`, owner-approved 2026-09-28); nothing pushed (`git status -sb` shows no upstream tracking on origin) — auto

**Measurement sampler (native, new file)**
- P2-1 — New file `composeApp/src/desktopMain/native/windows/display_mode_matcher.cpp` (`namespace nuvio_rr`).
  Upstream edits = exactly 2 added lines in `player_bridge.cpp`: H1 `#include` after `mpvApi()`, H4 one call per
  `waitEvent` return in `drainMpvEvents` (incl. timeouts). No other upstream file changed, no deleted/modified
  upstream line — auto (`verify.ps1 -Full` diff report + `git diff upstream/Dev --stat`)
- P2-2 — With `NUVIO_RR_MEASURE` unset, the sampler does nothing: no file written, no mpv option/property set,
  no log request, no thread, no Win32 call. The env var is read once per process — auto (verifier reads the code:
  every entry point returns before any side effect; a dev run without the env creates no `nuvio-rr-*` file)
- P2-3 — With `NUVIO_RR_MEASURE=1`, one log per player instance under `%LOCALAPPDATA%\Nuvio\Cache\nuvio-rr\`
  containing (a) mpv's own log at level `v` (via `mpv_request_log_messages`), and (b) a sample line at ≥ 1 Hz
  while a file is loaded with: wall time, `time-pos`, `pause`, `container-fps`, `estimated-vf-fps`, `display-fps`,
  `estimated-display-fps`, `vsync-ratio`, `vsync-jitter`, `frame-drop-count`, `decoder-frame-drop-count`,
  `mistimed-frame-count`, `vo-delayed-frame-count`, `video-sync`, `display-sync-active`, `hwdec-current`,
  `video-params/gamma`, `video-params/primaries`, plus the Windows mode of the player's monitor from
  `QueryDisplayConfig` (exact rational Hz, HDR on/off, bpc). Missing properties are logged as `na`, never crash — auto
- P2-4 — (reworded with owner approval 2026-09-28) Every sampler call on the mpv handle runs on the existing mpv
  event thread; the sampler makes no mpv or Win32 call once the player is stopping, and its state ends with that
  thread. The per-sample cost is logged (`cost_ms`) and stays ≤ 50 ms except right after a display mode change
  (Windows display queries can stall then). The only extra thread is the measure-only switch knob's (P2-5), which
  never touches mpv — auto (code review) + auto (log: max gap between sample starts ≤ 1.5 s during playback; `cost_ms`)
- P2-5 — Measure-only knobs, each read only when `NUVIO_RR_MEASURE=1`: `NUVIO_RR_MEASURE_DIR=<dir>` (log folder;
  measure.ps1 uses the run folder), `NUVIO_RR_MEASURE_IPC=1` (mpv `input-ipc-server` on `\\.\pipe\nuvio-rr-<pid>`, so
  measure.ps1 can pause/seek; DIR and IPC added 2026-09-28 after key injection proved unreliable, owner-approved), `NUVIO_RR_MEASURE_SYNC=<video-sync mode>`
  (sets `video-sync` for the power check) and `NUVIO_RR_MEASURE_SWITCH_HZ=<hz>` (switches the player's monitor with
  `ChangeDisplaySettingsExW(..., CDS_FULLSCREEN)` after file load, never `CDS_UPDATEREGISTRY`; used only for kill
  test J and the display-fps re-detection check). The registry mode stays 280 Hz throughout — auto (registry read
  before/after in measure.ps1)

**Test clips**
- P2-6 — `scripts/gen-testclips.ps1` (committed) writes to `testdata/` (git-ignored via a new `testdata/.gitignore`,
  no upstream `.gitignore` edit): 23.976, 24, 25, 29.97, 50, 59.94, 60 fps + 1 VFR; each in SDR (BT.709) and HDR10
  (PQ, BT.2020, mastering-display + MaxCLL/MaxFALL); 1080p and 2160p; HEVC Main 10, `yuv420p10le`; 120–180 s;
  constant-speed horizontal pan of a high-contrast pattern with burned-in frame number and timecode; pan distance per
  clip = whole pattern periods so it loops seamlessly — auto (`ffprobe` check in the script: fps, codec/profile,
  pix_fmt, colour tags, HDR side data, duration; VFR clip has ≥ 2 distinct frame durations)
- P2-7 — Loop is seamless: last→first frame continues the pan with no jump — [HUMAN] (one looped 1080p clip)
- P2-8 — `docs/research/08-test-samples.md` lists where to get one DV profile 5, one profile 8 and one HDR10+ sample
  (links, licence/ok-to-download notes) — auto (file exists, 3 sources)

**measure.ps1**
- P2-9 — `scripts/measure.ps1 -Clip <name> -Seconds N [-Fullscreen] [-PresentMon] [-Power] [-Sync <mode>]`
  launches the dev build through the isolated profile (upstream's `nuvio.desktop.smokePlayerUrl` harness, `file:///`
  clip, `NUVIO_RR_MEASURE=1`), plays N s, closes the app normally, and writes `measurements/<timestamp>-<clip>/`
  (git-ignored) with: the sampler log, the Windows rate + HDR + registry mode before / during (1 Hz) / after,
  a summary JSON (display-fps median, drops/mistimed/delayed per minute, final rate), and, with switches, a PresentMon
  CSV + `PresentMode` histogram and `nvidia-smi` power/clock at 1 Hz. Exits non-zero if the app fails to start or the
  desktop is not at 279.961 Hz afterwards — auto
- P2-10 — Repeatability: 3 runs each of 23.976, 25 and 59.94 fps SDR 1080p (120 s, windowed, feature absent) agree:
  `display-fps` within ±0.05 Hz, drop/mistimed/delayed counts within max(2, 20 %) between runs, rate 279.961 before/
  during/after in all 9 runs — auto (summary JSONs compared by a `-Compare` mode)
- P2-11 — measure.ps1 writes nothing to the official `%APPDATA%\Nuvio` — auto (timestamps, as P0-5)

**Kill test (research 01 §7) — decides the Phase 4 crash design**
- P2-12 — `observer.exe`, `switcher.exe`, `restore.exe` sources in `scripts/rr-tools/` (committed, fork tooling, built
  with the existing MSVC toolchain; so they can be re-run after driver updates). Only `cds`/`sdc` without
  save/registry flags; only DXGI-listed rates (240, 120, 100) — auto (code review)
- P2-13 — Cases A–J run with the owner, each recorded in `docs/research/09-kill-test.md` with: API return time,
  `WM_DISPLAYCHANGE` time, first stable QDC time, revert yes/no + delay, HDR kept, bpc kept, exact Hz reached
  (does "240" give 239.901?), owner's visible-blank/brightness-pop note — auto (file complete) + [HUMAN] observations
- P2-14 — After the session the desktop is at 279.961 Hz, HDR on, 10 bpc, registry mode 280 — auto
- P2-15 — Decision recorded in PROGRESS.md: B–E revert ⇒ rely on CDS_FULLSCREEN + explicit restore paths;
  otherwise the next-launch marker fallback, and a watchdog proposal to the owner — auto (entry exists)

**Baseline findings (recorded, not pass/fail)**
- P2-16 — PROGRESS.md "Measurements" gets the baseline table (P2-10 runs) for 23.976 / 25 / 59.94 — auto
- P2-17 — Present mode of the player, windowed and app-fullscreen, from PresentMon (`Composed: Flip` vs
  `Independent Flip`), and whether `MsBetweenDisplayChange` stays quantized to 1/279.961 s while playing, paused,
  seeking and with the controls animating ⇒ VRR engaged yes/no (decides the Phase 5 mitigation) — auto (CSV) +
  [HUMAN] monitor OSD refresh reading while paused in fullscreen
- P2-18 — Display-fps re-detection: with `NUVIO_RR_MEASURE_SWITCH_HZ=240`, record whether/when mpv's `display-fps`
  moves from ~280 to ~239.9 without `display-fps-override` (answers R8's WM_DISPLAYCHANGE question) — auto (log)
- P2-19 — GPU power/clock: 23.976 fps HDR10 2160p, 120 s, `video-sync=display-resample`, at 240 vs 120 Hz (mode set
  by `switcher.exe`), plus audio-sync at 280 as reference ⇒ median power per case (Q8 downside check) — auto

**[HUMAN] checklist for this phase (one sitting)**
1. Kill-test observations (P2-13): blank length, brightness pop, monitor OSD Hz per case.
2. OSD refresh reading while paused in fullscreen (P2-17).
3. One looped clip: seamless loop, frame counter readable (P2-7).

### Phase 2b — Display-resample diagnosis spike (written 2026-09-28, before code; D11)
Scope: find out whether `video-sync=display-resample` can be made to work in the embedded player, and how. Measure-only:
no product behaviour, no upstream edits beyond H1/H4, the display mode is held by `switcher.exe` (239.901) or left at
279.961. Every new knob is read only when `NUVIO_RR_MEASURE=1`. Base: `fe92d414` (rebase only if upstream moved, not required).

**Knobs and tooling**
- P2b-1 — New measure-only knob `NUVIO_RR_MEASURE_OPTS="k=v;k=v"`: each pair is set with `mpv_set_option_string`
  before the file loads (same point as `NUVIO_RR_MEASURE_SYNC`), and each result code is written to the log
  (`opt k=v rc=N`). A failed option is logged and skipped, never fatal. With the knob unset, nothing changes vs Phase 2
  — auto (code review + log lines in every P2b run)
- P2b-2 — New measure-only knob `NUVIO_RR_MEASURE_HIDE_OVERLAY=1`: after file load, hides the WebView2 overlay's
  child window for the run: `EnumChildWindows(containerHwnd)`, every direct child whose class is not mpv's (`mpv`)
  gets `ShowWindowAsync(SW_HIDE)` (async: the windows belong to the UI thread; the mpv event thread must not block).
  Both mpv (`wid`, PB:1667) and the WebView2 controller (PB:1522) are children of `containerHwnd`, so no new upstream line.
  The log records that it was found and hidden — auto (log + code review). If this needs an extra upstream line,
  stop and ask the owner first (upstream diff stays exactly 2 lines otherwise)
- P2b-3 — Upstream diff is still exactly the 2 Phase 2 lines (H1, H4); P2-2 still holds with `NUVIO_RR_MEASURE` unset
  (no new side effect outside measure runs) — auto (`verify.ps1 -Full` diff report)
- P2b-4 — `measure.ps1` gets `-Opts "<k=v;...>"` and `-HideOverlay`, recorded in `summary.json`, plus a
  **cadence** section computed from the PresentMon CSV (java.exe display changes): refreshes-per-video-frame histogram
  and % of frames at the ideal count (10 at 239.901, for 23.976 fps), std and mean |dev| of frame hold in ms. The
  analysis reproduces the Phase 2 fixed-240 result from its CSV (`*-fixed240`: 10 ×1476 / 11 ×250 / 9 ×238, ±5)
  — auto (run on the old folder)
- P2b-5 — `summary.json` for a resample run also has: `estimated-display-fps` median, `vsync-jitter` median, drops +
  mistimed per minute, audio underrun count (from the mpv log), median `MsInPresentAPI`, PresentMode histogram, and GPU
  power/clock median when `-Power` — auto
- P2b-6 — `verify.ps1 -Full` green with only the 8 known upstream failures; nothing written to the official profile
  (both folders); nothing pushed — auto

**Experiments (each: one knob set, one 120 s run of `sdr-1080p-23.976`, windowed AND fullscreen, PresentMon on)**
- P2b-7 — S1: display-resample at a fixed 239.901 (mode set by `switcher.exe` before the app starts), default options
  — auto (runs exist, numbers in the report)
- P2b-8 — S2: render cost — at least `scale=bilinear;cscale=bilinear;dscale=bilinear`, `deband=no`, and both together,
  with nvidia-smi clocks; if the GPU stays in a low power state, one run with a GPU-load-independent check of the
  power-state question (e.g. an extra GPU load running alongside, noted) — auto
- P2b-9 — S3: swapchain/present — at least `d3d11-flip=no`, `swapchain-depth=1` and `=2`, `d3d11-sync-interval=1`
  (check the option exists in the bundled mpv first; an unknown option is recorded as such), `video-timing-offset=0`,
  comparing `MsInPresentAPI` and PresentMode — auto
- P2b-10 — S4: windowed collapse — `-HideOverlay` windowed run; mpv log searched for occlusion/throttle messages
  (`occluded`, `DXGI_STATUS_OCCLUDED`, `Present` errors) — auto
- P2b-11 — S5: `video-sync=display-vdrop`, `display-desync`, `display-resample-vdrop` at 239.901 — auto
- P2b-12 — Stop rule: stop early at the first option set that meets P2b-13; stop and report to the owner if the spike
  reaches ~250k tokens or ~40 runs without a pass — auto (report states tokens and run count)

**Pass / fail (the spike's result, not a gate on the code)**
- P2b-13 — **Spike PASS** if one option set gives, at 239.901, over 120 s, in BOTH windowed and fullscreen:
  `estimated-display-fps` within 0.1 % of 239.901; drops + mistimed ≤ 1 per minute **counted after the first 5 s of
  playback** (owner-approved change 2026-09-28: every mistime in the clean runs falls in the first ~1.2 s, see
  docs/research/10 "startup mistimed frame(s)"); 0 audio underruns after the first
  5 s; PresentMon: ≥ 99 % of video frames held exactly 10 refreshes (**deferred 2026-09-28**: needs a UAC click, owner not
  at the PC; PresentMon also perturbs display-sync timing here ⇒ re-check in Phase 5 with the owner present). Confirmed by one repeat run of the winning set
  (same limits) and one run with `hdr-2160p-23.976` (same limits) with GPU power recorded (Q8) — auto
- P2b-14 — If PASS: [HUMAN] one slow-motion video (iPhone Slo-mo, same setup as `IMG_3036`) of the winning set in
  fullscreen, analysed with `scripts/rr-tools/slomo-analyze.py`; expected: clearly narrower hold spread than the
  Phase 2 fixed-240 video (std 2.65 ms). Optional; the owner may waive it
- P2b-15 — If FAIL: the report names the most likely cause with evidence and lists every option set tried with its
  numbers; the owner chooses (a) switch + audio sync only, (b) a deeper fix (e.g. libmpv render API), or (c) stop
- P2b-16 — Output: `docs/research/10-display-resample-spike.md` (table: option set × window mode × numbers, sourced vs
  inferred marked); PROGRESS.md Measurements + a decision entry; if PASS, the winning options become the Phase 5 plan
  input (PLAN.md Phase 5 line updated) — auto (files exist)

**Verification:** ONE lean verifier round: this criteria table + the list of measurement folders + the diff. No
second round unless it finds a real problem in the knobs (P2b-1..3).

**[HUMAN] checklist for this phase:** click Yes on the PresentMon UAC prompts (one per run); optionally P2b-14.

### Phase 3 — Pure decision logic, tests first (written 2026-09-28, before code)
Scope: Kotlin logic only, in `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/refreshrate/`,
tests in the same package under `desktopTest`. Nothing calls it yet (Phase 4 wires it), so the app behaves exactly as
after Phase 2b. File and class names are the implementer's choice; SPEC §3 lists them at the end of the phase.
Rates are exact rationals (numerator/denominator as `Long`, as DXGI/QDC report them); Hz as `Double` only for logging.
Test mode list = the owner's measured modes at 2560x1440, 10 bpc, progressive: 279961/1000, 239901/1000, 143973/1000,
119998/1000, 10000/100, 59951/1000 (plus a duplicate 59951/1000, as GDI's 59/60 alias).

**Isolation and footprint**
- P3-1 — The package imports nothing from Win32/JNI/AWT/Compose/JNA/`java.io`/`java.nio`/threads/clock/logging, and has
  no `external` functions, no global mutable state and no I/O: every function is deterministic for its inputs — auto
  (grep of the package's imports + code review)
- P3-2 — Upstream diff unchanged: still exactly the 2 Phase 2 lines (H1, H4) in `player_bridge.cpp`; no upstream Kotlin
  file touched; no new dependency (Gradle files untouched) — auto (`verify.ps1 -Full` diff report)
- P3-3 — `verify.ps1 -Full` green with only the 8 known upstream failures; `-Fast` runs the new tests; nothing written to
  the official profile (both folders); nothing pushed — auto
- P3-4 — Tests first: commit A adds the tests + signatures that throw `NotImplementedError`, and `verify.ps1 -Fast` is
  red on it (output saved); commit B adds the implementation and is green. Any change to test files between A and B is
  listed in the commit message with a reason and never removes or loosens an assertion — auto (`git diff A B -- desktopTest`)
- P3-5 — Total: no public function throws for any input (NaN, ±∞, 0, negative, zero or negative denominators, empty or
  duplicate mode lists, unknown display); bad entries are skipped. Checked by a seeded fuzz test (≥ 10 000 random inputs
  per public function) — auto

**Fps → standard rate (container first, estimate as fallback)**
- P3-6 — Standard set: 24000/1001, 24, 25, 30000/1001, 30, 48000/1001, 48, 50, 60000/1001, 60. An input fps snaps to the
  nearest member if within ±0.1 % (relative), else it is "not standard" — auto. Required cases: 23.976023976, 23.976,
  23.98 → 24000/1001; 24.0, 24.02 → 24; 25.02 → 25; 29.97 → 30000/1001; 47.952 → 48000/1001; 59.94 → 60000/1001;
  23.90, 23.810 (Kodi #28836 mkv value), 12, 15, 100, 119.88, 120, 144, 1000, 90000 → not standard
- P3-7 — Source order: a valid container fps that snaps is used (source = container). If the container value is missing
  (null/NaN/≤ 0) or does not snap, a valid estimated fps that snaps is used (source = estimate). Otherwise no switch,
  reason `fps-missing` (neither valid) or `fps-not-standard` — auto
- P3-8 — Cross-check: when both are valid and the container value snapped, an estimate more than 0.5 % from the snapped
  rate ⇒ no switch, reason `fps-disagree` (VFR / bad header); within 0.5 % ⇒ agreed. A separate after-start check
  (snapped rate vs a later estimate) returns agree / disagree / unavailable with the same 0.5 % limit — auto
- P3-9 — Image/album-art tracks ⇒ no switch, reason `image` — auto

**Mode selection**
- P3-10 — Candidates: same width, height and bits per colour channel as the current mode, progressive, valid rate
  (numerator and denominator > 0); duplicates (equal rationals) count once. A candidate r fits fps f when
  k = round(r/f) ≥ 1 and |r/(k·f) − 1| ≤ 0.1 %. Pick the fitting mode with the highest k; ties at the same k go to the
  smallest relative error — auto
- P3-11 — Owner's list, current 279.961: 23.976 / 24 / 29.97 / 30 / 47.952 / 48 / 59.94 / 60 → 239901/1000 (k = 10, 10, 8,
  8, 5, 5, 4, 4); 25 / 50 → 10000/100 (k = 4, 2); every "not standard" rate in P3-6 → no switch. This is the table in the
  project brief (requirement 3) — auto
- P3-12 — 1000/1001 twins (synthetic 1920x1080 list 60/1, 59940/1000, 120/1, 119880/1000): 23.976 → 119880/1000;
  24 → 120/1; 59.94 → 119880/1000; 60 → 120/1; 25 → no switch, reason `no-suitable-mode` — auto
- P3-13 — Filters: a 239.901 mode offered only at 8 bpc (current 10 bpc) is not chosen, so 23.976 → 143973/1000; an
  interlaced mode is never chosen; other resolutions are ignored; an empty or all-invalid list ⇒ `no-suitable-mode` — auto
- P3-14 — Current mode already equal to the chosen target (exact rational equality) ⇒ no switch, result "already at
  target" carrying that rate (so display-synced timing can still be used without a switch). A fitting but lower current
  mode (e.g. 119.998 for 24 fps) still switches to the highest (239.901) — auto
- P3-15 — Feature disabled ⇒ result `disabled` for every input, before any other rule — auto

**Session state machine (process-global in Phase 4; pure here)**
States: idle, switching, switched, restoring. Events: playback start (player id, display id, current display state incl.
HDR + bpc, selection result), switch finished (ok + observed state, or failed + failure kind), restore finished (ok /
failed), player screen gone (player id), app exit, display change observed (display id, observed state). Each
transition returns the new state and a list of commands: switch(display, mode), restore(display), and a timing
instruction: display-sync(exact rate) or upstream (no mpv option change).
- P3-16 — Every (state × event) pair has a defined result; one table-driven test asserts new state + commands for all
  24 pairs — auto
- P3-17 — At most one display command is outstanding at any time: an event that needs a new switch/restore while one is
  in flight is deferred and handled, after the in-flight result, as if it had arrived in the resulting state — auto
- P3-18 — Same-target skip (requirement 7): start with the same display and target as the current switched session ⇒ no
  command, timing display-sync, ownership moves to the new player. A different target on the same display ⇒ one switch
  straight to the new mode (no restore in between). Start on a different display ⇒ restore the old one, then switch the
  new one — auto
- P3-19 — Start with "no switch" (any reason) while switched ⇒ restore, timing upstream. Start with "already at target"
  while idle ⇒ no command, timing display-sync — auto
- P3-20 — Restore bookkeeping: switched always records the display, the original state (rate, HDR, bpc) and the owner.
  Screen gone from the owner ⇒ restore; from a non-owner ⇒ ignored (two players, P3-18 ownership). App exit while
  switched or switching ⇒ restore (after the in-flight switch, P3-17); playback starts still queued behind the exit are
  dropped, so nothing switches after exit (added after verifier round 1). After restore finished (ok or failed) ⇒ idle,
  nothing recorded — auto
- P3-21 — Switch verification: switch finished ok is accepted only if the observed rate equals the target (relative
  difference ≤ 1e-6) and HDR and bpc equal the original; otherwise restore + timing upstream, reason `verify-mismatch`
  — auto
- P3-22 — (Q13, owner 2026-09-28: re-switch) Display change observed while switched on that display (kill-test case H,
  monitor off/on): observed rate ≠ target ⇒ **one re-switch** to the same target (state switching, timing upstream
  until verified, then display-sync), reason `mode-lost`. The original state recorded at the first switch is kept. At
  most one re-switch per playback start: a second loss in the same playback ⇒ idle, no restore, no re-switch, timing
  upstream, reason `mode-lost-again`. A failed re-switch follows P3-23. Same rate ⇒ no command (an HDR-only change is
  reported, reason `hdr-changed`) — auto. **Amended by P4-22** (owner, option B): a change whose HDR or bpc differs from
  the recorded original is an HDR toggle, handled before these rules.

**Fail-safe (requirement 10: any failure ⇒ keep playing at the current rate)**
- P3-23 — Failure kinds: enumerate failed, display not found, switch API error, settle timeout, stop requested during
  switch, verify mismatch, restore failed, unexpected error. Before a switch was attempted ⇒ no command, timing
  upstream. After a switch was attempted (any failure) ⇒ restore (idempotent: restores to the registry mode) + timing
  upstream. Restore failed ⇒ idle, no retry (Windows reverts at process exit, D7), reason `restore-failed`. One test
  per kind — auto
- P3-24 — Seeded random event sequences (≥ 10 000 sequences of ≤ 30 events, fixed seed): never throws; invariant P3-17
  holds; timing is display-sync only in switched or "already at target"; every sequence ending with app exit + all
  in-flight results delivered ends idle, and every display still in our switched mode got a restore command (a display
  that went `mode-lost-again` needs none) — auto
- P3-25 — Every no-switch, failure and state-change result carries a stable lowercase reason code (as named above) plus
  the values behind it (fps in/snapped, source, k, error ppm, modes considered), so Phase 4 can log one clear line — auto

**Verification:** `verify.ps1 -Full`, then ONE lean verifier round (this table + the two commit ids + test report path).
No [HUMAN] items this phase.

### Phase 4 — Native switching + wiring (written 2026-09-28, before code; owner-approved 2026-09-28 with Q14–Q16)
Scope: wire the Phase 3 logic to Win32 and mpv. Switch, verify, settle, every restore path, races, fail-safe, log.
**Not in Phase 4:** mpv timing (Phase 5 applies `Timing`; here it is logged only, so playback at 240 Hz still uses
upstream audio sync, i.e. the "fixed 240" cadence measured in Phase 2), the settings toggle (Phase 6).
Design (from PLAN.md, refined): native glue registers mpv's `on_preloaded` hook (mpv holds playback there, so no pause
hack is needed) → a glue-owned worker reads fps + the display → JNI upcall into one Kotlin holder object → `decide()` +
`step()` → the holder runs each `Command` through a `DisplayPort` interface (native `external fun`s in production, a
fake in tests) and feeds the results back → the worker continues the hook. Kotlin events (screen gone, app exit,
display watcher) go through the same holder on one thread.

**Enable, footprint, tests first**
- P4-1 — Dev enable knob until Phase 6: the feature runs only when the process starts with `NUVIO_RR_ENABLE=1`
  (run-dev.ps1 / measure.ps1 `-Feature`). Unset ⇒ no mpv hook registered, no display API call, no thread started, no JNI
  upcall, no log output, mpv options unchanged (upstream behaviour). The Phase 2 measure-only knobs keep working — auto
  (code review: every entry returns first; a measure run without the knob shows 279.961 throughout and no `[nuvio-rr]`
  feature lines)
- P4-2 — Upstream diff ≤ 8 changed lines in total: `player_bridge.cpp` H1 + H4 (existing) + ≤ 2 new (hook registration
  between `mpv_initialize` and `loadfile`; H5 cancel at the start of `shutdown()`); `PlayerEngine.desktop.kt` ≤ 2 (H6,
  screen gone); `Main.kt` ≤ 1 (H8, window close). Each carries a `nuvio-rr fork hook Hn` comment. `NativePlayerController.kt`,
  `NativePlayerBridge.kt`, the `create()` signature and all Gradle files untouched; no new dependency — auto (`verify.ps1 -Full`)
- P4-3 — `verify.ps1 -Full` green with only the 8 known upstream failures; nothing written to either official folder;
  nothing pushed — auto
- P4-4 — Tests first for the Kotlin holder/orchestration (driving `step()`, running commands on a fake `DisplayPort`,
  serialising events, timeouts): commit A = tests + stubs, red; commit B = implementation, green; same rules as P3-4.
  The Phase 3 test files are unchanged — auto (`git diff`)

**Win32 layer (native, `display_mode_matcher.cpp`)**
- P4-5 — Display = the monitor hosting the player: `MonitorFromWindow(GetAncestor(container, GA_ROOT), NEAREST)` →
  GDI device name, read at each start (so PiP / fullscreen on another monitor is followed). Mode list and current state
  come from `QueryDisplayConfig` / DXGI as exact rationals with width, height, bpc, interlaced and HDR. On the owner's PC
  the list is exactly the 6 measured rates at 2560x1440 10 bpc (PROGRESS Measurements) — auto (log line in a measure run)
- P4-6 — Switch = only `ChangeDisplaySettingsExW(device, &dm, NULL, CDS_FULLSCREEN, NULL)` (DEVMODE Hz = the target's
  rounded rate, the exact rational is checked afterwards); restore = only `ChangeDisplaySettingsExW(device, NULL, NULL,
  0, NULL)`. No `CDS_UPDATEREGISTRY`, no `SetDisplayConfig`, no registry writes: the registry rate reads 280 before,
  during and after every run — auto (grep + measure.ps1 `regHz`)
- P4-7 — Every display API call checks its result and logs function, arguments, result and `GetLastError`/`DISP_CHANGE_*`
  code; each failure maps to one `FailureKind` and goes through `step()`. No C++ exception or JNI exception escapes the
  glue (caught ⇒ `unexpected-error`) — auto (code review + P4-19)
- P4-8 — Settle: after a switch, poll mode + HDR every 100 ms until two consecutive reads agree; cap 4 s ⇒
  `settle-timeout`; abort within 100 ms when the player stops ⇒ `stop-requested`. The observed state goes into
  `SwitchFinished` (P3-21 verify). Measured switch-to-settled time is logged per switch — auto

**mpv glue**
- P4-9 — The hook is registered before `loadfile` (no race with the first file). At the hook the glue reads
  `container-fps`, `estimated-vf-fps` and the current video track's image/album-art flag. The hook is continued exactly
  once on every path (switch, no switch, failure, stop, exception) and never later than 5 s after it fired. The mpv event
  thread never makes a display call, a JNI upcall or a wait (it only hands work to the worker) — auto (log timestamps in
  measure runs + code review)
- P4-10 — mpv starts at the new rate: `sdr-1080p-23.976` with the feature on ⇒ the switch line is logged before the
  first video frame, Windows reads 239.901 during playback, and mpv's `display-fps` is 239.901 (± 0.01 %) from the first
  stats line — auto (measure.ps1). Same for 25 fps ⇒ 100.000; 59.94 ⇒ 239.901; VFR clip ⇒ decided by its container header like any file (Q18, owner 2026-09-28: the
  test clip says 60 fps ⇒ 239.901; no estimate exists at on_preloaded and there is no mid-playback switch, Q11)
- P4-11 — No mpv timing change in Phase 4: the `Timing` result is logged, not applied; with the feature on, the only mpv
  difference from upstream is the hook — auto (code review + options log)
- P4-12 — Dispose mid-switch: `shutdown()` (H5) cancels the worker and waits ≤ 1 s for it to let go of the mpv handle,
  so nothing touches mpv after `mpv_terminate_destroy` and the 3 s join (F7) is never hit. The session still gets its
  `SwitchFinished` and restores if the screen is gone. Checked with 20 runs that close the window at random 0–1500 ms
  after the switch starts (with the P4-19 slow-settle knob): no crash / WER report, exit normal, 279.961 after — auto

**Kotlin holder and restore paths**
- P4-13 — One process-global holder owns the `Session` and runs all events on one thread (`nuvio-rr`), so `step()` calls
  never overlap and P3-17 holds for real. The EDT and the mpv event thread never wait on it, except window close, which
  waits ≤ 2 s for its restore. The player id is a per-player counter assigned natively at H2 (`p1`, `p2`… in the
  log; corrected during implementation: the JNI handle is a heap pointer that can be reused, so it is not used) — auto (unit tests with the fake port + review)
- P4-14 — Restore paths: (a) player screen gone (H6, `DisposableEffect(host).onDispose`); (b) main window close (H8);
  (c) a JVM shutdown hook in the holder (covers the `exitProcess` paths, ≤ 2 s); (d) crash/kill ⇒ Windows' revert (D7).
  (b) and (d) auto via measure.ps1 (window close; `-Kill`): 279.961 within 1 s, registry 280 throughout. (a) [HUMAN]
  performs (leave the player screen), log + Windows rate checked automatically
- P4-15 — Next episode (same target, requirement 7): [HUMAN] performs (play an episode, go to the next); the log shows
  one switch in total, `same-target` on the second start and no restore in between — auto from the log
- P4-16 — Display watcher: while switched, the holder reads the owner's display once per second (off the EDT and the mpv
  thread) and feeds `DisplayChanged`. Monitor off/on (case H) ⇒ one re-switch to 239.901 after the monitor returns
  (`mode-lost`); a second off/on ⇒ stays at 279.961 (`mode-lost-again`). HDR toggle (Win+Alt+B) ⇒ `hdr-changed`, no
  switch, playback continues. [HUMAN] performs; log + rate auto
- P4-17 — Player window moves to another monitor mid-playback (drag, PiP or fullscreen on another monitor): see Q15.
  The owner has one monitor ⇒ checked with the fake port only (documented as not hardware-tested) — auto
- P4-18 — Sleep/resume and a driver reset (Win+Ctrl+Shift+B) during switched playback: no crash, playback continues,
  the mode ends at 239.901 (re-switched) or 279.961, and a later exit leaves 279.961 — [HUMAN] performs; log + rate auto

**Fail-safe and logging**
- P4-19 — Fault injection, measure-only knob `NUVIO_RR_FAULT=<kind>` (enumerate, display-not-found, switch-api,
  settle-timeout, slow-settle, verify-mismatch, restore-failed, unexpected): for every kind the video plays (time-pos
  advances ≥ 10 s), no crash, and Windows is at 279.961 after the run (restore-failed: after process exit, D7) — auto
  (one measure.ps1 run per kind)
- P4-20 — Log: one line per decision, command and result, with the values behind the reason (fps in/snapped + source,
  k, target, observed vs target, HDR/bpc, ms), via Kermit tag `RefreshRateMatch` and a native `[nuvio-rr]` sink. Also
  written to `refresh-rate.log` in Nuvio's cache folder (dev runs: devprofile), ≤ 1 MB with one rotation — auto
- P4-21 — [HUMAN] one checklist for the phase: 23.976 start (≈ 1 s black, no brightness pop, HDR still on); leave the
  player (back to 280); next episode (no second black); monitor off/on; Win+Alt+B; Win+Ctrl+Shift+B; sleep/resume.
  Judder is NOT expected to be fixed yet (Phase 5)
- P4-22 — (added 2026-09-28 after the owner's checklist: toggling Windows HDR resets the temporary mode to 280, measured
  17:38 and 17:52; owner chose option B) While switched, a display change on that display whose HDR or bpc differs from the
  recorded original ⇒ the recorded original takes the new HDR and bpc (so the later restore and every verify use the
  user's current HDR choice), and, if the rate is no longer the target, **one re-switch to the same target**, reason
  `hdr-toggled`, verified against the new HDR/bpc. HDR-toggle re-switches do not use the one `mode-lost` re-switch
  (P3-22) and are capped at 3 per playback start (a 4th follows P3-22); a new playback start resets the count. Same rate +
  HDR change ⇒ original updated, no command, `hdr-changed` — auto (tests first, Phase 3 test changes listed in the
  commit) + [HUMAN] Win+Alt+B twice during a 23.976 playback ⇒ back at 239.901 each time, HDR as set (log: `hdr-toggled`,
  `switched`)

**Verification:** `verify.ps1 -Full` + the measure runs above, then ONE lean verifier round (this table, commit ids,
evidence folder list).

### Phase 5 — mpv timing + OLED stability (written 2026-09-28, before code; owner-approved 2026-09-28 with Q20 (a), Q21 yes, Q22 yes)
Scope: apply the `Timing` that Phase 3/4 already compute, i.e. display-synced mpv timing for every playback we switched,
and upstream timing everywhere else. Switch mpv back to its own timing at runtime when a session ends mid-playback. Keep
the panel rate constant (req. 9). Make the driver preconditions from Phase 2b visible.
**Not in Phase 5:** the settings toggle (Phase 6; the `NUVIO_RR_ENABLE=1` dev knob stays), Nuvio-only driver profiles
(need Phase 8's own exe, Q10c), the full matrix (Phase 7: audio device change, DV/HDR10+, sleep/resume, soaks).
Design: the hook worker applies `DisplaySync(rate)` as mpv properties before it continues `on_preloaded` (before VO
init). For timing changes outside the hook (watcher, window move), the controller calls a new `DisplayPort.setTiming(player,
timing)` → native `external fun` → mpv properties on that player. Native saves the three properties' values before the
first change on a player and puts exactly those back on revert.

**Footprint and tests first**
- P5-1 — Upstream diff unchanged: still the 6 Phase 4 lines (H1, H2, H4, H5, H6, H8). Everything new goes in
  `display_mode_matcher.cpp` and the `refreshrate` packages. No Gradle change, no new dependency (the NVAPI read in P5-9
  uses `nvapi_QueryInterface` IDs as `drsprobe.cpp` does, no SDK) — auto (`verify.ps1 -Full` diff report)
- P5-2 — `verify.ps1 -Full` green with only the known upstream failures; nothing written to either official folder;
  nothing pushed — auto
- P5-3 — Tests first for every Kotlin change (timing routing in the controller, P5-9 frame-cap rule, P5-11 health rule):
  commit A = tests + stubs, red; commit B = implementation, green, with an empty test diff between A and B. Changed Phase
  3/4 tests are listed in commit A's message with the reason — auto (`git diff`)
- P5-4 — Feature off (`NUVIO_RR_ENABLE` unset) ⇒ still no hook and no mpv property set by the feature (P4-1 holds).
  Feature on but `Timing.Upstream` at the hook (no switch, same rate, fail-safe) ⇒ **zero** mpv property calls for
  that player — auto (code review + the options line in the mpv log of a 25 fps run equals a feature-off run)

**Applying display-synced timing (native)**
- P5-5 — `DisplaySync(rate)` at the hook ⇒ before `mpv_hook_continue`, the worker sets on that player
  `video-sync=display-resample`, `interpolation=no` and `display-fps-override=<rate as decimal, ≥ 6 digits after the point>`,
  with the values read just before saved first. Each set is logged with its mpv result code. A failed set ⇒ revert what was
  already set, log `timing-failed`, continue the hook: playback continues with upstream timing (fail-safe, req. 10). The
  hook still continues exactly once and within 5 s (P4-9) — auto (log + code review; fault knob kind `timing-set` added to
  P4-19's list, one run: plays ≥ 10 s, no crash, 279.961 after exit)
- P5-6 — New `DisplayPort.setTiming(playerId, Timing): Boolean` + native export. `DisplaySync` ⇒ as P5-5 (a changed rate
  after a re-switch updates `display-fps-override` only). `Upstream` ⇒ if this player has our change, put the saved values
  back; otherwise do nothing. A player that is gone or stopping ⇒ `false`, no mpv call (the handle is checked under the
  entry lock that H5 takes, so no call after shutdown) — auto (code review + P5-8 runs)
- P5-7 — Controller routing: every `Timing` a step returns goes to the session owner, not only the hook's. Hook start ⇒
  returned to the worker (as now). Watcher, window-move and screen-gone steps ⇒ `setTiming(owner, …)` on the `nuvio-rr`
  thread (never on the EDT or the mpv event thread). Unit tests with the fake port: `mode-lost` re-switch OK ⇒
  `DisplaySync`; `mode-lost-again` ⇒ `Upstream`; `hdr-toggled` re-switch OK ⇒ `DisplaySync`; once the 3 HDR re-switches and the one `mode-lost` re-switch are used
  up (P4-22/P3-22), the next loss ⇒ `Upstream`;
  verify-mismatch on a re-switch ⇒ `Upstream`; window moved (Q15) ⇒ `Upstream` on the old owner; screen gone ⇒ no call
  needed (the player is leaving); a `setTiming` failure or exception ⇒ logged, session unchanged — auto (tests)
- P5-8 — Runtime changes work in the real player: new fault kind `drop-mode` (measure-only, part of `NUVIO_RR_FAULT`)
  drops the temporary mode natively at 20 s and again at 50 s, as a monitor off/on would. `sdr-1080p-23.976`, feature
  on, windowed, 90 s ⇒ log: 1st drop `mode-lost` re-switch ⇒ `setTiming display-sync ok`; 2nd drop `mode-lost-again` ⇒
  `setTiming upstream ok`, mpv reports the saved `video-sync` value (upstream: `audio`); playback continues to the end with
  no audio underruns and 0 drops in the last 30 s; 279.961 after exit — auto (measure.ps1)

**Driver preconditions (Phase 2b, D12)**
- P5-9 — At each playback start the native side reads, read-only, the NVIDIA driver settings that apply to this process:
  Max Frame Rate (`FRL_FPS` 0x10835002) and Power management mode (`PREFERRED_PSTATE` 0x1057EB71) — the app's own profile
  if one matches the exe, else the global profile. Only `NvAPI_Initialize`, `DRS_CreateSession/LoadSettings/
  GetBaseProfile/FindApplicationByName/GetSetting`, `DestroySession`; never `SetSetting`/`SaveSettings`. Logged as
  `driver frl=<fps|off|unknown> power=<mode|unknown> profile=<global|app> ms=<n>`. No NVIDIA GPU / nvapi missing / any
  error ⇒ `unknown`, never fatal. It runs on the hook worker (≤ 200 ms, measured) — auto (log; grep: no Set/Save IDs)
- P5-10 — Frame-cap rule (pure, in `decide()`, tests first; see **Q20**): a known Max Frame Rate below 1.05 × the chosen
  target rate ⇒ `NoSwitch("frame-cap")` (stay at 280 with upstream timing: a capped display-resample collapses to ≈ 6 Hz,
  Phase 2b). `off` or `unknown` ⇒ unchanged behaviour. Checked on hardware only if the owner sets the cap back (Q10c);
  otherwise by the unit tests — auto
- P5-11 — Health fallback (see **Q21**; drop this criterion if the owner says no): while `DisplaySync` is applied, the
  watcher reads that player's mpv counters once per second (new native read: `frame-drop-count`, `mistimed-frame-count`,
  `estimated-display-fps`, `time-pos`, `paused`; values only, no display call). Pure rule `ResampleHealth` (tests first):
  ignore the first 5 s after the hook and any 10 s window in which playback advanced < 8 s (pause, seek, buffering);
  a 10 s window with drops + mistimed > 20, or `estimated-display-fps` off the target by > 1 %, ⇒ `setTiming(Upstream)`,
  reason `resample-unhealthy`, at most once per playback start; the display stays at the target (no extra switch). Healthy
  runs (P5-12) never trigger it — auto (tests + P5-12 logs + one run with `-Opts` forcing a known-bad setup, e.g. the
  2b `d3d11-sync-interval=0` set, which must trigger it)

**Measurements (feature on, `-Feature`, 120 s each unless stated; counted after the first 5 s, P2b-13 rule)**
- P5-12 — Pass limits for every switched run below: Windows at the target during playback and 279.961 after; mpv
  `video-sync=display-resample`, `display-fps` = the override (± 0.001 Hz); `estimated-display-fps` within 0.1 % of the
  target; drops + mistimed ≤ 1 per minute; 0 audio underruns; `video-speed-correction` and `audio-speed-correction` within
  ± 0.2 %; registry 280 throughout. Runs: `sdr-1080p-23.976` windowed, fullscreen, and a repeat of each;
  `hdr-2160p-23.976` fullscreen with GPU power (max-performance power set, D12); `sdr-1080p-24`, `sdr-1080p-29.97`,
  `sdr-1080p-59.94` windowed; `sdr-1080p-vfr` windowed (switches by its header, Q18; for VFR the limit is 0 audio
  underruns and no A/V desync warning; drop/mistime counts are recorded, not judged — see **Q22**). `sdr-1080p-25`
  ⇒ no switch, upstream timing, no feature property set (Q17: no 100 Hz listed) — auto (measure.ps1 summary.json)
- P5-13 — Next episode with the same target (`same-target`): the second player also gets `DisplaySync` at its hook and
  meets P5-12. Checked by a measure.ps1 run with two consecutive files (new `-Clip a,b` or a second `loadfile`, whichever
  keeps the upstream diff at 6 lines), or by the owner in the P5-17 checklist — auto or [HUMAN]
- P5-14 — GPU power (Q8, re-measured now that resample works): median `nvidia-smi` power and clocks for
  `sdr-1080p-23.976` and `hdr-2160p-23.976`, fullscreen, feature on vs off (280 Hz audio sync), with the power mode
  recorded. Reported, not judged; the owner decides if it's material — auto
- P5-15 — PresentMon re-check (the deferred P2b-13 clause; needs the owner for the UAC click): one fullscreen and one
  windowed `sdr-1080p-23.976` feature-on run with PresentMon ⇒ ≥ 99 % of video frames held exactly 10 refreshes, and all
  display intervals on the 4.168 ms grid (VRR not engaged). The same run's mpv counters must also meet P5-12; if they
  don't (PresentMon perturbs timing, as with the 200 fps cap), the run is recorded as perturbed and P5-16's slow-mo video
  replaces the cadence check — auto + [HUMAN] UAC click

**OLED stability (req. 9)**
- P5-16 — Constant panel rate under display-resample at 239.901: in the P5-15 PresentMon runs (or, if PresentMon
  perturbs, a separate one used only for this), with pause 20 s, seek ±10 s, controls shown/hidden and fullscreen toggle
  driven through mpv IPC (`-Actions`): every display interval of java.exe is a whole multiple of 4.168 ms (± 0.25 ms),
  including the pause. Phase 2 found VRR not engaged at 280 in audio sync; this confirms it under the feature. If VRR
  does engage: stop and report (a keep-alive mitigation would be a new owner decision), no global driver change — auto
  + [HUMAN] UAC click
- P5-17 — [HUMAN] one checklist: (1) 23.976 fullscreen slow pan with the feature on: judder gone? (optional: iPhone
  slow-mo video, analysed with `slomo-analyze.py`; expected std clearly below the Phase 2 fixed-240 2.65 ms); (2) pause,
  seek, show the controls: any flicker or brightness pumping?; (3) 4K HDR clip: colours and brightness look the same as
  feature off; (4) next episode: no second black, still smooth; (5) monitor off/on mid-playback: comes back at 240 and
  smooth (log: `mode-lost` ⇒ `setTiming display-sync`)
- P5-18 — Docs: SPEC §1/§3 updated; FORK.md gets a "driver settings" section: Max Frame Rate off (or ≥ 1.05 × 240) and
  "Prefer maximum performance" for 4K HDR, how to set them (NVIDIA App / Control Panel, global or per app), how to read
  the `driver` log line, and that a capped setup makes the feature skip the switch (P5-10). PROGRESS updated with the
  measurements — auto (files exist)
- P5-19 — Phase 4 carry-over: the native `query <display> failed` line is logged once per change of the failure (first
  failure or a different error text, then one `query <display> ok again`), not once per second — auto (code review; the
  line is written natively, so no Kotlin unit test; corrected before code 2026-09-28)

**Verification:** `verify.ps1 -Full` + the measure runs above, then ONE lean verifier round (this table, commit ids,
evidence folder list).

### Phase 6 — Settings toggle + plumbing (written 2026-09-28, before code; awaiting owner approval, Q23–Q26)
Scope: replace the `NUVIO_RR_ENABLE=1` dev knob with a user setting **"Match display refresh rate"** in Playback
settings, Windows only, default OFF. The feature's behaviour (Phases 3–5) is unchanged.
**Not in Phase 6:** the test matrix (Phase 7), app identity / updater / Sentry (Phase 8), Nuvio-only driver profiles.
Design:
- Storage: new desktop store `DesktopStorage.store("nuvio_refresh_rate")`, key `match_display_refresh_rate`. Per PC:
  not profile-scoped, not in the sync payload (see **Q23**). `PlayerSettingsRepository` / `PlayerSettingsStorage` untouched.
- Common API for the settings page (commonMain can't see desktop code): new `expect object RefreshRateMatchSetting`
  (`available`, `enabled: StateFlow<Boolean>`, `setEnabled`) with actuals in desktopMain (`available = isWindows`, backed
  by the store) and androidMain/iosMain (`available = false`, no-op) — the same 3-actual pattern as `isWindows`.
- UI: new composable `RefreshRateMatchSettingsSection(isTablet)` in a new file in package `...features.settings` (so the
  `internal` `SettingsSection`/`SettingsGroup`/`SettingsSwitchRow` are reachable); draws nothing when `!available`. Called
  by ONE fully qualified line in `PlaybackSettingsPage.kt` right after the "NVIDIA RTX Video" section (H9). Its 3 strings
  go in `values/strings.xml` only (H10; other locales fall back to English).
- Enable path: the native H2 (`onMpvInitialized`) asks Kotlin per player through a new JNI upcall
  `RefreshRateMatch.nativeFeatureEnabled(): Boolean`, using the existing attach helper. Kotlin answers: env
  `NUVIO_RR_ENABLE=1` ⇒ on, `=0` ⇒ off (dev/measure override, **Q24**), otherwise the stored setting. So the value is
  read at each playback start and no upstream Kotlin line pushes it (the planned H7 is not needed).
- A change takes effect at the next playback start (**Q25**); a running session is left alone and restores as usual.

**Footprint and tests first**
- P6-1 — Upstream diff ≤ 7 changed code lines in 4 files: the 6 Phase 4 lines + H9 (1 line, `PlaybackSettingsPage.kt`),
  plus ≤ 3 added lines in `composeApp/src/commonMain/composeResources/values/strings.xml` (H10). Each carries a
  `nuvio-rr fork hook Hn` comment on the same line (XML comment for H10). `PlayerSettingsRepository.kt`,
  `PlayerSettingsStorage.*`, `NativePlayerController.kt`, `NativePlayerBridge.kt`, the `create()` signature, the attach
  `LaunchedEffect` keys, all Gradle files and all other locales untouched; no new dependency — auto (`verify.ps1 -Full`)
- P6-2 — `verify.ps1 -Full` green with only the known upstream failures; nothing written to either official folder;
  nothing pushed — auto
- P6-3 — Tests first (commit A = tests + stubs, red; commit B = implementation, green; empty test diff A→B; changed
  Phase 3–5 tests listed in commit A with the reason). Tests cover: the enable rule (env `1`/`0`/unset/other × setting
  on/off, 8 cases); store round trip with default OFF when no file/key exists; `setEnabled` updates the `StateFlow`;
  `nativeFeatureEnabled()` false ⇒ no dispatcher, no thread, no shutdown hook created; an exception while reading ⇒
  `false` — auto (tests)
- P6-4 — Not synced, per PC: the key is absent from `PlayerSettingsStorage.exportToSyncPayload()` (test) and the store
  key is not wrapped by `ProfileScopedKey` (code review) — auto

**Behaviour**
- P6-5 — Default OFF = upstream: fresh dev profile (no `nuvio_refresh_rate.properties`), no env knob, `sdr-1080p-23.976`
  ⇒ 279.961 throughout, no `player pN created` line, `refresh-rate.log` not created or appended, no `nuvio-rr` thread, and
  the mpv option/property lines in the mpv log equal those of the Phase 5 feature-off run of the same clip — auto
  (measure.ps1 in the new setting mode, P6-8)
- P6-6 — Setting ON, no env knob ⇒ the feature runs exactly as in Phase 5: `sdr-1080p-23.976` windowed meets every P5-12
  limit, and the log names the source (`enabled=setting`) — auto (measure.ps1)
- P6-7 — Env override: `NUVIO_RR_ENABLE=0` + setting ON ⇒ off as in P6-5; `NUVIO_RR_ENABLE=1` + setting OFF ⇒ on as in
  P6-6 (`enabled=env`). measure.ps1 without `-Feature` now passes `NUVIO_RR_ENABLE=0`, so baseline runs stay off whatever
  the dev profile holds — auto (tests + one measure run each)
- P6-8 — measure.ps1 gets `-Setting on|off`: writes the key into the run's dev-profile store before launch (never the
  official profile) and leaves `NUVIO_RR_ENABLE` unset; `-Feature` keeps its meaning (env `1`) — auto (the P6-5/P6-6 runs)
- P6-9 — Takes effect per playback start: two measure runs back to back on one dev profile, setting flipped between them
  (off ⇒ on ⇒ off), each run matches P6-5 or P6-6. The upcall runs once per player on the H2 thread, before `loadfile`,
  and its time is logged (≤ 50 ms) — auto
- P6-10 — Fail-safe: if the H2 upcall can't run (no JVM, class/method missing, Java exception) the player is treated as
  OFF (no hook), one native `[nuvio-rr]` line records why, playback starts normally — auto (code review; fault kind
  `enable-upcall` added to `NUVIO_RR_FAULT`, one measure run: plays ≥ 10 s, 279.961 throughout)

**UI and docs**
- P6-11 — UI (**Q26** for the wording): Playback settings shows a section "Display" with the switch "Match display refresh
  rate" and a one-line description, only on Windows, directly below "NVIDIA RTX Video"; default off; the value survives an
  app restart and a Nuvio profile switch — [HUMAN] (P6-12), persistence also auto (store file content after the runs)
- P6-12 — [HUMAN] checklist: (1) Settings → Playback: the new section is there, switch off; (2) turn it on, play a 24 fps
  title ⇒ ≈ 1 s black, 240 Hz, smooth; leave ⇒ 280; (3) turn it off, play ⇒ no black, stays 280; (4) close and reopen
  Nuvio ⇒ the switch kept its value; (5) RTX Video Super Resolution switch still works independently
- P6-13 — Docs: SPEC §1–3 (H9, H10, new files; `NUVIO_RR_ENABLE` described as the override), FORK.md (how to turn the
  feature on, the override, conflict hot spot: the RTX section of `PlaybackSettingsPage.kt`), run-dev.ps1/measure.ps1 help
  text, PROGRESS — auto (files exist)

**Verification:** `verify.ps1 -Full` + the measure runs above, then ONE lean verifier round (this table, commit ids,
evidence folder list).

## 5. Upkeep limits
- Upstream-file diff budget: _TBD_ lines (reported by `verify.ps1 -Full`).
