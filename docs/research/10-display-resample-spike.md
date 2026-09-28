# 10 — Display-resample diagnosis spike (Phase 2b, 2026-09-28)

Question (D11): can `video-sync=display-resample` be made to work in Nuvio's embedded mpv player, and how?
Status: **cause found — the NVIDIA global Max Frame Rate (200 fps). With it off, display-resample works** (see
"FRL off" below). PASS as written is blocked only by startup mistimes and the missing PresentMon cadence run (owner decisions).
Legend: **[measured]** = run data on this PC; **[inferred]** = my reading of the data, not proven.

## Setup
- Clip `sdr-1080p-23.976`, 120 s per run (dump-stats runs 40 s), display-resample unless noted.
- Mode held by `switcher.exe cds 240|120 hang` (CDS_FULLSCREEN) before the app starts; desktop verified back at
  279.961 Hz after every run.
- Knobs (P2b-1/2): `NUVIO_RR_MEASURE_OPTS` (mpv options before VO init; the log shows every `opt k=v rc=0`
  before `Initializing GPU context`), `NUVIO_RR_MEASURE_HIDE_OVERLAY`.
- Frame-timing sources: mpv counters (sampler), mpv `dump-stats` (per-call `video-draw`/`video-flip` timings,
  no ETW), PresentMon (ETW). Folders: `measurements/20260928-1104*` … `20260928-1328*`.

## Results

| Time | Case | Hz | Window | Options | est. display fps | jitter | drops + mistimed /min | audio underruns | PresentMon |
|---|---|---|---|---|---|---|---|---|---|
| 11:04 | S1 | 239.901 | win | — | 198.3 | 0.48 | 455 | 0 | consuming |
| 11:07 | S1 repeat | 239.901 | win | — | 198.6 | 0.48 | 458 | 0 | consuming |
| 11:10 | S1 | 239.901 | fs | — | 197.9 | 0.52 | 453 | 0 | consuming |
| 11:13 | S3 | 239.901 | win | swapchain-depth=1 | 193.9 | 0.54 | 485 | 0 | consuming |
| 11:16 | S3 | 239.901 | win | d3d11-flip=no | 31.2 | 6.7 | 2486 | 0 | stalled from here |
| 11:18 | diag | 239.901 | win | hwdec=no | 6.6 | 44.5 | 2795 | 0 | stalled |
| 11:21 | diag | 239.901 | win | vo=gpu | 6.4 | 45.4 | 2795 | 42 | stalled |
| 11:24 | S1 again | 239.901 | win | — | 6.5 | 45.0 | 2796 | 38 | stalled |
| 11:27 | S4 | 239.901 | win | overlay hidden | 6.5 | 45.2 | 2797 | 50 | stalled |
| 11:30 | S1 again | 239.901 | fs | — | 6.7 | 44.3 | 2795 | 57 | stalled |
| 13:04 | S5 | 239.901 | win | video-sync=display-desync | 6.6 | 45.1 | 0 (not counted in desync) | 45 | **none** |
| 13:06 | S5 | 239.901 | win | video-sync=display-vdrop | 6.4 | 45.5 | 2795 | 38 | none |
| 13:09 | S3 | 239.901 | win | video-timing-offset=0 | 6.4 | 45.7 | 2796 | 50 | none |
| 13:12 | S3 | 239.901 | win | d3d11-sync-interval=0 | 197.7 | 0.36 | 448 | 0 | none |
| 13:14 | diag | 239.901 | win | dump-stats | 6.4 | 45.6 | 2800 | 13 | none |
| 13:16 | diag | 239.901 | win | sync-interval=0 + dump-stats | 195.2 | 0.47 | 462 | 0 | none |
| 13:17 | diag | 239.901 | win | sync-interval=0, hwdec=no | **200.00** | **0.01** | 184 | 0 | none |
| 13:20 | FRL test | 119.998 | win | — | 6.4 | 22.5 | 2663 | 130 | none |
| 13:23 | FRL test | 119.998 | fs | — | 6.5 | 22.3 | 2717 | 108 | none |
| 13:26 | FRL test | 119.998 | win | sync-interval=0 | 199.4 | 0.40 | 1319 | 0 | none |
| 13:28 | FRL test | 119.998 | fs | sync-interval=0 | 199.3 | 0.41 | 1316 | 0 | none |

