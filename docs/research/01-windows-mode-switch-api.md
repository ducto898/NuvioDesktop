# 01 - Windows API for a temporary refresh-rate switch

- Date: 2026-09-28
- Scope: which Win32 API should switch the player's monitor to an fps-multiple refresh rate for the length of a session, and whether Windows puts the mode back if Nuvio dies.
- Method: MS Learn docs, the source code of players that already do this (MPC-HC, Jellyfin Media Player, Kodi, mpv), ReactOS's reimplementation of win32k, and **read-only** probes on the owner's PC (results in [02-mode-enumeration.md](02-mode-enumeration.md)). **No mode was changed during this research.**
- Labels: **[doc]** = Microsoft documentation. **[src]** = read in the source code of another project. **[measured]** = observed on this PC. **[inferred]** = my reasoning, not verified. **[uncertain]** = the evidence is weak or conflicting.

## Recommendation

**Use `ChangeDisplaySettingsExW(gdiName, &devmode, NULL, CDS_FULLSCREEN, NULL)` to switch. Restore with `ChangeDisplaySettingsExW(gdiName, NULL, NULL, 0, NULL)`. After each switch, read `QueryDisplayConfig` to check which exact rational rate was actually applied.**

Reasons:

1. **It is the only API with evidence that Windows undoes the change when the process dies.** ReactOS's win32k stores the process that last called `CDS_FULLSCREEN`. When that process's last GUI thread exits, ReactOS calls `UserChangeDisplaySettings(NULL…)`, which restores the registry mode. Thread teardown runs in the kernel, so this path also covers `TerminateProcess`, crashes and "End task" [src, see below]. MPC-HC shows the same assumption: its **"Restore resolution on program exit"** checkbox does nothing except pick `CDS_FULLSCREEN` instead of flags `0` [src]. This matches the one guarantee we must never break: the mode is never kept as the default.
2. **It never writes the registry.** Only `CDS_UPDATEREGISTRY` writes it [doc]. `ENUM_REGISTRY_SETTINGS` stays at 280 Hz the whole time, and that is exactly what the NULL-devmode restore goes back to.
3. **It is the precedent for mpv-based players.** Jellyfin Media Player (mpv embedded, like Nuvio) switches modes with exactly this call [src]. Kodi also uses `CDS_FULLSCREEN` [src].
4. On this monitor, the integer-Hz limitation of `DEVMODE` is harmless. At 2560x1440, each integer rate maps to exactly one timing (240 → 239.901, 120 → 119.998, 100 → 100.000, and so on). The only duplicate is 59/60, and both point at the same 59.951 timing [measured, see 02]. We confirm the result through `QueryDisplayConfig` anyway.

`SetDisplayConfig` (`SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG`, no `SDC_SAVE_TO_DATABASE`) is the runner-up. It can target an exact rational `vSyncFreq`. But **no source says Windows reverts it when the caller dies** [uncertain]. MS documents the way back as an explicit call (`SDC_APPLY | SDC_USE_DATABASE_CURRENT`) [doc]. A crash would leave the user at 240 Hz until something resets the config. It also runs "best mode logic" to fill in any missing mode info [doc], and that logic prefers this monitor's EDID-preferred timing, which is 59.951 Hz [measured]. **Keep it for reading (`QueryDisplayConfig`, `DisplayConfigGetDeviceInfo`), not for writing.**

## 1. `ChangeDisplaySettingsExW` semantics

