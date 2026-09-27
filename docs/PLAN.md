# Overall plan — "Match display refresh rate" (Nuvio Desktop, Windows)

## Context
Owner wants an opt-in feature in Nuvio's built-in mpv player: at playback start switch the monitor to the
highest refresh rate that is an integer multiple of the video fps (240 Hz for 23.976–60, 100 Hz for 25/50 on
the MO27Q28G), lock mpv timing to the display, keep the panel rate constant (OLED VRR flicker), and restore
280 Hz reliably — including after crash/kill (the MPC-HC/madVR "stuck at 240" failure must not happen).
Phase 1 research is done and accepted (docs/research/01–07, owner answers Q5–Q8 in PROGRESS.md). This plan
turns R1–R14 into an architecture and phase sequence. Each phase still gets its own acceptance criteria in
SPEC.md and an approval gate before its code.

## Architecture

```
Kotlin (desktopMain, package ...player.desktop.refreshrate)       Native (new display_mode_matcher.cpp, #included by player_bridge.cpp)
 RefreshRateMatch (object)  ── setEnabled / restoreNow ──JNI──▶  nuvio_rr::  process-global session state (original mode, target, owner)
   pure logic, unit-tested:                                         Win32 only: enumerate (QDC/DXGI exact rationals), switch
   FpsSnapper, ModeSelector,     ◀── decide(fps, estFps, modes,     (ChangeDisplaySettingsExW CDS_FULLSCREEN), verify, restore
   SessionStateMachine,               current, hdr) upcall ──       mpv glue: on_preloaded hook → worker thread → switch → settle
   FailSafePolicy                                                    → set video-sync/display-fps-override → mpv_hook_continue
 Logger.withTag("RefreshRateMatch")                                 log sink [nuvio-rr] → file under %LOCALAPPDATA%\…\Cache + Kotlin
```

Key decisions (from research):
- **API**: `ChangeDisplaySettingsExW(\\.\DISPLAYn, &dm, …, CDS_FULLSCREEN)`; restore `ChangeDisplaySettingsExW(name, NULL, NULL, 0, NULL)`. Never touch the registry. Exact rates from `QueryDisplayConfig`/DXGI; verify after every switch.
- **Decision logic in Kotlin** (testable in `composeApp/src/desktopTest`); native calls it synchronously via a JNI upcall from the glue worker thread (same attach pattern as existing `sendPlayerEvent`, `player_bridge.cpp:~1920`). Native = Win32 + mpv plumbing only.
- **Timing**: switch inside mpv's `on_preloaded` hook (before VO init), on a glue-owned worker so the mpv event thread never blocks; bounded settle (poll QDC mode + HDR until two reads agree, cap ~4 s, abort on player `stopping` — shutdown only waits 3 s, F7). Then `video-sync=display-resample`, `interpolation=no`, `display-fps-override=<exact>`, only when we switched. Feature OFF or no switch ⇒ zero option changes (upstream behaviour).
- **Session**: state is process-global (each episode creates a new native player, F3). Same target ⇒ no switch. Restore when the player *screen* goes away (`PlayerEngine.desktop.kt` `DisposableEffect(host).onDispose`), on window close (`Main.kt` `onCloseRequest`), and a JVM shutdown hook in the new object.
- **Crash/kill**: rely on Windows reverting CDS_FULLSCREEN when the process dies — *only if the kill test proves it*. Otherwise: marker file before switching + restore on next launch; watchdog only if the owner agrees.
- **VRR**: G-SYNC is "full screen only" (measured). Measure first; mitigate (keep-alive presents while paused) only if VRR engages. No global NVIDIA changes.
- **Setting**: separate small storage/repository (new files), not synced; upstream edits limited to `PlaybackSettingsPage.kt` + `strings.xml`. Pattern reference: `nvidiaRtxSuperResolutionEnabled` UI row; do not add it to the attach `LaunchedEffect` keys.
- **Diff budget**: ≤ 50 lines changed in upstream files (excl. strings.xml); `NativePlayerController.kt`, `NativePlayerBridge.kt`, `create()` signature and `build.gradle.kts` untouched (unity `#include`, `#pragma comment(lib, "dxgi.lib")`).

