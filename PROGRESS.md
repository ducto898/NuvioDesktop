# PROGRESS — refresh-rate matching fork

The only memory between phases. Read it at the start of every phase; update it at the end.

## Current state
- **2026-10-01: Phase 9 DONE.** Batches 1-6 done (partials listed in docs/phase9-plan.md), FORK.md section 11,
  verify -Full green (1612 tests, 1 known flaky), zip ..\dist\Nuvio-RR-1.1.26-f70deb6d.zip (HDR pass through default; 75c6887b had the HDR choice, packaged run: EDID read, target-peak=1532, 0/0; fd9c57ad passed the packaged smoke run).
  Open for the owner: try one torrent (P2P) stream in Nuvio RR (TorrServer IS bundled; the "missing" note was wrong, see Log).
  The OpenGL white window had cleared by 17:08 without a reboot (see Log).
- **2026-10-01: Phase 9 started** (owner: all audit fixes in the fork only, no upstream PRs; skip R7/E10). Plan and per-item status: docs/phase9-plan.md. Upstream files changed by fixes are listed in scripts/fork-fixes.txt.
- **2026-10-01: Phase 8 DONE, project done** (owner checklist accepted; see Log). Open: Q47, 250 Hz current-mode idea (Q48 closed: no upstream contributions).
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
- **Phase 3 DONE 2026-09-28.** P3-1..P3-25 approved (Q11–Q13, P3-22 rewritten for Q13). Tests-first: commit A `6d9c6de8`
  (tests + TODO stubs, red 47/48), commit B `65d306cf` (logic, green, test diff A→B empty), then a post-verifier fix
  commit. One lean verifier round: **PASS all 25**, 6 non-blocking notes: 4 fixed tests-first (tie-break test,
  ≤1e-6 accept test, `retarget` reason code, starts queued behind app exit are dropped — added to P3-20), 2 carried into
  Phase 4 below. verify -Full green (1397 tests, only baseline failures), upstream diff still 2 lines, not pushed.
  Mutation check: app exit without restore ⇒ the fuzz test fails. **Next: Phase 4** (write P4 criteria, stop at the gate).
- **Phase 4 DONE 2026-09-28 (owner accepted 18:15).** Next: **Phase 5** (write P5 criteria, stop at the gate).
- **Phase 5 criteria written 2026-09-28 (SPEC P5-1..P5-19); owner answered Q20–Q22 ⇒ taken as approval.**
  Code + automated checks DONE (19:30): commits A `fb812615` (tests red 22/129), B `26f6c4db` (green), C `6bc9059f`
  (native), `fe14ec08` (measure.ps1), fixes `34e64647` (app exit stops the health watch) and `1331ad4d` (health judges
  only a steady display at the target; found by the P5-8 run), each test-first. verify -Full green (1479 tests, 7 known
  failures), upstream diff still 6 lines, not pushed. 20 measure runs (`measurements/phase5-evidence.txt`): P5-5, P5-8,
  P5-11, P5-12, P5-14 PASS. Lean verifier round (≈115k): **PASS** on every automatic criterion; flag 1 (1 Hz stats read
  called mpv under the entry mutex that H5 takes on the UI thread ⇒ deadlock risk) fixed in `75ec3f57` (counters cached
  by H4 on the mpv event thread; re-measured OK), verify -Full green again. Known limits, not fixed: NVAPI calls are
  guarded by try/catch only (an access violation inside nvapi64.dll would not be caught; struct layouts match
  drsprobe.cpp); under the `timing-set` fault knob a later revert also reports ok=0 (knob only).
  **Owner session 19:45–20:05:** PresentMon runs + checklist "all OK" (owner). P5-15 PASS, P5-16 PASS after the follow-up (see
  Measurements: fullscreen pause on the grid; residual jitter ≤ 0.36 ms p99.9, larger only at seeks), P5-13 PASS from the log, P5-17
  owner-attested; monitor off/on not in the log ⇒ Phase 7 matrix. **Phase 5 DONE 2026-09-28 (P5-16 closed by the
  fullscreen-pause run 20:14). Next: Phase 6** (write P6 criteria, stop at the gate).
  History: code + automated checks, then the owner's [HUMAN] checklist P4-21/P4-22 and Q17–Q19. Commits: A `38244812` (tests red 40/91), B `41605034` (green), C `5a0e80ef` (native + 4 hooks),
  `88939e67` (tooling/docs), `858b9620` (verifier follow-ups). Upstream diff: **6 lines in 3 files** (PB H1/H2/H4/H5,
  PED H6, Main.kt H8). verify -Full green (1437 tests, 7 known failures). Not pushed. Lean verifier round 1: all criteria
  PASS except P4-5/P4-10 (environment: no 100 Hz; VFR header) and P4-12 (H5-mid-settle not reachable by a window close),
  plus problem 1 (query failures unlogged, unknown HDR read as SDR) ⇒ fixed in `858b9620` and re-measured.
  Known limits (verifier, documented, not fixed): H6 `onScreenGone()` restores whichever player owns the session (the
  host effect spans episodes, F3, so it fires only when the screen is left); a window close during a settle times out
  the 2 s wait and the restore then relies on the JVM shutdown hook or Windows' revert (D7).
- **Phase 6 DONE 2026-09-29** (owner approved the P6-12 checklist; details below). **Next: Phase 7** — write P7 criteria for
  the 240 Hz default (Q27) incl. the Q28 `rate-off` data collection, stop at the gate.