| Aspect | What we know | Label / source |
|---|---|---|
| `CDS_FULLSCREEN` | "The mode is temporary in nature. If you change to and from another desktop, this mode will not be reset." | [doc] [CDS Ex][cdsex] |
| Meaning of "temporary" | The window manager and Explorer **do not reposition windows or icons**, because the app promises to set things back. | [doc-ish] Raymond Chen, [What does it mean when a display change is temporary?][chen] (2008) |
| Flags `0` | "changed dynamically", not saved to the registry. There is **no** restore on process exit, so the mode stays until reboot or the next reset. | [doc]; MPC-HC uses this when "restore on exit" is off [src] |
| `CDS_UPDATEREGISTRY` | Writes the mode to the user profile, making it persistent. **Never use it.** | [doc] |
| `CDS_RESET` | Forces the change even when the requested mode equals the current one. Not needed. Could be useful to recover from a half-applied state. | [doc] |
| Restore call | `ChangeDisplaySettingsEx(name, NULL, NULL, 0, NULL)`. "If lpDevMode is NULL, all the values currently in the registry will be used… the easiest way to return to the default mode after a dynamic mode change." | [doc] |
| Scope of the auto-restore | **Per process, one global owner.** ReactOS: `static PPROCESSINFO gpFullscreen`. `UserUpdateFullscreen()` sets it to the caller's process on `CDS_FULLSCREEN` and **clears it on any non-`CDS_FULLSCREEN` change**. `UserDisplayNotifyShutdown(ppi)` restores when `ppi == gpFullscreen`. It is called from thread cleanup `if (ppiCurrent->cThreads == 0)`. | [src] ReactOS [`win32ss/user/ntuser/display.c`][rosdisp] (`gpFullscreen`, lines ~13, 712–719, 940–950) and [`main.c`][rosmain] (~line 891) |
| Does real Windows do the same? | Very likely. ReactOS clones Windows' observable behaviour, and MPC-HC ships a user-facing option that depends on it. **No Microsoft document says so.** | [inferred], confidence about 80% until the test in §7 is run |
| Multiple processes | Last caller wins [src ReactOS]. If another app (a game, NVCP, Windows Settings) changes the mode after us, we lose "ownership". Our death would then no longer trigger a restore, and our explicit restore would overwrite their mode. | [inferred from src] |
| Per-thread? | No. It is keyed on the process (`ppi`) and fires only when the **last** thread leaves [src ReactOS]. In Nuvio that process is `java.exe`/`Nuvio.exe`: the DLL runs inside the JVM, so a JVM kill counts. | [inferred] |
| Which monitor is restored | ReactOS restores with a NULL device, meaning the primary display. Windows might restore every display or only the primary. With one monitor it makes no difference. | [uncertain] (multi-monitor) |
| Secure desktop (UAC, Ctrl+Alt+Del) | The doc sentence "if you change to and from another desktop, this mode will not be reset" is ambiguous. Either the temp mode survives a desktop switch, or it is not re-applied when you come back. | [uncertain] test it (§7, case G) |

**Do not copy Kodi's "Windows 8+ workaround".** For 24/48/60 Hz, Kodi writes the target mode into the registry (`CDS_UPDATEREGISTRY|CDS_NORESET`), applies it with `CDS_FULLSCREEN`, then writes the old registry values back [src Kodi `WinSystemWin32.cpp` `ChangeResolution`]. A crash between those steps would persist the mode. That violates our hard rule. It exists because an integer `dmDisplayFrequency` of 24 or 60 could land on the 23.976 or 59.94 timing. On this monitor we don't need it (§3).

## 2. `QueryDisplayConfig` / `SetDisplayConfig`

| Aspect | Finding | Label |
|---|---|---|
| Exact rates | `DISPLAYCONFIG_VIDEO_SIGNAL_INFO.vSyncFreq` is a rational. This PC now reports **279961/1000** (total 2720x1653, pixel clock 1258.75 MHz). | [doc] + [measured] |
| Temporary apply | MS lists "Set a temporary display configuration (that is, the display configuration will not be saved)" = `SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG`. The way back is `SDC_APPLY | SDC_USE_DATABASE_CURRENT`. | [doc] [SetDisplayConfig][sdc] |
| Survives process death? | Nothing documents per-process tracking. A HLK test ([PersistentReset][hck]) checks that `SDC_USE_DATABASE_CURRENT` resets a non-saved SDC mode, and that the persisted config survives sleep and monitor-off. That implies nothing resets it automatically on process exit. | [inferred], confidence about 75% that it is **not** reverted |
| Best-mode logic | Any missing source or target mode info is filled in by "best mode logic", and `SDC_ALLOW_CHANGES` lets Windows adjust what we supplied [doc]. This monitor's **preferred** mode is 2560x1440 **@59.951 Hz** [measured], so a sloppy SDC call could land on 60 Hz. | [doc] + [measured] |
| Win11 virtual refresh | `SDC_VIRTUAL_REFRESH_RATE_AWARE` exists (Win11). It is only relevant to Dynamic Refresh Rate panels. This path has no virtual-refresh flag set (`pathFlags=0x1`). | [doc] + [measured] |

