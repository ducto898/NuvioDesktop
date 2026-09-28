# PROGRESS — refresh-rate matching fork

The only memory between phases. Read it at the start of every phase; update it at the end.

## Current state
- **Phase:** 2 (Measure only) **DONE 2026-09-28**. Verifier round 3/3: **PASS** on P2-0..P2-19 (P2-7/13/17 human parts
  recorded/accepted/waived by the owner). Upstream diff: 2 lines in `player_bridge.cpp`. Rebased on `fe92d414`, not pushed.
  Open: upstream `PluginRuntimeDesktopTest#desktop runtime handles concurrent scraper executions` is **flaky under load**
  (QuickJs NPE `this.closed`; failed 4 of ~10 full runs today, passes alone 3/3 and in 2 later full runs, unrelated to the
  patch) — **added to the known-failure baseline (owner approved 2026-09-28)**; baseline is now 8 entries.
- **Next (changed 2026-09-28, owner-approved, D11):** **Phase 2b — display-resample spike** BEFORE Phase 3, in a fresh
  session: read docs/PLAN.md "Phase 2b", write P2b acceptance criteria into SPEC.md, stop at the gate. Then Phase 3.
  2026-09-28: P2b criteria approved. **Spike result: cause found = NVIDIA global Max Frame Rate 200 fps.** With the owner's
  global FRL off (13:45): display-resample at 239.901, 1080p windowed + fullscreen + repeat: est. 239.898, jitter 0.00025,
  0 drops, 1 mistimed (at startup) per 120 s, 0 underruns. 4K HDR needs cheaper scalers (bilinear ⇒ same numbers; upstream
  spline36+deband ⇒ −0.8 %, 38 mistimed, GPU stuck at 210 MHz). Q10 answered: startup-exclusion rule added to P2b-13 (owner-approved); PresentMon clause deferred to Phase 5 (owner
  can't accept UAC remotely); owner resets the NVIDIA settings themselves via Cowork. **Spike PASS on mpv counters** (1080p
  win/fs/repeat + 4K HDR full quality with max-performance power: 0 drops+mistimed/min after 5 s). Next: one lean
  verifier round DONE (P2b-16 fixed after it) ⇒ **Phase 2b DONE 2026-09-28. Next: Phase 3** (write P3 criteria, stop at the gate). Phase 5 must plan the two driver settings
  (FRL off, Prefer max performance) as Nuvio-only or documented manual steps.
- **Phase 3 (2026-09-28):** P3-1..P3-25 criteria written into SPEC.md and **approved by the owner**. Q11–Q13 answered
  (see "Owner answers (Phase 3 gate)"); P3-22 rewritten for Q13. Next: tests (red commit) → logic (green commit). Carry into later phases: Phase 4 must handle monitor off/on dropping the mode (case H), move display queries off
  the mpv event thread, and switch before VO init (mpv misses external changes); Phase 5: display-resample works once the driver's
  Max Frame Rate is off (Phase 2b); it needs "Prefer maximum performance" for 4K HDR at full quality (D12); re-check the
  deferred PresentMon cadence clause with the owner present;
  Phase 7 soak clips by stream copy (D10); Phase 8 fix the WebView2 folder in the app identity work.
- **Start new sessions from `C:\Users\vicon\ClaudeProjects\NuvioRate\NuvioDesktop`** so the
  project's `.claude/settings.json` hook and `verifier` agent load.

## Repo facts (re-verified 2026-09-27)
- Upstream default branch is **`Dev`** (not `main`). Upstream HEAD at fork time: `083921cf`.
- Clone: `C:\Users\vicon\ClaudeProjects\NuvioRate\NuvioDesktop`
  - `origin` = ducto898/NuvioDesktop (**public** fork); `upstream` = NuvioMedia/NuvioDesktop
- Feature branch: `feature/refresh-rate-matching` from `upstream/Dev` @ `083921cf`. **Not pushed.**
- Submodules: only `MPVKit` is mapped. `libass-android`, `vendor/TorrServer` and `vendor/quickjs-kt`
  are unmapped gitlinks (upstream quirk); the Windows build doesn't need them (build is green).
