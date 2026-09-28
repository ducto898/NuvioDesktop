# PROGRESS — refresh-rate matching fork

The only memory between phases. Read it at the start of every phase; update it at the end.

## Current state
- **Phase:** 2 (Measure only) **IN PROGRESS 2026-09-28**. Rebased onto `upstream/Dev` `fe92d414` (local, backup
  branch `backup/pre-rebase-phase2`). Phase 2 acceptance criteria P2-0..P2-19 written in SPEC.md.
- **Next:** owner approval of the Phase 2 criteria ("before code" gate), then implement: sampler (H1+H4),
  gen-testclips.ps1, measure.ps1, rr-tools + kill test A–J with the owner, baseline runs.
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
- D6: Dev runs use an isolated profile (`scripts/run-dev.ps1`) until the Phase 8 app-identity work.

## Incidents
- 2026-09-27 23:52: the first `gradlew :composeApp:run` used the OFFICIAL profile for ~1 min before
  being stopped. It wrote `nuvio_updater.properties` (content: `update_channel=all`),
  `nuvio_meta_screen_settings.properties` and `nuvio_continue_watching_enrichment.properties`.
  Watch progress was untouched. Backup taken right after: `NuvioRate\profile-backup-2026-09-27`.

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
- Q7 PresentMon (portable, `NuvioRate	ools`): **OK**.
- Q8 highest multiple: **yes, if no downside** (smoother UI). Known potential downside: GPU power at 240 presents/s
  under display-resample (esp. with RTX VSR) ⇒ Phase 2 measures GPU power at 240 vs 120 Hz; revisit only if material.

## Owner answers (Phase 2 gate, 2026-09-28)
- Phase 2 criteria P2-0..P2-19 **approved**, incl. P2-5 (measure-only in-app switch knob) and P2-12 (kill-test tools committed).
- `HomeHeroSectionTest` (upstream 660→640 dp mismatch) added to the known-upstream-failure baseline: **approved**.
- PresentMon portable download into `NuvioRate	ools` + UAC prompt: **approved**.

## Open questions for owner
(none)

## Effort / token budget (rough)
| Phase | Estimate | Actual |
|---|---|---|
| 0 Setup | ~300k | ~260k (main ≈140k + existing-work subagent ≈123k) |
| 1 Research | ~600k (subagents) | ~1.09M (5 subagents ≈ 980k: API 183k, mpv 181k, prior art 267k, VRR 150k, codebase 198k; main ≈ 110k) — ~80% over |
| 2 Measure | ~400k (incl. kill test A–J) | |
| 3 Logic (TDD) | ~250k | |
| 4 Native switching | ~500k | |
| 5 mpv timing / OLED | ~400k | |
| 6 Settings/JNI | ~200k | |
| 7 Matrix + review | ~500k | |
| 8 Upkeep | ~250k | |

## Measurements
- 2026-09-28 (read-only enumeration, 02-mode-enumeration.md): 2560x1440 modes 279.961 (current+registry),
  239.901, 143.973, 119.998, 100.000, 59.951 Hz; HDR on, 10 bpc. No 1000/1001 timings.
- Baseline playback numbers: Phase 2.
- Note for Phase 2: the app's stdout contains NO player/mpv lines. Find where the native bridge and mpv
  log (stderr? a log file? the `log-file` mpv option?) before building measure.ps1. run-dev.ps1 captures stdout only.

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
