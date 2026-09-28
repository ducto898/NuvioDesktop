# SPEC — Match display refresh rate (delta spec on top of upstream NuvioDesktop)

Describes ONLY what this patch adds or changes. It's the reference for re-applying and
re-verifying the patch after an upstream update. Keep it in sync with the code.

Upstream base: NuvioMedia/NuvioDesktop `Dev` @ `083921cf` (2026-09-27).

## 1. Behaviour
_TBD after Phase 1 research and plan approval._ Summary of intent:
- Opt-in setting "Match display refresh rate" (Playback settings, Windows), default OFF.
  OFF ⇒ identical to upstream.
- On playback start: fps → target mode (integer multiple, 1000/1001 tolerance, same
  resolution/bit depth, monitor hosting the player) → switch while held → settle →
  display-synced mpv timing → play. Rate constant for the session.
- Restore on end/close/exit/crash/kill. Never persisted as the Windows default.

## 2. Upstream files touched (hooks)
| File | Hook | Lines |
|---|---|---|
| _TBD_ | | |

## 3. New files
| File | Purpose |
|---|---|
| _TBD_ | |

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
  6 known upstream failures; nothing pushed (`git status -sb` shows no upstream tracking on origin) — auto

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
- P2-4 — Sampling runs on the existing mpv event thread only (no new thread, no blocking call > 50 ms) and stops
  when that thread exits, so it cannot outlive the mpv handle — auto (code review) + auto (log: max gap between
  samples ≤ 1.5 s during playback)
- P2-5 — Measure-only knobs, each read only when `NUVIO_RR_MEASURE=1`: `NUVIO_RR_MEASURE_SYNC=<video-sync mode>`
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

## 5. Upkeep limits
- Upstream-file diff budget: _TBD_ lines (reported by `verify.ps1 -Full`).
