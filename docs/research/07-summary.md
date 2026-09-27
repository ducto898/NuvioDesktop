# 07 — Phase 1 research summary and recommendations

Date: 2026-09-28. Details, sources and confidence labels are in files 00–06; this file only
condenses them. "Measured" = run on the owner's PC (read-only); "sourced" = docs/source code;
"inferred" = reasoning, still to be verified.

## Recommendations (for owner approval)

| # | Topic | Recommendation | Basis | File |
|---|---|---|---|---|
| R1 | Switch API | `ChangeDisplaySettingsExW(\\.\DISPLAYn, &dm, NULL, CDS_FULLSCREEN, NULL)`; restore with `ChangeDisplaySettingsExW(name, NULL, NULL, 0, NULL)`. Never write the registry (no Kodi-style "Win8 workaround", no `SetDisplayConfig` + save). | Sourced: only API with evidence of auto-revert on process death (ReactOS win32k `gpFullscreen`, MPC-HC, Jellyfin). ~80% confidence — **must be proven by the kill test** (01 §7). | 01 |
| R2 | Exact rates | Read exact rationals via `QueryDisplayConfig` / DXGI `GetDisplayModeList1`; `DEVMODE` integer Hz is rounded to nearest (143.973 → 144) and must never be used for maths. Verify the mode after every switch. | Measured | 02 |
| R3 | Owner's modes | 280 = 279.961, 240 = 239.901, 144 = 143.973, 120 = 119.998, **100 = 100.000**, 60 = 59.951 Hz. 100 Hz is visible to the APIs. No 1000/1001 timings exist. Bit depth: compare `bitsPerColorChannel` (advanced colour), not `dmBitsPerPel` (always 32). | Measured | 02 |
| R4 | Mode selection | Snap fps to a standard rate first (23.976, 24, 25, 29.97, 30, 47.95, 48, 50, 59.94, 60; within ~0.1%), then pick the **highest** mode r with `|r/(k·f) − 1| ≤ ~0.1%` for integer k. Ratio tolerance, not Hz. Highest-multiple is our own policy (Kodi/Jellyfin/NuvioTV prefer 1×) — owner's spec, fully unit-tested. Gives 240 for 23.976…60 and 100 for 25/50 on this monitor. | Sourced + computed | 04 |
| R5 | Fps detection | `container-fps`/`track-list/N/demux-fps` at preload; missing ⇒ don't switch. After playback starts, cross-check `estimated-vf-fps` (±0.5%); disagreement ⇒ VFR/bad header ⇒ log, keep the rate we're at (no mid-play switch). | Sourced (mpv source at bundled commit) | 03 |
| R6 | When to switch | Inside mpv's `on_preloaded` hook (`mpv_hook_add`), i.e. before mpv creates its video output, so it starts at the new rate. Hold playback during switch + settle. | Sourced (Jellyfin uses `on_load`) | 03, 04 |
| R7 | Settle | Poll `QueryDisplayConfig` (mode + HDR state) until two reads agree, cap ~4 s (NuvioTV pattern), then an extra delay to be measured on this OLED in Phase 2/4. | Sourced; value TBD by measurement | 01, 04 |
| R8 | mpv timing | `video-sync=display-resample`, `interpolation=no`, set per file only when we switched (feature OFF or no switch ⇒ upstream untouched). Also set `display-fps-override` to the exact measured rate, since mpv's embedded child window probably never gets `WM_DISPLAYCHANGE`. 23.976 @ 239.901 Hz ⇒ 0.058% speed-up, ~1 cent pitch, no dropped/repeated frames. | Sourced; WM_DISPLAYCHANGE point **uncertain → measure** | 03, 04 |
| R9 | Session / episodes | Keep the switched mode across native-player re-creations (each episode gets a new native player); restore when the player *screen* is left (Compose surface `onDispose`), on window close and on JVM shutdown. State lives in process-global native state / a Kotlin object. Same target ⇒ no switch. | Codebase map | 06 |
| R10 | Crash / kill | Rely on Windows' CDS_FULLSCREEN revert **if the kill test proves it**. If not: a marker file written before switching, checked on next launch. Watchdog only if clearly needed. | 01 §7 test | 01 |
| R11 | OLED / VRR | No global NVIDIA changes. Likely safe today if G-SYNC is "Full screen only" (the WebView2 overlay forces DWM composition ⇒ fixed refresh). Measure first (Phase 2). Fallback if VRR engages: keep-alive presents while paused (GPU cost). mpv presents nothing while paused. | Sourced + inferred | 05 |
| R12 | DV / HDR | Bundled libplacebo v7.357 supports DV P5/P8 reshaping (P7 FEL → HDR10 base); HDR10+ only used for tone mapping. mpv re-reads output HDR state per frame. Verify visually in Phase 7; brief SDR/HDR flash on switch possible [inferred]. | Sourced | 03 |
| R13 | Logging | The bridge has **no native logging** today. Add a small log sink in the new native file (+ mpv `log-file` for measurements). | Codebase | 06 |
| R14 | Diff footprint | ~8 hook sites of 1–3 lines (player_bridge.cpp include + event-loop + pause hold; PlayerEngine.desktop.kt dispose/setting; Main.kt close) + setting plumbing. `NativePlayerController.kt` (40 upstream commits in 2 months) and `build.gradle.kts` untouched; `#pragma comment(lib, …)` for dxgi. Setting in a separate small repository, **not synced** (per-monitor value). | Codebase | 06 |

## Bundled versions (measured from the DLL)
mpv v0.40.0-465-gf6c116491 (2025-11-22), libplacebo v7.357.0, FFmpeg N-121828 — a
mpv-winbuild-cmake nightly (exact origin not recoverable).

## Risks
- Native shutdown gives mpv's event thread only 3 s before destroying it: the settle wait must
  abort on stop or run on its own thread (use-after-free otherwise).
- Kodi #21314 / Jellyfin #675: HDR state broken after restore — test explicitly.
- PiP can move video to another monitor.
- Switching costs a 1–3 s black screen at playback start (every implementation has it).

## Open questions for the owner
See PROGRESS.md "Open questions".
