# 02 - Enumerating the monitor's real modes (measured on the owner's PC)

- Date: 2026-09-28
- Machine: Windows 11 10.0.26200, RTX 4070 SUPER, DisplayPort, Gigabyte MO27Q28G (2560x1440 WOLED, HDR on).
- Method: two **read-only** C++ probes, built with VS 18 / SDK 10.0.26100. They call only `EnumDisplayDevicesW`, `EnumDisplaySettingsExW`, `GetDisplayConfigBufferSizes`/`QueryDisplayConfig`, `DisplayConfigGetDeviceInfo` (GET_* types only) and DXGI `GetDisplayModeList1`/`GetDesc1`. No `Change*`/`Set*`/`CDS_TEST` calls. Sources and raw output are in the session scratchpad (`enum_modes.cpp`, `enum_detail.cpp`, `modes_out.txt`, `detail_out.txt`), not in the repo.
- Labels as in 01: **[doc]**, **[src]**, **[measured]**, **[inferred]**, **[uncertain]**.

## TL;DR

- **100 Hz is visible to every API**: GDI with and without `EDS_RAWMODE`, and DXGI. NVCP hides it, but Windows does not [measured].
- **The exact rational rates come from DXGI or DisplayConfig, never from GDI.** 240 is really **239901/1000 = 239.901 Hz** (DXGI mode list). The current 280 is **279961/1000** (QDC).
- **Enumerate with DXGI `IDXGIOutput1::GetDisplayModeList1`** (exact rationals, one entry per real timing). Use **GDI `EnumDisplaySettingsExW` only to build the `DEVMODE`** for the switch. Use **QDC** to read the current rate. Use **`ENUM_REGISTRY_SETTINGS`** as the "original" to restore to.

## 1. Results at 2560x1440 (the only resolution we switch within)

| GDI `dmDisplayFrequency` (flags 0 = RAWMODE) | DXGI `RefreshRate` (exact) | Hz | Notes |
|---|---|---|---|
| 280 | 279961/1000 | 279.961 | current + registry mode |
| 240 | 239901/1000 | 239.901 | the "239.90" mode. CDS "240" can only mean this timing |
| 144 | 143973/1000 | 143.973 | |
| 120 | 119998/1000 | 119.998 | not 119.88: no NTSC-fraction mode at 1440p |
| **100** | **10000/100** | **100.000** | exact integer, unlike all the others. Listed last in GDI index order (idx 471, after 280) |
| 59 **and** 60 | 59951/1000 | 59.951 | one timing, two GDI aliases. Also the EDID **preferred** mode (QDC `GET_TARGET_PREFERRED_MODE`) |

- `dmBitsPerPel` is always 32. The same six timings appear for DXGI formats R8G8B8A8, R10G10B10A2 and R16G16B16A16_FLOAT, all progressive with native scaling [measured].
- GDI returns 492 raw entries / 190 unique (w,h,Hz,bpp) across 24 resolutions. `EDS_RAWMODE` returned **the identical set** here. `EDS_ROTATEDMODE` only adds the portrait orientations [measured]. By the docs, RAWMODE drops the "compatible with current monitors" filter [doc [EnumDisplaySettingsEx][eds]]. It changes nothing on this setup, so the 100 Hz mode is a monitor-validated mode and not a raw adapter mode [inferred].
- For comparison, 1920x1080 (scaled) has both **60/1 and 59940/1000**, and both **120/1 and 119880/1000**. So DXGI does list NTSC-fraction twins when the timing exists. At 1440p the panel simply doesn't offer them [measured].

### Current mode and colour state (QDC + DisplayConfigGetDeviceInfo)

```
source \\.\DISPLAY1 (id 0) -> target 'MO27Q28G' (id 4353), DisplayPort (outTech 10), pathFlags 0x1
targetMode: active 2560x1440, total 2720x1653, vSync 279961/1000, pixelRate 1258750000
preferred mode: 2560x1440 @ 59951/1000
ADVANCED_COLOR_INFO_2: HDR supported+userEnabled, active, activeColorMode=HDR, bpc=10, RGB
DXGI_OUTPUT_DESC1: BitsPerColor=10, ColorSpace=12 (G2084_P2020), MaxLum 8000 (EDID placeholder)
SDR white level: 240 nits
ENUM_CURRENT_SETTINGS  = 2560x1440 @ 280, 32 bpp
ENUM_REGISTRY_SETTINGS = 2560x1440 @ 280, 32 bpp
```

## 2. The APIs compared

