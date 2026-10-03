# SPEC — Match display refresh rate (delta spec on top of upstream NuvioDesktop)

Describes ONLY what this patch adds or changes. It's the reference for re-applying and
re-verifying the patch after an upstream update. Keep it in sync with the code.

Upstream base: NuvioMedia/NuvioDesktop `Dev` @ `ed77003b` (merged 2026-10-03; earlier rebases onto `fe92d414`, `cf185993`; forked at `083921cf` on 2026-09-27).

## 1. Behaviour
State after Phase 8 (details in §4 per phase):
- On/off: the setting **"Match display refresh rate"** (Settings → Playback → Display, Windows only, default OFF, per
  PC in `nuvio_refresh_rate.properties`, not synced). Read once per player at H2, so a change applies from the next
  video. `NUVIO_RR_ENABLE` is a dev/measure override: `1` forces on, `0` forces off, unset = the setting. Off ⇒ no mpv
  hook, no log, no thread (= upstream).
- At mpv's `on_preloaded` hook (before VO init) the glue reads the fps and the NVIDIA driver's Max Frame Rate, Kotlin
  decides (`decide()`: highest k·fps mode, 1000/1001 tolerance, same resolution + bpc; none ⇒ no switch; a frame cap
  below 1.05 × target ⇒ no switch), switches with `CDS_FULLSCREEN`, settles, verifies.
- Switched (or already at the target) ⇒ `video-sync=display-resample`, `interpolation=no`,
  `display-fps-override=<exact rate>` on that player before the hook continues; otherwise no mpv option is touched.
- Mid-playback: monitor off/on ⇒ re-switch once (HDR toggles: up to 3), timing re-applied; given up / window moved ⇒
  restore and put the saved mpv values back (playback continues); display-resample clearly broken (health check) ⇒
  mpv's own timing, the mode stays (rate error: 9 judged samples in a row, P5-11 / P7-15).