- libmpv: Git LFS, `composeApp/src/desktopMain/native/windows/runtime/libmpv-2.dll` (115 MB): mpv v0.40.0-465-gf6c116491,
  libplacebo v7.357.0, FFmpeg N-121828 (measured from the DLL, 03-mpv-libmpv.md).
- 2026-09-28 Phase 2 start: rebased onto `upstream/Dev` `fe92d414` (7 UI/inset commits; `Main.kt` +3 lines around `App()`,
  `onCloseRequest` block (H8) unchanged at l.125). No conflicts.
- Native bridge: `buildWindowsPlayerBridge` compiles ONE source file (`player_bridge.cpp`) and only
  runs when the DLL is missing (`onlyIf { !exists }`). ⇒ Phase 4: new native `.cpp` files need either
  a hook in `composeApp/build.gradle.kts` (compile list) or `#include` from player_bridge.cpp.
  verify.ps1 -Fast handles staleness.
- Tests: `composeApp/src/desktopTest` (JUnit4 + kotlin.test), 1345 tests. Patch tests go in package
  `com.nuvio.app.features.player.desktop.refreshrate` (verify -Fast targets it).
  6 upstream tests fail deterministically on Windows → `scripts/known-upstream-test-failures.txt`.
- Sentry desktop is inert when `SENTRY_DESKTOP_DSN` is blank (`SentryInitializer.kt:55`).
- Updater gate: `AppFeaturePolicy.inAppUpdaterEnabled` (commonMain expect; desktop actual TBD).
- Data dirs: `DesktopStorage.kt` → `%APPDATA%\Nuvio`, `%LOCALAPPDATA%\Nuvio\Cache`. **Shared with
  the official app**, so dev runs go through `scripts/run-dev.ps1` (redirects APPDATA/LOCALAPPDATA to
  `NuvioRate\devprofile`). MSI identity: `composeApp/build.gradle.kts` ~l.1389 (`upgradeUuid`, `menuGroup`).
- Upstream `.gitignore` ignores `Docs` and `scripts/*` → our files there are `git add -f`'d.
- Build secrets: none needed to build. Keys (Supabase, TMDB, Trakt, Sentry) are optional, in
  `local.properties` (git-ignored; must exist). Owner test with blank keys + a copied profile: everything works
  (Supabase starts with no session, as expected; Trakt data loaded). ⇒ no keys needed.

## Toolchain
See FORK.md §3. JDK Temurin 17.0.20.1 portable (`%USERPROFILE%\.jdks`), VS 2026 / MSVC 14.51,
WebView2 NuGet 1.0.4078.44, Gradle 9.4.1. Timings: verify -Full ≈ 150 s; -Fast ≈ 35 s with the full
suite, ≈ 15 s with only the patch tests.

## Decisions
- D1: Work in a git clone of the fork, not the provided zip.
- D2: Portable JDK instead of the winget MSI (the MSI waited on UAC; the portable one needs no admin).
- D3: Known-upstream-failure baseline instead of editing or skipping upstream tests (owner may veto).
- D4: Gradle configuration cache off in scripts (upstream's bridge task is incompatible; CI does the same).
- D5: Fork tooling and docs go in their own commit(s), separate from the product patch commits, so
  the exported product patch stays minimal.
- D6: Dev runs use an isolated profile (`scripts/run-dev.ps1`) until the Phase 8 app-identity work. Corrected
  2026-09-28: isolation needs `WEBVIEW2_USER_DATA_FOLDER` too (the bridge's WebView2 folder ignores LOCALAPPDATA);
  proven 09:53 (0 official writes, overlay data in devprofile\Local\Nuvio\WebView2). Phase 8 identity work must fix
  `webViewUserDataDirectory()` properly.
- D7 (kill test, 2026-09-28): **rely on Windows' CDS_FULLSCREEN revert for crash/kill** (proven for exit, crash,
  TerminateProcess, End task, real JVM) + explicit restore on every normal path. **No watchdog, no next-launch marker.**
  Phase 4 must also handle a monitor power-cycle dropping the temporary mode (case H). Evidence: docs/research/09-kill-test.md.
- D9 (owner, 2026-09-28): **stick with mode switching; no VRR-based frame pacing.** Reasons: OLED VRR gamma flicker on
  pause/seek/controls (req. 9), VRR does not engage in Nuvio's player (measured windowed + fullscreen) and enabling it
  would need a global G-SYNC change, 23.976 is below the VRR floor (LFC), mpv has no VRR pacing mode.
- D10: soak runs (Phase 7) use long stream-copied clips, not mpv `loop-file` (its EOF→seek restart hitches ~1–2 frames).
- D11 (owner, 2026-09-28): insert **Phase 2b, a display-resample diagnosis spike, before Phase 3.** Reason: a fixed 240 Hz
  with upstream audio sync is NOT smooth (measured below), so the project's value depends on display-synced timing,
  which is currently broken in the embedded player. Find out if it's fixable before building Phases 3–4.
- D12 (owner, 2026-09-28): **no lower picture quality.** Keep upstream's scale/cscale=spline36 + deband when the feature is
  active (no bilinear/cheaper scalers). The 4K HDR render margin must come from GPU clocks instead: NVIDIA "Power management
  mode: Prefer maximum performance" (a driver setting ⇒ owner sets it, ideally Nuvio-only once Phase 8 gives the app its own
  exe); measure its extra watts. Q10(d) answered by this.
  Startup mistimes (1–3 per run, first ~1.2 s only): NOT shader compile (warm cache), NOT seeks, NOT audio (ao=null);
  remaining candidate = new-swapchain warm-up / composed→independent-flip promotion [inferred] (docs/research/10).
  Measured 14:42: max-performance mode ⇒ 4K HDR at full quality clean (239.898, 2 mistimed at startup, 0 delayed), 40.3 W vs 31.7 W.