| API | Exact rational? | Sees 100 Hz? | Good for | Caveats |
|---|---|---|---|---|
| `EnumDisplaySettingsExW(dev, i, &dm, 0)` | No (integer) | Yes | Building the `DEVMODE` for `ChangeDisplaySettingsExW` | Rounds to nearest (239.901 → 240), but 59.951 shows up as 59 *and* 60. Mode list cached on `iModeNum = 0` [doc] |
| same + `EDS_RAWMODE` | No | Yes | Nothing extra here | Identical list on this PC. By the docs it can include modes the monitor can't show, so it is **riskier** [doc] |
| `ENUM_CURRENT_SETTINGS` | No | - | "What's live now" (integer) | During our session it shows the temp mode |
| `ENUM_REGISTRY_SETTINGS` | No | - | **The original to restore to.** It is exactly what `ChangeDisplaySettingsEx(name, NULL, …, 0)` re-applies [doc [CDS Ex][cdsex]] | Reliable as long as nobody writes the registry. `CDS_FULLSCREEN` doesn't. If the user changes Hz in Settings mid-session, restoring to the registry follows their new choice, which is correct |
| `QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS)` | **Yes** (current mode only) | Only if current | Confirming the applied rate after a switch. Getting target ids for HDR queries | Doesn't list available modes. Only the active one |
| DXGI `IDXGIOutput1::GetDisplayModeList1` | **Yes** | Yes | **Listing candidates with exact rates** | Needs a DXGI factory (cheap). Returns modes per format. Apply `DXGI_ENUM_MODES_SCALING` to include scaled modes. Filter to native size |
| `DisplayConfigGetDeviceInfo(GET_TARGET_PREFERRED_MODE)` | Yes | - | Knowing what "best mode" logic would pick (59.951 here) | Only for awareness |

**Which gives exact 239.90?** DXGI (`239901/1000`) for the list. After a switch, QDC's `targetVideoSignalInfo.vSyncFreq` would show the same value [inferred; confirm in the 01 §7 test]. GDI never does.

**Mapping GDI ↔ DXGI entries:** match on width, height and `round(num/den) == dmDisplayFrequency`. The 59/60 aliases are the exception: accept both. Kodi's and mpv's "(N+1)/1.001" heuristic for 23/59/119/143/239 is **wrong for this monitor** (143.973 is reported as 144, not 143) [src [mpv w32_common.c][mpvw32]; Kodi `WinSystemWin32.cpp`]. Don't reuse it.

## 3. What these modes mean for fps matching (derived from the measured rationals)

Drift is the error between mode/N and the video fps. "1 frame per" is how often a frame gets repeated or dropped with `video-sync=audio`. `display-resample` would hide this by retiming (a separate topic).

| Video fps | Best measured mode | Error | 1 frame per |
|---|---|---|---|
| 23.976 | 239.901 / 10 | +587 ppm | 71 s |
| 24.000 | 119.998 / 5 | −17 ppm | 2500 s |
| 25 / 50 | **100.000** / 4 or 2 | 0 | never |
| 29.970 | 59.951 / 2 | +183 ppm | 183 s |
| 30.000 | 119.998 / 4 | −17 ppm | 2000 s |
| 59.940 | 59.951 / 1 | +183 ppm | 91 s |
| 60.000 | 119.998 / 2 | −17 ppm | 1000 s |

Takeaways [inferred]:
1. **The panel has no true 1000/1001-family mode at 1440p.** 23.976 content never locks exactly. The candidates 240/144/120 are 587/812/983 ppm off, so 239.901 is the best.
2. **100 Hz is the only perfect mode (for 25/50 fps)**, so it has to be in the candidate list even though NVCP hides it.
3. **A lower multiple can beat a higher one** (24 fps: 120 Hz is better than 240 Hz). So the selector should rank by drift, not by the highest multiple.

## 4. Recommended enumeration routine (for the plan)

1. Resolve the player's HWND → `\\.\DISPLAYn` and the QDC target (see 01 §5).
2. `GetDisplayModeList1(DXGI_FORMAT_R8G8B8A8_UNORM, 0)` on the matching `IDXGIOutput` (match `DXGI_OUTPUT_DESC.DeviceName`). Keep only entries at the current width and height, progressive, with native scaling. That gives the candidate set of exact rationals.
3. For each candidate, check that a GDI `DEVMODE` exists with the same w/h, bpp 32 and `dmDisplayFrequency == round(rate)`. That is what gets passed to `ChangeDisplaySettingsExW(…, CDS_FULLSCREEN)`.
4. Record the original from `ENUM_REGISTRY_SETTINGS` plus the QDC rational. After the switch, re-read QDC and `ADVANCED_COLOR_INFO_2`, and abort/restore if the rate, HDR or bpc are not as expected.
5. Re-enumerate on every session start. Mode lists change after a driver update or hotplug [inferred].

## Open questions for the owner

1. Why does NVCP hide 100 Hz? It is the only exact-integer (10000/100) timing, which suggests a different EDID/DisplayID block or a driver-added mode. Worth confirming that the monitor OSD shows 100 Hz with HDR still on (during the 01 §7 test).
2. Is a 1–3 s blank acceptable at every start, including for 25/50 fps content that would move to 100 Hz?

[eds]: https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-enumdisplaysettingsexw
[cdsex]: https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-changedisplaysettingsexw
[mpvw32]: https://github.com/mpv-player/mpv/blob/master/video/out/w32_common.c

Other docs: [QueryDisplayConfig](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-querydisplayconfig), [IDXGIOutput1::GetDisplayModeList1](https://learn.microsoft.com/en-us/windows/win32/api/dxgi1_2/nf-dxgi1_2-idxgioutput1-getdisplaymodelist1), [DirectX with Advanced Color](https://learn.microsoft.com/en-us/windows/win32/direct3darticles/high-dynamic-range).