## 3. `DEVMODE` fields: integer Hz and "bit depth"

- **`dmDisplayFrequency` is an integer.** On this PC, **239.901 → 240, 279.961 → 280, 143.973 → 144, 119.998 → 120**, so the value is rounded to nearest, not truncated. **59.951 appears as both 59 and 60**, and DXGI lists only one ~60 Hz timing at 1440p [measured]. That fits the Windows 7-era behaviour where displays with a TV-compatible 59.94 timing expose both "59" and "60" (MS KB2006076, as quoted in [seven forums][kb59]; the KB page itself no longer resolves) [uncertain wording].
  - **Contradiction:** mpv's `get_refresh_rate_from_gdi` says the integer "is rounded down", so it maps 23/59/119/143/239… to (N+1)/1.001 [src [mpv w32_common.c][mpvw32]]. Kodi uses the same heuristic [src]. Here Windows reports **144** for 143.973 and **240** for 239.901, not 143/239. The heuristic is harmless in this case, but **never derive the real rate from `dmDisplayFrequency`**. Read `QueryDisplayConfig`. mpv already tries that first (`mp_w32_displayconfig_get_refresh_rate`) [src].
  - **Does asking CDS for 240 select the 239.901 timing?** It is the only 1440p timing whose integer is 240 [measured], so yes [inferred]. Confirm it with a QDC read-back after the switch (§7).
- **`dmBitsPerPel` is the desktop pixel format** (always 32 here; Windows 8+ refuses anything under 32 [doc]). It is **not** the 10-bit panel/link depth. The 10 bpc is the wire format chosen by the driver, reported as `DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO(_2).bitsPerColorChannel` = **10** and `DXGI_OUTPUT_DESC1.BitsPerColor` = **10** [measured].
  - **Definition for this feature:** "same bit depth" means (a) pass `dmBitsPerPel = 32` (or leave `DM_BITSPERPEL` out of `dmFields`, as Jellyfin MP does), **and** (b) after the switch, check that `bitsPerColorChannel` and `activeColorMode` are unchanged (10, HDR). If they changed, restore and back off. NVCP's "Output color depth" is a per-driver setting outside this API [inferred].

## 4. HDR / advanced colour

| Question | Answer | Label |
|---|---|---|
| Current state | `ADVANCED_COLOR_INFO_2`: HDR supported + user-enabled, `activeColorMode = HDR`, 10 bpc, RGB encoding, SDR white 240 nits. | [measured] |
| Does a refresh change drop HDR? | HDR is a separate per-target state (set via `DISPLAYCONFIG_SET_HDR_STATE` / `SET_ADVANCED_COLOR_STATE`). It is not part of the mode, so a pure refresh change should keep it. Every candidate rate needs less bandwidth than today's working 280 Hz 10-bit HDR, so the driver has no bandwidth reason to fall back to 8-bit or SDR. | [inferred]. **Must be tested** |
| Black flash / re-negotiation | Any timing change on DisplayPort re-trains the link and the monitor re-syncs, typically a 1–3 s blank on monitors. Kodi ships a "Delay after change of refresh rate" setting for this reason ([Kodi wiki][kodidisp]). Whether HDR metadata or tone-mapping re-initialises (a brightness "pop") on this WOLED is unknown. | [uncertain]; measure it |
| Detecting "HDR settled" | There is no Win32 event. MS says: poll `IDXGIFactory1::IsCurrent()`, re-read `DXGI_OUTPUT_DESC1`, and **do not** recreate swap chains or use `GetContainingOutput` (that gives stale data and a black flash) ([MS: DirectX with Advanced Color][hdrdoc]). For us: after `WM_DISPLAYCHANGE`, poll `QueryDisplayConfig` + `ADVANCED_COLOR_INFO_2` until the rate equals the target and `activeColorMode == HDR`, with a timeout. | [doc] + [inferred] |

## 5. HWND → monitor → device name → DisplayConfig ids

