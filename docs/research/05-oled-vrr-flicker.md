# 05: OLED VRR flicker and keeping the panel at a fixed refresh during playback

Status: research only (web plus read-only repo reads). Nothing was changed on the system, in NVIDIA or in the registry. Date: 2026-09-28.

Legend: every claim has a link, or is marked **[inferred]** (my reasoning, no direct source) or **[uncertain]** (sources conflict, or none found). Forum or GitHub-issue reports are marked **(anecdote)**. **[APPROVAL]** marks anything that would touch driver or OS settings. The owner must approve those before anyone does them.

Target system: RTX 4070 SUPER over DP, Gigabyte MO27Q28G (27" tandem WOLED, 2560x1440, 280 Hz, G-Sync Compatible), Windows 11 with HDR on.

---

## TL;DR

| Question | Answer | Confidence |
|---|---|---|
| Why do OLEDs flicker under VRR? | Panel gamma is tuned for one refresh rate. Changing the frame interval changes subpixel charge and hold time, so near-black luminance shifts. WOLED gamma tracks the frame rate. | High (TFTCentral, RTINGS) |
| Worst cases | Large or fast swings: loading screens, menus, near-static dark scenes, 0-fps stalls, crossing the LFC boundary | High |
| Does VRR engage on Nuvio's player today? | **Depends on the NVCP G-SYNC mode.** "Full screen only" (the NVCP default): probably **not**, because mpv presents into a child HWND with a full-size WebView2 child window on top, which should force DWM composition. "Windowed and full screen": **probably yes**, because the driver's windowed-VRR hack follows the focused app. | Medium-low. Phase 2 must measure it. |
| Does mpv present while paused? | No. In the default `video-sync=audio` mode and in display-sync modes, the VO renders nothing while paused except explicit redraws (mpv `vo.c`). Under VRR this drops the panel to the LFC floor. | High (source code) |
| Safest approach | Keep playback **DWM-composed** so the panel scans out at the mode set by the refresh switch (N x fps, fixed). Leave VRR on globally. Verify with PresentMon and the monitor's refresh-rate overlay. Add steady-cadence mitigations only if Phase 2 shows VRR engaging. | Medium |

---

## 1. Mechanism: why VRR flickers on OLED (WOLED in particular)

| Point | Detail | Source |
|---|---|---|
| Fixed gamma tuning | "OLED VRR flicker is due to a fixed gamma curve that is tuned to the max refresh rate". Deviating from it causes "overcharging and misfiring of the OLED subpixels". | [TFTCentral: Exploring and Testing OLED VRR Flicker](https://tftcentral.co.uk/articles/exploring-and-testing-oled-vrr-flicker) |
| Near-black is worst | Largest shifts at RGB ~5–30. Steep gamma near black plus OLED's true black floor makes the shift visible. | TFTCentral (above); [PanelPicks explainer](https://panelpicks.com/gaming-monitors/vrr-flicker/) (secondary source) |
| WOLED vs QD-OLED | WOLED: gamma is "commonly directly tied to the frame rate". The brightness shift scales with how far the frame rate moves, so smaller swings mean less flicker. QD-OLED: stable gamma but random spikes. | TFTCentral |
| Worst scenarios | Big swings (e.g. 480 to 10 Hz), loading screens with static dark backgrounds and fluctuating fps, and **crossing the LFC boundary** ("jarring gamma changes") | TFTCentral |
| Stress test | RTINGS alternates between max refresh and max−10 fps and measures luminance with a photodiode. All six gaming OLEDs tested showed flicker. | [RTINGS VRR flicker research](https://www.rtings.com/monitor/learn/research/vrr-flicker) |
| This monitor | PCMonitors' MO27Q28G review: "in consistent frame rate situations there's only a minor gamma change that doesn't usually result in a clear flicker. It's mainly loading screens, in-game maps screens…". The monitor's "anti-flicker" setting "is pretty basic and only useful in specific cases". | [PCMonitors MO27Q28G review](https://forum.pcmonitors.info/topic/gigabyte-mo27q28g-review/) |
| Video players specifically | mpv at 24/25 fps under VRR produced "wildly fluctuating refresh rate and flicker" **(anecdote)**. On Linux/AMD, mpv under VRR oscillated around 60 Hz ±2.5 Hz with visible flicker in dark areas **(anecdote, different OS)**. | [NVIDIA dev forum](https://forums.developer.nvidia.com/t/wildly-fluctuating-refresh-rate-and-flicker-at-low-framerates/285794); [mpv #14075](https://github.com/mpv-player/mpv/issues/14075) |
| An app with the same bug | An egui mail client flickered on G-Sync + OLED because of "long idle gaps punctuated by repaint bursts". The fix was an in-app steady repaint cadence. The developer rejected NVAPI profiles. **(anecdote, but a close analogue)** | [va1erian/esmail #91](https://github.com/va1erian/esmail/issues/91) |

**Why video under VRR is a bad case** **[inferred]**: 23.976 fps sits below most G-Sync Compatible floors, so the driver runs LFC frame multiplication. Pauses (0 presents), seeks, buffering stalls and dark film content are exactly the "large swing plus near-black" conditions listed above. A fixed-refresh panel has none of these problems.

---

## 2. When G-Sync/VRR engages on Windows

### 2.1 Presentation paths

| Path | What it is | VRR possible? | Source |
|---|---|---|---|
| Fullscreen exclusive (FSE) | App owns the output (`SetFullscreenState`) | Yes (NVCP "full screen" mode) | [Blur Busters G-SYNC 101](https://blurbusters.com/gsync/gsync101-input-lag-tests-and-settings/) |
| Flip model plus **DirectFlip / Independent Flip** | The DWM steps aside and the app swapchain is scanned out directly. Conditions: flip model, buffers match the screen (or within panel-fitter/MPO scaling), "your window client region covers the screen". PresentMon shows `Hardware: Independent Flip` or `Hardware Composed: Independent Flip` (MPO). | Yes. The driver treats it like fullscreen, so G-SYNC engages even with "full screen only" (Blur Busters expert posts) | [MS Learn: use DXGI flip model](https://learn.microsoft.com/en-us/windows/win32/direct3ddxgi/for-best-performance--use-dxgi-flip-model); [Blur Busters t=9880](https://forums.blurbusters.com/viewtopic.php?t=9880) |
| Flip model, **composed** (`Composed: Flip`) | The DWM composes the swapchain with other content at the desktop's refresh | Only with NVCP **"windowed and full screen"**, which is a driver hack that retimes DWM to the **focused** window | [Blur Busters forum summaries t=10099](https://forums.blurbusters.com/viewtopic.php?t=10099), [t=7737](https://forums.blurbusters.com/viewtopic.php?t=7737) **(expert forum posts)** |
| Blt model (`DISCARD`/`SEQUENTIAL`), `Composed: Copy with GPU GDI` | Copied into the DWM surface | Not natively. Win11 "Optimizations for windowed games" can **upgrade** DX10/11 blt apps to flip "enabling … variable refresh rate". | [MS Support: Optimizations for windowed games](https://support.microsoft.com/en-us/windows/hardware/display-graphics/optimizations-for-windowed-games-in-windows-11) |

If other content covers the app, "the DWM can either seamlessly transition back to composed mode, efficiently 'reverse compose' the contents on top …, or leverage MPO to maintain the independent flip mode" ([MS Learn](https://learn.microsoft.com/en-us/windows/win32/direct3ddxgi/for-best-performance--use-dxgi-flip-model)). **So an overlay on top does not guarantee composition.** This is the main uncertainty for Nuvio.

### 2.2 Tearing flags

- `DXGI_PRESENT_ALLOW_TEARING` is only for sync interval 0 in windowed or borderless mode. MS writes that VRR "require[s] tearing to be enabled" ([MS Learn: VRR displays](https://learn.microsoft.com/en-us/windows/win32/direct3ddxgi/variable-refresh-rate-displays)).
- **[inferred]** G-SYNC also runs with sync interval 1 (the usual "G-SYNC + V-SYNC" setup in [G-SYNC 101](https://blurbusters.com/gsync/gsync101-input-lag-tests-and-settings/)). **So not using ALLOW_TEARING does *not* opt a flip-model app out of VRR on NVIDIA.** mpv's d3d11 default is `--d3d11-sync-interval=1` ([mpv options.rst](https://github.com/mpv-player/mpv/blob/master/DOCS/man/options.rst)).

### 2.3 Windows 11 OS features

| Feature | Effect | Relevance |
|---|---|---|
| Settings > Display > Graphics > "Variable refresh rate" | Enables VRR for **DX11 full-screen games** that lack native VRR support | Low. Nuvio is not FSE. [Pureinfotech](https://pureinfotech.com/enable-variable-refresh-rate-vrr-windows-11/), [ElevenForum](https://www.elevenforum.com/t/enable-or-disable-variable-refresh-rate-for-games-in-windows-11.12052/) |
| "Optimizations for windowed games" (`SwapEffectUpgradeEnable`) | Upgrades DX10/11 windowed **blt** to flip. It cannot be disabled while Auto HDR is on. | Relevant only if mpv is forced to blt (`d3d11-flip=no`) as a mitigation. [MS Support](https://support.microsoft.com/en-us/windows/hardware/display-graphics/optimizations-for-windowed-games-in-windows-11) |
| Dynamic refresh rate (DRR) | Switches e.g. 60↔120 Hz for inking or scrolling. "No, DRR is only available on laptops." | Not applicable to this desktop monitor. [DirectX blog: DRR](https://devblogs.microsoft.com/directx/dynamic-refresh-rate/) |

### 2.4 Nuvio's actual window tree (read from `player_bridge.cpp`)

| Element | Code evidence | Notes |
|---|---|---|
| Host | `hostHwnd` passed in from Compose/AWT | The AWT/Skiko window hierarchy is not analysed here |
| `containerHwnd` | `CreateWindowExW(0, kContainerWindowClass, …, WS_CHILD \| WS_VISIBLE \| WS_CLIPSIBLINGS, …, hostHwnd, …)` (line ~1400) | Child window with no ex-styles |
| mpv | `wid = containerHwnd` (line ~1666); `vo=gpu-next`; `gpu-api=d3d11` only when RTX VSR is on, else `auto`; no `video-sync` or `d3d11-flip` set (defaults: `audio`, flip=yes) | mpv creates its own child window inside `wid` **[inferred from mpv's w32 wid behaviour]**. The swapchain is flip-model, sync interval 1. |
| WebView2 | `CreateCoreWebView2Controller(self->containerHwnd, …)` (windowed hosting); `put_DefaultBackgroundColor(transparent)`; `put_Bounds` = full client area; `put_IsVisible(TRUE)` always (`layoutNativeSubviews`, line ~1713) | A full-size, transparent, always-visible child window over the video. Chromium renders it through its own DirectComposition content **[inferred]**. |
| App fullscreen | `setBorderlessFullscreen()`: strips caption and frame, `SetWindowPos` to the monitor rect | **Borderless**, not FSE. mpv's `d3d11-exclusive-fs` is not set (default off). |

### 2.5 Verdict: would Nuvio's player get independent flip or VRR?

| Scenario | NVCP "full screen only" | NVCP "windowed and full screen" |
|---|---|---|
| Windowed player | **No VRR** (composed: not covering the screen) **[inferred, high]** | **Likely VRR**. The hack follows the focused window. mpv reportedly "triggers VRR regardless of windowed/fullscreen mode" **(anecdote, [mpv discussion #14597](https://github.com/mpv-player/mpv/discussions/14597))**. Which swapchain inside the process drives the timing is **[uncertain]**. |
| App (borderless) fullscreen | **Probably no VRR**. The swapchain is on a *child* HWND, and a transparent full-size WebView2 HWND sits on top, so the DWM has to blend it. It could still be kept by "reverse compose" or MPO. NVIDIA MPO support is contested: Chromium notes say NVIDIA lacks it, while [Special K wiki](https://wiki.special-k.info/en/SwapChain) says NVIDIA assigns ≥4 planes to one display. **[uncertain, ~65 % no VRR]** | **Likely VRR** **[inferred]** |

Bottom line: this cannot be settled on paper. Phase 2 must record PresentMon `PresentMode` for the mpv swapchain in both window states.

---

## 3. What the panel does during pause, controls, buffering and seeks

### 3.1 mpv presentation behaviour (another agent owns mpv internals; this is what I verified)

From [`video/out/vo.c` `render_frame()`](https://github.com/mpv-player/mpv/blob/master/video/out/vo.c):

- `} else if (in->paused || !in->current_frame || … ) { goto done; }`: **while paused, no new draws**. Only queued frames and redraw requests (OSD, resize, property changes) render. Comment: "Always render when paused (it's typically the last frame for a while)."
- Display-sync modes (`video-sync=display-*`): each frame carries `num_vsyncs`, which is decremented once per render, and `repeat = true`. So **mpv re-renders and presents the frame once per vsync** while playing (`more_frames = true` while `num_vsyncs` remains). With the default `video-sync=audio`, it presents once per video frame at the frame's pts.
- A 2019 report says mpv shows "0 FPS" on pause and G-Sync has to ramp back up on resume. VLC drops to about 10 fps instead **(anecdote, [mpv #6884](https://github.com/mpv-player/mpv/issues/6884))**.
- Seeks and buffering: mpv presents no new frames until the decoder delivers **[inferred]** (Nuvio sets `hr-seek=no` and a large cache; stall length is unknown).

### 3.2 Effective panel refresh

| Event | Fixed refresh (composed, VRR not engaged) | VRR engaged |
|---|---|---|
| Playing 23.976 fps, `video-sync=audio` | Panel = mode rate (e.g. 240 Hz). The DWM repeats the last frame. **Constant.** **[inferred]** | Panel follows presents, about 23.976 Hz × LFC multiplier. The multiplier choice is up to the driver **[uncertain]**. Jitter in present timing becomes refresh jitter. |
| Paused | Constant mode rate | Presents stop, the panel falls to the LFC floor or minimum, then **jumps** on resume. This is the worst case (TFTCentral: large swings, static dark frame). |
| WebView2 controls animate (fade, seek-bar) | Constant. The DWM composes at the fixed rate. | **[uncertain]** With windowed G-Sync the DWM may be retimed by the focused process's presents. WebView2 renders in `msedgewebview2.exe`, not the focused `Nuvio`/`java` process. Expect erratic rates. |
| Buffering / seek | Constant | Presents stall, so refresh sags, then snaps back |
| Compose (Skiko) UI repaint | Constant | **[uncertain]** Same process as mpv, so it may drive the windowed-VRR timing |

**Conclusion [inferred]:** under fixed refresh, none of these events changes the panel rate. Under VRR, almost every event does.

---

## 4. Ways to keep the panel FIXED during playback without global changes

| # | Option | Mechanism | Global change? | Pros | Cons / risk | Verdict |
|---|---|---|---|---|---|---|
| **a1** | **Keep the current architecture composed** (child HWND + full-size WebView2 overlay, borderless fullscreen, no FSE) | Presentation never qualifies for independent flip, so the "full screen only" G-SYNC mode never engages | None | No code, no cost. The mode switch alone sets the rate. | Relies on the DWM not promoting via MPO or reverse-compose (§2.5). Does nothing if NVCP is "windowed and full screen". | **Primary**, pending Phase 2 proof |
| a2 | Harden composition: never let the overlay be fully absent, never use `d3d11-exclusive-fs` | Removes the triggers for promotion to independent flip | None | Cheap guard | Transparent overlays can still be MPO-promoted **[uncertain]** | Adopt as a rule: keep `d3d11-exclusive-fs=no` |
| a3 | `d3d11-flip=no` (blt model) | Blt is always copied and composed | None (but see risk) | Strong no-independent-flip guarantee | Win11 windowed-games optimization can **upgrade blt back to flip**, and it is forced on with Auto HDR ([MS Support](https://support.microsoft.com/en-us/windows/hardware/display-graphics/optimizations-for-windowed-games-in-windows-11)). mpv notes resize black-flicker with `d3d11-flip=no` ([mpv #12642](https://github.com/mpv-player/mpv/issues/12642)). **HDR passthrough and `target-colorspace-hint` on blt: [uncertain], likely degraded.** Extra copy cost. | Fallback only. Test HDR. |
| a4 | `d3d11-output-mode=composition` (mpv hands a composition swapchain to the app's DComp tree) | Always DWM-composed | None | Clean composition path | Needs bridge work (DComp target). DComp swapchains can still get MPO **[uncertain]**. | Future option, not needed now |
| **b1** | Steady cadence while playing: `video-sync=display-resample` (or `display-vdrop`) | mpv presents **every vsync** (§3.1), so even under VRR the panel stays at the mode rate | None | Also fixes judder and is the natural pair for N×fps modes | GPU work ×N: at 240 Hz and 23.976 fps, each frame is redrawn about 10× with the gpu-next pipeline (scalers, deband, tone-map). Power and heat rise **[inferred, measure]**. mpv reports display-resample interacting badly with Nvidia adaptive sync **(anecdote, [#14597](https://github.com/mpv-player/mpv/discussions/14597), [#8537](https://github.com/mpv-player/mpv/issues/8537))**. | Evaluate separately. Primarily a judder question (other agent). |
| b2 | Keep-alive presents while paused, buffering or seeking (e.g. periodic `redraw`/OSD tick at vsync rate) | Stops present gaps from dropping VRR to the floor | None | Targets the worst VRR case | Must run at the full mode rate to help. Anything slower is itself a swing. Costs GPU while paused. Hacky. | Only if Phase 2 shows VRR engaged |
| **c** | **[APPROVAL]** NVIDIA DRS per-app profile for Nuvio's exe: `VRR_APP_OVERRIDE` (`0x10A879CF`) = `VRR_APP_OVERRIDE_FIXED_REFRESH` (4), or `FORCE_OFF` (1) / `DISALLOW` (2). This is the same as NVCP "Monitor Technology: Fixed Refresh" per program. Related IDs: `VRR_MODE` `0x1194F158` (0 off / 1 FS only / 2 FS+windowed), `VRRREQUESTSTATE` `0x1094F1F7`. | The driver refuses VRR while the app is focused | Per-app driver profile (not global, but a driver-settings change) | Deterministic | IDs are public in [NvApiDriverSettings.h](https://docs.nvidia.com/nvapi/_nv_api_driver_settings_8h_source.html) but the semantics are undocumented. Per-app VRR-off can cause **monitor blanking or mode switches** on some monitors **(anecdotes: [#14597](https://github.com/mpv-player/mpv/discussions/14597), [Blur Busters t=14417](https://forums.blurbusters.com/viewtopic.php?t=14417))**. If the process is `java.exe`/`javaw.exe` in dev runs, the profile hits every Java app. | Last resort, owner decision |
| d | `SetDisplayConfig` / CCD per-mode VRR flag | None exists. `DISPLAYCONFIG_DEVICE_INFO_TYPE` has HDR, WCG and SDR-white entries, **no VRR entry** ([MS Learn](https://learn.microsoft.com/en-us/windows/win32/api/wingdi/ne-wingdi-displayconfig_device_info_type)) | n/a | n/a | n/a | Not available |
| e | **[APPROVAL]** Windows per-app graphics settings: `HKCU\Software\Microsoft\DirectX\UserGpuPreferences`, value named with the exe path, data string such as `VRROptimizeEnable=0;SwapEffectUpgradeEnable=0;` (the global equivalent is `DirectXUserGlobalSettings`) | Turns off *Windows'* VRR-for-DX11-FSE and the blt→flip upgrade for that app | User-level per-app setting | Needed with a3 to stop the flip upgrade | `VRROptimizeEnable` controls Microsoft's DX11-FSE VRR feature, **not NVIDIA's G-SYNC decision**, so it probably doesn't stop G-SYNC **[inferred]**. Format per [shoober420 scripts](https://github.com/shoober420/windows11-scripts/blob/main/EnableSwapEffectUpgrade.bat), [Winhance #363](https://github.com/memstechtips/Winhance/issues/363) (community sources). | Only paired with a3, owner decision |
| f | Monitor OSD "VRR: OFF" | The monitor ignores the VRR signal ([MO27Q28G manual p.14](https://download.gigabyte.com/FileList/Manual/GIGABYTE_MO27Q28G_MO27Q28GR_UM_English_20251001.pdf): "VRR Enable/Disable receiving VRR signal") | Global (monitor-wide) | Guaranteed | Violates "VRR untouched elsewhere" | Out of scope; diagnostic use only with owner consent |

There is no DXGI swapchain flag or D3D11/12 API to "opt out of VRR" on a flip-model swapchain **[uncertain: none found in MS docs]**. The documented levers are the presentation mode (composed vs independent flip) and driver profiles.

---

## 5. How to measure

| Tool | What it shows | Caveats | Install / approval |
|---|---|---|---|
| **Monitor "Refresh Rate" overlay** (OSD > Game Assist > GAME INFO > Refresh Rate; the OSD header also shows e.g. "2560X1440 / 280Hz") | The refresh the monitor receives. On VRR it should move live **[inferred, verify]**. | Whether it updates in real time under VRR is **[uncertain]**. Monitor-side setting only. | No PC change. Ask the owner. [Manual p.12](https://download.gigabyte.com/FileList/Manual/GIGABYTE_MO27Q28G_MO27Q28GR_UM_English_20251001.pdf) |
| NVIDIA "G-SYNC Compatible Indicator" (NVCP Display menu; DRS `VRRFEATUREINDICATOR` `0x1094F157`) | Overlay text when VRR is active for the focused app | Global NVIDIA setting. In 2019 it showed "60 FPS" while mpv was paused and not refreshing **(anecdote, [#6884](https://github.com/mpv-player/mpv/issues/6884))**. | **[APPROVAL]**: global NVCP toggle |
| **PresentMon** (Intel GameTechDev, MIT) | Per-present `PresentMode` (`Hardware: Independent Flip`, `Hardware Composed: Independent Flip`, `Composed: Flip`, `Composed: Copy with GPU GDI`…), `MsBetweenPresents`, `MsBetweenDisplayChange`, `DisplayedTime`. Options: `--process_name`, `--timed`, `--output_file`. | Needs admin/ETW (`--restart_as_admin`). It shows presents, not panel scanout. Under fixed refresh, `MsBetweenDisplayChange` quantizes to 1/mode. | **[APPROVAL]**: new tool dependency. [README](https://github.com/GameTechDev/PresentMon/blob/main/README-ConsoleApplication.md) |
| `DwmGetCompositionTimingInfo` | `rateRefresh`, `qpcRefreshPeriod`, `cRefresh` | Reports the DWM/mode timing. Likely **blind to VRR** **[inferred]**. Useful to confirm the mode switch (e.g. 240 Hz). | None (API call) |
| `EnumDisplaySettingsEx(ENUM_CURRENT_SETTINGS)` / `QueryDisplayConfig` | Current mode refresh (numerator/denominator) | Mode only, not live VRR | None |
| ETW (DxgKrnl via WPR/GPUView) | vsync/flip events | Heavy; PresentMon wraps the useful parts | WPR is built into Windows. Capture is read-only but needs admin. |
| Photodiode / RTINGS-style luminance | Actual flicker | Needs hardware | Out of scope |

### Recommended `scripts/measure.ps1` capture (Phase 2, measure-only)

1. **Static facts (no tools):** current mode via `EnumDisplaySettingsEx`/`QueryDisplayConfig` (width, height, refresh rational), HDR state (`DISPLAYCONFIG_DEVICE_INFO_GET_ADVANCED_COLOR_INFO`), `DwmGetCompositionTimingInfo.rateRefresh`. Log before playback, during playback and after restore.
2. **Owner-recorded facts:** the NVCP G-SYNC mode ("full screen" vs "windowed and full screen") and G-SYNC on/off. Collect these by asking the owner or with a read-only screenshot. Do not write DRS. Also record the monitor OSD VRR = ON.
3. **If PresentMon is approved:** `PresentMon --process_name <Nuvio exe> --timed 30 --output_file …` per scenario, plus a capture of `dwm.exe` for comparison. Summarize the `PresentMode` histogram and `MsBetweenDisplayChange` stats.
4. **Scenarios** (each ~30 s): windowed/playing; windowed/paused; windowed/controls fading; app-fullscreen/playing; app-fullscreen/paused; seek burst; buffering (throttled network if possible). Content: 23.976 and 25 fps, one SDR and one HDR file, preferably a dark scene.
5. **Monitor refresh overlay:** the owner notes, or phone-films, the OSD refresh number for each scenario.

---

## 6. Recommendation

1. **Design stance:** fixed-refresh presentation during playback, VRR untouched elsewhere. The refresh-switch feature sets the mode to N×fps. The player's job is to **stay DWM-composed** so the panel scans out exactly at that mode for the whole session. Pauses, UI animations and seeks then cannot move the panel.
2. **Keep** the current structure: mpv in a child HWND, full-size WebView2 overlay, borderless fullscreen, `d3d11-exclusive-fs` off. Add a code comment or invariant so nobody "optimizes" it into FSE or independent flip later **[inferred]**.
3. **If Phase 2 shows `Hardware*: Independent Flip` or live VRR:**
   - first `video-sync=display-resample` plus a pause/seek keep-alive (b1+b2), after measuring the GPU power cost;
   - then consider a3 (`d3d11-flip=no`), but only after an HDR test, since it probably needs option e **[APPROVAL]** to block the flip upgrade;
   - option c (NVIDIA per-app Fixed Refresh) **[APPROVAL]** is the last resort.
4. **If the owner uses NVCP "windowed and full screen" G-SYNC:** composition alone won't hold the rate. Tell the owner and choose between b1+b2 (in-app) and c **[APPROVAL]**.

### Needs owner approval

- Installing or using **PresentMon** (new tool dependency).
- Toggling the **NVCP G-SYNC indicator** (global).
- Any **NVAPI DRS / per-app NVIDIA profile** (option c).
- Any **UserGpuPreferences** per-app registry value (option e).
- Monitor OSD changes (the refresh-rate overlay is harmless; VRR OFF is out of scope).

### Phase 2 empirical checks (measure-only)

| # | Verify | How |
|---|---|---|
| 1 | NVCP G-SYNC mode (FS only vs FS+windowed) | Owner reads NVCP |
| 2 | mpv swapchain `PresentMode`, windowed and app-fullscreen | PresentMon (if approved) |
| 3 | Is it promoted by MPO or reverse-compose despite the WebView2 overlay? | PresentMon `Hardware Composed: Independent Flip` rows |
| 4 | Panel refresh stays at the mode during pause, seek, buffering and control animations | Monitor OSD refresh overlay (and the indicator, if approved) |
| 5 | Mode actually switched (e.g. 239.76/240 Hz) and restored | `EnumDisplaySettingsEx` / `QueryDisplayConfig` / DWM timing |
| 6 | Does the OSD overlay track VRR live (sanity test with a known VRR app)? | Owner observation |
| 7 | GPU power and clocks for `video-sync=audio` vs `display-resample` at 240 Hz | `nvidia-smi` (already installed with the driver; read-only query) |
| 8 | HDR stays intact with any mitigation candidate (especially `d3d11-flip=no`) | Visual check plus mpv `video-out-params` / log |
| 9 | Which process/HWND owns focus in fullscreen (the windowed-VRR hack follows focus) | `GetForegroundWindow` → PID log |
