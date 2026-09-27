# 03 - mpv / libmpv: fps detection, display-fps, video-sync, stats, HDR

- Date: 2026-09-28
- Scope: the Windows player (`composeApp/src/desktopMain/native/windows/player_bridge.cpp`), which loads the bundled `runtime/libmpv-2.dll`
- Method:
  - Read the DLL's strings. The DLL was not executed.
  - Read the mpv source **at the exact bundled commit**, fetched from `raw.githubusercontent.com/mpv-player/mpv/f6c116491…`.
  - Checked a few items on the web.
- Citation base: `M = https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25`. A link written as `M/video/out/vo.c#L539` means that file and line at that commit.
- Markers:
  - **[code]** means verified in source at the bundled commit.
  - **[inferred]** is my reasoning and has not been verified on this machine.
  - **[uncertain]** means unknown or weakly sourced.

## TL;DR

| Topic | Finding |
|---|---|
| Bundled mpv | **v0.40.0-465-gf6c116491**. This is a git build dated 2025-11-21. It is 45 commits *before* the v0.41.0 tag (released 2025-12-21). |
| libplacebo | **v7.357.0** (`v7.351.0-90-g2e5a392-dirty`, commit dated 2025-11-06) |
| FFmpeg | **N-121828-gc75ada504** (git master, Nov 2025). The library versions are Lavc 62.19.100 and Lavf 62.6.103, which is the FFmpeg 8.x API line. |
| Build origin | A `mpv-winbuild-cmake` GitHub Actions build (x86_64-w64-mingw32). It is either shinchiro's or zhongfly's; the exact nightly cannot be recovered. |
| fps source | Read `track-list/N/demux-fps` at the `on_preloaded` hook, or `container-fps` at `file-loaded`. Confirm it against `estimated-vf-fps` after about 1 s of playback. Snap the result to a canonical rational. If the two disagree, treat the file as VFR or unknown and do not switch. |
| display-fps re-detect | **Not reliable in embedded (`wid`) mode.** Only `WM_DISPLAYCHANGE` forces a re-query on the same monitor, and a `WS_CHILD` window most likely never receives it. The self-correction from measured vsync timing only works while display-sync is active, and it takes a few seconds. |
| Best design | Switch the mode **before the VO exists** (in the `on_preloaded` hook). mpv then reads the new rate when it creates its window. As a belt-and-braces measure, also set `display-fps-override`. |
| video-sync | `display-resample` with the default limits. 23.976 fps on 239.90 Hz gives +0.058 % speed and about +1 cent of pitch. The mode can be changed at runtime through `set_property`. |
| DV P5 | Supported: FFmpeg DOVI side data plus libplacebo reshaping, applied when `disable_residual_flag=1`. P8 is reshaped the same way. P7 FEL falls back to base layer, and P7 MEL is **[uncertain]**. |
| Paused | mpv **does not** present continuously while paused. It presents only on redraw requests. |

---

## 1. Bundled libmpv version

### Evidence from the DLL
The file is `composeApp/src/desktopMain/native/windows/runtime/libmpv-2.dll`. It is 115,320,296 bytes and begins with the `MZ` header, so it is a real PE file and not an LFS pointer. `.gitattributes` tracks it through LFS, and CI (`.github/workflows/desktop-release.yml:390-398`) fails the build if the file is smaller than 1 MB.

| String found (via `grep -a`) | Meaning |
|---|---|
| `mpv v0.40.0-465-gf6c116491` ... `Nov 22 2025 00:09:47` ... `Copyright © 2000-2025 mpv/MPlayer/mplayer2 projects` | mpv git describe string and build timestamp |
| `v7.357.0 (v7.351.0-90-g2e5a392-dirty)` | libplacebo version, with git describe in parentheses |
| `FFmpeg version: %s` / `N-121828-gc75ada504` | FFmpeg master snapshot |
| `Lavc62.19.100`, `Lavf62.6.103` | libavcodec 62, libavformat 62 (FFmpeg 8.x ABI) |
| `/__w/mpv-winbuild-cmake/mpv-winbuild-cmake/build_x86_64/...` (456 hits) | Built in a GitHub Actions container from `mpv-winbuild-cmake` |