1. `MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)` (the bridge already does this at `player_bridge.cpp:247`).
2. `GetMonitorInfoW(hmon, &MONITORINFOEXW)` → `szDevice` = `\\.\DISPLAY1` (the GDI source name, which is what `ChangeDisplaySettingsExW` takes).
3. `QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS)`, then for each path `DisplayConfigGetDeviceInfo(GET_SOURCE_NAME)`. Match `viewGdiDeviceName == szDevice` to get `(adapterId, sourceId)` and `(adapterId, targetId)`. The target id is used for `GET_TARGET_NAME` (friendly "MO27Q28G"), `GET_ADVANCED_COLOR_INFO(_2)` and the target's `vSyncFreq`. mpv does exactly this [src mpv `video/out/win32/displayconfig.c`]. Here: source 0 → target 4353, DisplayPort (`outputTechnology = 10`) [measured].
4. **Re-resolve on every call.** `\\.\DISPLAYn` numbering and adapter LUIDs can change after a hotplug or driver reset [inferred].

## 6. Notifications, timing, and pitfalls

- **`WM_DISPLAYCHANGE`**: "sent to all windows when the display resolution has changed… only sent to top-level windows. For all other windows it is posted." [doc [WM_DISPLAYCHANGE][wmdc]]. Its parameters carry only bpp and size, not Hz. Whether it fires on a **refresh-only** change is very likely but should be confirmed [uncertain].
- **mpv under `wid`:** mpv refreshes `display-fps` only from `WM_DISPLAYCHANGE`, monitor moves and DPI changes (`force_update_display_info`) [src mpv w32_common.c:1664]. mpv's window is a **child** of our host, so it gets the message *posted* at best [doc above]. The bridge should give mpv the new rate explicitly (`display-fps-override`) or verify mpv's `display-fps` property after the switch [inferred].
- **Switch latency (NVIDIA over DP):** there is no authoritative figure. Community reports and Kodi's delay setting suggest 1–3 s of black [uncertain]. The API call itself is synchronous (it returns after the mode set) [inferred]. We should measure: time from call to return, to `WM_DISPLAYCHANGE`, and to the first stable QDC read.
- **G-Sync/VRR:** a fixed mode still carries VRR. Whether mpv's windowed flip presentation then runs VRR (so the fixed rate matters less) is a topic for another research file. Flag it for the owner [uncertain].
- **TDR / driver reset / monitor power-cycle / sleep:** after these, dxgkrnl re-applies a configuration. Whether that is our temp mode or the database (280 Hz) is **unknown** [uncertain]. The OLED's own pixel-refresh power cycle falls in the same bucket. Treat any unexpected `WM_DISPLAYCHANGE` whose rate ≠ our target as "the session lost the mode". Re-apply once, or give up quietly.
- **DPI:** same resolution, so no DPI change and no `WM_DPICHANGED` [inferred]. `CDS_FULLSCREEN` also stops windows and icons from being repositioned [doc Chen].
- **Window moves to another monitor mid-session:** restore the old monitor first, then decide on the new one. Kodi calls `RestoreDesktopResolution(oldMonitor)` on a screen change [src].
- **Exclusive fullscreen:** Nuvio uses borderless fullscreen (`setBorderlessFullscreen`), not DXGI exclusive, so there is no DXGI mode ownership to fight. If another app takes exclusive fullscreen, it becomes the mode owner (last caller wins).
- **User changes the rate in Settings during playback:** that writes the registry. Our NULL restore then goes to the user's *new* choice, which is correct [inferred].

## 7. Proposed empirical test (later phase, with the owner present)

**Goal:** settle whether Windows reverts a `CDS_FULLSCREEN` change on kill, crash and normal exit, as a control against `SetDisplayConfig`, and measure HDR and bit depth across the switch.

**Tools:** two small C++ programs built in the scratchpad (not the repo):
- `observer.exe`, read-only. Every 50 ms it logs to CSV: QDC `vSyncFreq`, `ADVANCED_COLOR_INFO_2` (active mode, bpc), `ENUM_REGISTRY_SETTINGS` Hz, plus a timestamp. It also logs every `WM_DISPLAYCHANGE` from a hidden top-level window.
- `switcher.exe <api> <hz> <end>`, where api ∈ {cds, sdc} and end ∈ {restore, exit, hang, crash}. It applies the mode, confirms it via QDC, then ends as told.