- Restore on player screen gone, window close, JVM exit; Windows reverts on crash/kill (D7).
- On-screen badge (audit E3): mpv `show-text` for 2.5 s top-right after the hook decision and after a runtime timing
  change; per player at H2 (upcall `nativeBadgeEnabled`): `NUVIO_RR_OSD` 0/1, else the per-PC setting
  `show_rate_badge` (default on); off in measure runs unless `NUVIO_RR_OSD=1`. Driver read (audit #9): started at H2 on a detached thread,
  collected at the hook; if not ready, the process's last read is used and refreshed.
- Fork app identity (Phase 8): the packaged fork is "Nuvio RR" (own exe, start-menu group, MSI upgrade UUID) with its
  own `%APPDATA%\Nuvio RR`, `%LOCALAPPDATA%\Nuvio RR\Cache` and `...\WebView2`; in-app updater off; the build refuses a
  crash-upload DSN. Without `fork-identity.properties` (or with `NUVIO_FORK_IDENTITY=off`) every name is upstream's.

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
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/PlaybackSettingsPage.kt` | H9 `RefreshRateMatchSettingsSection(isTablet = isTablet)` (fully qualified) right after the `if (isWindows)` "NVIDIA RTX Video" section | 1 |
| `composeApp/src/commonMain/composeResources/values/strings.xml` | H10 3 strings after `settings_playback_nvidia_rtx_super_resolution_desc` (`settings_playback_display_section`, `settings_playback_match_refresh_rate`, `settings_playback_match_refresh_rate_desc`, `settings_playback_rate_badge`, `settings_playback_rate_badge_desc`), XML comment on each; other locales fall back to English | 5 |

| `composeApp/src/desktopMain/kotlin/com/nuvio/app/core/storage/DesktopStorage.kt` | H11 Roaming folder, H12 Local cache folder: `ForkIdentity.appDirName` instead of `"Nuvio"` (Windows branches only) | 2 |
| `composeApp/src/desktopMain/kotlin/com/nuvio/app/core/build/AppFeaturePolicy.desktop.kt` | H13 `inAppUpdaterEnabled = ForkIdentity.updaterEnabled` | 1 |
| `composeApp/src/desktopMain/native/windows/player_bridge.cpp` | H14 `webViewUserDataDirectory()`: `\<nuvioRrAppDirName()>\WebView2` (block-scope declaration + use on one line) | 1 |
| `composeApp/build.gradle.kts` | H15 `nuvioForkName` from `fork-identity.properties` (off with `NUVIO_FORK_IDENTITY=off`), H16 Sentry DSN guard, H17 `-Dnuvio.fork.name` in `application.jvmArgs`, H18 `packageName`, H19 Windows `upgradeUuid`, H20 Windows `menuGroup` | 6 |
| `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/settings/WindowsAppShortcutIconUpdater.kt` | H21 `update()` returns early when `ForkIdentity.shortcutIconsEnabled` is false (fork: never touch the official shortcuts or `%LOCALAPPDATA%\Nuvio\icons`, Q44) | 1 |

H7 (a Kotlin line pushing the setting to native) was planned but is not needed: H2 asks Kotlin itself (Phase 6).
Budget (owner Q42 + Q44 + badge switch 2026-10-01, enforced by `verify.ps1 -Full`): 12 code lines, 5 string lines, 6 Gradle lines; every added line tagged.

## 3. New files
| File | Purpose |
|---|---|
| `composeApp/src/desktopMain/native/windows/display_mode_matcher.cpp` | Native side (`namespace nuvio_rr`, `#include`d by `player_bridge.cpp`; closes/reopens its anonymous namespace for `<dxgi1_2.h>` and the JNI exports; `#pragma comment(lib, "dxgi.lib")`). Phase 2/2b: measure-only sampler + knobs. Phase 4: feature config (`NUVIO_RR_ENABLE`, `NUVIO_RR_FAULT`), `refresh-rate.log` sink, player registry, `on_preloaded` hook worker + JNI upcall, Win32 port (QDC query, DXGI modes, CDS switch + settle, restore), JNI exports for `NativeDisplayPort`. Phase 5: timing apply/revert with saved values (`applyDisplaySyncLocked`, `setTiming`), `timingStats`, read-only NVAPI DRS read (`readDriverSettings`), fault kinds `timing-set`/`drop-mode`, query-failure log once per change. Phase 6: per-player enable at H2 through the `nativeFeatureEnabled` upcall (`upcallFeatureEnabled`, `gFeatureUsed` gates H4/H5), fault kind `enable-upcall`. Phase 7: measure-only mode cap `NUVIO_RR_MEASURE_MAX_HZ` in `enumerateModes` (only with `NUVIO_RR_MEASURE=1`, Q30) |
| `composeApp/src/desktopMain/kotlin/com/nuvio/app/fork/ForkIdentity.kt` + `desktopTest/.../fork/ForkIdentityTest.kt` | Phase 8 app identity: folder name from `-Dnuvio.fork.name` (validated, else "Nuvio"), updater flag; read by H11–H13 and, through JNI, by H14 (`nuvioRrAppDirName()` at file scope in `display_mode_matcher.cpp`) |
| `fork-identity.properties` (repo root) | `name=Nuvio RR`, read by Gradle hook H15 |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/RefreshRateMatchSetting.kt` + `.desktop.kt`/`.android.kt`/`.ios.kt` | `expect object RefreshRateMatchSetting` (`available`, `enabled`, `setEnabled`); desktop: store `nuvio_refresh_rate`, key `match_display_refresh_rate`, `RefreshRateMatchPreference`; android/iOS: unavailable no-op (P6-3, P6-4) |
| `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/RefreshRateMatchSettingsSection.kt` | the "Display" section with the switch, nothing when unavailable (P6-11) |
| `.../refreshrate/runtime/RefreshRateEnablement.kt` | the enable rule: env override, else the setting; native codes 0/1/2, -1 on error (P6-3, P6-7) |
| `composeApp/src/desktopTest/kotlin/.../refreshrate/runtime/RefreshRateEnablementTest.kt`, `RefreshRateMatchSettingTest.kt` | Phase 6 tests |
| `.../refreshrate/ResampleHealth.kt` | pure health rule for display-synced timing (P5-11) |
| `composeApp/src/desktopTest/kotlin/.../refreshrate/ResampleHealthTest.kt`, `.../runtime/RefreshRateTimingTest.kt` | Phase 5 tests (P5-7, P5-10, P5-11) |
| `.../refreshrate/runtime/DisplayPort.kt` | `DisplayPort` interface (the Win32 side) + `StartInput` (P4-5..P4-9) |
| `.../refreshrate/runtime/RefreshRateController.kt` | runs `decide()`/`step()` against a `DisplayPort`, every command to its result; fail-safe; watcher with two-read debounce; monitor move (Q15) (P4-13..P4-17) |
| `.../refreshrate/runtime/RefreshRateDispatcher.kt` | one daemon thread `nuvio-rr`, bounded waits, 1 s watcher (P4-13, P4-16) |
| `.../refreshrate/runtime/NativeCodec.kt` | `long[]` layouts across JNI |
| `.../refreshrate/runtime/NativeDisplayPort.kt` | `external` functions implemented in `display_mode_matcher.cpp` |
| `.../refreshrate/runtime/RefreshRateMatch.kt` | process-global entry points: native upcalls (`nativeFeatureEnabled` at H2, `nativePlaybackStart` at the hook), H6, H8, JVM shutdown hook; Kermit tag `RefreshRateMatch` |
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
  a 10 s window with drops + mistimed > 20, or `estimated-display-fps` off the target by > 1 % **in 5 judged samples in a
  row** (amended 2026-09-28 in Phase 6, owner-approved **Q28**: one 1.3 s stall bent the estimate for one sample and
  dropped display sync for the rest of the video, run p6-env1-set-off; `814cef33`/`3b6d8fda` (3), `8ee62087`/`c1623bc8`
  (5); a stretch that recovers by itself is logged `rate-off pN <n> samples, worst <fps> (<err> %), recovered`;
  **Phase 7 set the final count to 9** (P7-15 rule, Q31: a fullscreen toggle bent the estimate for 7 samples at
  143.973, 3–4 at 239.901; `c5ef3e5a`/`120453b9`)), ⇒ `setTiming(Upstream)`,
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

### Phase 6 — Settings toggle + plumbing (written 2026-09-28, before code; owner-approved 2026-09-28 with Q23–Q26 yes; DONE 2026-09-29, P6-12 approved)
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
  `RefreshRateMatch.nativeFeatureEnabled()`, using the existing attach helper. (Built as `(): Int` — 0 off, 1 on by the
  env, 2 on by the setting, -1 error — so the native log can name the source, P6-6/P6-7; anything but 1/2 is off.) Kotlin answers: env
  `NUVIO_RR_ENABLE=1` ⇒ on, `=0` ⇒ off (dev/measure override, **Q24**), otherwise the stored setting. So the value is
  read at each playback start and no upstream Kotlin line pushes it (the planned H7 is not needed).
- A change takes effect at the next playback start (**Q25**); a running session is left alone and restores as usual.
- **Q27 (owner, 2026-09-28 23:15): the desktop default is now 240 Hz** (239.901 live, 240 in the registry), not 280.
  Read every "279.961"/"280" in P6-5..P6-12 as that default (measure.ps1 `-ExpectHz`/`-ExpectRegHz` defaults). With
  it, `sdr-1080p-23.976` is already at its target, so "on" = **no switch, no black**, display-synced timing only
  (P5-12 limits except the switch/settle/restore ones); "off" = upstream audio sync at 240. The switch and restore
  paths stay proven by the Phase 4/5 evidence (made at 280).

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
  title ⇒ no black (already 240, Q27), smooth; leave ⇒ still 240; (3) turn it off, play ⇒ stays 240 (upstream timing); (4) close and reopen
  Nuvio ⇒ the switch kept its value; (5) RTX Video Super Resolution switch still works independently
- P6-13 — Docs: SPEC §1–3 (H9, H10, new files; `NUVIO_RR_ENABLE` described as the override), FORK.md (how to turn the
  feature on, the override, conflict hot spot: the RTX section of `PlaybackSettingsPage.kt`), run-dev.ps1/measure.ps1 help
  text, PROGRESS — auto (files exist)

**Verification:** `verify.ps1 -Full` + the measure runs above, then ONE lean verifier round (this table, commit ids,
evidence folder list).

### Phase 7 — Test matrix + independent review (written 2026-09-29, before code; owner-approved 2026-09-29 with Q30–Q35 as recommended; Q36–Q38 yes)
Amendments from the Phase 7 fixes (owner Q36/Q37): **P3-21** "within 1e-6" ⇒ within 100 ppm (`RATE_MATCH_TOLERANCE`, also
mode-lost, same-target, already-at-target, native settle; a verified switch keeps the observed rate); **P5-7** "screen
gone ⇒ no call needed" ⇒ screen gone routes `setTiming(Upstream)` to the owner; new: a start the hook stopped waiting
for gets its display-sync when it lands; switch/restore catch `Throwable`. Known limits: FORK §10.
Scope: prove the finished feature (Phases 3–6) across the clip matrix, the disturbance cases carried from Phases 4–5,
10-min soaks and one owner checklist, at the **240 Hz desktop default** (Q27: 239.901 live, 240 in the registry); set
`ResampleHealth.RATE_ERROR_SAMPLES` from measured data (Q28); end with a fresh, independent review of the whole patch.
**Not in Phase 7:** app identity / own exe / Nuvio-only driver profiles, updater, Sentry, patch export (Phase 8); any
NVIDIA or Windows setting change by Claude (the owner makes those, FORK §9); new product behaviour.

Design notes:
- **The 240 default hides the switch path.** 23.976/24/29.97/59.94/60 (and VFR by its 60 fps header) target 240 ⇒
  `already-at-target`: display-synced timing, no switch, no black, nothing to restore. 25/50 ⇒ `no-suitable-mode`
  (100 Hz is raw-only, Q17). So switch, settle, verify, restore, `mode-lost`, `hdr-toggled` and kill-revert would get
  no fresh evidence. Proposed (**Q30**, recommended option a): a **measure-only mode cap**
  `NUVIO_RR_MEASURE_MAX_HZ=<n>`, honoured only when `NUVIO_RR_MEASURE=1`, that drops listed modes above `<n>` from the
  list handed to `decide()` (the current-state reads are untouched). With `144`: 23.976/24 ⇒ 143.973 (×6),
  29.97/59.94 ⇒ 59.951 (×2/×1; 120/119.88 is just outside the 1000/1001 tolerance, measured), 60 ⇒ 120.000 (listed
  as 12000/100, runs 119998/1000: finding F1, fixed by a 100 ppm rate match, Q36) — real switches away from 240 and a real `NULL` restore back to the registry mode
  240, the same path a 280 desktop takes. Rejected alternative: `switcher.exe` holding 280 around a run — the feature's
  restore (`ChangeDisplaySettingsExW(NULL)`) goes to the **registry** mode 240, not to switcher's 280, so the run would
  test an artificial two-process state.
- **Rate-off data (Q28, Q31).** No `rate-off` line exists yet in any run folder (0 of 65 `refresh-rate.log`; the
  line exists since `c1623bc8` and no stall has happened since). Two sources: (1) every `rate-off … recovered` and
  `resample-unhealthy` line from every Phase 7 run and soak; (2) an offline **replay** of the per-second sampler stats
  (`estimated-display-fps`, `time-pos`, `pause`, counters — logged by the measure sampler in every run since Phase 2,
  all Phase 2–6 run folders) through the P5-11 rule for N = 1..10 samples, so a decision exists even if no live stall happens.
- Soaks use long **stream-copied** clips (D10): ffmpeg concat demuxer, `-c copy`, 4 × the 150 s clip ≈ 10 min, into
  `testdata/` (git-ignored); no `loop-file`.
- Every unattended run wakes the monitor first (measure.ps1's 1 px nudge). A run with the asleep signature (Phase 4
  caveat: hundreds of drops + underruns in 30 s, feature on or off) is re-run, never judged.
- Runs are launched only through `scripts/measure.ps1` / `scripts/run-dev.ps1`. Runs without `-Feature`/`-Setting`
  are off (`NUVIO_RR_ENABLE=0`). Default limits `-ExpectHz 239.901 -ExpectRegHz 240`.

**Footprint and tooling**
- P7-1 — Upstream diff unchanged from Phase 6 (7 code lines + 3 string lines, 5 files). The only product-source change
  allowed is the measure-only `NUVIO_RR_MEASURE_MAX_HZ` in `display_mode_matcher.cpp` (Q30) and the
  `RATE_ERROR_SAMPLES` value (P7-16); no Gradle change, no dependency; nothing pushed — auto (`verify.ps1 -Full`)
- P7-2 — `verify.ps1 -Full` green with only the known upstream failures (baseline 8 entries, incl. the flaky
  `PluginRuntimeDesktopTest` case); nothing written to either official folder in any Phase 7 run — auto
- P7-3 — Mode cap (if Q30 = a): active only with `NUVIO_RR_MEASURE=1`; each start logs one line
  `measure max-hz=<n> dropped=<rates>`; without the knob the mode list and the log are unchanged (a 23.976 run without
  it = `already-at-target`, as in Phase 6); the restore path is not touched by the knob — auto (code review + runs)
- P7-4 — measure.ps1 fixes carried from Phase 5: `after5s` sums only the positive increments of each counter (mpv resets
  mistimed/delayed on a seek); every PresentMon grid check fits the period from the display times instead of a constant;
  `f11` in `-Actions` is confirmed by reading the window state and retried once, and the summary records the final
  mode; new `-MaxHz <n>` (sets the P7-3 knob, and makes "during" expect the chosen target); new action `alttab@<s>`
  (focus another window for 3 s, then back) — auto (a rerun of the Phase 5 `p5-pm-actions` summary via `-Cadence`, and
  one run using each new option)
- P7-5 — summary.json gets a `health` section: every `rate-off` line (samples, worst fps, error %, time),
  `resample-unhealthy` (time, reason) and the count of judged samples; `scripts/rr-tools/p7-collect.py rateoff` builds one
  table from all run folders (≥ the Phase 7 folders + any older ones containing such lines) into
  `measurements/phase7-rate-off.txt` — auto
- P7-6 — `scripts/rr-tools/health-replay.py`: reads the sampler lines of a run folder and applies the P5-11 rule
  (5 s ignored, 10 s windows, advance 8–12 s, > 20 bad frames, > 1 % rate error for N judged samples in a row). **Cross-
  check:** at N = 5 its verdicts (fallback yes/no and time, recovered streaks) equal the app's own log lines in every
  Phase 7 feature-on run; one mismatch ⇒ the tool is fixed before its results are used — auto

**Matrix (unattended, 60 s each unless stated; limits = P5-12's, read against 239.901/240, counted after 5 s)**
- P7-7 — Feature on (`-Setting on`), windowed, **all 32 clips** (7 rates + VFR × SDR/HDR × 1080p/2160p):
  23.976/24/29.97/59.94/60/VFR ⇒ `already-at-target`, no switch call, Windows 239.901 and registry 240 throughout,
  display-resample, `display-fps` = 239.901000 ± 0.001, estimated within 0.1 %, drops + mistimed ≤ 1/min, 0 underruns,
  speed corrections within ± 0.2 % (VFR: 0 underruns + no desync warning, counts recorded, Q22); 25/50 ⇒
  `no-suitable-mode`, upstream timing, no feature mpv property set. The 2160p HDR clips also fullscreen (7 + VFR) with
  "Prefer maximum performance" as the owner keeps it (D12). A limit miss is re-run once; two misses = a finding —
  auto (summary.json + p7-collect table)
- P7-8 — Feature off (no `-Feature`/`-Setting`, env 0): `sdr-1080p-23.976`, `sdr-1080p-59.94`, `hdr-2160p-23.976` ⇒
  no hook, no `refresh-rate.log`, upstream audio sync at 239.901, mpv option lines equal Phase 6's off runs — auto
- P7-9 — Switch path (with the Q30 cap `-MaxHz 144`, feature on): `sdr-1080p-23.976` and `sdr-1080p-24` (⇒ 143.973),
  `sdr-1080p-29.97`, `sdr-1080p-59.94` (⇒ 59.951), `sdr-1080p-60` (⇒ 120.000) windowed; `hdr-2160p-23.976`
  fullscreen ⇒ switch before `file-loaded`, settle ≤ 4 s, verify OK, HDR/bpc unchanged, P5-12 limits at the new rate,
  restore on close ⇒ 239.901 within 1 s, registry 240 in every observer sample — auto
- P7-10 — Lifecycle at the capped switch: (a) window close during the switch (`-CloseAfterSwitchMs` 0/50/150/300,
  2 runs each) ⇒ no crash, 239.901 after; (b) `-Kill` while switched ⇒ Windows reverts to 239.901 within 1 s (D7);
  (c) next episode with the same target (two consecutive files, as P5-13) ⇒ one switch only, second player gets
  `DisplaySync` (a new native player needs the real UI ⇒ owner item P7-17 (8) with `run-dev.ps1 -MaxHz 144`); (d) `drop-mode` fault ⇒ `mode-lost` re-switch + `setTiming display-sync ok`, then `mode-lost-again` +
  `setTiming upstream ok`, as P5-8; (e) `-Actions` pause 20 s, seek ±10 s, controls, f11 ×2, alttab ×2 ⇒ mode kept,
  limits met outside a 5 s window after each action, every seek/f11/alttab's `rate-off` line (if any) collected — auto
- P7-11 — Fail-safe regressions (one short run each, feature on): every `NUVIO_RR_FAULT` kind (8 Phase 4 kinds +
  `timing-set`, `drop-mode`, `enable-upcall`) ⇒ plays ≥ 10 s, no crash, 239.901 after exit; with the cap where the
  kind needs a switch to fire — auto

**Soaks (unattended, stream-copied ≈ 10 min, D10; see Q32)**
- P7-12 — `hdr-2160p-23.976` fullscreen (already at target), `sdr-1080p-59.94` windowed (already at target),
  `sdr-1080p-25` windowed (no switch, upstream timing), `sdr-1080p-23.976` windowed with `-MaxHz 144` (switched,
  143.973): P5-12 limits over the whole soak (drops + mistimed ≤ 1/min averaged AND no 60 s bin > 3), 0 underruns, no
  `resample-unhealthy`, GPU power median + max logged, 239.901 after — auto

**Rate-off data and the final sample count (Q28, Q31)**
- P7-13 — After P7-7..P7-12 and the owner batch: `measurements/phase7-rate-off.txt` lists every recovered stretch
  (run, time, samples, worst %, cause if known: seek/f11/alttab/HDR/monitor/driver/sleep/none) and every fallback — auto
- P7-14 — Replay (P7-6) over every run folder with sampler stats, N = 1..10: per N the number of **false fallbacks**
  in runs judged healthy by P5-12, and the **time to fallback** in the known-bad runs (Phase 5's forced
  `d3d11-sync-interval=0` run + one new P7 run of the same set, at 239.901 and at the capped 143.973). Table in the same
  file — auto
- P7-15 — Rule for the final value (Q31, recommended): N = max(3, longest recovered stretch seen live or in the replay
  + 2), and at most the largest N for which every known-bad run still falls back within 20 s of its first judged
  sample; if nothing ever went off-rate, N stays 5. The chosen N, the numbers behind it and the rule go into
  PROGRESS/SPEC P5-11 — auto (table) + owner decides at the Phase 7 end gate
- P7-16 — If N changes: tests first (commit A: `ResampleHealthTest` + controller tests updated to the new count, red;
  commit B: the constant, green, empty test diff A→B), `verify.ps1 -Full` green, one feature-on run and the known-bad
  run re-measured — auto

**Owner batch (one session, ≈ 40 min, see Q33)**
- P7-17 — [HUMAN] one checklist. For items 1–6 Claude gives ONE command (a long capped `measure.ps1 -Feature -MaxHz 144
  -Seconds 900` run, or two if one crashes) and the owner does the steps in any order, noting clock times; each step
  must appear in `refresh-rate.log`: (1) Win+Alt+B HDR off ⇒ `hdr-toggled` re-switch in SDR, then HDR back on ⇒ re-switch
  in HDR (P4-22), smooth after each; (2) monitor power off/on ⇒ `mode-lost` re-switch + `setTiming display-sync`;
  (3) Win+Ctrl+Shift+B driver reset ⇒ no crash, playback continues, mode back or restored per P4-18; (4) sleep and
  resume (Start → Sleep) ⇒ no crash, playback resumes or the player recovers, 239.901 after exit; (5) switch the
  Windows default audio device and back ⇒ sound follows or stays, no A/V desync warning, display sync kept;
  (6) (optional, Q34, UAC click) PresentMon on the capped fullscreen run with a 20 s pause ⇒ resume on the 143.973 grid
  (P5-16 at a second rate). Items 7–9 in the normal dev build (`scripts/run-dev.ps1`, setting on, 240 default):
  (7) a real **Dolby Vision** (P5/P8) title and an **HDR10+** title from Nuvio's catalogue: colours/brightness as with
  the setting off, `already-at-target`, smooth; (8) a series: play an episode to "next episode" ⇒ no black, smooth;
  (9) a 25 fps title ⇒ stays 240, plays normally (known judder, Q17); plus a yes/no: any OLED flicker in the player or
  the browse UI with the java.exe Fixed Refresh entry (FORK §9)?
- P7-18 — Every checklist item left unproven by the log is named in PROGRESS as owner-attested or open, never PASS by
  default — auto

**Independent review and docs**
- P7-19 — A fresh subagent with no Phase 3–7 context (the project's `verifier` agent) reviews the whole patch
  (`git diff upstream/Dev...HEAD`, product files only) against SPEC §1–4 and FORK.md: every criterion P3–P7 mapped to
  code/test/evidence, plus a bug hunt (threads/locks, JNI lifetime, restore on every exit path, fail-safe on every
  Win32/mpv/NVAPI call, off = upstream). ONE round; findings fixed tests-first or listed as known limits with the
  owner's OK; a second round only if a fix touches native code — auto
- P7-20 — Docs: SPEC §1–4 current (cap knob, final N), FORK.md §5/§6 (the Phase 7 re-verify recipe: which measure
  runs to repeat after an upstream or driver update, with the cap), PROGRESS (results, Phase 7 DONE, Phase 8 next) — auto

**Verification:** `verify.ps1 -Full` + the runs above + the owner batch, then the P7-19 review (it replaces the usual
lean verifier round).

### Phase 8 — Upkeep: own app identity, updater/Sentry off, patch export, docs (written 2026-09-29, before code; owner-approved 2026-09-29 with Q39–Q43 as recommended)
Deviations found while building (2026-09-29): P8-4's "window title `<name>`" is dropped — the title is hard-coded
`"Nuvio"` in `Main.kt` and a 12th code hook would exceed Q42; exe, taskbar grouping, start menu and folders carry the
name. The patch and zip go outside the repo (`..\patches`, `..\dist`), not into `patches/`. The WebView2 name reaches
native code through a JNI read of `ForkIdentity` (MSVC binds the block-scope declaration to a global function, so
`nuvioRrAppDirName()` is defined at file scope).
Scope: make the fork a separate app that can run next to the official Nuvio (requirement 20, D6), switch off what must
not run in a private build (updater, crash upload), export the patch, and finish FORK.md so an upstream update can be
re-applied without this session. **Not in Phase 8:** new feature behaviour; any NVIDIA/Windows setting change by Claude
(the owner creates the per-exe driver entry, FORK §9); pushing, a PR or the upstream feature request (Q2/Q3 stay: keep
local, hold).
Design (see **Q39–Q42**):
- One identity switch, owned by the fork: Gradle property `nuvio.fork.identity` (default on in this fork through a
  fork-owned properties file read by the build, not `gradle.properties`) sets the package name, exe name, start-menu
  group and a new MSI `upgradeUuid`, and passes `-Dnuvio.appDirName=<name>` to the app (run task and packaged
  launcher). Without it every value is upstream's.
- App code reads the folder name in one place: `DesktopStorage` (Roaming + Local/Cache, one hook line each) and the
  bridge's `webViewUserDataDirectory()` (one hook line; the name reaches native code through the existing create call's
  environment, or `WEBVIEW2_USER_DATA_FOLDER` set by the launcher — decided at implementation, both keep the diff ≤ 1
  line) ⇒ fixes the D6 WebView2 leak for good.
- Updater: the desktop `AppFeaturePolicy.inAppUpdaterEnabled` becomes false when the fork identity is on (one hook
  line). Sentry: the fork build refuses a non-blank `SENTRY_DESKTOP_DSN`.

**Footprint and tests first**
- P8-1 — Upstream diff ≤ 11 code lines + 3 string lines + ≤ 6 lines in `composeApp/build.gradle.kts` (**Q42**), each
  tagged `nuvio-rr fork hook Hn` (H11–H15); no new dependency; nothing pushed — auto (`verify.ps1 -Full`, which gets the
  new budget)
- P8-2 — `verify.ps1 -Full` green with only the known upstream failures; no run writes either official folder — auto
- P8-3 — Tests first for every Kotlin change (commit A red, commit B green, empty test diff): folder name from the
  property, upstream `Nuvio` without it, blank/invalid property ⇒ upstream; updater policy off with the identity, on
  without — auto

**Identity and side-by-side (Q39)**
- P8-4 — With the identity on: app data in `%APPDATA%\<name>`, cache in `%LOCALAPPDATA%\<name>\Cache`, WebView2 in
  `%LOCALAPPDATA%\<name>\WebView2`, window title/taskbar/start menu `<name>`, exe `<name>.exe`, MSI upgradeUuid ≠
  upstream's — auto (a packaged run; file-system diff of both official folders before/after = 0 files)
- P8-5 — Side by side: the official Nuvio (installed) and the fork run at the same time, each with its own profile and
  settings; closing one leaves the other running — [HUMAN] (P8-13) + auto (folder diff)
- P8-6 — Profile import (**Q41**): `scripts/import-profile.ps1` copies the official Roaming profile into the fork's
  folder once (refuses if the fork profile exists, never writes the official folder, prints what it copied) — auto
- P8-7 — `run-dev.ps1` keeps working (its redirected dev profile wins over the identity); `measure.ps1` gets
  `-Packaged` to launch the built app image instead of Gradle, and one packaged `sdr-1080p-23.976` feature run meets
  P5-12 (already at target) plus one capped switch run — auto

**Private-build safety**
- P8-8 — Updater off in the fork: no update check at start (policy test + no request to the release feed in a
  packaged run's log), no update banner — auto
- P8-9 — Crash upload off: the fork build fails with a clear message if `SENTRY_DESKTOP_DSN` is non-blank; the packaged
  app logs Sentry inert — auto

**Distribution (Q40)**
- P8-10 — `./gradlew :composeApp:createDistributable` (app image, no WiX, no admin) builds `<name>\<name>.exe` with the
  bundled runtime, libmpv and the bridge DLL; `scripts/package-fork.ps1` builds it and zips it with the commit id —
  auto. (MSI only if the owner picks it in Q40: needs the WiX toolset + a UAC prompt.)
- P8-11 — Driver entry for the own exe: FORK §9 gives the exe path and the three per-app settings (Monitor Technology
  = Fixed Refresh, Max Frame Rate off, Power = Prefer maximum performance); the owner creates them in the NVIDIA
  Control Panel; the feature's `driver` log line then shows `exe=<name>.exe app_profile=yes` — [HUMAN] + log

**Upkeep**
- P8-12 — Patch export: `scripts/export-patch.ps1` writes `patches/nuvio-rr-<upstream base>-<head>.patch` (product
  files + tests + fork scripts/docs, never measurements/testdata) and proves it with `git apply --check` on a clean
  worktree at the upstream base — auto
- P8-13 — [HUMAN] one checklist: install/unzip the fork, run it next to the official Nuvio, sign in/check the imported
  profile, NVCP entry for the exe, play a 24 fps title (smooth, no flicker), updater banner absent; **plus the still-open
  Phase 7 checklist** (`docs/phase7-owner-checklist.md`, **Q43**)
- P8-14 — Docs: FORK.md has no `TBD` left: §4 upstream-update runbook (fetch, rebase, hot spots §5, `verify.ps1 -Full`,
  §6 re-verify recipe, export), §7 identity/updater/Sentry, §9 exe path; `docs/ci-proposal.md` (a windows-latest job:
  build, patch tests, upstream-diff budget, not installed since nothing is pushed); SPEC §1–3; PROGRESS — auto
- P8-15 — One lean verifier round (this table, commit ids, evidence folders) — auto

**Verification:** `verify.ps1 -Full` + the packaged runs above + the owner checklist, then ONE lean verifier round.

## 5. Upkeep limits
- Upstream-file diff budget: **7 code lines + 3 string lines in 5 files** (P6-1; measured again in Phase 7), each tagged
  `nuvio-rr fork hook Hn`; reported by `verify.ps1 -Full`. Anything more needs the owner's OK.
