# 04 - Prior art: how other players match the display refresh rate

- Date: 2026-09-28
- Scope: Kodi, MPC-HC / MPC-BE, madVR, Jellyfin Media Player (JMP) / Plex Media Player (PMP), NuvioTV, Stremio, and mpv user scripts. For each: how it picks a mode, which API it calls, how it waits after the switch, how it restores, and its known bugs.
- Method: read-only. Source code was fetched with `gh`/raw GitHub at pinned commits, so every link is a permalink. Issues were read with `gh api`. Some forum and docs pages came from the web. Nothing was posted, starred or reacted to.
- Labels: **[src]** = read in the linked source. **[issue]** = a bug report or PR discussion. **[doc]** = official docs. **[inferred]** = my reasoning, not verified. **[uncertain]** = weak or conflicting evidence.
- Related: the API choice and how Windows cleans up after a crash are covered in [01](01-windows-mode-switch-api.md). The owner's measured modes (239.901 / 143.973 / 119.998 / 100.000 / 59.951 / 279.961 Hz) are in [02](02-mode-enumeration.md). VRR/OLED is covered in [05](05-oled-vrr-flicker.md). This file does not repeat them.

Pinned commits used in links:

| Project | Repo @ commit |
|---|---|
| Kodi | [`xbmc/xbmc@05b1d49`](https://github.com/xbmc/xbmc/tree/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96) |
| MPC-HC | [`clsid2/mpc-hc@5e9fbd7`](https://github.com/clsid2/mpc-hc/tree/5e9fbd7524060ea2ca74ece023f44a7e47da1e5c) |
| MPC-BE | [`Aleksoid1978/MPC-BE@b9eaaf9`](https://github.com/Aleksoid1978/MPC-BE/tree/b9eaaf9606ede1f52119ff2d218c606e434cd487) |
| JMP (repo now named `jellyfin-desktop`) | [`jellyfin/jellyfin-desktop@2cb4a44`](https://github.com/jellyfin/jellyfin-desktop/tree/2cb4a4456fd29b5b62d825ef0e5df93ed6913328) |
| NuvioTV | [`NuvioMedia/NuvioTV@cc27f00`](https://github.com/NuvioMedia/NuvioTV/tree/cc27f0028b75d6e33ddb614368c0035f4608e2ee) |
| mpv | [`mpv-player/mpv@a1bf4b6`](https://github.com/mpv-player/mpv/tree/a1bf4b6559d6e644b967c22d87232dc77c7dd442) |
| mpv scripts | [`kevinlekiller/mpv_scripts@2bd1b3d`](https://github.com/kevinlekiller/mpv_scripts/tree/2bd1b3d369b136b9f7dc6fab1a2ca4fa457c4849), [`CogentRedTester/mpv-changerefresh@603eaba`](https://github.com/CogentRedTester/mpv-changerefresh/tree/603eaba9b8967ef329887850f57c47b85d5bb4fa), [`lvml/mpv-plugin-xrandr@6057558`](https://github.com/lvml/mpv-plugin-xrandr/tree/60575586b0589a87d62cf8dc5e4f697628f5c4fb) |

---

## TL;DR

1. **No open-source player picks the highest integer multiple.** Kodi tries only 1×, 2× and 2.5×, in that order. JMP/PMP score the modes and prefer the exact 1× match. NuvioTV tries 1×, then 2×, then 2.5×. xrandr.lua tries 1× to 3×, lowest first. The only precedent for "240 Hz for 24p" is madVR with a user-written mode list (closed source, [uncertain]) and a rejected Kodi PR. **Choosing the highest multiple is our own policy and needs its own tests.**
2. **Everyone uses `ChangeDisplaySettingsEx` with an integer `dmDisplayFrequency`.** Nobody uses `SetDisplayConfig`. Each project works around the integer limit in its own way: Kodi's registry trick for 24/48/60, the "(N+1)/1.001" guesses in Kodi, JMP and mpv. [02](02-mode-enumeration.md) shows that these guesses are wrong for the owner's monitor.
3. **Restore on "session end" is where every project gets hurt.** It fails when a player window stays open (madVR, Kodi "On start"), when the rate is restored at post-play and then switched back for the next episode (PMP #814), and with HDR (Kodi #21314, JMP #675). The best pattern is JMP's: on end-file, restore only if mpv is `idle-active`. That check doubles as the same-target skip between episodes.
4. **Settle delays in practice**: 0 by default (Kodi, MPC-HC, MPC-BE, each configurable), **3 s** (JMP's hidden default, change-refresh.lua), 2 s fixed (autospeedwin). NuvioTV instead polls the mode until it is stable: 60 ms × 2 stable polls, 4 s timeout. Nobody measures when the display is actually ready.
5. **Every project that runs display-sync overrides mpv's display-fps.** JMP sets `display-fps-override` from its own integer mode table, which is itself a bug at 119/239 Hz. mpv refreshes `display-fps` only on `WM_DISPLAYCHANGE` or a monitor change, and `--wid` embedding makes that delivery [uncertain].
6. **Wrong fps in → wrong switch or a second switch mid-playback.** Matroska files with millisecond timestamps declare 23.810 fps for 23.976 content, and mpv reads that value unchanged (Kodi PR #28836, mpv `demux_mkv.c`). Snap the fps to the standard rates before choosing a mode.
7. **With `video-sync=display-resample`, a 23.976-vs-24 mismatch costs no dropped or repeated frames.** mpv retimes anything within 1 % ([src](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/player/video.c#L695-L706)). The "one hitch every ~42 s" problem exists only with `video-sync=audio` or with bitstream passthrough.

---

## 1. Kodi: "Adjust display refresh rate"

### 1.1 Settings

| Setting (id) | Values / default | Source |
|---|---|---|
| `videoplayer.adjustrefreshrate` | Off / Always / **On start/stop (default)** / On start | [settings.xml L50-62](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/system/settings/settings.xml#L50-L62) [src] |
| `videoscreen.delayrefreshchange` ("Delay after change of refresh rate") | 0 (default) to 20.0 s in 0.1 s steps (`MAX_REFRESH_CHANGE_DELAY = 200`) | [settings.xml L2981-2988](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/system/settings/settings.xml#L2981-L2988), [DisplaySettings.cpp L52](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/settings/DisplaySettings.cpp#L52), [L767-775](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/settings/DisplaySettings.cpp#L767-L775) [src] |
| `videoscreen.whitelist` (+ `whitelistdoublerefreshrate`, `whitelistpulldown`, both default false) | list of modes | [settings.xml L3253-3280](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/system/settings/settings.xml#L3253-L3280) [src] |
| `videoplayer.usedisplayasclock` ("Sync playback to display") | default false. Its help text says it disables passthrough because audio may be resampled | [settings.xml L73-77](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/system/settings/settings.xml#L73-L77) [src] |
| advancedsettings `<refresh><override>` (fpsmin/fpsmax → refreshmin/max) plus a fallback | manual override | [Resolution.cpp L308-362](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/Resolution.cpp#L308-L362) [src] |

The brief's name "Pause during refresh rate change" does not appear in the current strings. The current label is "Delay after change of refresh rate" (#13550), with the help text "Delay of reset event after a change of refresh rate". "Pause during refresh rate change" is probably the name from older Kodi releases [uncertain].

### 1.2 What the modes mean (from [GraphicContext.cpp `SetFullScreenVideo` L321-363](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/GraphicContext.cpp#L321-L363)) [src]

| Mode | Leaving fullscreen video while playing (back to the GUI) | Stop |
|---|---|---|
| Always | GUI goes back to the desktop rate. The rate switches again when you return to video | restore |
| On start/stop | stays at the video rate | restore |
| On start | stays at the video rate | **never restores** (restores only on exit) |

- The switch runs only when `IsFullScreenRoot()` is true, i.e. Kodi itself is fullscreen. In a windowed Kodi nothing switches ([VideoPlayer.cpp L4444-4458](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/cores/VideoPlayer/VideoPlayer.cpp#L4444-L4458)). Going windowed forces `RestoreDesktopResolution` ("we do not support resolution change in windowed mode", [WinSystemWin32.cpp L609-615](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32.cpp#L609-L615)) [src].
- The frame rate comes from the stream hint before the first frame, and is doubled for interlaced content ([VideoPlayer.cpp L4450-4453](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/cores/VideoPlayer/VideoPlayer.cpp#L4450-L4453)) [src].

### 1.3 Mode choice: `CResolutionUtils::ChooseBestResolution`

[Resolution.cpp L57-306](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/Resolution.cpp#L57-L306) [src]:

1. Overrides from advancedsettings, then the override fallback, then the **whitelist**.
2. With no user whitelist, Kodi builds a default one from all modes at the current resolution or larger, **excluding 25 and 29.97 Hz** ("kodi cannot cope with them on playback start… ugly double switching") ([L86-107](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/Resolution.cpp#L86-L107)).
3. The search order is fixed and **the first tier that matches wins**: exact rate at the video resolution → **2×** → **2.5×** (3:2) → the same three at the desktop resolution. With a user whitelist, 2× and 2.5× apply only if their toggles are on ([L122-303](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/Resolution.cpp#L122-L303)).
4. **The tolerance is absolute: `|refresh − k·fps| < 0.01 Hz`** (`MathUtils::FloatEquals`, [MathUtils.h L210-213](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/utils/MathUtils.h#L210-L213)). So 23.976 and 24.000 are separate (Δ = 0.024). The 0.01 Hz absolute tolerance is **not scaled by k**: at 2× the effective tolerance is 0.01/48, tighter than at 1× [inferred].
5. **Nothing above 2.5× is ever chosen.** A 23.976 video tops out at 59.94 Hz. Kodi PR [#24576 "Allow Kodi to use higher refresh rates"](https://github.com/xbmc/xbmc/pull/24576) (still open) adds "highest multiple preferred". A maintainer replied that among equally good matches Kodi keeps the first and lowest one, and that users should remove the lower rates from the whitelist instead [issue].
6. `RefreshWeight()` still exists: multiple-distance plus a `round/10000` penalty above 60 Hz ([L365-387](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/Resolution.cpp#L365-L387)). In the current code it only fills the log's "weight" for overrides. Older Kodi versions scored all modes with it [inferred from the code shape; uncertain].

### 1.4 Windows API and the integer-Hz problem

- **`ChangeDisplaySettingsExW(..., CDS_FULLSCREEN)`** with `dmDisplayFrequency = (int)fRefreshRate` ([WinSystemWin32.cpp L909-1000](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32.cpp#L909-L1000)) [src].
- **"Windows 8+ workaround" for 24.0, 48.0 and 60.0 Hz**: asking for integer 24 can land on 23.976. So Kodi writes the mode to the registry (`CDS_UPDATEREGISTRY|CDS_NORESET`), applies it from the registry with `CDS_FULLSCREEN`, then writes the old registry values back ([L937-977](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32.cpp#L937-L977)) [src]. madVR saw the same OS bug in Direct3D exclusive mode, and it was fixed only in Windows 10 Fall Creators Update ([madshi bug 90](http://bugs.madshi.net/view.php?id=90)) [issue]. **A crash between the two registry writes would leave the registry changed** [inferred].
- Fractional guess: an integer N where (N+1) is divisible by 24 or 30 is treated as (N+1)/1.001 ([L1054-1058](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32.cpp#L1054-L1058)) [src]. [02](02-mode-enumeration.md) shows this is wrong on the owner's monitor.
- **D3D driver hook**: Kodi hooks the UMD `OpenAdapter10_2`/`CreateResource` and rewrites the primary's refresh rational to the exact NTSC fraction when it is off by 0.05-10 % ([WinSystemWin32DX.cpp L340-368](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32DX.cpp#L340-L368)) [src]. This applies to exclusive fullscreen only [inferred]. It is overkill for us.

### 1.5 The delay after a switch

`ResolutionChanged()` → `OnDisplayLost()` + `OnDisplayBack()`. If `delayrefreshchange > 0`, the display-reset event is held back that long ([L1181-1192](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32.cpp#L1181-L1192), released in [WinSystemWin32DX.cpp L73-75](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32DX.cpp#L73-L75)). VideoPlayer stays paused while the display is lost (`GENERAL_PAUSE, m_displayLost`) [src]. **The default is 0**, so Kodi resumes as soon as the OS reports the change.

### 1.6 Restore

- On stop: depends on the mode (§1.2). On exit: `DestroyWindowSystem()` → `RestoreDesktopResolution()` ([L107-116](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32.cpp#L107-L116)). It restores to the monitor details captured at enumeration, with the rate rebuilt from the integer guess ([L734-758](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32.cpp#L734-L758)) [src]. On a crash Kodi relies on the `CDS_FULLSCREEN` process-exit revert (see [01](01-windows-mode-switch-api.md)) [inferred].
- Moving to another monitor restores the old monitor first ([L577-584](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32.cpp#L577-L584)) [src].

### 1.7 HDR

- Windows HDR is toggled separately. `SetTogglingHDR` now also calls `ResolutionChanged()`, with a 6 s HDR timer ([L1333-1342](https://github.com/xbmc/xbmc/blob/05b1d49dddf4fe8da196fc499e1ba2353ce1bd96/xbmc/windowing/windows/WinSystemWin32.cpp#L1333-L1342)). This came from PR [#28672](https://github.com/xbmc/xbmc/pull/28672): an HDR toggle plus a refresh switch **meant two HDMI renegotiations** (about 2.2 s for the refresh change, then a swapchain rebuild), and passthrough audio came up dead [issue].

### 1.8 Known issues (Kodi)

| Issue | What happened |
|---|---|
| [#21314](https://github.com/xbmc/xbmc/issues/21314) | Win11 22H2: after **HDR** playback the rate is **not restored on stop**. It stays at 24 Hz until Kodi is closed (the user says closing Kodi restores 60 Hz). SDR is fine [issue] |
| [PR #28836](https://github.com/xbmc/xbmc/pull/28836) | MKV with 1 ms timestamps declares 42 ms per frame, i.e. **23.810 fps** (and 30.303, 58.824). That matches no whitelisted mode, so playback starts at the GUI rate. Seconds later the measured fps triggers a **second switch mid-playback**: black screen, audio re-open, receiver re-lock. The fix snaps 1000/N declared rates to the standard rate and uses the `NUMBER_OF_FRAMES`/`DURATION` tags to tell 23.976 from 24 [issue] |
| [#24608](https://github.com/xbmc/xbmc/issues/24608) | Window moved to another display with a different rate → A/V desync. The frame rate/refresh wasn't re-read on the screen change [issue] |
| [PR #24576](https://github.com/xbmc/xbmc/pull/24576) | No multiples above 2.5× (see §1.3) [issue] |
| Default whitelist | Excludes 25 and 29.97 Hz to avoid double switching on interlaced content [src] |

---

## 2. MPC-HC and MPC-BE: "Auto-change fullscreen monitor mode"

### 2.1 MPC-HC

- **A user-edited table of fps ranges → modes.** The defaults are the current mode for 0-0 (the "default mode"), 23.500-23.981, 23.982-24.499, 24.500-25.499, 29.500-29.981, 29.982-30.499, 49.500-50.499, 59.500-59.945 and 59.946-60.499, **all pre-filled with the current mode**, so the user has to pick the targets ([PPageFullscreen.cpp L123-137](https://github.com/clsid2/mpc-hc/blob/5e9fbd7524060ea2ca74ece023f44a7e47da1e5c/src/mpc-hc/PPageFullscreen.cpp#L123-L137)) [src]. Each row also carries an **audio delay** (`msAudioDelay`) [src].
- The fps is `10000000 / AvgTimePerFrame` from any connected output pin. The first matching checked row wins; if none matches, the mode goes back to row 0 ([MainFrm.cpp `AutoChangeMonitorMode` L13518-13564](https://github.com/clsid2/mpc-hc/blob/5e9fbd7524060ea2ca74ece023f44a7e47da1e5c/src/mpc-hc/MainFrm.cpp#L13518-L13564)) [src].
- **The switch runs only when entering fullscreen or opening a file while fullscreen** ([L4453](https://github.com/clsid2/mpc-hc/blob/5e9fbd7524060ea2ca74ece023f44a7e47da1e5c/src/mpc-hc/MainFrm.cpp#L4453), [L13181](https://github.com/clsid2/mpc-hc/blob/5e9fbd7524060ea2ca74ece023f44a7e47da1e5c/src/mpc-hc/MainFrm.cpp#L13181)) [src].
- **`SetDispMode`** ([L13471-13514](https://github.com/clsid2/mpc-hc/blob/5e9fbd7524060ea2ca74ece023f44a7e47da1e5c/src/mpc-hc/MainFrm.cpp#L13471-L13514)): it skips the call if size, bpp and frequency already match, so the same target is skipped for free. Otherwise it calls `ChangeDisplaySettingsExW` with **`CDS_FULLSCREEN` if "Restore resolution on program exit" is on (default TRUE), else flags 0** ([AppSettings.cpp L1816-1819](https://github.com/clsid2/mpc-hc/blob/5e9fbd7524060ea2ca74ece023f44a7e47da1e5c/src/mpc-hc/AppSettings.cpp#L1816-L1819)) [src].
- **Delay**: `uDelay` in seconds, **default 0**. If a switch happens during playback, MPC-HC pauses; after `DISPLAY_MODE_AUTOCHANGED` it schedules play after `uDelay` s. When opening a file it holds the play/pause for `uDelay` ([L801-821](https://github.com/clsid2/mpc-hc/blob/5e9fbd7524060ea2ca74ece023f44a7e47da1e5c/src/mpc-hc/MainFrm.cpp#L801-L821), [L4601-4615](https://github.com/clsid2/mpc-hc/blob/5e9fbd7524060ea2ca74ece023f44a7e47da1e5c/src/mpc-hc/MainFrm.cpp#L4601-L4615)) [src]. The wait is a fixed timer, not a readiness check.
- **Restore when leaving fullscreen only if "Apply default mode at fullscreen exit" is on, and that defaults to FALSE** ([L13217-13219](https://github.com/clsid2/mpc-hc/blob/5e9fbd7524060ea2ca74ece023f44a7e47da1e5c/src/mpc-hc/MainFrm.cpp#L13217-L13219), AppSettings L1817). Otherwise the mode is restored only when the process exits. **This is the owner's "stuck at 240 Hz while a player window is open" complaint, built in by default** [src + inferred].
- The API is integer Hz only (`dm.freq = dmDisplayFrequency`), so 23 vs 24 are told apart only by what the driver lists [src].

### 2.2 MPC-BE differences

- Its fps source is MediaInfo's fps if known, else AvgTimePerFrame, **doubled for interlaced or MPCVR "doubleRate"** ([MainFrm.cpp L11366-11441](https://github.com/Aleksoid1978/MPC-BE/blob/b9eaaf9606ede1f52119ff2d218c606e434cd487/src/apps/mplayerc/MainFrm.cpp#L11366-L11441)) [src].
- The table is per monitor, keyed by monitor device ID [src].
- **When D3D exclusive fullscreen is possible (`bForceRegistryMode`), or "restore after exit" is off, it writes the mode to the registry** (`CDS_UPDATEREGISTRY|CDS_NORESET`, then apply) ([L11504-11511](https://github.com/Aleksoid1978/MPC-BE/blob/b9eaaf9606ede1f52119ff2d218c606e434cd487/src/apps/mplayerc/MainFrm.cpp#L11504-L11511)) [src]. That mode **survives a crash and a reboot**. This is a clear anti-pattern for us.
- `iDMChangeDelay` is 0-9 s, default 0. `fRestoreResAfterExit` defaults to true ([AppSettings.cpp L528](https://github.com/Aleksoid1978/MPC-BE/blob/b9eaaf9606ede1f52119ff2d218c606e434cd487/src/apps/mplayerc/AppSettings.cpp#L528), [L565](https://github.com/Aleksoid1978/MPC-BE/blob/b9eaaf9606ede1f52119ff2d218c606e434cd487/src/apps/mplayerc/AppSettings.cpp#L565), [L1350](https://github.com/Aleksoid1978/MPC-BE/blob/b9eaaf9606ede1f52119ff2d218c606e434cd487/src/apps/mplayerc/AppSettings.cpp#L1350)) [src].

---

## 3. madVR display modes (closed source)

Behaviour from third-party guides and madshi's tracker, not from source code:

| Behaviour | Evidence |
|---|---|
| The user types a list such as `1080p23, 1080p24, 1080p60, 2160p23…`. The number is the **Windows integer rate** (23 = 23.976, 24 = 24.000) | [AVS thread](https://www.avsforum.com/threads/what-do-i-set-madvr-display-modes-to-the-tv-display-card-or-both.1689242/) (search summary; page not fetched) [uncertain] |
| "switch to matching display mode… when playback starts / when media player goes fullscreen" | [anime.my guide](https://anime.my/tutorials/madvr/) [doc-ish] |
| "restore original display mode… when media player is closed / when media player leaves fullscreen" | same guide. With "when closed", **an open but idle player window keeps the mode**, which matches the owner's 240 Hz complaint [inferred] |
| "treat 25p movies as 24p (requires Reclock or VideoClock)". It only changes the **mode choice** (25p → the 24p mode). The player/audio renderer must slow playback down | [anime.my](https://anime.my/tutorials/madvr/), [JRiver madVR expert guide](https://wiki.jriver.com/index.php/Madvr_expert_guide) (search summary; 403 on fetch) [uncertain] |
| How it chooses between several valid multiples (e.g. 120 vs 240 for 24p) | **Not documented anywhere I found** [uncertain]. The owner observes 23.976/24/30/60 → 240 and 25/50 → 100. madVR picks from the modes listed, so the user's list decides [inferred] |
| "Delay playback start…" | I found no source for a display-mode-specific delay. madVR has "delay playback start until render queue is full" (a rendering option), which is a different thing [uncertain] |
| Known bugs | [bug 90](http://bugs.madshi.net/view.php?id=90): exclusive fullscreen on Win8 set 23/59 instead of 24/60 while the OS **reported** success, fixed in Win10 FCU. [bug 706](https://bugs.madshi.net/view.php?id=706): Win11 + MPC-HC, switching did nothing on the old build. On the beta, "going back to the previous mode fails every time" [issue] |

---

## 4. Jellyfin Media Player / Plex Media Player (Qt + mpv, `DisplayManagerWin`)

JMP is PMP's code base. The file headers still say "konvergo", PMP's codename ([DisplayManagerWin.cpp L1-6](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/display/win/DisplayManagerWin.cpp#L1-L6)) [src]. The current Plex HTPC is closed source [uncertain].

### 4.1 Enumeration and API

- Modes come from `EnumDisplaySettingsExW`. **Only 23, 29 and 59 are converted to (N+1)/1.001**; 119, 143 and 239 stay integers ([L16-32](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/display/win/DisplayManagerWin.cpp#L16-L32)) [src].
- Switch: `ChangeDisplaySettingsExW(adapter, &mode, NULL, CDS_FULLSCREEN, NULL)` ([L92-120](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/display/win/DisplayManagerWin.cpp#L92-L120)) [src].
- Which monitor: the one containing the **centre of the app window**, matched against each adapter's `dmPosition`/size ([L175-203](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/display/win/DisplayManagerWin.cpp#L175-L203), [DisplayComponent.cpp L237-257](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/display/DisplayComponent.cpp#L237-L257)) [src].

### 4.2 Scoring: `DisplayManager::findBestMatch`

[DisplayManager.cpp L80-200](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/display/DisplayManager.cpp#L80-L200), weights in [DisplayManager.h L75-84](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/display/DisplayManager.h#L75-L84) [src]:

| Term | Weight | Test |
|---|---|---|
| Same resolution and bpp | 1000 (required: the winner must score > 1000) | |
| Exact | 200 | `|r − fps| ≤ 0.01` |
| Exact multiple | 75 | `factor = lrint(r)/lrint(fps)` (integer division), `|factor·fps − r| < 0.01·factor` |
| Close | 50 | `|r − fps| ≤ 0.5` |
| Approx multiple | 25 | same, tolerance 1 Hz |
| Interlace match | 10 | |
| Current mode | 5 | |

- **The lowest valid multiple wins ties**: the first mode with a strictly higher weight is kept, and the exact 1× match scores highest. So 24p never goes to 120 or 240 while 24 Hz exists [src + inferred].
- The multiple tolerance was tightened from 0.1 to 0.01·factor in PMP [PR #757](https://github.com/plexinc/plex-media-player/pull/757). Before, 29.97 content chose 60 Hz over 59.94 Hz [issue].
- `refreshrate.avoid_25hz_30hz` (hidden, default **true**) skips 25/30 Hz so that 50/60 wins ([PMP #415](https://github.com/plexinc/plex-media-player/issues/415), [settings_description.json L285-299](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/resources/settings/settings_description.json#L285-L299)) [src/issue].
- Integer-division bug [inferred]: for fps 23.976 and r = 119.88, which Windows reports as "119" and JMP keeps as 119, `factor = 119/24 = 4` and `4 × 23.976 = 95.9`, so it gets no multiple credit. Harmless only because 1× wins anyway.

### 4.3 Timing and delay: the mpv `on_load` hook

- The switch runs inside mpv's **`on_load` hook**, before demux or decoder init. JMP then waits `refreshrate.delay` seconds (hidden, **default 3**) before `hook-continue`. The comment explains why: "mode changing can take some time, during which the screen is black, and initializing hardware decoding could fail" ([PlayerComponent.cpp L666-686](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/player/PlayerComponent.cpp#L666-L686), [L357-398](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/player/PlayerComponent.cpp#L357-L398)) [src].
- The fps comes from the **server metadata** (`metadata["frameRate"]`), not from mpv ([L294](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/player/PlayerComponent.cpp#L294)) [src].
- The switch runs **only if the whole client is in fullscreen mode** (the `main.fullscreen` setting) ([L367-376](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/player/PlayerComponent.cpp#L367-L376)), which [JMP #91](https://github.com/jellyfin/jellyfin-desktop/issues/91) confirms [src/issue].
- After switching, `updateVideoConfiguration()` sets **`display-fps-override` = JMP's own table value** ([L1464-1465](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/player/PlayerComponent.cpp#L1464-L1465)). At 239.901 Hz that would be "240", and mpv's docs warn that "setting an incorrect value (even if slightly incorrect) can ruin video playback" ([options.rst L1316-1324](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/DOCS/man/options.rst#L1316-L1324)) [src/doc + inferred]. JMP also picks the audio delay per display rate (24/25/50/normal) ([L1072-1086](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/player/PlayerComponent.cpp#L1072-L1086)) [src].

### 4.4 Restore

- `switchToBestVideoMode` saves the original mode only on the first switch (`m_lastVideoMode < 0`). So a chain of switches still restores to the **real** original ([DisplayComponent.cpp L104-153](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/display/DisplayComponent.cpp#L104-L153)) [src].
- On `MPV_EVENT_END_FILE` a 0 ms timer runs `onRestoreDisplay`, which **restores only if `idle-active` is true**: "If the player will in fact start another file… don't restore." Starting a new file stops any pending restore timer ([PlayerComponent.cpp L400-405](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/player/PlayerComponent.cpp#L400-L405), [L529-531](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/player/PlayerComponent.cpp#L529-L531), [L385](https://github.com/jellyfin/jellyfin-desktop/blob/2cb4a4456fd29b5b62d825ef0e5df93ed6913328/src/player/PlayerComponent.cpp#L385)) [src]. **This is the cleanest same-target and next-episode handling found.**
- For crashes it relies on `CDS_FULLSCREEN` [inferred].

### 4.5 Known issues (JMP/PMP)

| Issue | What happened |
|---|---|
| [JMP #675](https://github.com/jellyfin/jellyfin-desktop/issues/675) | Win11 HDR: entering or leaving fullscreen blanks the screen like an exclusive-mode change, and **Windows HDR settings end up "borked" on exit**. The trigger is OpenGL + refresh-rate sync together. Another user reports **"each new episode flickering both monitors"** [issue] |
| [JMP #761](https://github.com/jellyfin/jellyfin-desktop/issues/761) | The GUI renders at the video frame rate (20-30 fps) during playback. The UI is tied to the video presentation [issue] |
| [PMP #814](https://github.com/plexinc/plex-media-player/issues/814) | The rate is restored **as soon as the post-play screen appears**. On slow TVs the countdown is almost over before the TV settles, then the next episode switches back. Proposal: switch on start, restore only when the player closes [issue] |
| [PMP #754](https://github.com/plexinc/plex-media-player/issues/754) | Live TV + mode switching → a long delay on skip. Same "too eager" switching [issue] |
| [PMP #772](https://github.com/plexinc/plex-media-player/issues/772) | Windows: jerky 23.976 playback with switching + display sync. It started after an ANGLE/Qt DLL bump [issue] |
| [PMP #247](https://github.com/plexinc/plex-media-player/issues/247) | The pause after a switch stopped working ("Unable to retrieve main display"). The TV was blank for 2-3 s, so the start of every video was missed [issue] |

---

## 5. NuvioTV (Android TV AFR), the sibling app by the same maintainer

[FrameRateUtils.kt](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt) [src]:

| Aspect | Implementation |
|---|---|
| Modes | `OFF / START / START_STOP` ([PlayerSettingsDataStore.kt L381-383](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt#L381-L383)). Only `START_STOP` restores when the player is disposed. `START` just forgets the original ([PlayerScreen.kt L459-472](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt#L459-L472)) |
| fps snapping | `snapToStandardRate`: 23.90-23.988 → 24000/1001, 23.988-24.1 → 24, 29.90-29.985 → 30000/1001, 59.9-59.97 → 60000/1001, and so on ([L460-473](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L460-L473)). A probe near 24 is decided by the **average frame duration** (±120 µs) ([L475-490](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L475-L490)). It also has its own MKV/MP4 range-probe to get the fps before playback |
| Tolerance | `max(0.08 Hz, 0.3 % of target)`, taking the closest mode ([L163-172](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L163-L172)). **This lets 24.000 Hz count as a match for a 23.976 target when no 23.976 mode exists** (Δ 0.024 < 0.08) [inferred]. `refineFrameRateForDisplay` chooses between 23.976 and 24 when the panel has both ([L212-250](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L212-L250)) |
| Choice order | exact → **2×** → **2.5×** → lowest `refreshWeight` over the ratios {1, 2, 2.5, 3, 4, 5, 6}, plus 0.5 as a penalty for PAL refresh with film/NTSC content, plus `div/10000` above 60 Hz. **This prefers lower multiples**, like Kodi ([L174-200](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L174-L200), [L252-262](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L252-L262)) |
| Resolution | Same size as now, unless resolution matching is on. Then it takes the smallest mode ≥ the video size that shows the rate at 1× or 2×, never below 720p ([L272-331](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L272-L331)) |
| Same target | If the chosen `modeId` is already active, it returns right away with no wait ([L367-374](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L367-L374)) |
| Settle | After setting `preferredDisplayModeId` it **polls `display.mode` every 60 ms until 2 polls in a row match** (by id or by rate), with a 4 s timeout ([L30-42](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L30-L42), [L333-426](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L333-L426)). This confirms the **OS** has switched, not that the TV has re-locked |
| Original | Recorded once per session (`if (originalModeId == null)`) ([L202-207](https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt#L202-L207)) |
| Crash | `preferredDisplayModeId` is a **window attribute**. When the activity's window dies, Android stops applying it, so a crash restores for free. Windows has nothing equivalent except `CDS_FULLSCREEN` ([01](01-windows-mode-switch-api.md)) [inferred] |

---

## 6. Stremio

- Desktop (stremio-shell, Qt + mpv): I found no refresh-rate switching [uncertain]. Open request: [stremio-bugs #2760 "sync to refresh rate"](https://github.com/Stremio/stremio-bugs/issues/2760) [issue].
- Android TV AFR bugs worth noting:
  - [#2261](https://github.com/Stremio/stremio-bugs/issues/2261) / [#2565](https://github.com/Stremio/stremio-bugs/issues/2565): the switch fires **about 500 ms after playback starts**. The player then pauses itself and must be resumed by hand, and sometimes plays jittery until it is reloaded.
  - [stremio-features #1706](https://github.com/Stremio/stremio-features/issues/1706): **gradual audio drift** (noticeable after 20-30 min) when 23.976/29.97 content lands on a flat 24.00/30.00 Hz mode [issue].

---

## 7. mpv user scripts

| Script | Mode choice / tolerance | Switch tool and wait | Restore | Pitfalls |
|---|---|---|---|---|
| **autospeedwin** ([lua](https://github.com/kevinlekiller/mpv_scripts/blob/2bd1b3d369b136b9f7dc6fab1a2ca4fa457c4849/autospeedwin/autospeedwin.lua)) | `floor(fps)`, so 23.976 → 23. It tries multipliers 1..round(maxrate/fps) against the user's `rates` list, within `minspeed..maxspeed` (0.9-1.1). **Closest wins; on ties the list order decides** ([L184-249](https://github.com/kevinlekiller/mpv_scripts/blob/2bd1b3d369b136b9f7dc6fab1a2ca4fa457c4849/autospeedwin/autospeedwin.lua#L184-L249)) | `nircmdc setdisplay monitor:N w h bpp rate` with fixed monitor/size options. Optional pause `spause` s, then a hard-coded ~2 s `ping` wait, then it re-reads `display-fps` ([L251-298](https://github.com/kevinlekiller/mpv_scripts/blob/2bd1b3d369b136b9f7dc6fab1a2ca4fa457c4849/autospeedwin/autospeedwin.lua#L251-L298)) | only on the mpv `shutdown` event, to a **fixed** `exitrate` ([L325-341](https://github.com/kevinlekiller/mpv_scripts/blob/2bd1b3d369b136b9f7dc6fab1a2ca4fa457c4849/autospeedwin/autospeedwin.lua#L325-L341)) | Integer rates only. `ping … > NUL` goes through `subprocess` without a shell, so the redirect is passed as arguments [inferred]. No restore on crash. Changes `speed` to fake the match (alternative to display-resample) |
| **mpv-changerefresh** ([lua](https://github.com/CogentRedTester/mpv-changerefresh/blob/603eaba9b8967ef329887850f57c47b85d5bb4fa/change-refresh.lua)) | `floor(container-fps)` → whitelist entry, e.g. `"23;24;25-50;30;60"`. Custom `a-b` maps rate a to mode b ([L405-418](https://github.com/CogentRedTester/mpv-changerefresh/blob/603eaba9b8967ef329887850f57c47b85d5bb4fa/change-refresh.lua#L405-L418)). It picks the closest entry, not the one that divides evenly ([#3](https://github.com/CogentRedTester/mpv-changerefresh/issues/3)) | nircmd, then **pause 3 s** by default ([L236-250](https://github.com/CogentRedTester/mpv-changerefresh/blob/603eaba9b8967ef329887850f57c47b85d5bb4fa/change-refresh.lua#L236-L250)) | on keybind or on `shutdown` ([L525-531](https://github.com/CogentRedTester/mpv-changerefresh/blob/603eaba9b8967ef329887850f57c47b85d5bb4fa/change-refresh.lua#L525-L531)) | [#10](https://github.com/CogentRedTester/mpv-changerefresh/issues/10): **re-announces and re-pauses for the next playlist item even when the rate is the same**. [#1](https://github.com/CogentRedTester/mpv-changerefresh/issues/1): paused even when nothing changed. [#5](https://github.com/CogentRedTester/mpv-changerefresh/issues/5): "Display FPS (specified)" stayed at 60 after the switch → **stutter for seconds** under display-resample. That turned out to be mpv.net forcing display-fps; stock mpv updated fine. `display-fps` is the lowest rate across the displays the window spans, so multi-monitor restore is "unpredictable" ([L305-325](https://github.com/CogentRedTester/mpv-changerefresh/blob/603eaba9b8967ef329887850f57c47b85d5bb4fa/change-refresh.lua#L305-L325)) |
| **mpv-plugin-xrandr** (Linux; [lua](https://github.com/lvml/mpv-plugin-xrandr/blob/60575586b0589a87d62cf8dc5e4f697628f5c4fb/xrandr.lua)) | multipliers **1..3, lowest first**. "Perfect" < 0.001 Hz, then "less precise" < 0.2 Hz (passes the exact fps to xrandr for 1×). Otherwise the highest rate ([L152-203](https://github.com/lvml/mpv-plugin-xrandr/blob/60575586b0589a87d62cf8dc5e4f697628f5c4fb/xrandr.lua#L152-L203)) | `xrandr --output --mode --rate`, no wait | on `shutdown` ([L324-383](https://github.com/lvml/mpv-plugin-xrandr/blob/60575586b0589a87d62cf8dc5e4f697628f5c4fb/xrandr.lua#L324-L383)) | **Skips if `container-fps` is unchanged** ([L228-233](https://github.com/lvml/mpv-plugin-xrandr/blob/60575586b0589a87d62cf8dc5e4f697628f5c4fb/xrandr.lua#L228-L233)), a simple same-target skip. [#1](https://github.com/lvml/mpv-plugin-xrandr/issues/1): a high-refresh user had to flip the loop to `4..1` to get 96/100 Hz. The author prefers the lowest multiple so TVs can interpolate [issue] |

All three restore **only on a clean mpv shutdown**. A crash, a kill or a hung mpv leaves the mode set, unless nircmd happens to use `CDS_FULLSCREEN`, which I did not verify [uncertain].

---

## 8. mpv internals that matter here

| Fact | Source |
|---|---|
| On Windows, `display-fps` comes from `QueryDisplayConfig` (exact rational), with a GDI fallback that guesses (N+1)/1.001 for 23/29/47/59/71/89/95/119/143/164/239/359/479 | [w32_common.c L589-621, L670-697](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/video/out/w32_common.c#L589-L697); [displayconfig.c](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/video/out/win32/displayconfig.c#L108) [src] |
| **The value is cached per `HMONITOR`**: `update_display_info` returns early when the monitor is unchanged. It is re-read only on `WM_DISPLAYCHANGE` (`force_update_display_info`) or a monitor change | [L670-674](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/video/out/w32_common.c#L670-L674), [L713-717](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/video/out/w32_common.c#L713-L717), [L1664-1666](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/video/out/w32_common.c#L1664-L1666) [src] |
| `WM_DISPLAYCHANGE` "is only sent to top-level windows. For all other windows it is posted." With `--wid`, mpv's window is a child of Nuvio's host, so whether and when it gets the message is **[uncertain]**. Measure it, or set `display-fps-override` ourselves | [MS docs](https://learn.microsoft.com/en-us/windows/win32/gdi/wm-displaychange) [doc] |
| `display-fps-override` (renamed from `override-display-fps`) forces the value. The docs warn that even a slightly wrong value "can ruin video playback" | [options.rst L1316-1324](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/DOCS/man/options.rst#L1316-L1324), [options.c L191, L259](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/options/options.c#L191) [doc/src] |
| Display-sync retimes if `|ratio·k / rint(ratio·k) − 1| ≤ 1 %` for k = 1..`video-sync-max-factor` (5). For **integer multiples like 240/23.976 = 10.01, k = 1 already fits** (speed 1.001), so a high multiple is fine | [video.c L695-706](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/player/video.c#L695-L706), [options.rst L8238-8266](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/DOCS/man/options.rst#L8238-L8266) [src/doc] |
| Display-sync modes "revert to audio mode" for low or undetectable fps. Toggling fullscreen or resizing can skip frames. VFR is unsupported (e.g. VFR in mkv) | [options.rst L8167-8200](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/DOCS/man/options.rst#L8167-L8200) [doc] |
| `estimated-display-fps` exists only while display-sync is active. It needs a few seconds to converge ([changerefresh #5](https://github.com/CogentRedTester/mpv-changerefresh/issues/5) screenshots) | [input.rst L3130-3140](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/DOCS/man/input.rst#L3130-L3140) [doc/issue] |
| mkv `container-fps` = `1e9 / DefaultDuration`, with no snapping, so the 23.810 fps case from Kodi #28836 reaches us too | [demux_mkv.c L984-991](https://github.com/mpv-player/mpv/blob/a1bf4b6559d6e644b967c22d87232dc77c7dd442/demux/demux_mkv.c#L984-L991) [src] |
| VRR and display-sync don't combine. There is no "display-vrr" mode, and frame doubling for VRR is a user-side `--vf=fps` workaround | [mpv #12005](https://github.com/mpv-player/mpv/issues/12005), [#6137](https://github.com/mpv-player/mpv/issues/6137), [#14075](https://github.com/mpv-player/mpv/issues/14075) [issue]. OLED/VRR details: [05](05-oled-vrr-flicker.md) |

---

## 9. Cross-cutting pitfalls

| Pitfall | Where seen | Notes |
|---|---|---|
| **Stuck at the wrong rate after a crash** | MPC-BE registry mode (§2.2). All mpv scripts (shutdown-only restore). Kodi's 24/48/60 registry trick if it crashes midway | `CDS_FULLSCREEN` is the only mitigation that survives a crash; see [01](01-windows-mode-switch-api.md) for evidence and the test plan. Never `CDS_UPDATEREGISTRY` |
| **Stuck while a window is open** | madVR "restore when closed", MPC-HC `bApplyDefaultModeAtFSExit=FALSE`, Kodi "On start" | The owner's complaint. Restore must be tied to the **playback session**, not the process |
| **Restore too early, then re-switch** | PMP #814 (post-play), JMP #675 ("each new episode flickering"), changerefresh #10 | JMP's `idle-active` check plus stopping the restore timer when a new file starts |
| **HDR interaction** | Kodi #21314 (no restore after HDR, Win11 22H2), Kodi PR #28672 (HDR toggle = a second renegotiation, audio lost), JMP #675 (HDR settings broken on exit) | Our monitor already runs HDR on the desktop ([02](02-mode-enumeration.md)). Check after each switch and restore that advanced-colour state is unchanged [inferred] |
| **Black screen / missed start** | PMP #247, Stremio #2261/#2565, JMP comment "screen is black" | Switch **before** playback starts (JMP's `on_load` hook) or while paused. Resume only after settle |
| **Wrong fps → wrong switch or a second switch** | Kodi PR #28836 (MKV 23.810), Kodi default whitelist (interlaced double switch) | Snap to standard rates. Decide once per session. Never re-switch mid-playback |
| **display-fps stale** | changerefresh #5 (mpv.net forcing it); JMP forces its own integer value | Feed mpv the exact QDC rational through `display-fps-override`, or verify that mpv re-reads it under `--wid` [uncertain] |
| **Multi-monitor** | changerefresh (display-fps = lowest across the displays spanned; restores the wrong display), Kodi #24608 (moved window → desync), JMP (window-centre monitor) | Bind to the monitor hosting the window at switch time. Restore **that** monitor [inferred] |
| **Audio desync after a switch** | Kodi PR #28672 (passthrough dead), Stremio #1706 (drift on a 24.000 mode), MPC-HC per-mode audio delay, JMP per-rate `audio_delay.*` | With display-resample the drift is corrected by resampling. Passthrough is incompatible with resampling (Kodi help text) |
| **23.976 vs 24 mismatch** | Stremio #1706, NuvioTV 0.08 Hz tolerance, Kodi's Windows 8 integer-24 bug | See §10 |
| **G-Sync/VRR** | JMP #91 (unclear whether VRR or switching is active), mpv #12005 | See [05](05-oled-vrr-flicker.md) |
| **UI tied to video rate** | JMP #761 (UI at 24 fps) | Relevant only if our Compose UI shares the swapchain/present path. It doesn't ([06](06-codebase-hooks.md)) [inferred] |

## 10. The arithmetic: how bad is a 1000/1001 mismatch?

With `video-sync=audio` (no retiming), a mismatch of Δ Hz between the refresh and k·fps causes one extra or missing vsync every 1/Δ s [inferred, standard arithmetic]:

| Content | Mode | k·fps | Δ | Hitch every | Hitch size |
|---|---|---|---|---|---|
| 23.976 | 24.000 | 23.976 | 0.024 | **41.7 s** | 1 frame = 41.7 ms (a visible repeat) |
| 23.976 | 239.901 (owner's "240") | 239.760 | 0.141 | 7.1 s | 1 vsync = 4.2 ms |
| 24.000 | 239.901 | 240.000 | 0.099 | 10.1 s | 4.2 ms |
| 25.000 | 100.000 | 100.000 | 0 | never | - |
| 29.970 | 239.901 | 239.760 | 0.141 | 7.1 s | 4.2 ms |

With **`display-resample`** all of these are within mpv's 1 % window, so video is retimed (speed ≈ 1.0006 for 23.976 at 239.901) and audio is resampled. The result is **no drops or repeats** [src video.c]. Higher multiples make each hitch smaller, which is part of why "240 Hz for everything" looks good in practice [inferred].

---

## 11. Lessons → implications for our design

| # | Lesson (evidence) | Implication for Nuvio |
|---|---|---|
| 1 | **Tolerance math.** The field uses a flat absolute tolerance: 0.01 Hz (Kodi, JMP exact), 0.01·k Hz (PMP #757), 0.08 Hz or 0.3 % (NuvioTV), 0.001/0.2 Hz (xrandr). A flat 0.01 Hz can't match the owner's 239.901 to 23.976·10 = 239.76 at all | Work in **ratios, not Hz**: accept mode `r` for fps `f` when `k = round(r/f) ≥ 1` and `|r/(k·f) − 1| ≤ tol`. Use tol ≈ 0.1 % (just over 1000/1001 = 0.0999 %) for "1000/1001-tolerant", and stay inside mpv's 1 % retime window. Use exact rationals from DXGI/QDC ([02](02-mode-enumeration.md)), never (N+1)/1.001 guesses |
| 2 | **Nobody prefers the highest multiple** (Kodi PR #24576 open, xrandr #1). Lower is the default elsewhere so that TVs can interpolate | Our "highest multiple" rule is deliberate for a monitor, not a TV. Keep it, add unit tests for 23.976/24/25/29.97/30/50/59.94/60 against the owner's mode set, and log the choice |
| 3 | **fps input is unreliable** (Kodi #28836: 23.810 from MKV; mpv passes it through) | Snap `container-fps` to the standard set (NuvioTV `snapToStandardRate`-style ranges, plus the 1000/N-ms fingerprint). Fall back to `estimated-vf-fps` only when snapping fails. Decide once, before playback |
| 4 | **Switch before or while paused, then wait** (JMP `on_load` hook + 3 s; MPC-HC/BE pause + `uDelay`; changerefresh 3 s; NuvioTV poll 60 ms × 2, 4 s cap). The "screen is black" risk and hwdec init failures are the reasons | Switch in an mpv `on_load` hook (or with `pause=yes`). Confirm with QDC that the target rational is live (poll ~50-100 ms, need 2 stable reads, cap ~4 s). Then wait an extra **configurable** settle (default ~1-2 s for a DP monitor; TVs need 2-3 s, per PMP #247) [inferred defaults; measure in Phase 2] |
| 5 | **display-fps staleness** (mpv caches per monitor. `WM_DISPLAYCHANGE` delivery under `--wid` is [uncertain]. JMP overrides with an integer value, which is subtly wrong) | After a confirmed switch, set `display-fps-override` to the **exact QDC rational**. On restore, clear it (`0`) so mpv re-detects. Verify `display-sync-active` and `estimated-display-fps` in the logs |
| 6 | **Restore strategy.** Restoring on process exit only leaves the monitor stuck (madVR, MPC-HC default). Restoring at every end-file thrashes between episodes (PMP #814, JMP #675) | Restore when the **playback session** ends: player closed / navigated away / idle. On end-file, restore only if no next item follows (JMP's `idle-active` check with a 0 ms-to-short timer, cancelled by the next load). Restore to `ENUM_REGISTRY_SETTINGS` ([01](01-windows-mode-switch-api.md)), not to a remembered integer |
| 7 | **Crash handling.** The only crash-safe pattern seen is `CDS_FULLSCREEN` (MPC-HC's "restore on exit", JMP, Kodi). MPC-BE's registry mode and all the mpv scripts fail on a crash | `CDS_FULLSCREEN` only, never `CDS_UPDATEREGISTRY`. Also persist a "mode dirty" marker so the next launch restores if the OS didn't. Run the kill test in [01](01-windows-mode-switch-api.md) §7 |
| 8 | **Same-target skip.** MPC-HC/BE and NuvioTV skip if the current mode already equals the target. xrandr skips on the same fps. changerefresh #10 shows what happens without it (a pause per episode) | Compare the **target mode** (not the fps) with the live QDC mode. If equal: no switch, **no settle wait**, no OSD. Keep the session "owner" state so the eventual restore still happens |
| 9 | **Keep the rate constant for the session** (Kodi "Always" flips when leaving fullscreen; Kodi #28836 re-switches mid-play) | Once switched, don't re-evaluate on seek, track change, fullscreen toggle or measured-fps drift. Only session end restores |
| 10 | **HDR** (Kodi #21314, PR #28672; JMP #675) | Log the advanced-colour state before, after the switch and after the restore. If HDR drops or changes, warn and restore. Never toggle HDR ourselves in this feature |
| 11 | **Multi-monitor** (changerefresh multi-display, Kodi #24608) | Resolve the target from `MonitorFromWindow(host)` at switch time, store its GDI name, and restore **that** device even if the window moves. Moving the window mid-session is out of scope, but the move should be logged |
| 12 | **Audio** | Use `video-sync=display-resample` only while switched and our own sync is active. It is incompatible with passthrough (Kodi help text), so if passthrough is on, fall back to `audio` sync and accept the §10 hitches |
