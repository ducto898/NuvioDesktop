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

**Phase 2b — display-resample diagnosis spike (added 2026-09-28, D11; owner at the PC only for PresentMon UAC clicks)** · est. 150–250k
Why: a fixed 240 Hz with upstream audio sync is not smooth (75 % of frames at 10 refreshes, 11/9 bursts ~60 of 80 s,
PROGRESS "Fixed 240 Hz"). The project's value depends on display-synced timing, which is broken in the embedded player:
`video-sync=display-resample` gives estimated display fps ≈ 6 Hz windowed / ≈ 191 Hz fullscreen at 280 Hz, ~650
mistimed/min, `MsInPresentAPI` median 4.8 ms. Answer "can it be fixed, and how" before building Phases 3–4.
Measure-only: no product behaviour, no upstream edits beyond H1/H4, mode held by `switcher.exe` (240 fixed) or 280.

Hypotheses to test, cheapest first (each one knob change + one measure.ps1 run, windowed AND fullscreen):
- S1 **Run at the real target first.** Everything so far was at 280 Hz. Repeat display-resample at a fixed 239.901
  (mode set before start, so mpv reads it), windowed + fullscreen. Maybe 240 is enough headroom.
- S2 **Render cost per refresh.** display-resample re-renders every refresh through gpu-next (spline36, deband, HDR
  swapchain, tone mapping). Try cheaper settings one at a time (scale/cscale=bilinear, deband=no) and read nvidia-smi
  clocks: the GPU sat at 210–465 MHz, so it may be the power state, not the load.
- S3 **Swapchain / present queue.** mpv d3d11 options: `d3d11-flip`, `swapchain-depth`, `d3d11-sync-interval`,
  `video-timing-offset`; compare `MsInPresentAPI` and PresentMon PresentMode.
- S4 **Windowed-only collapse (≈ 6 Hz).** Occlusion/throttling by the full-size WebView2 overlay or DWM: check for
  DXGI_STATUS_OCCLUDED-style throttling in the mpv log. Test with a measure-only native knob that hides the WebView2
  child window for the run (no product change).
- S5 **Other sync modes:** `display-vdrop`, `display-desync` (diagnostic) and `display-resample-vdrop`, to separate
  "vsync measurement is wrong" from "presentation can't keep up".
Mechanism: a generic measure-only knob `NUVIO_RR_MEASURE_OPTS="k=v;k=v"` (applied as mpv properties before load) plus,
for S4, `NUVIO_RR_MEASURE_HIDE_OVERLAY=1`. Both need the owner's approval in the P2b criteria.
Pass (the spike succeeds): some option set gives, at 239.901 in BOTH windowed and fullscreen, over 120 s:
estimated display fps within 0.1 % of 239.901, mistimed + drops ≤ 1/min, no audio underruns, and PresentMon shows
≥ 99 % of 23.976 frames at exactly 10 refreshes. Also record GPU power (Q8).
Fail: documented cause + the options tried ⇒ owner decides: (a) proceed with the switch + audio sync only (smaller gain,
measured above), (b) try a deeper fix (e.g. a libmpv render-API path — large, likely upstream-diff heavy), or (c) stop.
Output: docs/research/10-display-resample-spike.md; PROGRESS measurements + decision. Verifier: ONE lean round
(criteria table + evidence folder list only).

**Phase 3 — Pure logic, tests first** · est. 250k
FpsSnapper (standard rates, ±0.1%), ModeSelector (highest k·f within ratio tolerance, same resolution + bpc, else none), VFR/unknown ⇒ no switch, SessionStateMachine (idle/switching/switched/restoring; same-target skip; restore bookkeeping), FailSafePolicy (every API failure ⇒ stay at current rate, play). Tests use the measured mode list (279.961, 239.901, 143.973, 119.998, 100.000, 59.951).

**Phase 4 — Native switching** · est. 500k
Enumeration, switch, verify, restore, settle, all hooks H2–H8 native side, JNI upcall, log sink, races (dispose mid-switch, two players, monitor move/PiP, sleep/resume, HDR toggle, driver reset → fail-safe), crash fallback per kill-test result.

**Phase 5 — mpv timing + OLED stability** · est. 400k
display-resample + exact display-fps-override; confirm via measure.ps1 that mpv uses the new rate with ~0 drops/mistimes (counted after the first 5 s, P2b-13 rule); VRR mitigation only if Phase 2 showed VRR engaging. **Phase 2b result (docs/research/10):** display-resample works only with the NVIDIA driver's Max Frame Rate off (the owner's global 200 fps cap collapses it) and, for 4K HDR at full quality (D12, no cheaper scalers), "Power management: Prefer maximum performance" (+~9 W). Plan both as Nuvio-only driver settings once Phase 8 gives the app its own exe, or document them as manual owner steps; detect/log the FRL value at start (read-only) so a capped setup is visible. Re-check the deferred PresentMon cadence clause with the owner present.

**Phase 6 — Setting + plumbing** · est. 200k
Toggle (default OFF, Windows only), separate storage, strings, H6/H7 wiring; OFF ⇒ byte-identical mpv options to upstream (checked by a test/log assertion).

**Phase 7 — Test matrix + independent review** · est. 500k
Full matrix (fps × SDR/HDR × 1080p/2160p, DV/HDR10+ manual, audio device change, windowed/fullscreen, pause/seek/buffer, next episode, alt-tab, close, kill, sleep); 10-min soaks for 23.976 HDR 2160p, 59.94 SDR, 25 SDR; one [HUMAN] checklist; fresh-subagent review vs SPEC/FORK.
Re-plan for the 240 Hz desktop default (Q27: 24/30/60 fps no longer switch). Collect every `rate-off pN <n> samples, worst …,
recovered` log line (Q28, since `c1623bc8`) across all runs and soaks: how long a stall bends mpv's rate estimate on this PC;
set `ResampleHealth.RATE_ERROR_SAMPLES` (now 5) from that data.

**Phase 8 — Upkeep** · est. 250k
FORK.md complete, patch export, updater off, Sentry off, side-by-side identity, CI job proposal, optional PR branch (no PR without approval).

## Verification (every step)
`scripts/verify.ps1 -Fast` (hook) → `-Full` → read-only `verifier` subagent (≤3 rounds) against the step's SPEC.md acceptance criteria; measure.ps1 numbers for display phases; [HUMAN] items batched once per phase. Nothing pushed; approval gate before each phase's code.