Not run yet: S2 (render cost). Its premise is already ruled out: PresentMon shows 0.43 ms of GPU work per present
and `video-draw` takes 0.23 ms median [measured]. `display-resample-vdrop` wasn't run either (it shares the collapse).

## What the data shows
1. **Two states, both failing.**
   (a) "Collapsed": est. display fps ≈ 6.5, vsync-jitter ≈ 45, about 22 of 24 frames/s dropped, audio underruns.
   `dump-stats`: `video-flip` (mpv's Present call) median **235 ms**, p90 259 ms, draw 0.26 ms [measured].
   (b) "Capped": est. ≈ 198–200, jitter ≈ 0.4, but about 450 mistimed/min at 240 Hz. `video-flip` median **4.75 ms**,
   flips exactly every **5.000 ms** (p10–p90: 4.99–5.01 ms), i.e. 200 presents/s [measured].
2. **The collapse is the normal state for users** (no PresentMon: 13:04–13:09, 13:14, 13:20–13:23), in windowed AND
   fullscreen, at 240 AND 120 Hz, with or without the overlay, with hwdec, vo=gpu or bitblt presentation. The Phase 2
   "windowed 6 Hz vs fullscreen 191 Hz" split was a coincidence: the fullscreen runs happened to have a PresentMon
   session active [measured: the identical default runs gave 198 while PresentMon was consuming, 6.5 after it stalled].
3. **The collapse goes away with `d3d11-sync-interval=0`** (Present doesn't wait for vsync) or with a consuming
   PresentMon trace [measured]. In both cases mpv then presents at 200/s whatever the refresh rate, **even at 120 Hz**
   (13:26/13:28). So interval 0 is not a fix: mpv loses its vsync pacing and runs to the cap.
4. **The 200/s cap is the NVIDIA driver's frame limiter.** Read-only probe (`scripts/rr-tools/drsprobe.cpp`, NVAPI DRS
   read calls only): global profile `FRL_FPS` (0x10835002, "Max Frame Rate") = **200**, user-set (not predefined).
   No `java.exe`/`javaw.exe` application profile. Also global: VRR_MODE = 1 (full screen only),
   VRR_APP_OVERRIDE = 1, VSYNCMODE = 0x60925292 (application-controlled) [measured].
   Flip intervals of exactly 5.000 ms with ~4.75 ms spent inside Present are the signature of a limiter holding
   Present [inferred, strong].
5. **Why vsync-waiting Presents block 235 ms is not proven.** Audio sync uses the same Present(1) at 24/s with
   0.12 ms per call and correct vsync cadence (Phase 2), so the swapchain itself is fine at low present rates. The
   collapse starts when mpv presents every refresh. Most likely cause: the driver limiter (FRL) interacting badly
   with a vsync-waiting flip queue [inferred; FRL is the only rate limiter found, and it is also the only thing
   that explains the 200/s ceiling]. Against it: at 120 Hz, 120 presents/s is under 200 and it still collapses.
   So either the limiter misbehaves below its cap with vsync on, or there is a second cause.

## Status of the P2b pass criteria
- P2b-13 (spike PASS): **not met** by any option set tried. The best states have ≈ 450 mistimed/min at 240 Hz.
- P2b-1..5: done (knobs, measure.ps1 `-Opts`/`-HideOverlay`/`-Cadence`/`-PresentMonCsv`; the cadence analysis
  reproduces the Phase 2 fixed-240 result exactly: 10 ×1476 / 11 ×250 / 9 ×238, std 2.08 ms).
- P2b-7 (S1), P2b-9 (S3), P2b-10 (S4), P2b-11 (S5 except resample-vdrop): run. P2b-8 (S2) skipped as ruled out (above).
- PresentMon caveat: ETW tracing changes mpv's behaviour here, so PresentMon runs are not representative for
  display-sync modes. Use mpv's counters and `dump-stats` as the primary evidence.

## The next experiment needs owner approval (driver setting)
One experiment would confirm or kill the FRL hypothesis: **Max Frame Rate = Off for the dev build only**, i.e. a
new NVIDIA application profile for `java.exe` with `FRL_FPS` = 0 (the global 200 cap stays for games). Then
re-run S1 at 239.901, windowed and fullscreen, no PresentMon. Undo = delete that profile.
- If it passes: Phase 5 needs an app-profile step for the fork (or a documented manual setting). A shipped
  `java.exe` profile also affects other Java apps. Phase 8's app identity (own exe name) would allow a Nuvio-only profile.
- If it still collapses: the cause is elsewhere (DWM/MPO flip handling of the embedded child swapchain) ⇒ owner
  options (a) switch + audio sync only, (b) deeper fix (e.g. libmpv render API), (c) stop.
The owner could instead toggle the global Max Frame Rate off for the test themselves (NVIDIA App → Graphics →
Global → Max Frame Rate), and back on after.

## FRL off (owner turned global Max Frame Rate off, 2026-09-28 ~13:45; confirmed FRL_FPS = 0 with drsprobe)
No PresentMon, 120 s each, 239.901 Hz, display-resample [measured]:

| Time | Clip | Window | Options | est. display fps | jitter | drops | mistimed | delayed | underruns | GPU |
|---|---|---|---|---|---|---|---|---|---|---|
| 13:46 | sdr-1080p-23.976 | win | — | 239.898 | 0.00025 | 0 | 1 | 1 | 0 | |
| 13:48 | sdr-1080p-23.976 | fs | — | 239.898 | 0.00025 | 0 | 1 | 4 | 0 | |
| 13:51 | sdr-1080p-23.976 | win | — (repeat) | 239.898 | 0.00025 | 0 | 1 | 1 | 0 | |
| 13:54 | hdr-2160p-23.976 | fs | — | 237.99 | 0.10 | 0 | 38 | 184 | 0 | 31.7 W, 210 MHz P3, 29 % |
| 13:57 | hdr-2160p-23.976 | fs | deband=no | 239.42 | 0.045 | 0 | 3 | 46 | 0 | 29.8 W, 210 MHz, 26 % |
| 13:59 | hdr-2160p-23.976 | fs | scale/dscale/cscale=bilinear | 239.898 | 0.00026 | 0 | 3 | 5 | 0 | 30.6 W, 210 MHz, 22.5 % |
| 14:03 | sdr-1080p-23.976 | win | — (PresentMon UAC declined) | 239.77 | 0.00002 | 0 | 3 | 3 | 0 | |

- **Every mistimed frame in these runs falls in the first 1.2 s of playback**; from then to the end: 0 mistimed, 0 drops
  (timeline from the 1 Hz samples). Delayed frames: a handful, spread out, mostly near close.
- 4K HDR with upstream's spline36 + deband: the GPU stays in P3 at 210 MHz and misses about 0.3 frames/s; cheaper
  scalers fix it. So S2 matters at 4K HDR, but as a render-cost margin at a low-power clock, not as the root cause.
  Upstream sets only `scale`/`cscale=spline36` + `deband=yes`; the 4K→1440p step uses `dscale` (not set by upstream).
  Which single option is enough (e.g. only `dscale`) is not yet tested.
- ⇒ The collapse and the 200/s cap were both the driver limiter [measured: same build, same options, FRL 200 → est. 6.5 Hz;
  FRL off → 239.898]. Why FRL + vsync collapses even at 120 Hz (below the cap) stays unexplained [open].

### P2b-13 against the data
- est. display fps within 0.1 % of 239.901: **PASS** 1080p win/fs + repeat; 4K HDR with bilinear PASS, default FAIL (−0.8 %).
- drops + mistimed ≤ 1/min over 120 s: 1080p **PASS** (0.5/min); 4K bilinear 1.5/min **FAIL as written**, 0/min after the
  first 5 s (all 3 at startup).
- 0 audio underruns after 5 s: **PASS** everywhere.
- PresentMon ≥ 99 % of frames at 10 refreshes: **not measured** (UAC declined; also PresentMon perturbs display-sync, see above).

## Full quality via GPU power mode (D12; owner set global "Prefer maximum performance", PREFERRED_PSTATE = 1, 14:42)
hdr-2160p-23.976, fullscreen, 239.901, upstream options (spline36 + deband), FRL off, no PresentMon [measured]:
est. display fps 239.898, jitter 0.00024, 0 drops, 2 mistimed (both at 1.08 s), **0 delayed**, 0 underruns;
GPU P0 2490 MHz in all 120 samples, **40.3 W** median (vs 31.7 W and 38 mistimed / 184 delayed in Normal mode) ⇒ +8.6 W.
A global setting keeps the GPU at high clocks all the time (idle desktop too) [inferred from the P0 samples]; the right scope is a Nuvio-only profile (Phase 8 identity).
