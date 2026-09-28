# 09 — Kill test (research 01 §7), measured 2026-09-28

Owner present. Driver 616.92, Windows 11 26200, MO27Q28G over DisplayPort, HDR on, 10 bpc, G-SYNC "full screen only".
Tools: `scripts/rr-tools/` (observer 50 ms QDC poll + `WM_DISPLAYCHANGE`, switcher, restore). Raw CSV/logs:
`measurements/killtest-20260928-0808/` and `measurements/*J-kill`, `*switch240-close` (git-ignored).
Load note: an x265 encode was running on the CPU during A–J; call durations may be slightly high.

**Result: every way a `CDS_FULLSCREEN` caller can die reverts the mode, within ~0.2–0.45 s. `SetDisplayConfig` does not.**

Times are from the call start (switcher log `apply result ... in N ms` minus N; observer `WM_DISPLAYCHANGE` stamps).
Exact Hz is the `QueryDisplayConfig` target vSyncFreq. Blank: owner saw ≈ 1 s per switch, not timed per case.

| Case | Action | API return | WM_DISPLAYCHANGE | Stable at target (exact Hz) | Revert after end | HDR / bpc | Registry |
|---|---|---|---|---|---|---|---|
| A | `cds 240 restore` | 267 ms, 0 | +290 ms | +379 ms, 239.901 (239901/1000) | explicit restore → 279.961 (WM +0.04 s after restore call) | kept 1 / 10 | 280 throughout |
| B | `cds 240 exit` (no restore) | 253 ms | +273 ms | +364 ms, 239.901 | **0.35 s** after exit → 279.961 | kept | 280 |
| C | `cds 240 hang` + `taskkill /F` | 354 ms | +384 ms | +479 ms, 239.901 | **0.44 s** after kill → 279.961 | kept | 280 |
| D | `cds 240 hang` + Task Manager "End task" (owner) | 262 ms | +280 ms | +374 ms, 239.901 | **< 0.05 s** after end task → 279.961 | kept | 280 |
| E | `cds 240 crash` (null write, 0xC0000005) | 246 ms | +283 ms | +362 ms, 239.901 | **0.33 s** after crash → 279.961; no WerFault process running 4 s later | kept | 280 |
| F | control: `sdc 239901/1000 hang` + `taskkill /F` | 206 ms | +214 ms | +325 ms, 239.901 | **no revert** (6 s at 239.901) → `restore.exe --sdc` OK | kept | 280 |
| G | `cds 240 hang` + UAC prompt (owner clicked **Yes**) | 241 ms | +277 ms | +358 ms, 239.901 | mode **survived** the secure desktop; killed later → 279.961 after 0.45 s | kept | 280 |
| H | `cds 240 hang`, monitor off/on, then PC sleep/resume (owner) | 245 ms | +279 ms | +365 ms, 239.901 | **monitor off/on dropped the temporary mode while the caller was alive** → 279.961 (08:12:28.9, WM 08:12:29.25); sleep/resume stayed 280; after kill no change | kept | reads 0/60 for < 1 s while the monitor was absent, then 280 (no write) |
| I | P1 `cds 240 hang`, P2 `cds 120 hang`; kill P1, then P2 | P1 236 ms / P2 295 ms | P1 +257 / P2 +331 ms | P1 +355 ms 239.901; P2 +426 ms, 119.998 (119998/1000) | kill P1: **no change** (stays 119.998); kill P2: → **279.961** (registry), not 240 | kept | 280 |
| J | real app (`java.exe` + `player_bridge.dll`), in-app `CDS_FULLSCREEN` 240 at file-loaded, `Stop-Process -Force` | in-app (251 ms in the J' run) | 08:18:57.469 | 239.901 during the run | **0.23 s** after kill → 279.961 | kept | 280 |
| J' | same, normal app close (`*switch240-close`) | 251 ms, 0 | +268 ms | 239.901 | reverted at exit (WM 08:18:17.855) | kept | 280 |

Owner observations: blank ≈ **1 s** per switch (both directions); monitor OSD does not show the Hz when changing
(not checked further). No brightness pop reported.

## Conclusions
1. **R1/R10 confirmed:** `CDS_FULLSCREEN` is reverted by Windows when the owning process dies by any path (exit, TerminateProcess,
   End task, crash), including the real JVM with the bridge DLL. ⇒ **No watchdog and no next-launch marker needed.** Phase 4 still
   restores explicitly on every normal path (player screen dispose, window close, shutdown hook) so the revert does not wait for exit.
2. **"240" selects 239.901 Hz** (the only 240-class timing). 120 → 119.998. HDR and 10 bpc were kept in every switch.
3. **Last caller owns the mode (I):** the revert happens when the process that made the *latest* change dies, and goes to the
   registry mode, not to the previous caller's mode.
4. **New risk (H): a monitor power-cycle / hot-plug during playback silently drops the temporary mode** (back to 280) while
   Nuvio still thinks it is at 240. With `display-fps-override=239.9` that would mistime mpv. ⇒ Phase 4 must watch for display
   changes (WM_DISPLAYCHANGE or the QDC poll) and on an unexpected mode: drop the override (let mpv run at the real rate) or
   re-switch; never keep a stale override. Added to the Phase 4 race list.
5. **mpv does not re-detect `display-fps` after an external mode change** in the embedded `wid` window: at 239.901 Hz for 40 s,
   `display-fps` stayed 279.961 (run `*switch240-close`). ⇒ R8 confirmed: Phase 5 sets `display-fps-override` to the exact
   measured rate (or switches before VO init and verifies).