- **Phase 7 criteria written 2026-09-29 (SPEC P7-1..P7-20), **approved by the owner 2026-09-29, Q30–Q35 as
  recommended** (Q30 a: mode cap; Q31 rule; Q32 4 soaks + one 60 min; Q33 one owner session; Q34 PresentMon yes; Q35 review
  replaces the lean verifier). Found while planning: 0 `rate-off` lines exist in any of the 65 `refresh-rate.log`s
  (no stall since `c1623bc8`) ⇒ P7-6/P7-14 replay the per-second sampler stats of all old runs too. At the 240 default
  nothing switches (24–60 fps already at target, 25/50 no mode) ⇒ Q30 measure-only mode cap. `switcher.exe` holding 280
  is not a valid substitute: the feature's `ChangeDisplaySettingsExW(NULL)` restore goes to the registry mode 240.
  **Runs + fixes 2026-09-29 04:38–08:09 (`measurements/phase7-evidence.txt`, `phase7-rate-off.txt`):** tooling
  `4e55ba37` (cap knob, measure.ps1 fixes, replay/judge tools, soak clips). 80 judged runs: matrix 40/40 PASS (every
  display-synced run 0 drops + mistimed after 5 s), off 3/3, capped switches 143.973/59.951 PASS, close-during-switch
  8/8, kill revert 0.17 s, drop-mode OK, faults 10/10, soaks 4 × 10 min + 60 min clean (0 d+m, no rate-off; audio
  correction > 0.2 % only at the test clips' 150 s stream-copy joins, and at the capped 143.973). **F2 (fixed):** f11 at
  143.973 bent mpv's estimate for 7 samples ⇒ with N = 5 the health check dropped display sync for the rest of the
  video; at 240 f11 bends it 3–4 samples. Rule Q31 ⇒ **N = 9** (`c5ef3e5a` red 4/149, `120453b9` green, verify -Full
  green); re-measured: display sync kept, known-bad still falls back (bad frames, ~16 s). **F1 (open, Q36):** DXGI lists
  120 Hz as 12000/100 but Windows runs 119998/1000 ⇒ settle/verify (1e-6) time out ⇒ fail-safe restore; latent (120
  is picked only under the cap on this monitor). Owner checklist `docs/phase7-owner-checklist.md` (P7-17) still to do.
  **Independent review P7-19 (fresh agent, ≈ 228k, read-only, verify -Full green):** every P3–P7 criterion mapped
  except P7-9 (F1) and the open HUMAN items (P4-15/18, P5-13, P7-10c, P7-17/18). Findings: (1) F1 confirmed and wider:
  watcher mode-lost, health `atTarget` after a same-target keep, and already-at-target use the same 1e-6 ⇒ proposed one
  shared 1e-4 tolerance (above the 16.7 ppm gap, below 143.973/144 = 188 ppm); (2) `hz = llround(num/den)` in the CDS
  call can't select a 1000/1001 mode when an integer twin exists (TVs; not this monitor); (3) START_TIMEOUT 4.5 s <
  CDS + 4 s settle cap ⇒ a late switch can leave the display switched with upstream timing; (4) controller catches
  `Exception`, not `Throwable` ⇒ an Error leaves the session stuck until exit; (5) H6 restore doesn't route
  `setTiming(Upstream)`; (6) no monitor-move check for an already-at-target player; (7) narrow H5/hook race (release
  then relies on mpv_terminate_destroy); (8) unchecked `NewString`. Lock order, JNI, knob gating, off = upstream,
  exit paths: no finding. P7-20 doc gaps fixed (`10f36b1c`, `ce479f31`). ⇒ Q36–Q38.
  **Owner 2026-09-29 (away from the PC): Q36–Q38 yes; "do what you can automated, else move on to the next phase".**
  Fixes tests-first: `8d8e2239`/`0764a655` (100 ppm `RATE_MATCH_TOLERANCE` Kotlin + native settle, late start routed,
  Throwable, screen gone ⇒ upstream); known limits 2/6/7/8 ⇒ FORK §10 (`f94ae173`). Review round 2 (≈ 107k): FAIL on
  the late start (a live 1 s watcher tick drops the health watch before the late-start task) and on a same-target next
  start still getting the rounded 12000/100 ⇒ `492e65cd`/`6941fcf8` (late start keys on the session owner; a verified
  switch keeps the observed rate; 188 ppm edge test). verify -Full green (1506, 7 known failures; upstream 5 files /
  10 lines). Live: 60 fps capped now switches (settled 388 ms at 119.998, override 119.998000, 0 d+m). Kotlin-only ⇒
  no round 3. **Phase 7 status: everything automatic DONE; open = the owner checklist P7-17/18 (and P4-15/18, P5-13,
  P7-10c inside it) ⇒ combined into the Phase 8 owner batch (Q43).**
- **Phase 8 criteria written 2026-09-29 (SPEC P8-1..P8-15); owner approved with Q39–Q43 as recommended.**
  Built 2026-09-29 (`measurements/phase8-evidence.txt`): `ForkIdentity` tests-first (`a5208c45` red / `4b01b44b` green),
  hooks H11–H20 (storage ×2, updater, WebView2 via JNI, 6 Gradle lines incl. Sentry guard); `fork-identity.properties`
  (`name=Nuvio RR`); run-dev keeps upstream names (`NUVIO_FORK_IDENTITY=off`); verify.ps1 now enforces the hook budget
  (every added upstream line tagged; code 11/11, strings 3/3, Gradle 6/6). Scripts: `measure.ps1 -Packaged`,
  `package-fork.ps1` (zip `..\dist\Nuvio-RR-1.1.26-14aca000.zip`), `import-profile.ps1` (tested into a temp folder;
  skips updates\, *.part, sync client id), `export-patch.ps1` (`..\patches\...patch`, `git apply --check` OK).
  Packaged runs: already-at-target and capped switch PASS, 0 official writes, 169 files in the fork's WebView2.
  Sentry guard tested (dummy DSN ⇒ build fails). Docs: FORK 0 TBD (§4 runbook, §5, §7, §9 exe entry, §10), CI
  proposal, SPEC §1–3. Deviation: window title stays "Nuvio" (budget). **Verifier round (≈ 129k): FAIL** — the
  native feature log still went to the official `%LOCALAPPDATA%\Nuvio\Cache` (fixed in the fork file, `d1fc4173`,
  proven by a packaged run without the measure redirect: 0 official writes); upstream's App icon setting touches the
  official shortcuts ⇒ **Q44**; 3 small fixes done. **Q44 yes (owner 2026-09-29):** hook H21 makes the icon updater a
  no-op in the fork (`db0e38dc` red 1/167, then green; verify -Full 1513 tests, budget code 12/12, strings 3/3, Gradle 6/6).
  **Status: all automatic P8 work DONE; open = the owner checklist `docs/phase8-owner-checklist.md` (incl. the
  Phase 7 one, Q43).** After it: Phase 8 and the project done.
- **Phase 6 criteria written 2026-09-28 (SPEC P6-1..P6-13); **approved by the owner 2026-09-28 (Q23–Q26 yes, wording OK)**. Design: per-PC store
  `nuvio_refresh_rate`, `expect object RefreshRateMatchSetting` (3 actuals), new settings composable called by one line
  in `PlaybackSettingsPage.kt` (H9) + 3 strings (H10); native H2 asks Kotlin per player via a JNI upcall, so the planned
  H7 line is not needed. Upstream diff target: 7 code lines + 3 string lines.
  **Code 2026-09-28 (resumed 22:45 after the hook hang, see Incidents):** A `1949ae5b` (tests + stubs, red 10/144),
  B `2fd4c5e7` (Kotlin + UI + H9/H10, green, test diff A→B empty), C `1f71038c` (native per-player enable upcall,
  fault `enable-upcall`). No Phase 3–5 test changed. Deviation from the SPEC wording: `nativeFeatureEnabled()` returns
  an Int (0 off / 1 env / 2 setting / -1 error) instead of Boolean, so the log names the source (SPEC Design note added).
  measure.ps1: `-Setting on|off|absent`, `-EnableEnv 0|1`, default env `0`; summary `enable` section. Docs: SPEC §1–3,
  FORK §1/§5, run-dev/measure help (P6-13).
  **Runs 23:15–23:30 at the new 240 default (Q27 answered: keep 240):** `measurements/phase6-evidence.txt`. P6-5, P6-6,
  P6-7, P6-9, P6-10 PASS (upcall 0.8–2.1 ms; off ⇒ no log, audio sync; on ⇒ already-at-target, display-resample
  239.898, 0 d+m/min). The `volume=100` mpv line is upstream (also in pre-Phase-6 runs). **Found: P5-11 health rule
  judged the rate on ONE sample** ⇒ one 1.3 s stall (1 drop, est 235.649) dropped display sync for the rest of the video
  (run p6-env1-set-off). Fixed tests-first: `814cef33` (red 3/146; 2 Phase 5 tests changed to expect the 3rd judged
  sample), `3b6d8fda` (rate error must hold 3 judged samples in a row). Re-runs clean; the fix is proven by a replay
  test, not yet by a live stall (none occurred). verify -Full green (1492 tests, 7 known failures; upstream 5 files,
  7 code + 3 string lines = P6-1).
  **Lean verifier round (≈116k): PASS** on P6-1..P6-10, P6-13; P6-11/P6-12 HUMAN. Health fix judged justified (not a
  weakening). 2 notes fixed: null-JNIEnv guard in both upcalls (commit after `ae2217e2`, verify -Fast green, native
  rebuilt); SPEC P5-11 text amended. **Q28 answered: 5 samples + log recovered stretches** ⇒ `8ee62087` (tests, red
  5/148), `c1623bc8` (green; controller logs `rate-off pN …, recovered`); docs/PLAN.md Phase 7 collects those lines and
  sets the final count. **Owner checklist P6-12 approved 2026-09-29 ⇒ Phase 6 DONE 2026-09-29. Next: Phase 7** (write P7
  criteria for the 240 default, stop at the gate).
- **Phase 5 carry-overs from Phase 4:** apply `Timing` (it is computed and logged; hook worker in
  display_mode_matcher.cpp `runHook`, "logged only in Phase 4"); a Q15 move / `mode-lost-again` / screen-gone restore
  mid-playback must switch mpv back to upstream timing at runtime; audio sync at 240 shows a few drops at 59.94 and VFR
  (5–6 per 30 s); the watcher logs "query failed" once per second while a monitor is absent (bounded by the 1 MB log).
- **Phase 4 carry-overs from Phase 3:** `Step.reasons` are codes only. For one clear log line, Phase 4 takes the values
  from the event and the prior state (e.g. observed vs target on `verify-mismatch`). DisplayChanged is *ignored* (not
  deferred) while switching/restoring, so a monitor power cycle mid-switch is caught only by the switch verify or the
  next display change. The pure API is `ModeSelector.decide()` → `RefreshRateSession.step()`; hold the session in one
  process-global place, and apply only `Command`s and `Timing`. Carry into later phases: Phase 4 must handle monitor off/on dropping the mode (case H), move display queries off
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
- Feature branch: `feature/refresh-rate-matching` from `upstream/Dev` @ `083921cf`. **Pushed to origin (public fork)**:
  `origin/feature/refresh-rate-matching` = `6a9ae226` (2026-10-01 17:13), local is ahead (24 commits on 2026-10-02).
  Checked 2026-10-02: no secrets in any git object (Real-Debrid key scan, all refs + unreachable: 0 hits).
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
- 2026-09-28 20:33–22:41: the Phase 6 session hung 2 h right after writing its first stub. Cause (reproduced 22:44):
  with no Gradle daemon running, the edit hook's `gradlew` started a new daemon that inherited the hook's stdout pipe,
  so Claude Code waited for EOF forever (hook itself exited 0). Fixed in `scripts/hook-verify-fast.ps1`: verify runs in
  a ShellExecute'd process (no inherited handles, output to a temp file) with a 270 s kill. Re-tested: no daemon ⇒ 15 s,
  compile error ⇒ exit 2 with the error. Nothing else was affected (only the stub file had been written).

## Owner answers (2026-09-28)
- Q1 known-upstream-failure baseline (D3): **approved**.
- Q2 push: **keep local**. Do not push to origin until the owner says so. (Superseded: the branch was pushed to
  origin by 2026-10-01 17:13, see Repo facts; still ask before any further push.)
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

## Owner answers (Phase 4 gate, 2026-09-28)
- P4-1..P4-21: **approved**. Q14 dev knob `NUVIO_RR_ENABLE=1` until Phase 6: **yes**. Q15 monitor move mid-playback ⇒
  restore the old monitor, keep playing, no switch until the next video: **yes**. Q16 fault knob `NUVIO_RR_FAULT=<kind>` +
  measure.ps1 `-Feature`/window-close runs: **yes**.
- Q18 VFR clip switching by its header: **accepted** (option a); P4-10 amended.
- Checklist so far (owner, 17:37–17:53, log `devprofile\Local\Nuvio\Cache\refresh-rate.log`): switch and exit restore OK;
  a second player with the same target did not re-switch (P4-15 evidence). **Toggling Windows HDR resets the temporary mode
  to 280** (HDR off ⇒ 8 bpc): the old logic re-switched once, then verify-mismatch ⇒ restore ⇒ 280 SDR. Owner chose
  **option B** ⇒ P4-22 (re-switch in the new HDR state, own cap of 3): commits `c4186101` (red 6/96) + `30be0da6` (green),
  verify -Full green, upstream diff still 6 lines. Q19 (leave during settle): both owner runs missed the 3 s window (the
  settle ended normally, no stop) ⇒ retried 17:57: **Q19 PASS** — Back 0.3 s and 2.0 s into the slow settle: H5 continued the
  hook, settle stopped (`stop-requested`) within 14 ms, restore ⇒ 279.961 10 bpc HDR on, no crash. P4-14a PASS (leave the
  player 17:56:18 ⇒ restored in 0.17 s).
- Final checklist (owner, 18:09–18:10, then "all OK" + "that's fine, I think it's OK" when told the log was incomplete):
  **owner-attested** P4-15, P4-16, P4-18, P4-21, P4-22. **Log evidence only for:** one display reset with HDR on at 18:09:49 ⇒
  `mode-lost` re-switch to 239.901 OK (monitor off/on or driver restart), exit restore OK. That session still ran with the
  slow-settle knob (Q19 window reused). **Not in the log:** Win+Alt+B under P4-22 (`hdr-toggled` never logged), sleep/resume,
  a second display reset, next episode ⇒ **carry into the Phase 7 matrix** (HDR toggle mid-playback, sleep/resume, driver
  reset, next episode) with log evidence. P4-13 wording fix (player id = native counter): **acknowledged** by the owner.
- Q17 follow-up (17:35): owner created a 250 Hz custom mode. It appears only in GDI EDS_RAWMODE (with 100 and 265),
  not in the normal GDI list or DXGI ⇒ the feature still does not see it; 25 fps stays at 280 (run `*p4-250check`).

## Owner answers (Phase 5 gate, 2026-09-28)
- Q20: owner **keeps Max Frame Rate off** (global) ⇒ also answers Q10c (no Nuvio-only FRL profile needed). The capped case
  is then only a safety rule: recommended option (a) kept — capped ⇒ `NoSwitch("frame-cap")` (P5-10).
- Q21 health fallback (P5-11): **yes**. Q22 VFR judged on underruns/desync only: **yes**.
- Q17 follow-up: owner says a custom 250 Hz mode is set in NVIDIA. Re-checked 2026-09-28 (read-only EnumDisplaySettingsEx
  at 2560x1440): normal list 59/60/120/144/240/280; 100, 250, 265 only with EDS_RAWMODE (unchanged) ⇒ still invisible to
  the feature; 25/50 stay at 280. Told the owner.

## Owner answers (Phase 6 gate, 2026-09-28)
- P6-1..P6-13 **approved**; Q23 per PC **yes**; Q24 env override **yes**; Q25 next video **yes**; Q26 wording **OK**.
- Q27 (23:15): desktop default **240 Hz is the new normal, keep it.** ⇒ SPEC Phase 6 note: limits read against 239.901/240;
  measure.ps1 `-ExpectHz 239.901 -ExpectRegHz 240` are the new defaults. 23.976 ⇒ already at target (no switch).

## Open questions for owner
- Q47 (audit E2, 2026-09-30): turn on mpv frame blending (display-resample + interpolation, tscale=oversample) for
  25/50 fps titles at the 240 Hz desktop? Measured clean (0 drops/mistimed/underruns, +0..4 W;
  measurements/audit-e2-evidence.txt); the look (one blended refresh at each frame change instead of uneven 9/10-refresh
  holds) is the owner's call (D12). Recommended: watch one 25 fps title with it first.
- Q48 CLOSED 2026-10-01 (owner: upstream CONTRIBUTING.md rejects feature/behavior changes without an approved feature
  request, so don't bother; nothing goes upstream, docs/feature-request.md stays unposted; branches kept locally).
  Was: 4 upstream PRs ready on local branches upstream-pr/* (docs/upstream-prs-2026-09-30.md);
  sending needs issues opened + a push (Q2/Q3). Send, keep, or also carry #1 (atomic writes) in the fork?
- Q44 (Phase 8, ANSWERED 2026-09-29: yes, the one line): upstream's Settings → App icon rewrites the **official** Nuvio shortcuts and writes
  `%LOCALAPPDATA%\Nuvio\icons` (8 hard-coded names in `WindowsAppShortcutIconUpdater.kt`). Fix with ONE hook line that
  makes that updater do nothing in the fork (the portable fork has no shortcuts of its own), raising the code budget
  11 ⇒ 12? Alternative: leave it and just never use that setting in Nuvio RR (FORK §10). Recommended **the one line**.
- Q39 (Phase 8, ANSWERED 2026-09-29: as recommended): fork app name **"Nuvio RR"** (exe `Nuvio RR.exe`, folders `%APPDATA%\Nuvio RR`, start menu group)?
  Recommended **yes** (any short name works; it only must differ from "Nuvio").
- Q40 (Phase 8, ANSWERED 2026-09-29: as recommended): distribution as a **portable app folder** (`createDistributable`, zipped; no installer, no WiX, no admin)
  instead of an MSI (needs the WiX toolset + UAC)? Recommended **app folder**.
- Q41 (Phase 8, ANSWERED 2026-09-29: as recommended): copy your official Nuvio profile into the fork once (script you run; official folder untouched), or
  start the fork empty? Recommended **copy**.
- Q42 (Phase 8, ANSWERED 2026-09-29: as recommended): raise the upstream-diff budget to ≤ 11 code lines + 3 strings + ≤ 6 lines in `build.gradle.kts`
  (identity: 2 storage lines, 1 WebView2 line, 1 updater line, the Gradle identity block)? Recommended **yes**.
- Q43 (Phase 8, ANSWERED 2026-09-29: as recommended): do the still-open Phase 7 checklist in the same owner session as Phase 8's; Phase 7 is closed then.
  Recommended **yes**.
- Q36 (Phase 7, ANSWERED 2026-09-29: yes): fix F1 with one shared 100 ppm rate tolerance (native settle + Kotlin verify/mode-lost/atTarget/
  already-at-target, override from the observed rate), tests first + a native rebuild + one more review round
  (≈ 80k)? Recommended **yes**.
- Q37 (Phase 7, ANSWERED 2026-09-29: yes): also fix review findings 3 (start timeout vs settle cap), 4 (catch Throwable), 5 (H6 routes Upstream)
  tests first in the same round, and list 2 (1000/1001 on TVs, not testable here), 6, 7, 8 as known limits in FORK?
  Recommended **yes**.
- Q38 (Phase 7, ANSWERED 2026-09-29: yes): confirm `RATE_ERROR_SAMPLES` = 9 (P7-15). Recommended **yes**.
- Q30 (Phase 7, ANSWERED 2026-09-29: as recommended): how to test switch/restore at the 240 default? (a) measure-only `NUVIO_RR_MEASURE_MAX_HZ=144` (only with
  NUVIO_RR_MEASURE=1): 23.976/24 ⇒ 143.973, 29.97/59.94 ⇒ 119.998, 60 ⇒ 120, restore to 240 — **recommended** (no
  Windows change, real switch + real registry restore); (b) owner sets the Windows default to 280 for one matrix block and
  back (most realistic target, owner-only change); (c) no fresh switch evidence, rely on Phase 4/5 (made at 280).
- Q31 (Phase 7, ANSWERED 2026-09-29: as recommended): final `RATE_ERROR_SAMPLES` rule (P7-15): N = max(3, longest recovered stretch seen live or in the replay
  + 2), capped so every known-bad run still falls back within 20 s; nothing off-rate seen ⇒ stays 5. Recommended **yes**.
- Q32 (Phase 7, ANSWERED 2026-09-29: as recommended): soaks = 4 × ~10 min stream-copied (2160p HDR 23.976 fs, 1080p 59.94, 1080p 25, 1080p 23.976 capped),
  plus ONE unattended 60 min `hdr-2160p-23.976` fullscreen for more rate-off data? Recommended **yes to both**.
- Q33 (Phase 7, ANSWERED 2026-09-29: as recommended): owner batch (P7-17) as one ~40 min session after the unattended runs: one long capped measure command
  for HDR toggle / monitor off-on / driver reset / sleep / audio device, then dev-build items (DV, HDR10+, next episode,
  25 fps, flicker yes/no). You pick the DV and HDR10+ titles. Recommended **yes**.
- Q34 (Phase 7, ANSWERED 2026-09-29: as recommended): include the optional PresentMon capture (one UAC click) to prove the constant panel rate at 143.973 too?
  Recommended **yes** (cheap; P5-16 proved it only at 239.9).
- Q35 (Phase 7, ANSWERED 2026-09-29: as recommended): the P7-19 independent review (fresh verifier, whole patch vs SPEC/FORK, ~150k) replaces the usual lean
  verifier round; one round, a second only if a fix touches native code. Recommended **yes**.
- Q29 (ANSWERED 2026-09-28 23:58: **VRR confirmed** — owner set NVCP Program settings
  `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe` ⇒ Monitor Technology = Fixed Refresh ⇒ flicker gone. Workaround
  documented in FORK §9; Phase 8: Nuvio-only profile for the app's own exe, official app needs the same entry.)
  Original report (2026-09-28 ~23:55): **OLED flicker when scrolling/pointing on Nuvio's home/settings screens**,
  fullscreen and windowed, dev build. Not the feature: the dev-profile refresh-rate.log shows no switch/restore (both
  owner plays 23:50/23:51 already-at-target at 240) and nothing while browsing. Driver unchanged (VRR_MODE=1 fullscreen
  only, VRR_APP_OVERRIDE=1, Windows VRROptimizeEnable=0). Hypothesis [inferred]: G-SYNC engages for the Compose UI,
  which presents only on change ⇒ refresh follows frame rate ⇒ OLED gamma flicker (D9); windowed case doesn't fit
  "fullscreen only". Asked owner: NVCP Program settings java.exe ⇒ Monitor Technology = Fixed Refresh, retest; and
  whether the official app flickers too. If VRR: Phase 8 Nuvio-only profile (research 05 option c). If not: PresentMon.
- Q28 (ANSWERED 2026-09-28: 5 samples, and log the recovered stretches for Phase 7): OK to amend P5-11 so the display-rate part needs 3 judged samples in a row (~3 s) instead of one?
  Reason: one 1.3 s stall dropped display sync for the rest of a video. A real breakage is still caught, ~2 s later.
- Q27 (ANSWERED 23:15: keep 240): the monitor's Windows default is now 240 Hz (was 280 all day). Did you change it?
  Phase 6 runs need 280 as the default (every criterion compares against 279.961). Set it back to 280 in Windows
  display settings (I won't change a Windows default myself), or say if 240 is the new normal (then the P6 limits
  need rewriting: at 240 the feature finds "already at target" for 24 fps).
- Q23 (Phase 6, ANSWERED yes): setting stored **per PC** (one value for every Nuvio profile, not synced to other devices)? It is about
  this monitor, so recommended **yes**. Alternative: per Nuvio profile like the RTX VSR switch.
- Q24 (Phase 6, ANSWERED yes): keep `NUVIO_RR_ENABLE` as a dev/measure override (`1` forces on, `0` forces off, unset = the setting)?
  measure.ps1 needs it so baseline runs stay off. Recommended **yes**.
- Q25 (Phase 6, ANSWERED yes): a change applies from the next video; a video already playing is left alone. Recommended **yes**.
- Q26 (Phase 6, ANSWERED yes): wording. Section "Display", switch "Match display refresh rate", description "Switch the monitor to a
  multiple of the video's frame rate while playing (e.g. 240 Hz for 24 fps), then back." OK or other text?
- Q20 (ANSWERED, see Phase 5 gate answers): Max Frame Rate set below ~252 fps (e.g. the old 200 cap) makes display-resample collapse. Then:
  (a) **don't switch at all** (stay 280, upstream timing; log `frame-cap`) — recommended: at 240 without display sync the
  motion is no smoother than at 280 (Phase 2 fixed-240 run) and you'd get the black flash for nothing; or (b) switch
  anyway with audio sync.
- Q21 (ANSWERED yes): add a health fallback (P5-11)? If display-resample is clearly broken during playback (> 20 drops +
  mistimed in 10 s, or the measured display rate > 1 % off), mpv goes back to its own audio timing and the display stays
  at 240. Catches driver changes after an update (e.g. power mode back to Normal for 4K HDR). Recommended: **yes** (small,
  pure logic, tested). No = P5-11 removed.
- Q22 (ANSWERED yes): VFR clips (header says 60 ⇒ 240 Hz, Q18) under display-resample: judge only "no underruns, no A/V
  desync" and record drops/mistimes, since mpv's display sync can't be exact for truly variable frame times? Recommended:
  **yes**.
- Q17 (Phase 4, 2026-09-28): **100 Hz is gone from the monitor's normal mode list.** Today GDI (non-raw) and DXGI list
  only 59.951/120 (12000/100, was 119.998)/143.973/239.901/279.961 at 1440p; 100 and a new 265 exist only with
  EDS_RAWMODE (driver-pruned modes). Research 02 (a day earlier) saw 100 Hz in every API. switcher.exe refuses 100.
  Result: 25/50 fps stay at 280 (`no-suitable-mode`, fail-safe). Did anything change (NVIDIA App reset, monitor OSD,
  cable/DSC)? Options: (a) accept; (b) owner restores 100 Hz (e.g. NVCP custom resolution) — then it works with no code
  change; (c) also consider raw modes — NOT recommended (a pruned mode can mean "no signal" while Windows reports success).
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
| 3 Logic (TDD) | ~250k | ≈ 290k (main ≈ 175k incl. criteria + gate; verifier 114k) — ~15 % over, the verifier again the biggest single item |
| 4 Native switching | ~500k | ≈ 480k (main ≈ 360k incl. 37 measure runs, the P4-22 change and log reviews; verifier 117k) — within the estimate |
| 5 mpv timing / OLED | ~400k | ≈ 470k so far (criteria + gate ≈ 140k, code + 22 runs + fixes ≈ 215k, verifier 115k); the owner's PresentMon/checklist session still to come ⇒ ~500k, ~25 % over (verifier again the largest item) |
| 6 Settings/JNI | ~200k | ≈ 140k so far (criteria + gate, mostly reading PROGRESS/SPEC/plan) |
| 7 Matrix + review | ~500k; re-estimated at the gate ≈ 550k (criteria ≈ 90k, tooling ≈ 80k, ~90 runs + soaks ≈ 130k, owner batch ≈ 50k, N change ≈ 25k, review ≈ 150k, docs ≈ 25k) | ≈ 790k: main ≈ 455k (criteria, tooling, ~95 runs, F1/F2 analysis, N change, Q36/Q37 fixes, docs) + review 228k + round 2 107k — ~45 % over; the reviews were the largest items and round 1 caused the extra fix round |
| 8 Upkeep | ~250k; re-estimated at the gate ≈ 350k | ≈ 330k so far: main ≈ 200k (identity code + tests, hooks, packaging, 3 packaged runs, 4 scripts, docs) + verifier 129k; within the re-estimate (Q44 + the owner checklist review ≈ 30k more) |

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
- **Phase 4 feature runs (2026-09-28, `measurements/phase4-evidence.txt`, 20–60 s each, audio sync, windowed):**
  23.976 1080p / 4K HDR ⇒ 239.901 during, mpv display-fps 239.901 from the first stats line, switch before file-loaded,
  0–1 drops, HDR stays on; 59.94 ⇒ 239.901 (5 drops/30 s); 25 ⇒ no switch (no 100 Hz listed, Q17); VFR ⇒ 239.901 (Q18).
  Switch call 84–192 ms, settle (call → two equal reads) 207–329 ms, mpv held at the hook 240–360 ms. Restore on window
  close 51–155 ms; `-Kill` ⇒ 279.961 0.17 s after the kill (Windows revert). All 8 fault kinds ⇒ playback continues,
  279.961 after (settle-timeout holds the hook 4.39 s < 5 s). 20 close-during-switch runs: no crash, 279.961 after,
  registry 280 in all 9423 observer samples. Feature off ⇒ no feature activity, 279.961 throughout. Registry 280 in every run.
  **Measurement caveat (new):** with the monitor asleep (Windows display timeout 15 min) every run shows ~500 drops +
  audio underruns per 30 s, feature on or off; measure.ps1 now wakes the display (1 px mouse nudge) first.
- **Phase 5 feature runs (2026-09-28 18:46–19:27, `measurements/phase5-evidence.txt`, feature on, 120 s unless noted,
  counted after the first 5 s):** display-resample at 239.901 with `display-fps-override=239.901000`, mpv
  `estimated-display-fps` 239.8979 (0.001 % off), **0 drops, 0 mistimed, 0 audio underruns** in every switched run:
  23.976 windowed ×3, fullscreen ×2, 4K HDR fullscreen, 24, 29.97, 59.94. Speed corrections: video 1.000587 (23.976/
  29.97/59.94) / 0.999587 (24), audio 0.99934–1.00184. VFR (Q22): 0 underruns, no desync warning; speed corrections
  0.9935–1.0011 (recorded). 25 fps: no switch, video-sync=audio, no feature property set. Driver line on every start:
  `frl=off(global) power=1(global) exe=java.exe app_profile=no`, read in 150–160 ms.
  Faults: `timing-set` ⇒ reverted, upstream timing, plays, 279.961 after; `drop-mode` (rerun after fix `1331ad4d`) ⇒
  20 s `mode-lost` re-switch + `setTiming display-sync ok`, 50 s `mode-lost-again` + `setTiming upstream ok`, 0 drops
  after 51 s, 0 underruns; forced `d3d11-sync-interval=0` ⇒ `resample-unhealthy mistimed=240 in 10 s` ⇒ upstream timing
  ~16 s after the hook (5 s ignored + one 10 s window + the first steady read), mode kept. **GPU power (P5-14, fullscreen, max-performance power on):** 1080p 23.976 feature on 38.2 W vs off
  (280 Hz audio sync) 37.8 W; 4K HDR 40.1 W vs 40.2 W ⇒ no material difference.
  Tooling note: samples from the close request on are now excluded (the first run counted the restore to 280 as 3 drops +
  4 mistimed).
- **Phase 5 PresentMon (owner UAC, 19:45–19:53, feature on, 23.976 at 239.901):** fullscreen `*p5-pm-fs`: all 26 865
  java.exe display changes exactly 1 refresh apart (max 0.12 ms off the 4.168 ms grid), 0 missed refreshes, mpv
  vsync-ratio 10.0, jitter 0.00023, 0 drops/mistimed with PresentMon running ⇒ **PresentMon no longer perturbs
  display-sync** (that was the 200 fps cap) and every 23.976 frame is on screen for exactly 10 refreshes (display-resample
  presents every refresh, so frame holds come from vsync-ratio + 0 missed refreshes, not from the present histogram).
  Windowed with pause 20 s, seeks, controls, f11 ×2 (`*p5-pm-actions`, run twice): every interval a whole number of
  refreshes; off-grid > 0.25 ms only as compensated pairs (e.g. 4.420 + 3.916 = 2 refreshes; run 2: 7 pairs of 22 044,
  pair sums ≤ 0.22 ms off, 0 unpaired; run 1: 1 pair after cutting the last second, which captured the close and restore
  to 280) ⇒ timestamp jitter, **VRR not engaged, panel rate constant** through pause/seek/controls/fullscreen. Harness
  note: both action runs ended fs=yes (one f11 not applied), a measure-script issue. The summary's own grid check uses the
  280 Hz period; fix in the tooling before Phase 7.
  **Follow-up analysis (owner: "investigate further", 2026-09-28 ~20:20), run 2 `*195048*-p5-pm-actions`:**
  (a) The pairs are not caused by other apps (no other process presented within 30 ms of 6 of the 7). One is the f11 present-mode
  change (65.5 s capture ≈ load + 71.5 s). The other 6 come at capture 98–102 s (≈ load + 104–108 s, fullscreen, no action), some 250 ms
  apart. Present pacing stays steady (`MsBetweenPresents` 4.07–4.35 ms); only the display timestamp moves 0.25 ms
  late then early; `MsFlipDelay` is NA on every present, so there is no VRR indicator from PresentMon. mpv's own flip statistics see it too:
  `vsync-jitter` 0.00023 ⇒ 0.00035 at the same time-pos (84–86). (b) Each f11 toggle disturbs display sync for ~4 s:
  vsync-jitter 0.095, estimated rate 238.7, +1 mistimed, +1..3 delayed. (c) **The windowed 20 s pause lands on the grid:** resume
  19 962.495 ms = 4789.02 refreshes, 0.094 ms off ⇒ fixed refresh while nothing is presented (run 1: 7.8 s gap, 0.026 ms
  off). (d) **Not yet proven: a pause in FULLSCREEN**, the only mode where G-SYNC ("full screen only") could engage. A
  steady 239.9 fps presenter looks the same under VRR and under a fixed 240 Hz, so the pm-fs run alone can't tell them apart. The test is a
  fullscreen pause (a VRR panel would slow down and the resume would land off the grid). Phase 2 proved this at 280 in audio sync
  (20 s pause = 5595 vsyncs exactly), not under the feature at 240. ⇒ one more owner PresentMon run needed.
  **Fullscreen pause run (owner UAC, 20:14–20:17, `*201454*-p5-pm-fs-pause`, fullscreen throughout, pause 20 s, seek ±,
  controls): P5-16 PASS.** Resume after the 19.929 s fullscreen pause = 4781.0104 refreshes, **0.044 ms off the grid** ⇒ the
  panel kept 240 Hz with no presents ⇒ **VRR not engaged in fullscreen either**. Grid fitted from the display times: 239.8979 Hz
  (= mpv's estimated-display-fps) in this run and in `*p5-pm-fs`; phase residuals p99 0.24 ms / p99.9 0.36 ms in both
  (so the earlier interval counts overstated this run's jitter). Bigger residuals (≤ 1.73 ms) only in the 10 s bins with the two
  seeks (one display 1.45 ms after the previous one, then back on the same grid phase, no re-phasing ⇒ a timestamp oddity
  of superseded flips, not VRR). mpv counters: 1 mistimed at the pause resume, 1 at the seek, 0 drops, 0 underruns.
  Tooling to fix before Phase 7: after5s deltas go negative across a seek (mpv resets mistimed/delayed on seek) ⇒ sum the
  positive increments; grid checks must fit the period (not the 280 Hz constant or nominal 239.901); f11 toggle
  unreliable in -Actions.
- Kill test A–J: see docs/research/09-kill-test.md (CDS_FULLSCREEN reverts on every death path; monitor off/on drops the
  temporary mode; "240" = 239.901; blank ≈ 1 s per switch).
- Audio note: mpv outputs 96 kHz 7.1 float to the current default device (Arctis base: `Remix: stereo -> 7.1`).
- SDR clips are rendered to a PQ/BT.2020 swapchain (`RGB_FULL_G2084_NONE_P2020`) because Windows HDR is on.

## Log
- 2026-10-01 23:13-23:45 Seek timing (owner: "faster seeking time?", measure first). Dev build, feature off, mpv IPC;
  time = seek command to mpv playback-restart. Realistic clips made with NVENC (scratchpad seek\: 4K HEVC 10-bit
  41 Mbit/s keyframes every 10 s and 2 s, 1080p H.264 10 Mbit/s every 10 s); streams through a local Range server
  (fast: 80 ms first byte, 300 Mbit/s; slow: 250 ms, 80 Mbit/s). Medians: local / buffered exact seek 0.14-0.33 s
  (max 0.48, decode from the keyframe; d3d11va); keyframe seek 0.02-0.04 s but lands up to 9.7 s early. NOT buffered
  exact: fast 1.1-1.2 s, slow 3.5 s (max 5.2) with 10 s keyframes and 1.0 s (max 1.6) with 2 s; keyframe seek 0.16 /
  0.52 s. Cause: an exact seek downloads keyframe->target first (9.5 s x 41 Mbit/s = 47 MB). Ruled out:
  cache-pause-wait 0.3 vs 1.0 (same), sync seek command blocking the UI (reply 0.1 ms), keyframe-then-exact (picture
  at 0.5 s but the exact spot later, 5.3 vs 3.9 s). Found: +-10 s buttons (relative+keyframes) land up to 6 s off
  with 10 s keyframes (-10 can go -20). Not tested: real debrid/TorrServer, the UI drag path.
  Owner's real film (Blade Runner 2049 HDR-X DoVi, 4K HEVC, 61 Mbit/s, keyframes every 0.5 s): local exact seek
  25 ms (max 30), +-10 s lands within 0.5 s; stream at 150 Mbit/s + 250 ms first byte: buffered 23-44 ms, not
  buffered exact 0.63-0.75 s vs keyframe 0.43 s (the 250 ms first byte + 1-2 requests is most of it).
  => with normal short keyframe intervals exact seeks cost ~0.2 s extra at most; the slow case is 10 s-keyframe encodes.
- 2026-10-01 23:56 Stream download rate (owner: "full debrid bandwidth?"). Debrid links go to mpv as plain HTTPS URLs
  (one connection, FFmpeg http, OpenSSL in libmpv; no proxy). Same film from an uncapped local server: mpv read at
  >= 1 Gbit/s (raw-input-rate) until the 512 MiB forward buffer was full (1.5 s, ~120 s of video), then only at the
  playback rate (9-83 Mbit/s). => the app is not the limit; only a debrid per-connection cap below the film's bitrate
  would show (mpv cannot open parallel connections). Real debrid link not tested.
- 2026-10-02 00:02 Real-Debrid test (owner's Comet link, Blade Runner 2049 BDRemux 63 GB ~52 Mbit/s; link not stored:
  it embeds the RD API key). Comet resolve 3.6 s (302 to *.download.real-debrid.cloud, Cloudflare); RD first byte
  1.3 s per request. curl: 1 connection 311-328 Mbit/s, 4 parallel 447, 8 parallel 427 (line limit). App (mpv):
  ramp 96 -> 350-510 Mbit/s over ~8 s, 512 MiB buffer (~130 s of film) full 14.5 s after start (avg ~300 Mbit/s), then
  playback rate. App start -> file-loaded 8.9 s. => the app uses the full single-connection speed; parallel
  connections would add ~35 % on this line and are not needed (6x the film's bitrate). Found: measure-mode sampler
  logs (Cache\nuvio-rr\*.log) copy mpv's "Opening done: <url>" line unredacted (old devprofile logs hold the key).
- 2026-10-01 Subtitle look ("optimise every aspect"): 1394a71c red 3/27 -> green (automatic font, outline 2,
  shadow 1 @50 %, blur 0.3, libass-mode size made linear, desktop size default 15). Live: NetflixSans-Medium + look
  applied with no setting touched. Open: owner to check the size on a real film (the smoke launcher doesn't apply
  the app's subtitle style); the owner's saved size 12 now renders at 36 (was 24).
- 2026-10-01 Subtitle font (owner asked about the best subtitle font, supplied a Netflix Sans zip: 4 genuine OTFs,
  installed per-user only, never bundled/committed). Default was mpv's sans-serif -> Arial. Setting built
  tests-first: 3f643bca red 5/5 -> 4ca80d59 green; a22d31d2/88af7fad trim fix (Java lists "Netflix Sans "); 40433d0d/
  aaab3cab Netflix Sans Medium/Bold by PostScript name (live screenshots: Regular, Medium, Bold faces all render;
  family+bold request did not find Bold). Lesson: Start-Process -ArgumentList with an array splits values with
  spaces (sub-font=Netflix Sans became "Netflix"); quote one argument string.
- 2026-10-01 HDR metadata test: scripts/gen-metadata-clips.py (030c2d1f) made 2 clips, identical decoded frames,
  HDR10 metadata 10000/10000/400 vs 400/400/100 (C:\TestAPP\hdr-meta + launchers). Pass through hands each clip's
  own metadata to Windows (read live). Owner: the clips look DIFFERENT in Nuvio RR => metadata reaches the monitor
  and it tone-maps by it. Owner: make Pass through the default; thinks madVR is set up wrong. 1792082b red 1/17,
  dd000930 green (test diff empty); fresh profile live: hint-mode=source, target 400/400/100. The owner's own
  Nuvio RR profile stored hdr=WINDOWS_CALIBRATION (19:47), so it keeps that until changed in the settings.
- 2026-10-01 HDR vs MPC + madVR (owner saw differences): Nuvio RR outputs HDR10 correctly, but mpv's hint-mode=target
  used the Windows calibration profile (1500 HDR Calibrated.icc: 8000 nits, min 0.015; owner re-ran the app 19:32:
  3500 nits) while the EDID (owner: correct; madVR shows it) says 1532 / avg 296. Owner chose a VIDEO QUALITY choice:
  HDR = Monitor peak from EDID (default) / Pass through (hint-mode=source) / Windows calibration. f00af3b6 red 7/17,
  cfe84b96 green (test diff empty). Live 4K HDR: 1532 / 1000+203 P3 / 3500. mpv default dscale = hermite (read live).
  Open: owner A/B against madVR on real films (madVR's own HDR mode, passthrough or tone-map, not seen yet).
- 2026-10-01 Video quality (owner: "build it with SSimDownscaler as an option"): 5b517aac red 9/9, e644dfc8 green
  (test diff A->B empty), 878832b6 shader kept LF. Settings > Playback > VIDEO QUALITY: Scaling quality Standard/High,
  Downscaler default/Catmull-Rom/SSimDownscaler (igv, LGPL-3.0, gist rev 38992bce, sha256 f46f4710...). FORK.md section 12.
  Live: High+SSim 4K HDR fs 240 Hz: 8 options rc=0, 4 shader passes registered, 0/0, 51.2 W 19 %; default: no options.
  verify -Full OK (only PluginRuntimeDesktopTest flaky). Zip ..\dist\Nuvio-RR-1.1.26-c3108ff0.zip; packaged run with
  High+SSim: shader copied from the jar into "Nuvio RR\Cache\shaders", 4 passes, 0/0.
  Open: owner to judge the look on real films (Standard vs High, default vs Catmull-Rom vs SSim).
- 2026-10-01 18:03-18:20 Quality headroom (owner asked "is it the best quality?"): measure -Fullscreen -Feature -Power
  60 s, 240 Hz desktop, Windows HDR on (hdr=1, so HDR is passed through and tone-mapping options do nothing), VSR off.
  HQ = scale+cscale=ewa_lanczossharp, hdr-peak-percentile=99.995, hdr-contrast-recovery=0.30, allow-delayed-peak-detect=no
  (all rc=0). after5s drops/mistimed, GPU W median: 4K HDR base 0/0 46.1, HQ 0/0 48.2, HQ+dscale=mitchell 0/0 47.5,
  HQ+dscale=ewa_lanczossharp 0/0 49.4; 1080 SDR base 0/0 44.7, HQ 0/0 46.2; 1080 HDR base 0/0 45.0, HQ 3/4 then 0/0
  and 0/0 on 2 repeats (one-off). GPU util 14-18 %: HQ fits easily. Clips are synthetic, so the look is not judged yet.
- 2026-10-01 Correction: fork builds DO contain TorrServer. The batch 4 note looked only at the unmapped
  vendor/TorrServer source submodule; the prebuilt binary is a Git LFS file (resources/torrserver/windows-amd64/
  TorrServer.exe, 58 MB, SHA-256 13031185... = upstream release check) and is in the zip's composeApp-desktop jar
  (checked in Nuvio-RR-1.1.26-f3f2324b.zip). P2P not yet played live.
- 2026-10-01 17:08 White window gone: the dev build started with its own setting (OpenGL, no SKIKO_RENDER_API) draws
  the home screen normally (scratchpad gl-check.ps1 + screenshot). No reboot since 2026-09-30 23:31, so it was a
  temporary OpenGL/driver state that cleared by itself, not a fork change. All three profiles (official, Nuvio RR, dev)
  have had open_gl_enabled=true since 2026-08-25. Dev runs no longer need SKIKO_RENDER_API=DIRECT3D.
- 2026-10-01 Owner: movies did not play with Passthrough on. Reproduced on a real stream (dev profile, 4K E-AC3,
  default device SteelSeries Sonar virtual): bitstream refused, mpv "Falling back to PCM output" but never reopened
  the AO, time-pos stuck at the resume point (local files recover from the same fallback, so they missed it).
  Fix f74c8fe2/eec4ba22: WASAPI probe of the device per video, audio-spdif only for accepted codecs (Sonar: none ->
  plays; Realtek S/PDIF: ac3,dts). Lesson: test audio paths on a real stream through the UI, not only local files.
- 2026-10-01 Owner: focus ring stayed on while mouse-scrolling. Fixed (2f07bed2 red, 6312a44a green): ring only after
  arrows/Tab, hidden by a mouse press or wheel. New zip Nuvio-RR-1.1.26-6312a44a.zip.
- 2026-10-01 Owner: no Backspace-back (E4 stays Esc + Alt+Left + mouse Back).
- 2026-10-01 Phase 9 DONE: verify -Full OK (1612 tests, only PluginRuntimeDesktopTest flaky); package-fork zip
  Nuvio-RR-1.1.26-fd9c57ad.zip (198 MB); measure -Packaged smoke: file-loaded, audio options applied at H2, MEASURE OK.
- 2026-10-01 Phase 9 batch 6 DONE: E6 shelf arrows, E5 Reduce motion, E9 window restore, E7 scrub preview, E1 audio
  options, E9 keyboard navigation (commits in docs/phase9-plan.md). White window narrowed: the dev profile uses the
  OpenGL renderer; Direct3D and software are fine, so live checks run with SKIKO_RENDER_API=DIRECT3D. Lessons: live
  scripts must click the title bar (SetForegroundWindow alone failed once, so no key reached the app); arrow keys need
  KEYEVENTF_EXTENDEDKEY; scroll containers take focus without bounds, so focus moves step into children
  (FocusDirection.Enter); verify -Fast already rebuilds the bridge when any native/windows/*.cpp is newer (Gradle's
  onlyIf only builds a missing DLL). Next: verify -Full, package-fork zip.
- 2026-10-01 E4 DONE (4247f329 red, 2d6f716c green). White window was NOT E4: it started between the 11:38 and 11:46
  runs, stays with #20 reverted, EDT idle, logs identical to a good run; SKIKO_RENDER_API=SOFTWARE renders fine, so the
  DirectX path on this machine broke (driver/GPU state?); owner to check (reboot, packaged app). Live checks now run with
  the software renderer. E4 B redesigned: Compose 1.12 already maps Esc to the window NavigationEventDispatcher (NavDisplay
  listens there); the old separate handler list missed Alt+Left on details and made Esc on home ask to exit. Now desktop
  PlatformBackHandler = NavigationBackHandler, Alt+Left = DirectNavigationEventInput on that dispatcher, home exit handler
  off on desktop, mouse Back left to upstream (MainAppContent pops the stack), no Backspace. Real-input script:
  scratchpad e4-real.ps1 (arrow keys need KEYEVENTF_EXTENDEDKEY, or Compose sees no Left; posted WM_ clicks do nothing).
- 2026-10-01 HANDOFF (session ended on context). Batch 6 started: E4 A committed (DesktopBackDispatcherTest red).
  E4 B is UNCOMMITTED in the working tree: PlatformBackHandler.desktop.kt (dispatcher + composable) and Main.kt
  (SwingWindow onPreviewKeyEvent for Esc/Alt+Left + AWT listener for mouse button 4). It compiles and its test
  passes, but the live check (scratchpad e4-live.ps1: posted click on a poster, posted Esc) gave a WHITE window in
  both screenshots, no exceptions in the log. First job next session: find out whether the Main.kt change breaks
  rendering (run home-mem.ps1 with and without it; check the screenshot) or the posted click/Esc caused it. Do not
  commit E4 B until the home screen renders. Then: E1, E5, E6, E7, E9; then FORK.md docs, verify -Full, zip.
  Owner to be told: Nuvio RR builds contain no TorrServer binary (P2P cannot work).
- 2026-10-01 Phase 9 batch 5 DONE: #7 timeout (serial runtime tried: deadlocked to the 60 s timeout, reverted;
  lesson: check suite durations, a passing loop hid a 60 s hang), R2, R4 (6 of 7 baseline failures fixed, list now
  holds only PluginRuntimeDesktopTest), R5; R3/R6 n/a. verify -Full green. Next: batch 6 (E1, E4-E7, E9).
- 2026-10-01 Phase 9 batch 4 DONE (#10-#14, #18, #20-#22; partials listed in the plan). Found: Nuvio RR builds
  contain no TorrServer binary (vendor/TorrServer unmapped gitlink), so P2P streaming cannot work in the fork; tell
  the owner. Next: batch 5 (#7 QuickJS, R2-R6), then batch 6 (E1, E4-E7, E9), then docs + verify -Full + zip.
- 2026-10-01 Phase 9 batch 4: #13 done, #14 and #18 partial (plan has what is left). Mistake found: piping a
  measurement script into Select-Object -First 1 stops it after its first line, so the screenshot + app-close steps
  never ran and 4 dev apps stayed open; later runs then measured an old idle instance (the "CPU 0.0 s" runs for #14
  and #18). Apps closed, home-mem.ps1 now picks the newest instance, #18 re-checked properly (home loads, screenshot).
  Never pipe these scripts into Select-Object.
- 2026-10-01 Phase 9 batch 3 DONE (8, S1-S4, S6; S5 n/a) and batch 4 #10-#12 done; per-item commits + numbers in
  docs/phase9-plan.md. Next: #13 Coil caches (App.kt AppEnvironment ImageLoader, BadgeImageLoader), then #14, #18,
  #20, #21, #22, batch 5, batch 6. Method per item: test-first (red commit A, green commit B) where testable, a
  live measurement where it shows (scratchpad scripts: home-mem.ps1 = dev app home + scroll + screenshot; cpu-run.ps1;
  drag-repro.ps1; resize-lag.ps1; packaged runs via measure.ps1 -Packaged). verify -Full at the end of each batch.
  Shell lessons: backslashes in heredocs get mangled (use the Edit tool or chr(92)); close dev apps via
  MainWindowHandle; never Remove-Item Env: in the main shell (blocked); run tests with APPDATA=testprofile in a child.
- 2026-10-01 Phase 9 batches 1-2 DONE (docs/phase9-plan.md has per-item commits + numbers). Batch 1: atomic writes
  (60/60 kills intact), downloads (2 real races reproduced red, fixed; writes <=1/10 s), geometry (1 write/drag, IO
  thread), background store writer (save 2.7 -> 0.25 ms on caller; exit flush, wipe discards), runtime pruning.
  Batch 2: dead links reach onError (404 -> "Playback failed" in 16 s), async sub-add + newest pick wins, shutdown
  without std::terminate, track lists cached (3 rebuilds/30 s), resize catch-up 297 -> 31 ms, exact absolute seeks
  (were up to 7 s early on a 10 s GOP), VSR scale fitted (4K 45.5 -> 39.6 W; trigger needs the owner with RTX on;
  deband kept per D12). verify -Full green (1574, known failures only); upstream fix lines 362 in 11 listed files.
  Lesson: run-dev scripts must close the app via the process MainWindowHandle (one run left a dev app open, which
  locked the bridge DLL).
- 2026-10-01 Q48 (owner: send the 4 upstream PRs). Upstream rules: bug issue required per PR, issues only via the web
  form (a daily bot closes unlabeled issues; API-created issues from non-collaborators get no label) => owner submits 4
  pre-filled form links, then Claude pushes the branches and opens the PRs. Reproductions (temporary tests/scripts, not
  committed; copies in the session scratchpad): #1 kill harness, 60 random hard kills of a child JVM saving a 2.1 MB
  store: 60/60 damaged before (58 partial, 2 empty), 60/60 intact with the atomic write. #2 real downloader, local server
  ~80 Mbit/s, 100 MB: 14 400 progress calls (1 400/s) before, 42 after. #3 dev build, 100 SetWindowPos moves: ~100
  rewrites of nuvio_window_state.properties before (the audit said 4 per event: wrong, only changed keys persist), 1 after;
  PR commit comment corrected (upstream-pr/window-geometry-debounce 1cfc3921). #4 mpv IPC + 3 s slow subtitle host:
  synchronous sub-add returns after 3 012 ms, playback keeps going (UI freeze follows from the EDT + mpvMutex path; not
  timed through the app menu). All 4 branches on cf185993: full desktopTest, only the 7 known failures. Worktree
  NuvioRate/wt (detached). No upstream private security channel (no SECURITY.md, private reporting off).
- 2026-10-01 Rebased onto upstream/Dev `cf185993` (34 new upstream commits, 175 files; overlap only build.gradle.kts,
  strings.xml, PlaybackSettingsPage.kt; 0 conflicts; H9 still right after the RTX section). Backup branch
  `backup/pre-rebase-2026-10-01`; fork patch lines identical before/after. verify -Full green (1556 tests, 7 known
  failures, budget 12/5/6). Live: `*rebase-240` already-at-target and `*rebase-cap144` switch 143.973 + restore 239.901,
  both 0 drops/mistimed/underruns after 5 s, 0 problems. Zip rebuilt from the rebased tip. The 4 upstream-pr/* branches
  rebased too (patches identical; backups `backup/upstream-pr-*`). Incident: a failed worktree (path too long) made the
  PR-branch rebase run in the main checkout during a verify run; verify failed, checkout restored, nothing lost, verify
  re-run green. Use short paths for worktrees (e.g. C:\wt).
- 2026-10-01 Why the custom 100/250 Hz modes are raw-only (read-only EDID decode, monitor Gigabyte MO27Q28G, registry
  DISPLAY\GBT273C\5&360e9452&0&UID4353): 2560x1440 timings listed = 59.951 (base DTD), 119.998 + 143.973 (CTA DTDs),
  239.901 + 279.961 (DisplayID type I) = exactly the DXGI list. Range limits descriptor: V 80-280 Hz but **H 510-510 kHz**
  (bytes 0e 50 19 ff ff 7e 01, both H offsets set), "range limits only" (no formula). Every listed mode runs at 89-463
  kHz, so the H range is bogus; 250 Hz needs ~371-413 kHz, 100 Hz ~150 kHz => any non-listed mode falls outside the
  range => Windows prunes it (EDS_RAWMODE only, not in DXGI) [inferred from the EDID + EnumDisplaySettings docs]. Fix
  options for the owner: EDID override (CRU) adding 250/100 as detailed timings or a sane H range; then the feature
  picks them unchanged. 24 fps at 240: already reads the display rate (already-at-target, override = current exact
  239.901) and display-resample speed-matches (1.000587); no change needed.
- 2026-10-01 Owner checklist Part 1 (packaged Nuvio RR next to the official app, zip c8b68048): "all fine in Nuvio RR"
  (owner-attested: both apps at once, settings separate, 24 fps smooth, no flicker, badge on/off, no update banner).
  **Phase 8 DONE 2026-10-01 => project done.** Still open: Q47 (blending), Q48 (upstream PRs), the 250 Hz idea.
- 2026-10-01 Owner checklist Part 2 (Phase 7): B PresentMon `*004708*-p7-pm-cap` PASS: 23.976 capped => 143.973 settled
  409 ms (before file-loaded), display-resample, vsync-ratio 6.0, after 5 s 0 drops / 1 mistimed (pause resume) / 0
  underruns; PresentMon 8937 java.exe display changes all 1 refresh apart (6.9458 ms grid, 0 off > 0.5 ms, 0 missed,
  Independent Flip); 20 s fullscreen pause = 2867.0014 refreshes (0.01 ms off grid) => no VRR at 143.973; restore
  252 ms => 239.901; 0 problems. A and C: **owner-attested** from earlier use ("it's fine"), not run as written, so no log
  evidence for HDR toggle under P4-22, sleep/resume, audio device switch, DV/HDR10+, next episode under the cap.
  Part 1 (packaged app next to the official one): not yet answered.
- 2026-10-01 Owner set the desktop to 250 Hz (custom mode, still raw-only): run `*at250-check` => Windows current
  2560x1440@250/1, DXGI list unchanged (no 250) => 25 fps `no-suitable-mode`, upstream timing. Proposed: count the
  current mode as a candidate (25/50 => exact 10/5 holds at 250, would replace Q47 blending); owner has not decided.
  Owner checklist session to run at 240 (owner). Zip rebuilt: `../dist/Nuvio-RR-1.1.26-c8b68048.zip`.
- 2026-10-01 Badge switch (owner request 2026-09-30, resumed from uncommitted tests): Settings → Playback → Display
  "Show refresh rate badge" (per-PC key `show_rate_badge`, default on, greyed while the main switch is off); native asks
  Kotlin once per player at H2 (`nativeBadgeEnabled`), `NUVIO_RR_OSD` 0/1 still overrides, measure runs off unless 1.
  Owner: string budget 3 ⇒ 5 (2 H10 lines). A `fcd08d8b` (red 8/170), B green (test diff A→B empty); verify -Full
  1516 tests, 7 known failures, budget code 12/12, strings 5/5, gradle 6/6. Live: `*badge-osd1` ⇒ `badge=on` + note
  drawn; `*badge-default` ⇒ `badge=off`, no note; 0 problems. Open: owner to try the toggle in the app (setting path
  is covered by tests only; measure runs bypass it by design).
- 2026-09-30 Audit (docs/audit-2026-09-30.md, 4 parallel reviews, ~650k): fork items done (cf7c4fbb): driver read
  starts at H2 and later videos reuse the last read (the FIRST video still waits: local files reach the hook 4 ms after
  H2); mpv on-screen rate note (screenshots at 239.90/143.97 Hz). E2 25/50 fps blending trial clean (b99ba6e6, Q47).
  4 upstream PR branches from upstream/Dev b1e00724 (upstream moved 32 commits), each: full desktopTest, only the 7
  known failures (Q48). Found: packaged test runs started from the repo loaded the repo's build DLL (dev-path fallback,
  audit #8), so the unzip-and-run extraction path is still unexercised (owner checklist).
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
- 2026-09-28 Phase 3: criteria P3-1..25 + Q11–Q13 approved; Kotlin package `...player.desktop.refreshrate` (5 files) with
  51 tests (table of all 24 state×event pairs, seeded fuzz 2×10 000). Red→green commits, lean verifier PASS, 4 small
  follow-ups fixed. No upstream change.
- 2026-09-28 Phase 4: criteria P4-1..21 + Q14–Q16 approved. Runtime subpackage `refreshrate.runtime` (controller,
  dispatcher, codec, native port, entry object; 40 new tests, red→green), native feature in display_mode_matcher.cpp
  (on_preloaded hook worker, JNI upcall, DXGI/QDC/CDS, log sink, fault knob), 4 upstream hook lines (6 total).
  36 measure runs; lean verifier round: 3 env/test-design issues to the owner (Q17–Q19), 1 logging fix. Stopped for the
  owner's checklist.
- 2026-09-28 Phase 4 gate: Q17 (no 100 Hz; owner's 250 Hz custom mode also only in EDS_RAWMODE ⇒ 25/50 fps stay at 280),
  Q18 accepted, P4-22 added on the owner's request (option B, HDR toggle ⇒ re-switch in the new HDR state), Q19 proven by
  hand (stop during settle), checklist accepted by the owner. **Phase 4 DONE.**
- 2026-09-28 Phase 5: criteria P5-1..19 + Q20 (a)/Q21/Q22 approved; 250 Hz custom mode still raw-only (Q17). Tests-first
  commits A/B (Kotlin: timing routing, frame-cap rule, ResampleHealth), C (native timing apply/revert, setTiming/stats,
  NVAPI read, faults). 20 measure runs: display-resample clean at 239.901 for every rate; two health-check bugs found by
  the runs and fixed test-first. Waiting for the verifier round and the owner's PresentMon + checklist session.
- 2026-09-28 Phase 5 closed: verifier PASS (flag 1 deadlock risk fixed `75ec3f57`), owner PresentMon runs + checklist all
  OK; next episode proven in the log. Next: Phase 6.
