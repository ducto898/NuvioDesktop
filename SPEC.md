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
  `estimated-display-fps` within 0.1 % of 239.901; drops + mistimed ≤ 1 per minute; 0 audio underruns after the first
  5 s; PresentMon: ≥ 99 % of video frames held exactly 10 refreshes. Confirmed by one repeat run of the winning set
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

## 5. Upkeep limits
- Upstream-file diff budget: _TBD_ lines (reported by `verify.ps1 -Full`).