Upstream hook sites (from docs/research/06): H1 `#include` after `mpvApi()` (PB:613); H2/H3 pause-hold (PB:1625-1664, 1708); H4 event dispatch in `drainMpvEvents` (PB:1899-1902); H5 cancel on `shutdown()` (PB:~905); H6 restore in PED:157-162; H7 push setting PED:~134; H8 `Main.kt:124-128`; H9 settings UI + strings.

## Phases

**Phase 2 — Measure only (owner needed at the PC)** · est. 400k
- Rebase the branch onto current `upstream/Dev` (c6c9c308) first (local; no push).
- Add H1 + H4 with a measurement-only sampler in the new native file: mpv `log-file` + periodic stats (container/estimated fps, display-fps, frame-drop/mistimed/delayed counts, vsync-ratio/jitter, display-sync-active) to a log file, **active only with env `NUVIO_RR_MEASURE=1`** (no behaviour change otherwise).
- `scripts/gen-testclips.ps1` (ffmpeg → `testdata/`, git-ignored): 23.976/24/25/29.97/50/59.94/60 + VFR; SDR + HDR10; 1080p + 2160p HEVC 10-bit; 2–3 min pan + frame counter. Note sources for DV P5/P8 and HDR10+ samples.
- `scripts/measure.ps1`: launches the dev build (via run-dev profile) with a named clip, plays N s, collects the log, samples the Windows rate before/during/after, PresentMon (portable, `NuvioRate\tools`) present mode, `nvidia-smi` power.
- **Kill test** (research 01 §7, standalone scratchpad tools, owner present): cases A–J. Decides the crash design before Phase 4.
- Baseline (feature absent) for 23.976/25/59.94, 2–3 runs to prove repeatability; VRR/present-mode findings; GPU power 240 vs 120 Hz (Q8 downside check). Record in PROGRESS.md.

**Phase 3 — Pure logic, tests first** · est. 250k
FpsSnapper (standard rates, ±0.1%), ModeSelector (highest k·f within ratio tolerance, same resolution + bpc, else none), VFR/unknown ⇒ no switch, SessionStateMachine (idle/switching/switched/restoring; same-target skip; restore bookkeeping), FailSafePolicy (every API failure ⇒ stay at current rate, play). Tests use the measured mode list (279.961, 239.901, 143.973, 119.998, 100.000, 59.951).

**Phase 4 — Native switching** · est. 500k
Enumeration, switch, verify, restore, settle, all hooks H2–H8 native side, JNI upcall, log sink, races (dispose mid-switch, two players, monitor move/PiP, sleep/resume, HDR toggle, driver reset → fail-safe), crash fallback per kill-test result.

**Phase 5 — mpv timing + OLED stability** · est. 400k
display-resample + exact display-fps-override; confirm via measure.ps1 that mpv uses the new rate with ~0 drops/mistimes; VRR mitigation only if Phase 2 showed VRR engaging.

**Phase 6 — Setting + plumbing** · est. 200k
Toggle (default OFF, Windows only), separate storage, strings, H6/H7 wiring; OFF ⇒ byte-identical mpv options to upstream (checked by a test/log assertion).

**Phase 7 — Test matrix + independent review** · est. 500k
Full matrix (fps × SDR/HDR × 1080p/2160p, DV/HDR10+ manual, audio device change, windowed/fullscreen, pause/seek/buffer, next episode, alt-tab, close, kill, sleep); 10-min soaks for 23.976 HDR 2160p, 59.94 SDR, 25 SDR; one [HUMAN] checklist; fresh-subagent review vs SPEC/FORK.

**Phase 8 — Upkeep** · est. 250k
FORK.md complete, patch export, updater off, Sentry off, side-by-side identity, CI job proposal, optional PR branch (no PR without approval).

## Verification (every step)
`scripts/verify.ps1 -Fast` (hook) → `-Full` → read-only `verifier` subagent (≤3 rounds) against the step's SPEC.md acceptance criteria; measure.ps1 numbers for display phases; [HUMAN] items batched once per phase. Nothing pushed; approval gate before each phase's code.