### Resolving the commits
- **mpv `f6c116491`** is dated 2025-11-21 ("DOCS/man/input: don't discourage hook usage"). The GitHub compare `f6c116491...v0.41.0` reports "ahead 45, behind 0", so this is a pre-0.41.0 development build. Its `DOCS/interface-changes.rst` already has a `--- mpv 0.41.0 ---` section.
  - v0.41.0 was released 2025-12-21 ([release](https://github.com/mpv-player/mpv/releases/tag/v0.41.0)).
  - Per its release notes, v0.41.0 makes `gpu-next` the default VO and needs FFmpeg ≥ 6.1 and libplacebo ≥ 6.338.2 ([UbuntuHandbook summary](https://ubuntuhandbook.org/index.php/2025/12/mpv-0-41-0-improved-wayland/)).
- **libplacebo `2e5a392`** is dated 2025-11-06 ("vulkan/formats: check hostImageCopy…"). It sits between tags v7.351.0 (2025-05) and v7.360.0 (2026-02) ([code.videolan.org API](https://code.videolan.org/videolan/libplacebo/-/tags)).
- **FFmpeg `c75ada504`** has author date 2025-11-08 and committer date 2025-11-22T18:38Z.
  - **[uncertain]** That committer date is later than the mpv `__DATE__` stamp. Either the stamp is in a different timezone, or the build ran after the stamp. This does not affect any conclusion here.

### Where the DLL comes from
- **Nothing in the repo downloads it.** `composeApp/build.gradle.kts` only references it as a runtime file. The CI workflow just runs `git lfs pull`. It was added by `tapframe` in commit `069e376c` (2026-06-16, "fix libmpv runtime bundling").
- Both [shinchiro/mpv-winbuild-cmake](https://github.com/shinchiro/mpv-winbuild-cmake/releases) and [zhongfly/mpv-winbuild](https://github.com/zhongfly/mpv-winbuild/releases) build under this path. Both publish `mpv-dev-x86_64-*-git-<hash>.7z` and rotate old nightlies out.
  - Neither repo's current release list still contains a `f6c1164` asset, so the exact release cannot be named. **[uncertain]**
- **Implication:** every behaviour below was checked against the source at `f6c116491`, not against the latest mpv manual. Where the current manual differs, the bundled version wins.

---

## 2. Reliable fps

### Properties and their sources

| Property | Source | Available from | Notes |
|---|---|---|---|
| `track-list/N/demux-fps` | Demuxer. For lavf this is `st->avg_frame_rate` ([demux_lavf.c#L750](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/demux/demux_lavf.c#L750)). For mkv it is `1e9 / DefaultDuration` ([demux_mkv.c#L972](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/demux/demux_mkv.c#L972), [#L1708](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/demux/demux_mkv.c#L1708)). | After the demuxer opens, so it is already available at the **`on_preloaded` hook** ([loadfile.c#L1779](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/loadfile.c#L1779)), before track selection and VO creation. **[code]** | "Not always accurate" ([manual](https://mpv.io/manual/master/#command-interface-track-list/N/demux-fps)) |
| `container-fps` | Same value, via `vo_chain->filter->container_fps` ([command.c#L3190](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/command.c#L3190)). Overridable with `--container-fps-override` (renamed from `--fps` in 0.37). | Once the video chain exists: `reinit_video_chain` runs at [loadfile.c#L1836](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/loadfile.c#L1836), before `MPV_EVENT_FILE_LOADED` at [#L1869](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/loadfile.c#L1869). So it is **available at `file-loaded`**. It is UNAVAILABLE if the value is < 0.1. **[code]** | The manual says it "can easily contain bogus values" |
| `estimated-vf-fps` | `1 / average(past_frames[].approx_duration)` ([command.c#L3200](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/command.c#L3200), [video.c#L662](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/video.c#L662)) | Only after frames have been queued to the VO, so a few frames after `playback-restart`. It is unavailable before the first frame. **[code]** | Measured from timestamps of the filter-chain output, so it reflects deinterlacer or filter rate changes. The manual warns it is unstable with mkv timestamp rounding and after framedrops ([manual](https://mpv.io/manual/master/#command-interface-estimated-vf-fps)). |
| `video-params` / `video-out-params` | Decoded frame parameters | After `video-reconfig` (first decoded frame) | **They contain no fps field.** Use them only for w/h, colour and HDR metadata. **[code]** |

- **How display-sync itself picks the frame duration** ([video.c#L968](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/video.c#L968) `calculate_frame_duration`):
  1. It takes the pts difference of the next two frames.
  2. It "unrounds" mkv's 1 ms rounding.
  3. It snaps to `1/container_fps` when that fits within about 3.1 ms tolerance over ≥16 frames.
  - So on a clean CFR file, mpv's own timing ends up at exactly `24000/1001`.

### VFR detection
- The manual says display-sync modes require CFR: "Section-wise VFR might work … but not e.g. mkv" ([manual, --video-sync](https://mpv.io/manual/master/#options-video-sync)).
- mpv exposes no "is VFR" flag. **[code]**
- Heuristics **[inferred]**:
  - `demux-fps` or `container-fps` is absent or 0. This is common for raw TS/HLS without probing, and for some WebM.
  - `estimated-vf-fps` differs from `container-fps` by more than 0.5 %, or wanders by more than about 1 % between samples taken every 250 ms over 3–5 s. mkv rounding produces ±0.01 fps noise, which is fine.
  - Container fps with an implausible value, such as 1000 (Matroska timebase) or 90000.

### Streams, interlaced and telecine
- **HLS / HTTP MKV.**
  - HTTP MKV behaves like a local mkv, since `DefaultDuration` is in the track header. **[code]**
  - For HLS (lavf `hls` demuxer, TS or fMP4 segments), `avg_frame_rate` comes from FFmpeg's probe. It is usually set but can be missing or rough. **[uncertain]**
  - Treat a missing value as "wait for `estimated-vf-fps`".
- **Interlaced.**
  - In this build `--deinterlace` defaults to **`no`**, and `auto` exists ([options.rst `--deinterlace`](https://mpv.io/manual/master/#options-deinterlace)). So 1080i25 is shown as 25 combed frames.
  - If deinterlacing is enabled, bwdif outputs field rate: 50 fps from 25i. `estimated-vf-fps` shows 50 while `container-fps` still says 25. **[inferred]**
  - `video-frame-info/interlaced` tells you the source is interlaced.
- **Telecine.**
  - mpv does no inverse telecine by default. Hard-telecined 29.97 content is presented as 29.97; to get 23.976 you would need `vf=fieldmatch,decimate` or similar.
  - Soft-telecine MPEG-2 (repeat_pict flags) usually decodes to 23.976 progressive frames with uneven pts. **[inferred/uncertain]**
  - Both cases are rare in Nuvio's sources (debrid MKV/MP4, HLS). Recommend "don't switch" when `container-fps` ≈ 29.97 and `video-frame-info/interlaced` is set.

### Recommended rule
1. **At `on_preloaded`** (register with `mpv_hook_add(mpv, id, "on_preloaded", 0)`; the DLL exports `mpv_hook_add` and `mpv_hook_continue`):
   - Read `track-list`, taking the first video track with `default=yes`, or else the first video track.
   - Read `demux-fps`, `image`, and `albumart`.
   - If the track is an image or has no fps, skip.
2. Snap `demux-fps` to the nearest canonical rate within 0.1 %:
   - 24000/1001, 24, 25, 30000/1001, 30, 48000/1001, 48, 50, 60000/1001, 60.
   - If nothing matches, do not switch.
3. Switch the display mode, wait for it to settle, then call `mpv_hook_continue`. See §3 for why before the VO exists.
4. **Verify after `playback-restart` plus about 1–2 s:**
   - `estimated-vf-fps` must be within 0.5 % of the snapped rate. If it is not, the file is VFR, interlaced-and-deinterlaced, or has a bogus header → switch back to the original mode, or leave it and turn display-sync off.
   - `display-sync-active` must be `yes`.
5. **Fallback when `demux-fps` is missing** (e.g. some HLS):
   - Start in the native mode with `video-sync=audio`.
   - Sample `estimated-vf-fps` for about 3 s.
   - Optionally switch mid-playback and apply the §3 mitigations.

---

## 3. display-fps on Windows (vo=gpu-next, gpu-api=d3d11, `wid`)

### Where the number comes from [code]
1. **Nominal rate, `display-fps`.** The w32 backend's `update_display_info()` ([w32_common.c#L692](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/w32_common.c#L692)) tries two sources in order:
   1. `mp_w32_displayconfig_get_refresh_rate()` ([win32/displayconfig.c#L108](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/win32/displayconfig.c#L108)). This calls `QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS)` and returns the **target mode's rational `vSyncFreq`** (Numerator/Denominator), falling back to `path->targetInfo.refreshRate`. So it is the driver's exact rational, not an integer.
   2. Otherwise `EnumDisplaySettingsW(ENUM_CURRENT_SETTINGS).dmDisplayFrequency` ([#L611](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/w32_common.c#L611)). This is an integer, and values such as 23, 59, 119 and **239** are mapped to `(n+1)/1.001`.
   - **Not** DXGI and **not** `DwmGetCompositionTimingInfo`.
2. **Measured rate, `estimated-display-fps`.**
   - The d3d11 context's `d3d11_get_vsync()` ([d3d11/context.c#L282](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/d3d11/context.c#L282)) uses `IDXGISwapChain::GetFrameStatistics` (`SyncRefreshCount` and `SyncQPCTime`).
   - It only works with a flip-model swapchain and `d3d11-sync-interval=1`.
   - `vo.c` averages the last ≤1000 vsync intervals ([vo.c#L481](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L481)).
   - Samples are only collected while frames are display-synced and not paused ([vo.c#L988](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L988)). So the property exists only while display-sync is active, as the manual says.
3. **Which one timing uses** ([vo.c#L416](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L416) `check_estimated_display_fps`):
   - It starts from nominal, or from `display-fps-override` if that is > 0 ([vo.c#L556](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L556)).
   - It switches to the **measured** interval once all of these hold:
     - at least 500 samples (about 2 s at 240 Hz);
     - every sample within 25 % of the average;
     - the average between 20 and 400 Hz;
     - the measured value fits better (`mjitter*1.01 < njitter`).
   - In this version there is no longer an "at least 1 % off" guard, so even the 239.90 vs nominal gap is adopted once the measurement is clean.

### Is it re-detected after a mode change? Mostly no in embedded mode (confidence: medium-high)
[code] facts:
- `update_display_fps()` re-queries `VOCTRL_GET_DISPLAY_FPS` only when `VO_EVENT_WIN_STATE` has been raised ([vo.c#L539](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L539)).
- `VOCTRL_GET_DISPLAY_FPS` → `update_display_info()` **returns early if `MonitorFromWindow` gives the same `HMONITOR` as last time** ([w32_common.c#L694-L696](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/w32_common.c#L694)). The cached value is then returned ([#L2432](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/w32_common.c#L2432)).
- A same-monitor re-query only happens through `force_update_display_info()`, which resets `w32->monitor = 0`. That is called from:
  - `WM_DISPLAYCHANGE` ([#L1714](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/w32_common.c#L1714));
  - a zero-DPI init path.
- `WM_MOVE`, `WM_SIZE` and `WM_DPICHANGED` call the non-forced version, so on the same monitor they do **not** refresh fps.
- With `wid`, mpv creates its window as `WS_CHILD` of our container HWND ([#L2074-L2083](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/w32_common.c#L2074)).

[inferred / widely reported, not verified here]:
- Windows sends `WM_DISPLAYCHANGE` only to top-level windows, so mpv's child window never gets it ([cprogramming thread](https://cboard.cprogramming.com/windows-programming/44480-wm_displaychange-clarification.html)).
- The official doc just says "sent to all windows" ([MS Learn](https://learn.microsoft.com/en-us/windows/win32/gdi/wm-displaychange)).
- **Result:** after a mid-playback 280→240 Hz switch, `display-fps` stays at 280 until mpv's measurement takes over.
  - That takes ≥500 samples, and the ring buffer still holds old 280 Hz samples, so expect several seconds.
  - Meanwhile display-sync computes cadence from 280 Hz. For 23.976 it picks factor 3 (35 vsyncs per 3 frames, see §4). That is wrong cadence until the measurement takes over.
- I found no mpv issue specifically about stale `display-fps` in `wid` mode. [uncertain]
  - Related issues: [#3433](https://github.com/mpv-player/mpv/issues/3433) and [#8780](https://github.com/mpv-player/mpv/issues/8780).

### Mitigations (use in this order)

| # | Approach | Why |
|---|---|---|
| 1 | **Switch before the VO is created.** Do it in the `on_preloaded` hook, before `mpv_hook_continue`. | The VO and window are created at `reinit_video_chain`, after the hook. `vo_thread` calls `update_display_fps` at startup ([vo.c#L1141](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L1141)), so mpv reads the new rate first time. The bridge creates a fresh `mpv_create()` per `startMpv` and sets no `force-window`, so there is no earlier VO. **[code + inferred]** |
| 2 | **Set `display-fps-override`** to the exact value, preferably the same `QueryDisplayConfig` rational we switched to (e.g. `239.90`). | It is a runtime-settable VO option: `vo_sub_opts` has a change callback ([vo.c#L237](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L237), [options.c#L185](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/options/options.c#L185)), and it is read on every render loop ([vo.c#L556](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L556)). **Name:** `display-fps-override` (0.37+). `override-display-fps` is an `OPT_REPLACED` alias ([options.c#L252](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/options/options.c#L252)), and writing `display-fps` itself was removed in 0.37. Set it back to `0` on restore. |
| 3 | **Forward `WM_DISPLAYCHANGE`** from our top-level window to mpv's child. | Find it with `FindWindowExW(containerHwnd, NULL, L"mpv", NULL)` (class name `mpv`, [#L54](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/w32_common.c#L54)) and `SendMessage` it. This triggers mpv's own forced re-query. **[inferred, test]** |

The manual warns that a slightly wrong override "can ruin video playback" ([manual](https://mpv.io/manual/master/#options-display-fps-override)). So derive it from `QueryDisplayConfig`, not from a hard-coded integer. The measured-vsync takeover (§3.3) still corrects small errors.

### Other mid-switch risks [inferred]
- The monitor resync (black screen for about 1–3 s on OLED over DP/HDMI) makes presents stall. This gives `vo-delayed-frame-count` spikes and A/V offset, which display-resample then fixes by drop/repeat (`mistimed-frame-count`++).
- `GetFrameStatistics` may return `DISJOINT`. mpv handles this by resetting its counters ([context.c#L316](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/d3d11/context.c#L316)).

---

## 4. video-sync modes

The source for everything in this section is the [manual, `--video-sync`](https://mpv.io/manual/master/#options-video-sync). The implementation is [video.c#L810](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/video.c#L810) `handle_display_sync_frame`.

| Mode | Video | Audio | A/V drift fix | Use? |
|---|---|---|---|---|
| `audio` (default) | Timed to the audio clock. Occasional drop/repeat when fps ≠ Hz. | Untouched | Video drop/repeat | Current behaviour |
| `display-resample` | Each frame shown for an integer number of vsyncs. Video speed is scaled so fps×N = Hz. | **Resampled** to the same speed (pitch shifts), plus ≤ `video-sync-max-audio-change` extra | Audio speed nudge, then frame drop/repeat | **Recommended** |
| `display-resample-vdrop` | Same | Resampled, no extra nudge | Video drop/repeat only | Fallback if audio nudging misbehaves |
| `display-resample-desync` | Same | Resampled | None (for testing) | No |
| `display-tempo` | Same | Speed applied via audio filters (scaletempo2), so no pitch change | Same as resample | Unnecessary at 0.06 % |
| `display-vdrop` | Integer vsyncs, **no speed change** (`speed_factor_v = 1`, [video.c#L847](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/video.c#L847)) | Untouched | Video drop/repeat | Periodic drop/repeat, e.g. one repeat per 7 s at 23.976 on 239.90, which is small |
| `display-adrop` | Integer vsyncs, speed-scaled | Whole audio frames dropped/repeated | Audio drop | Audible artifacts; no |
| `display-desync` / `desync` | Test modes | | none | No |

### Key knobs (defaults at this commit, [options.c#L861-L865](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/options/options.c#L861))
- `video-sync-max-video-change=1` (%). This is the largest speed change allowed; beyond it display-sync is disabled for that frame.
- `video-sync-max-audio-change=0.125` (%). Extra audio speed for drift correction.
- `video-sync-max-factor=5` (range 1–10). This limits the number of *frames* per cadence cycle, **not** the vsyncs per frame.
  - `calc_best_speed` ([video.c#L680](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/video.c#L680)) tries `factor = 1..5`, computing `ratio*factor / rint(ratio*factor)`.
  - At 23.976 on 239.90 Hz, factor 1 already fits, so a 10:1 cadence is fine.
- **Disabled** when:
  - the frame duration is > 0.5 s;
  - `display-fps` is unknown;
  - the window is not visible;
  - audio is S/PDIF passthrough (resample modes) ([video.c#L824-L842](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/video.c#L824)).
  - Nuvio always decodes to PCM, so the passthrough case does not apply.
- `interpolation` / `tscale`:
  - Interpolation needs a `display-*` mode.
  - `interpolation-threshold=0.01` switches it off when retiming alone is within 1 % ([manual](https://mpv.io/manual/master/#options-interpolation-threshold)).
  - With exact integer multiples it gains nothing and costs GPU. **Leave `interpolation=no`**, the default.

### Speed maths (display 239.90 Hz measured; mpv logic `speed = (Hz/fps) / rint(Hz/fps)`)

| Content | Hz/fps | N | Video/audio speed | Pitch | 2 h runtime change |
|---|---|---|---|---|---|
| 23.976 (24000/1001) | 10.00583 | 10 | **×1.000583 (+0.058 %)** | +1.0 cent | −4.2 s |
| 24.000 | 9.99583 | 10 | ×0.999583 (−0.042 %) | −0.7 cent | +3.0 s |
| 29.97 | 8.00466 | 8 | ×1.000583 | +1.0 cent | −4.2 s |
| 59.94 | 4.00233 | 4 | ×1.000583 | +1.0 cent | −4.2 s |
| 25 **without** a switch (on 239.90) | 9.596 | 48 per 5 frames (factor 5) | ×0.99958 | −0.7 cent | 10/9/10/9/10 cadence (≈4.2 ms jitter) |
| 25 on a 100 Hz mode | ≈4.00 | 4 | depends on the real 100 Hz timing (measure it) | | |

- All values are well inside the 1 % limit.
- A 1-cent pitch shift is inaudible [inferred; the commonly cited just-noticeable difference is about 5 cents].
- Without a mode switch, 23.976 on the native 280 Hz gives 280/23.976 = 11.678. Factors 1 and 2 fail the 1 % test, and factor 3 gives 35 vsyncs per 3 frames (speed ×1.001, cadence 12/12/11). That is judder of about 3.6 ms, which is the problem this feature solves.

### Runtime change
- `video-sync` is a VO sub-option with a change callback, and it is re-read on **every frame** (`vo->opts->video_sync` in [video.c#L814](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/video.c#L814)). So `mpv_set_property_string(mpv, "video-sync", "display-resample")` works per file without reinit. **[code]**
- Setting it before `loadfile` or in the hook is the cleanest option.
- Restore it to `audio` when the feature is off.

### Risks and behaviour [manual + inferred]
- **Seek, resize, fullscreen toggle.** Frames that "should have been displayed" are skipped, where `audio` mode would show them late ([manual](https://mpv.io/manual/master/#options-video-sync)). Timing resets on seek (`reset_vsync_timings`).
- **Pause.** Display-sync timing is suspended while paused (`use_vsync = … && !paused`, [vo.c#L988](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L988)) and restarts on resume. It takes about 10 frames (`DELAY_VSYNC_SAMPLES`) to re-lock.
- **Audio device change or AO reinit.** Audio goes to `STATUS_SYNCING`, and display-sync keeps running. Speed updates are deferred while syncing ([video.c#L935](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/player/video.c#L935)). Expect a few `mistimed` frames. [inferred]
- **No audio track.** Display-sync still works, with video paced to vsync. [inferred]
- **`untimed`.** Do not set it; it disables all timing.
- **Load.** In display-sync each vsync is a real Present. At 240 Hz that is 240 presents/s, versus 24/s in `audio` mode, which Windows repeats itself. gpu-next caches the frame for repeats (`will_redraw`/`cache_frame`, [vo_gpu_next.c#L1014](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo_gpu_next.c#L1014)), so each repeat is a cheap blit. RTX VSR (`vf=d3d11vpp`) runs per source frame and is unaffected. [inferred: measure GPU %]

---

## 5. Stats to measure (all present as strings in the bundled DLL)

| Property | Meaning ([manual, properties](https://mpv.io/manual/master/#property-list)) | In DLL |
|---|---|---|
| `display-sync-active` | Whether display-sync is actually engaged | yes |
| `display-fps` | Nominal/override rate the VO uses | yes |
| `estimated-display-fps` | Measured vsync rate (display-sync only) | yes |
| `vsync-ratio` | Average vsyncs per frame (expect 10.0 at 23.976→240) | yes |
| `vsync-jitter` | Relative stddev of the vsync interval | yes |
| `mistimed-frame-count` | Frames drop/repeated **by display-sync** for A/V sync | yes |
| `vo-delayed-frame-count` | Guessed vsyncs missed or delayed by the driver/compositor | yes |
| `frame-drop-count` | VO drops (framedrop=vo, default) | yes |
| `decoder-frame-drop-count` | Decoder drops | yes |
| `audio-speed-correction`, `video-speed-correction` | Speed factors (expect ≈1.000583) | yes |
| `avsync`, `total-avsync-change` | A/V difference and cumulative correction | yes |
| `container-fps`, `estimated-vf-fps`, `track-list/N/demux-fps` | Source fps (§2) | yes |
| `vo-passes` | Per-pass GPU timings (fresh vs redraw) | yes |
| `video-frame-info/interlaced` etc. | Per-frame flags | yes |

- Old names `drop-frame-count` and `vo-drop-frame-count` were removed in 0.37.
- Observe these with `mpv_observe_property`. The DLL exports it, but **the bridge does not load it yet**. It only loads the `create/initialize/…/wait_event/wakeup` family (player_bridge.cpp around line 485–520).
- A suggested pass criterion, per 10-minute sample:
  - `mistimed-frame-count` and `vo-delayed-frame-count` roughly flat after the first seconds;
  - `vsync-ratio` ≈ N;
  - `display-sync-active=yes` throughout.

## 6. Logging from embedded libmpv

| Method | How | Notes |
|---|---|---|
| `mpv_request_log_messages(mpv, "v")` | Then handle `MPV_EVENT_LOG_MESSAGE` (`prefix`, `level`, `text`) in `drainMpvEvents` | The export is present in the DLL, but the bridge does not load it. The bridge's drain loop currently ignores every event except shutdown. Use `"trace"` for per-frame display-sync lines. [client.h](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/include/mpv/client.h) |
| `log-file=<path>` | `setMpvOptionStringLocked("log-file", "…\\mpv.log")` before `mpv_initialize` | Forces at least `-v -v` level and truncates the file ([manual](https://mpv.io/manual/master/#options-log-file)). Easiest for a measurement script. |
| `msg-level=all=v,cplayer=trace,vo=trace` | Option | Also affects log-file and API logging ([manual](https://mpv.io/manual/master/#options-msg-level)) |

Useful log lines [code]:

| Line | Source |
|---|---|
| `Container reported FPS: %f` | vd, [f_decoder_wrapper.c#L1227](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/filters/f_decoder_wrapper.c#L1227) |
| `display-fps: %f` | win32 |
| `Assuming %f FPS for display sync.` | vo |
| `Video sync mode enabled/disabled.` | cplayer |
| `Change display sync audio drift: %d` | cplayer |
| `s=%f vsyncs=%d dur=%f ratio=%f err=…` | trace, per frame |
| `adjusting display FPS to a value closer to %.3f Hz` | vo trace |

## 7. Dolby Vision / HDR10+

| Item | Status in this build |
|---|---|
| **DV Profile 5 reshaping** | **Supported.** FFmpeg's HEVC decoder parses the RPU in software even with hwaccel, and exports `AV_FRAME_DATA_DOVI_METADATA`. mpv maps it with libplacebo `pl_map_avdovi_metadata` when `header->disable_residual_flag` is set ([mp_image.c#L1164-L1185](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/mp_image.c#L1164)), and passes it through the hwdec mapper ([vo_gpu_next.c#L526](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo_gpu_next.c#L526)). libplacebo DV reshaping needs PL_API ≥ 191 (2022; [FFmpeg-devel patch](https://ffmpeg.org/pipermail/ffmpeg-devel/2022-January/290837.html)). The bundled API is 357. The DLL contains `dovi_reshape` shader and "Dolby Vision profile - 5" strings. [code + DLL] |
| DV P8.x | Same path, since the RPU has no residual, so reshaping is applied. It can be stripped with `vf=format:dolbyvision=no` ([vf.rst](https://mpv.io/manual/master/#video-filters-format)). |
| DV P7 FEL | The residual flag is 0, so there is **no reshaping**: mpv plays the HDR10 base layer and never decodes the EL. The DLL contains the string "Dolby Vision enhancement-layer playback is not supported". |
| DV P7 MEL | Depends on how the RPU sets `disable_residual_flag`. **[uncertain]** |
| DV RPU → HDR metadata | `pl_hdr_metadata_from_dovi_rpu` feeds per-scene luminance from `AV_FRAME_DATA_DOVI_RPU_BUFFER` ([mp_image.c#L1188](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/mp_image.c#L1188)). This may need libdovi inside libplacebo; whether libdovi is linked is **[uncertain]**. |
| **HDR10+** | `AV_FRAME_DATA_DYNAMIC_HDR_PLUS` is mapped into `pl_hdr_metadata` ([mp_image.c#L1150](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/mp_image.c#L1150)). It is used by tone mapping, and `tone-mapping=st2094-40` is available. Full HDR10+ is not passed to the display: `target-colorspace-hint-mode=source-dynamic` sends per-scene HDR10 values only, and that mode is experimental ([options.rst](https://mpv.io/manual/master/#options-target-colorspace-hint-mode)). |

### Refresh switch vs `target-colorspace-hint=yes`
- [code] facts:
  - The hint is recomputed on **every `draw_frame`** from `d3d11_target_color_space()`, which reads `IDXGIOutput6::GetDesc1` for the window's monitor ([context.c#L206](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/d3d11/context.c#L206), [vo_gpu_next.c#L1090-L1160](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo_gpu_next.c#L1090)).
  - The DXGI factory is recreated when `IsCurrent()` is false, which happens after a mode change ([d3d11_helpers.c#L1004](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/gpu/d3d11_helpers.c#L1004)).
  - The default hint mode here is `target`.
  - So after a switch, mpv picks up the output's new state automatically.
- Risk [inferred]:
  - If Windows briefly reports the output as SDR during the mode change, one or more frames get an SDR hint and the swapchain colour space flips and flips back. That could show as a flash.
  - A same-resolution refresh change does not resize the swapchain.
  - Switching before VO creation (§3 mitigation 1) avoids this whole class of problem.

## 8. Pause cadence [code]
- **Paused, no presents.** `render_frame` bails out while paused unless a new frame is queued ([vo.c#L935](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L935)). The VO thread then sleeps up to 1000 s ([vo.c#L1133](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L1133)). It wakes only for a redraw request, such as an OSD or subtitle change, a window expose, an option change, or `vo_redraw`, and then presents a single frame (`do_redraw`, [vo.c#L1079](https://github.com/mpv-player/mpv/blob/f6c116491a82314332f8ded74fc92b2b5cdf7e25/video/out/vo.c#L1079)).
- **Display-sync while paused.** Display-sync is effectively off while paused (`use_vsync = display_synced && !paused`). There is no vsync-rate presenting while paused in any `video-sync` mode.
- **Playing, display-sync.** Presents every vsync (240/s at 240 Hz).
- **Playing, `video-sync=audio`.** Presents once per video frame.
- **No option forces continuous redraw on Windows.**
  - `--force-render` only affects X11, Wayland and macvk ([options.rst](https://mpv.io/manual/master/#options-force-render)).
  - A workaround would be an app-side timer that calls `mpv_command("frame-step")` (no) or keeps triggering redraws, e.g. by toggling an invisible OSD. Both are hacks. [inferred]
- On a fixed-refresh (non-VRR) mode, DWM keeps scanning out the last frame, so this is harmless. VRR interaction is covered in another doc.

---

## Open questions (need measurement on the owner's machine)
1. Does mpv's `WS_CHILD` window really never see `WM_DISPLAYCHANGE`? Test by switching the mode mid-playback and watching the `display-fps` property and log.
2. What rational does `QueryDisplayConfig` report for the 240 Hz mode (239.90 or 240.00), and for the 100 Hz mode?
3. How long does the OLED take to resync after a mode switch? This sets the `on_preloaded` wait and the verification delay.
4. HLS sources: is `demux-fps` present at `on_preloaded`?
5. GPU cost of 240 presents/s with gpu-next plus RTX VSR.
6. DV P7 MEL: does mpv reshape it? Also whether libdovi is linked.