**Safety:** before each case, check the registry mode is 280 Hz. Keep a `restore.exe` (`ChangeDisplaySettingsExW(NULL,NULL,NULL,0,NULL)`) pinned. The owner knows Win+Ctrl+Shift+B (driver reset) and Settings → Display as fallbacks. Only candidate rates listed by DXGI are used (240, 120, 100). Nothing uses `CDS_UPDATEREGISTRY` or `SDC_SAVE_TO_DATABASE`.

| Case | Action | Pass criterion |
|---|---|---|
| A | `cds 240 restore` | Back to 279.961. Registry stayed 280 throughout. |
| B | `cds 240 exit` (return from main, no restore) | Observer sees a revert to 279.961 within ~3 s of exit. |
| C | `cds 240 hang` + `taskkill /F` (TerminateProcess) | Same as B. |
| D | `cds 240 hang` + Task Manager "End task" | Same as B. |
| E | `cds 240 crash` (null write, unhandled) | Same as B, including after the WER dialog closes. |
| F | `sdc 239901/1000 hang` + `taskkill /F` | Expected: **no** revert (the control). Then run `restore.exe`. |
| G | `cds 240 hang`, trigger a UAC prompt, dismiss it | Record whether the mode survives the secure desktop. |
| H | `cds 240 hang`, monitor off/on, then sleep/resume | Record which mode comes back. |
| I | Two processes: P1 `cds 240 hang`, P2 `cds 120 hang`. Kill P1, then kill P2 | Tests the "last caller owns it" behaviour. |
| J | Load the DLL into a JVM (the real Nuvio build), switch, kill `java.exe` | Same as C. |

For every switch, also record: API return time, `WM_DISPLAYCHANGE` time, first stable QDC time, whether HDR stayed on, whether bpc stayed at 10, whether the monitor OSD shows the target Hz, and whether the owner saw a visible blank or brightness pop. This also answers §3 (does "240" select 239.901?) and §4.

**Decision rule:** if B–E revert, ship `CDS_FULLSCREEN` with an explicit restore on every exit path. If they don't, add a tiny watchdog helper process that `WaitForSingleObject`s on Nuvio's process handle and runs the NULL restore when Nuvio disappears [inferred design]. The watchdog must itself survive the Nuvio kill, so it must not be in a kill-on-close Job with Nuvio.

## Open questions for the owner

1. Is 100 Hz selectable in Windows Settings → Advanced display (NVCP hides it)? It is enumerated with an exact 100/1 rate, unlike the others (see 02).
2. Is G-Sync enabled for windowed mode in NVCP? If it is, a fixed-rate switch may matter less than it seems.
3. Are you OK running the §7 test session (about 20 min, several 1–3 s black screens)?

[cdsex]: https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-changedisplaysettingsexw
[chen]: https://devblogs.microsoft.com/oldnewthing/20080104-00/?p=23923
[rosdisp]: https://github.com/reactos/reactos/blob/master/win32ss/user/ntuser/display.c
[rosmain]: https://github.com/reactos/reactos/blob/master/win32ss/user/ntuser/main.c
[sdc]: https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setdisplayconfig
[hck]: https://learn.microsoft.com/en-us/previous-versions/windows/hardware/hck/hh998426(v=vs.85)
[kb59]: https://www.sevenforums.com/graphic-cards/108557-59hz-instead-60hz.html
[mpvw32]: https://github.com/mpv-player/mpv/blob/master/video/out/w32_common.c
[kodidisp]: https://kodi.wiki/view/Settings/System/Display
[hdrdoc]: https://learn.microsoft.com/en-us/windows/win32/direct3darticles/high-dynamic-range
[wmdc]: https://learn.microsoft.com/en-us/windows/win32/gdi/wm-displaychange

Other source code read: MPC-HC [`src/mpc-hc/MainFrm.cpp` `SetDispMode`](https://github.com/clsid2/mpc-hc/blob/develop/src/mpc-hc/MainFrm.cpp) and `mpc-hc.rc` ("Restore resolution on program exit"); Jellyfin Media Player `src/display/win/DisplayManagerWin.cpp` `setDisplayMode` ([jellyfin-media-player](https://github.com/jellyfin/jellyfin-media-player)); Kodi [`xbmc/windowing/windows/WinSystemWin32.cpp`](https://github.com/xbmc/xbmc/blob/master/xbmc/windowing/windows/WinSystemWin32.cpp).