- D8: `verify.ps1` runs Gradle with APPDATA/LOCALAPPDATA redirected to `NuvioRate\testprofile` and fails if the official
  `%APPDATA%\Nuvio` / `%LOCALAPPDATA%\Nuvio` changed (upstream desktopTests write through real storage).

## Incidents
- 2026-09-27 23:52: the first `gradlew :composeApp:run` used the OFFICIAL profile for ~1 min before
  being stopped. It wrote `nuvio_updater.properties` (content: `update_channel=all`),
  `nuvio_meta_screen_settings.properties` and `nuvio_continue_watching_enrichment.properties`.
  Watch progress was untouched. Backup taken right after: `NuvioRate\profile-backup-2026-09-27`.
- 2026-09-28 (found by verifier round 1): `verify.ps1` desktopTests rewrote the OFFICIAL
  `%APPDATA%\Nuvio\nuvio_continue_watching_enrichment.properties` (6785 → 59 bytes, header only) and wrote 18 files to
  the official `%LOCALAPPDATA%\Nuvio\Cache\gif-cache` (07:41–09:11). Fixed by D8. Enrichment file restored from the
  backup at 09:32 (owner decision, hash verified). gif-cache files: a cache, left as is.
- 2026-09-27/28 (found by verifier round 2): every dev/measure player run wrote the OFFICIAL
  `%LOCALAPPDATA%\Nuvio\WebView2\EBWebView` (player overlay's browser profile: Local/Session Storage, caches): 11 files
  on 09-27 night, 76 on 09-28. No backup of that folder exists. Fixed in run-dev.ps1 (D6 correction); measure.ps1 now
  checks both official folders. Owner to decide whether anything needs doing about the official WebView2 folder.

## Owner answers (2026-09-28)
- Q1 known-upstream-failure baseline (D3): **approved**.
- Q2 push: **keep local**. Do not push to origin until the owner says so.
- Q3 feature request: **hold** until Phase 2 measurements exist; re-ask then.
- Q4 git identity: repo-local `ducto898 <24544110+ducto898@users.noreply.github.com>` (GitHub no-reply).

## Owner answers (Phase 1 gate, 2026-09-28)
- Research recommendations R1–R14 (docs/research/07-summary.md): accepted via the answers below.
- Q5 G-SYNC mode — owner asked me to check. **Measured** (read-only NVAPI DRS probe, driver 616.92, global
  profile): `VRR_MODE` (0x1194F158) = 1 = **full screen only**; `VRRREQUESTSTATE` (0x1094F1F7) = 1 (full screen
  only); G-SYNC enabled. Value meanings from NVIDIA/nvapi `NvApiDriverSettings.h`. ⇒ the favourable case for R11;
  still to be confirmed per present mode in Phase 2 (app fullscreen is borderless, not exclusive).
- Q6 a short black screen when switching: **fine**.
- Q7 PresentMon (portable, `NuvioRate\tools`): **OK**.
- Q8 highest multiple: **yes, if no downside** (smoother UI). Known potential downside: GPU power at 240 presents/s
  under display-resample (esp. with RTX VSR) ⇒ Phase 2 measures GPU power at 240 vs 120 Hz; revisit only if material.

## Owner answers (Phase 2 gate, 2026-09-28)
- Phase 2 criteria P2-0..P2-19 **approved**, incl. P2-5 (measure-only in-app switch knob) and P2-12 (kill-test tools committed).
- `HomeHeroSectionTest` (upstream 660→640 dp mismatch) added to the known-upstream-failure baseline: **approved**.
- PresentMon portable download into `NuvioRate\tools` + UAC prompt: **approved**.
- (later, 2026-09-28) Restore the official enrichment file from backup: **yes** (done). P2-4 rewording: **approved**.
  Extra ~100k tokens for Phase 2: **OK**. Mode switching, not VRR pacing (D9): **decided**.
- Official `%LOCALAPPDATA%\Nuvio\WebView2` written by dev runs: **leave it** (no restore).
- Extra measure-only knobs `NUVIO_RR_MEASURE_DIR` and `NUVIO_RR_MEASURE_IPC` (mpv IPC pipe, only in measure runs): **approved**.
- P2-13 per-case blank notes: owner's collective note (≈ 1 s per switch, no brightness pop) **accepted**. P2-17 OSD reading
  while paused: impossible on this monitor → **waived**, PresentMon grid evidence (resume lands on the 280 Hz grid) accepted.

## Owner answers (Phase 3 gate, 2026-09-28)
- P3-1..P3-25: **approved**.
- Q11 no container fps at file open (some HLS): **don't switch** (no mid-playback switch from `estimated-vf-fps`).
- Q12 switched session + next video with no suitable rate: **restore 280** (P3-19).
- Q13 mode dropped by a monitor off/on (case H): **re-switch to the target** (once per playback; P3-22).

## Open questions for owner
- Q10 (Phase 2b, 2026-09-28): (a) YES (rule added). (b) deferred to Phase 5 (no remote UAC). (c) Max Frame Rate: keep global off, or restore 200 and
  plan a Nuvio-only driver profile (needs Phase 8 app identity/own exe; profile creation by Claude was blocked by the
  permission classifier)? (d) answered by D12.
- Q9 (answered 2026-09-28: owner turned the GLOBAL Max Frame Rate off themselves; backup of all driver profiles in
  NuvioRate\drs-backup-2026-09-28.nip). Original: approve ONE driver-profile experiment: a new NVIDIA application profile for `java.exe` with
  Max Frame Rate = Off (the global 200 cap untouched), re-run S1 at 239.901 windowed + fullscreen, then delete the profile?
  (Alternative: the owner turns global Max Frame Rate off in the NVIDIA App for the test and back on after.)
  If it still fails ⇒ choose (a) switch + audio sync only, (b) deeper fix (libmpv render API), (c) stop.

## Effort / token budget (rough)
| Phase | Estimate | Actual |
|---|---|---|
| 0 Setup | ~300k | ~260k (main ≈140k + existing-work subagent ≈123k) |
| 1 Research | ~600k (subagents) | ~1.09M (5 subagents ≈ 980k: API 183k, mpv 181k, prior art 267k, VRR 150k, codebase 198k; main ≈ 110k) — ~80% over |
| 2 Measure | ~400k (incl. kill test A–J) | ~800k (main ≈ 355k + 3 verifier rounds ≈ 445k: 154k, 178k, 114k) — 2× over; the verifier rounds found real issues (2 official-profile leaks) but were the main overrun. For later phases: give the verifier a tighter evidence list and one criterion table to cut its cost |
| 2b Resample spike | ~150–250k | ≈ 330k (criteria 45k + 33 runs/diagnosis ≈ 165k + verifier 117k) — ~30 % over the top estimate; the verifier again cost the most |
| 3 Logic (TDD) | ~250k | |
| 4 Native switching | ~500k | |
| 5 mpv timing / OLED | ~400k | |
| 6 Settings/JNI | ~200k | |
| 7 Matrix + review | ~500k | |
| 8 Upkeep | ~250k | |

## Measurements
- 2026-09-28 (read-only enumeration, 02-mode-enumeration.md): 2560x1440 modes 279.961 (current+registry),
  239.901, 143.973, 119.998, 100.000, 59.951 Hz; HDR on, 10 bpc. No 1000/1001 timings.
- **Phase 2 baseline, 2026-09-28** (feature absent, upstream audio sync, windowed/maximized 2576x1408, desktop 279.961 Hz,
  HDR on; `measurements/*-baseline-r{1,2,3}`, 120 s each; P2-10 `measure.ps1 -Compare` PASS for all three):

  | Clip (SDR 1080p) | runs | display-fps | drops / dec-drops / delayed | Windows Hz before/during/after |
  |---|---|---|---|---|
  | 23.976 | 3 | 279.961 (all) | 0 / 0 / 0 | 279.961 / 279.961 / 279.961 |
  | 25     | 3 | 279.961 | 0 / 0 / 0 | same |
  | 59.94  | 3 | 279.961 | 0 / 0 / 0 | same |

  mpv's counters stay 0 in audio sync even though motion judders; the judder is only visible in PresentMon cadence:
  23.976 @ 279.961 (windowed, `*pm-windowed`): frames held 11 vsyncs ×463, 12 ×782, 13 ×50, 10 ×8, 14 ×3 (ideal 11.68 avg).
  ⇒ Phase 5 "after" target: 23.976 @ 239.901 = constant 10 vsyncs/frame.
- **Present mode / VRR (P2-17, windowed):** mpv swapchain (java.exe) = `Hardware Composed: Independent Flip` (MPO overlay
  plane, despite the WebView2 overlay), WebView/DWM = `Hardware: Legacy Flip`. Every `MsBetweenDisplayChange` is an integer
  multiple of 3.5719 ms (±0.13 ms) **while playing** ⇒ **VRR not engaged during playback, panel fixed at 279.961 Hz**.
  Correction (verifier): in that run the pause/seek keys never reached the player, so pause, seek and controls are
  NOT yet proven. Actions now go through mpv IPC and fullscreen through a foreground fix (tested OK 09:28, no PresentMon).
  Fullscreen: NOT yet measured with PresentMon.
- **mpv does not re-detect display-fps** after an external switch in the embedded window (stayed 279.961 for 40 s at 239.901).
  When the mode is set BEFORE the player starts, mpv reads it correctly (239.901 / 119.998).
- **display-resample is broken in the embedded player (new, blocks R8 as written):** at 280, 240 and 120 Hz, 1080p and
  2160p alike: `estimated-display-fps` ≈ 6 Hz, `vsync-jitter` ≈ 47–55, ~1 drop + ~1 mistimed per video frame
  (≈ 2700 each per 120 s), 12–47 audio underruns + mpv's "A/V desync" warning. GPU nearly idle (465 MHz). Cause unknown
  (candidate: present/vsync feedback of the embedded MPO swapchain). ⇒ Phase 5 must diagnose (PresentMon in resample mode)
  or fall back to audio sync at the N×fps rate.
- **GPU power (P2-19, hdr-2160p-23.976, 120 s, median nvidia-smi):** audio sync @280 = 15.3 W (210 MHz); display-resample
  @240 = 18.4 W (465 MHz); display-resample @120 = 12.2 W (345 MHz). Small either way; but the resample numbers come from the
  broken mode above (it presented far fewer frames than intended), so re-measure in Phase 5 once resample works.
- **Fullscreen present mode (P2-17, 09:33, `*pm-fullscreen-auto`):** java.exe `Hardware Composed: Independent Flip`, fs=yes;
  all 1486 intervals on the 3.5719 ms grid (max err 0.195 ms), incl. the 20 s pause (5595 vsyncs exactly), ±10 s seeks and
  controls ⇒ **VRR not engaged in fullscreen either**; no Phase 5 VRR mitigation needed. Monitor OSD can't show Hz.
- **display-resample re-measured (09:36 with PresentMon, 09:37 without):** est. display fps ≈ 191 (not ≈ 6 as 08:55–09:03).
  **The difference is the window mode:** all ≈ 6 Hz runs were windowed (fs=no), both ≈ 191 Hz runs fullscreen (fs=yes; the
  app had restored fullscreen from the previous run — measure.ps1 now forces the requested mode). Fullscreen: jitter 0.8, drops ≈ 80/min, mistimed ≈ 650/min; mpv presents ~191/s at 280 Hz,
  `MsInPresentAPI` median 4.8 ms (> 1 vsync). ⇒ still unusable as-is; Phase 5 diagnoses first.
- **Loop (P2-7):** clip content seamless (measured: edge x 38→19→(0≡304) at N-1→0, 19 px/frame); owner saw a jump —
  explained by mpv `loop-file` (EOF, seek to 0, restart: ~50–90 ms gap). See D10.
- **Fixed 240 Hz + audio sync (owner question "just set 240?", 10:31, `*fixed240`):** switcher held 239.901, clip
  sdr-1080p-23.976, windowed, 90 s, PresentMon. mpv display-fps 239.901, 0 drops/delayed. Of 1964 frames: **10 refreshes
  ×1476 (75 %), 11 ×250, 9 ×238** — the non-10 frames come as 11/9 pairs in **bursts starting every ~5–7 s and lasting
  3–11 s** (14 bursts, ~60 of 80 s inside a burst). Cause [inferred]: audio-clock timing noise (~±1 ms) against a 4.17 ms
  refresh while the 0.06 % rate mismatch drifts the frame phase across a refresh boundary. ⇒ A fixed 240 Hz alone is not
  smooth; it trades the constant 11/12 wobble at 280 for bursts of ±4 ms 9/11 hitches. Display-synced timing is needed
  for a clean 10/10 cadence (→ D11). All intervals on the 4.168 ms grid (max err 0.21 ms): fixed refresh, no VRR.
- **Owner slow-motion videos (iPhone Slo-mo 240 fps, 1080p; `G:\My Drive\IMG_3035.MOV` = 280 Hz, run `*slomo-280`;
  `IMG_3036.MOV` = fixed 240 Hz, run `*slomo-240`; both fullscreen, sdr-1080p-23.976, audio sync).** Analysis
  (scratchpad `slomo/analyze.py`: 1-px strip through the stripes, pattern phase tracking, transition time = midpoint
  crossing on real camera timestamps; the phone skips ~1 in 8 frames as 12.5 ms gaps; timing precision ≈ ±1 ms):
  280 Hz: 705 holds, mean 41.71 ms (ideal 41.71), std 2.69 ms; refreshes 11 ×225, 12 ×341, 13 ×78, 10 ×41, 14 ×8.
  240 Hz: 424 holds, mean 41.71 ms, std 2.65 ms; refreshes 10 ×247 (58 %), 9 ×81, 11 ×82, 8 ×7, 12 ×6.
  Confirms PresentMon (camera noise spreads counts). Exact PresentMon spread: 280 windowed std 2.03 ms / mean |dev|
  1.79 ms; fixed 240 std 2.08 ms / mean |dev| 1.05 ms ⇒ same total unevenness, different shape (constant fine wobble vs
  mostly-perfect with ±4.2 ms bursts). Owner also reports edges look smeared by eye: sample-and-hold blur, 640 px/s × 41.7 ms
  ≈ 27 px, inherent to 24 fps (not changed by refresh rate). Reference "before" for Phase 2b.
- **Phase 2b spike (2026-09-28, docs/research/10):** correction to the Phase 2 display-resample notes: the ≈ 6 Hz vs
  ≈ 191 Hz split was **not** windowed vs fullscreen but **whether a PresentMon (ETW) trace was consuming**. With no
  trace, display-resample collapses (est. ≈ 6.5, `video-flip` median 235 ms) windowed and fullscreen, at 240 and 120 Hz,
  overlay hidden or not. `d3d11-sync-interval=0` or an active trace give ≈ 200 presents/s, flips exactly every 5.000 ms,
  ≈ 450 mistimed/min at 240 Hz. NVIDIA global DRS: `FRL_FPS` (Max Frame Rate) = 200, user-set; VRR_MODE 1; VRR_APP_OVERRIDE 1;
  VSYNCMODE application-controlled; no java.exe profile (`scripts/rr-tools/drsprobe.cpp`, read-only). PresentMon runs
  are therefore NOT representative for display-sync modes; use mpv counters + `dump-stats`. PresentMon 2.6 `--date_time`
  is +7 h off on this PC (measure.ps1 `-PresentMonCsv` calibrates it). A `d3d11-flip=no` run stalled PresentMon's output.
- Kill test A–J: see docs/research/09-kill-test.md (CDS_FULLSCREEN reverts on every death path; monitor off/on drops the
  temporary mode; "240" = 239.901; blank ≈ 1 s per switch).
- Audio note: mpv outputs 96 kHz 7.1 float to the current default device (Arctis base: `Remix: stereo -> 7.1`).
- SDR clips are rendered to a PQ/BT.2020 swapchain (`RGB_FULL_G2084_NONE_P2020`) because Windows HDR is on.

## Log
- 2026-09-27 Phase 0: cloned the fork, added upstream, created the branch. Existing-work search found
  nothing done or planned (docs/research/00-existing-work.md); NuvioTV (Android TV) has AFR by
  tapframe = positive precedent. Drafted docs/feature-request.md (not posted). Toolchain installed.
  The unmodified build and tests are green (6 known upstream failures). verify.ps1 proven: green on
  the clean fork, red on an induced failing test (exit 1) and an induced compile error (exit 1);
  a native timestamp change forces a bridge rebuild. The hook no-ops on non-source files. Dev build
  launched with an isolated, copied profile.
- 2026-09-28 P0-4 PASS [HUMAN]: owner played a video in the dev build — picture, sound, seek and fullscreen all OK.
- 2026-09-28 Phase 1: 5 parallel research subagents → docs/research/01–06, summary 07. Key results: CDS_FULLSCREEN
  (auto-revert on kill ~80% likely, to be proven); exact rates measured; mpv in `wid` likely misses
  WM_DISPLAYCHANGE ⇒ switch in on_preloaded + display-fps-override; each episode = new native player ⇒
  session state must be process-global; bridge has no native logging. Upstream re-checked: nothing new.
- 2026-09-28 Phase 1 gate: owner answered Q5–Q8; G-SYNC mode measured read-only via NVAPI (full screen only).
  Overall plan written in plan mode and approved (docs/PLAN.md): Kotlin decision logic + native Win32/mpv glue,
  switch in on_preloaded on a worker, process-global session, kill test moved into Phase 2, ≤50-line upstream diff.
- 2026-09-28 Phase 2: rebased onto fe92d414 (1 new upstream test failure, baseline-listed with approval). Built the
  measure-only sampler (2 upstream lines), 32 test clips, measure.ps1, rr-tools. Kill test A–J with the owner → D7.
  Baselines repeatable (P2-10 PASS). Findings: mpv misses external mode changes; display-resample broken in the embedded
  player (est. display fps ≈ 6); VRR not engaged windowed; monitor off/on drops the temporary mode. Verifier round 1
  found verify.ps1 tests writing the OFFICIAL profile (one cache file) → fixed (D8); other findings fixed as listed above.
- 2026-09-28 Phase 2b: criteria P2b-1..16 approved. Knobs OPTS/HIDE_OVERLAY + measure.ps1 -Opts/-HideOverlay/-Cadence/
  -PresentMonCsv built (upstream diff still 2 lines). 21 runs: overlay ruled out (S4), render cost ruled out, sync modes
  and swapchain options don't fix it; found the driver's 200 fps limiter. Q9: owner turned FRL off ⇒ display-resample works
  (1080p clean; 4K HDR clean with bilinear scalers). Stopped for Q10.
- 2026-09-28 Phase 2b closed: max-performance power ⇒ 4K HDR full quality clean (D12); startup mistimes isolated (not shaders,
  seeks or audio); P2b-13 startup rule added (owner-approved); lean verifier round: all criteria PASS except P2b-16 (PLAN.md
  Phase 5 line not updated) ⇒ fixed; drsfrl.cpp (driver-profile writer; only its `backup` ran, `create` was blocked) removed
  as scope creep. Driver-profile backup kept at NuvioRate\drs-backup-2026-09-28.nip. Owner's global FRL 0 + PSTATE 1 still set
  (Q10c open; owner changes them via Cowork).
