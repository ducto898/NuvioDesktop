# FORK.md — ducto898/NuvioDesktop (refresh-rate matching fork)

Private fork of [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) (GPL-3.0).
It adds an opt-in "Match display refresh rate" feature to the Windows mpv player.
Behaviour and acceptance criteria: [`SPEC.md`](SPEC.md). Status: [`PROGRESS.md`](PROGRESS.md).

**Get the app:** `scripts\package-fork.ps1` builds the portable app folder **"Nuvio RR"** and zips it to
`..\dist\Nuvio-RR-<version>-<commit>.zip`. Unzip anywhere, run `Nuvio RR.exe`. It runs next to the official Nuvio with
its own profile (§7). First time: `scripts\import-profile.ps1` copies the official profile into it (once).

## 1. What the patch does
See SPEC.md §1. **Turn it on:** Settings → Playback → Display → "Match display refresh
rate" (Windows only, default off, stored per PC in `nuvio_refresh_rate.properties`, not synced to other devices or
profiles; applies from the next video). Override for dev/measure runs: `NUVIO_RR_ENABLE=1` forces it on
(`scripts/run-dev.ps1 -Feature`, `measure.ps1 -Feature`), `NUVIO_RR_ENABLE=0` forces it off (measure.ps1's default
for baseline runs), unset = the setting. When on, the player switches the monitor to the best integer multiple of the video fps before
mpv creates its video output, verifies it, and restores it when the player screen goes away, the window closes or the
JVM exits (Windows itself reverts it on a crash or kill). Since Phase 5 a switched playback also gets display-synced
mpv timing (`video-sync=display-resample`, `interpolation=no`, `display-fps-override=<exact rate>`), set before the
video starts. If the session ends mid-playback (the monitor lost the mode twice, the window moved) or display sync is
clearly broken (health check), mpv goes back to its own timing while it keeps playing. Log:
`%LOCALAPPDATA%\Nuvio RR\Cache\refresh-rate.log` in the packaged fork (`devprofile\Local\Nuvio\Cache\` in dev runs).
Driver requirements: §9. Known limits: §10. The fork also has its own app identity (§7).
At each start mpv shows a 4 s note top-left, e.g. "Display 239.90 Hz · synced to the video" or "Display 240.00 Hz ·
not matched", and "Display sync off · video timing" if it falls back during playback (`NUVIO_RR_OSD=0` turns the
notes off; measure runs have them off unless `NUVIO_RR_OSD=1`). The NVIDIA driver read starts when the player is
created, and later videos of a session reuse the last read (audit 2026-09-30, #9/E3).

## 2. Files and hooks touched
SPEC.md §2 (hook lines, each tagged `nuvio-rr fork hook Hn`) and §3 (new files). `scripts/verify.ps1 -Full` prints
the current list and line counts against upstream.

## 3. Toolchain (Windows 11, verified 2026-09-27)
Nothing below needs admin rights except Visual Studio, which was already installed.

| Tool | Version | Where | How |
|---|---|---|---|
| JDK | Temurin 17 (17.0.20.1) — same major as upstream CI | `%USERPROFILE%\.jdks\jdk-17.0.20.1+1` | Portable zip: `https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse`, extracted into `%USERPROFILE%\.jdks`. The scripts find it automatically; `gradlew` alone needs `JAVA_HOME` set. |
| MSVC C++ | Visual Studio Community 2026 (18.9.3), MSVC 14.51, "Desktop development with C++" | `C:\Program Files\Microsoft Visual Studio\18\Community` | Gradle finds `vcvars64.bat` via `vswhere`. Override: `-Pnuvio.windows.vcvars.path=...`. The harmless message `'vswhere.exe' is not recognized` is printed during the bridge build. |
| WebView2 SDK | NuGet `Microsoft.Web.WebView2` **1.0.4078.44** (upstream CI pin) | `%USERPROFILE%\.nuget\packages\microsoft.web.webview2\1.0.4078.44` | Download `https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/1.0.4078.44`, unzip there (it's a zip). Gradle picks the newest folder there automatically; override: `-Pnuvio.webview2.dir=...`. |
| libmpv | bundled, `libmpv-2.dll` (Git LFS, ~115 MB) | `composeApp/src/desktopMain/native/windows/runtime/` | Comes with `git clone` (needs Git LFS). Version: mpv v0.40.0-465-gf6c116491, libplacebo v7.357.0, FFmpeg N-121828 (read from the DLL, research 03). |
| Gradle | 9.4.1 | wrapper | `gradlew.bat` downloads it. |
| ffmpeg | 9.0 (winget, already installed) | PATH | Only for generating test clips (Phase 2). |

### Clone
```powershell
git clone --recurse-submodules https://github.com/ducto898/NuvioDesktop.git
cd NuvioDesktop
git remote add upstream https://github.com/NuvioMedia/NuvioDesktop.git
git fetch upstream
```
Upstream's default branch is **`Dev`**. `git submodule status` errors on `libass-android`
(an unmapped gitlink upstream). That's harmless on Windows; only `MPVKit` (iOS/macOS) is a real
submodule.

### local.properties (required, git-ignored)
Upstream's build fails if `local.properties` doesn't exist in the repo root. The file can be
empty; ours holds commented, blank keys. Keys (each can instead be an environment variable of
the same name):
- `NUVIO_SUPABASE_URL`, `NUVIO_SUPABASE_ANON_KEY`, `NUVIO_SUPABASE_FALLBACK_URL`: accounts/sync
  (upstream release CI requires them).
- `TMDB_API_KEY`: metadata.
- `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`: Trakt (optional).
- `SENTRY_DSN`, `SENTRY_DESKTOP_DSN`: **leave blank in this fork**. A blank DSN means Sentry
  never starts (`SentryInitializer.kt`).

Never commit this file.

### Build, test, run
```powershell
pwsh -File scripts/verify.ps1 -Fast   # incremental compile + unit tests
pwsh -File scripts/verify.ps1 -Full   # clean build + all tests + diff report vs upstream
pwsh -File scripts/run-dev.ps1        # run the dev build with an ISOLATED profile
```
- **Always launch with `scripts/run-dev.ps1`, not `gradlew :composeApp:run`.** Upstream stores
  settings and watch progress in `%APPDATA%\Nuvio`, the same folder the official app uses.
  `run-dev.ps1` redirects the profile to `..\devprofile` (outside the repo). To start from a
  copy of the official profile, copy `%APPDATA%\Nuvio` to `..\devprofile\Roaming\Nuvio` while
  both apps are closed.
- Upstream's `buildWindowsPlayerBridge` only builds when `player_bridge.dll` is **missing**.
  `verify.ps1 -Fast` deletes the DLL when a native source is newer than it; when building by
  hand, delete `composeApp/build/native/windows/player_bridge.dll` after native edits.
- Upstream's `.gitignore` ignores `Docs` (matches `docs/` on Windows) and `scripts/*`. This
  fork's files there are force-added (`git add -f`), and upstream's `.gitignore` is left
  alone. **New files in `docs/` or `scripts/` need `git add -f`.**
- Known upstream test failures on Windows (6, deterministic) are listed in
  `scripts/known-upstream-test-failures.txt`. verify reports them but fails only on NEW
  failures. Prune the list after upstream updates.

## 4. Updating to a new upstream release (runbook)
1. `git fetch upstream` and read what changed in the hook files (§5): `git diff <old base> upstream/Dev -- <file>`.
2. `git rebase upstream/Dev` on `feature/refresh-rate-matching` (local; never push without the owner's OK). Conflicts
   only happen at the tagged hook lines: `git grep -n "nuvio-rr fork hook"` lists all 21 (H1–H21, SPEC §2). Keep each
   hook's line at the same place in the new code; the fork's own files never conflict.
3. `scripts\verify.ps1 -Full` ⇒ green with only the known upstream failures (prune
   `scripts\known-upstream-test-failures.txt` if upstream fixed some) and the hook budget met (every added upstream
   line tagged; code 12, strings 3, Gradle 6).
4. Re-verify on the hardware: §6.
5. `scripts\package-fork.ps1` (new zip) and `scripts\export-patch.ps1` (archive patch in `..\patches`, checked with
   `git apply --check` on the new base).
6. Fallback if a rebase gets messy: apply the last archived patch on a fresh branch from `upstream/Dev`
   (`git apply --3way ..\patches\nuvio-rr-<base>-<head>.patch`) and fix the rejected hunks by the hook tags.
Update SPEC's upstream base line and PROGRESS "Repo facts" after a rebase.

## 5. Known conflict hot spots
- `PlaybackSettingsPage.kt`, the `if (isWindows)` "NVIDIA RTX Video" section: H9 sits on the line right after it.
  If upstream moves or removes that section, keep H9 at the same level (inside `PlaybackSettingsSection`), below RTX.
- `values/strings.xml` around `settings_playback_nvidia_rtx_super_resolution_desc` (H10, 3 lines).
- `player_bridge.cpp` `startMpv()` / `drainMpvEvents()` / `shutdown()` (H2, H4, H5) and `PlayerEngine.desktop.kt`
  `DisposableEffect(host)` (H6), `Main.kt` `onCloseRequest` (H8).
- Phase 8 identity: `DesktopStorage.kt` `resolveAppDataDir()`/`resolveCacheDir()` Windows branches (H11, H12),
  `AppFeaturePolicy.desktop.kt` `inAppUpdaterEnabled` (H13), `player_bridge.cpp` `webViewUserDataDirectory()` (H14),
  `composeApp/build.gradle.kts`: after `windowsMsiUpgradeUuid` (H15), after `runtimeConfigValue` (H16), the
  `application.jvmArgs` list (H17), `nativeDistributions.packageName` (H18), `windows { upgradeUuid, menuGroup }`
  (H19, H20), `WindowsAppShortcutIconUpdater.kt` `update()` (H21). If upstream renames folders or moves the updater
  flag, move the hook with it.

## 6. Verifying after an update
After an upstream rebase, an NVIDIA driver update or a Windows feature update (Phase 7 recipe, ~2 h unattended):
1. `scripts\verify.ps1 -Full` ⇒ green with only the known upstream failures; hook budget met (every added upstream line tagged; code 12, strings 3, Gradle 6; §4).
2. Check the `driver` line of one feature run (§9): `frl=off`, `power=1` (max performance).
3. `scripts\rr-tools\p7-matrix.ps1 -Set matrix,off,switch,lifecycle,faults,bad` (clips: `scripts\gen-testclips.ps1`;
   the switch/lifecycle/fault sets use the measure-only cap `-MaxHz 144`, because at the 240 Hz desktop default
   24–60 fps are already at their target and nothing would switch). Soaks: `-Set soak` / `soak60` after
   `scripts\gen-soakclips.ps1`.
4. `python scripts\rr-tools\p7-collect.py judge <run folders>` ⇒ PASS, except the expected ones: the `badsync`
   runs must show `resample-unhealthy` (the health fallback works), kill runs have no restore call.
5. `python scripts\rr-tools\p7-collect.py rateoff measurements` and `python scripts\rr-tools\health-replay.py
   measurements`: no false fallback in healthy runs; a recovered `rate-off` stretch longer than 7 samples means
   `ResampleHealth.RATE_ERROR_SAMPLES` (9) needs re-checking.
Evidence of the Phase 7 baseline: `measurements\phase7-evidence.txt`.

## 7. Updater, crash reporting, app identity
- **Identity (Q39):** `fork-identity.properties` (`name=Nuvio RR`) is read by the build (H15). The packaged app gets
  `-Dnuvio.fork.name=Nuvio RR` (H17) ⇒ `ForkIdentity.appDirName` ⇒ data `%APPDATA%\Nuvio RR` (H11), cache
  `%LOCALAPPDATA%\Nuvio RR\Cache` (H12, also where the bundled libmpv/bridge DLLs are unpacked), WebView2
  `%LOCALAPPDATA%\Nuvio RR\WebView2` (H14, fixes the D6 leak). Package/exe name, start-menu group and MSI upgrade UUID
  follow the name (H18–H20). The window title stays "Nuvio" (it is hard-coded upstream; changing it would exceed the
  owner's hook budget, Q42). An unusable name (path characters, `.`, > 64 chars) falls back to upstream's "Nuvio".
- **Build note:** H15 reads `fork-identity.properties` while Gradle configures the build. The fork scripts pass
  `--no-configuration-cache`; after editing that file, build with the scripts (or pass that flag), or a bare `gradlew`
  may reuse the old name from the configuration cache.
- **Feature log:** the packaged fork writes `%LOCALAPPDATA%\Nuvio RR\Cache\refresh-rate.log` (the native log folder
  follows the same name since the Phase 8 verifier round; before that it went to the official `...\Nuvio\Cache`).
- **Dev runs:** `scripts\run-dev.ps1` sets `NUVIO_FORK_IDENTITY=off`, so the dev profile keeps upstream folder names
  (`devprofile\Roaming\Nuvio`). `measure.ps1 -Packaged` runs the built app with a separate `..\packprofile`.
- **Profile (Q41):** `scripts\import-profile.ps1` copies `%APPDATA%\Nuvio` to `%APPDATA%\Nuvio RR` once (refuses if
  the fork profile has files or either app is running; skips `updates\`, `*.part` and the sync client id).
- **Updater:** off in the fork (H13, `ForkIdentity.updaterEnabled`): the in-app updater would install the official
  build. Update the fork with §4 + `package-fork.ps1`.
- **Crash reporting:** the fork build stops with an error if `SENTRY_DESKTOP_DSN` is set (H16); with it blank (the
  default in `local.properties`) Sentry is inert.
- **Distribution (Q40):** portable app folder via `createDistributable` (no installer, no WiX, no admin);
  `scripts\package-fork.ps1` builds, checks the launcher option and the bundled DLLs, and zips.

## 8. Licence
GPL-3.0 (inherited). A private fork is fine. If binaries are ever shared, the corresponding
source must be available.

## 9. NVIDIA driver settings (Phase 2b/5)
Display-synced timing depends on two NVIDIA settings. Nuvio only **reads** them (it never writes driver profiles); each
playback start logs one line, e.g. `hook p1 driver frl=off(global) power=1(global) exe=java.exe app_profile=no ms=156`.

| Setting (NVIDIA App / Control Panel → Manage 3D settings) | Needed | Why | If not set |
|---|---|---|---|
| **Max Frame Rate** | **Off**, or at least 1.05 × the target rate (≥ 252 fps for 240 Hz) | a lower cap throttles mpv's presents and display-resample collapses to ≈ 6 Hz (Phase 2b) | the feature does **not switch** (`selection=no-switch reason=frame-cap`); playback stays at 280 Hz with upstream timing |
| **Power management mode** | **Prefer maximum performance** for 4K HDR at full quality | at "Normal" the GPU stays at a low clock and 4K HDR misses ≈ 0.8 % of refreshes (Phase 2b, D12); 1080p is fine either way | not checked; the health fallback catches only clear breakage |

- Global or per app: the log's `(global)` / `(app)` / `(default)` says where the value came from. A per-app profile
  needs the app's own exe (Phase 8); dev runs are `java.exe`. The owner keeps both set globally (2026-09-28).
- **OLED flicker on Nuvio's home/settings screens (Q29, 2026-09-28):** with G-SYNC on, the UI (which draws only when
  something changes) makes the refresh rate follow scrolling/pointing ⇒ visible gamma flicker on OLED. Not caused by
  this patch (upstream + driver). Fix, owner-confirmed: NVIDIA Control Panel → Manage 3D settings → Program settings →
  the app's exe → **Monitor Technology = Fixed Refresh**. Packaged fork: `<unzip folder>\Nuvio RR\Nuvio RR.exe`
  (also set Max Frame Rate = Off and Power management = Prefer maximum performance there if they are not global);
  the log's driver line then reads `exe=Nuvio RR.exe app_profile=yes`. Dev runs:
  `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe` (set by the owner 2026-09-28). Official
  app: its own exe needs the same entry.
  A JDK update changes the java.exe path and drops the dev entry; the packaged fork's entry keeps working while the
  unzip folder stays the same.
- `frl=unknown` (no NVIDIA GPU, nvapi missing) ⇒ no cap is assumed; the health fallback is then the only guard.
- Cost of max performance: GPU power in PROGRESS.md "Measurements" (Phase 5).
- After **every NVIDIA driver update**: check the `driver` log line (an update or "restore defaults" can bring a cap
  back) and re-run `scripts/measure.ps1 -Feature` (§6).

## 10. Known limits (Phase 7 review, owner-accepted Q37, 2026-09-29)
- **1000/1001 twins on TVs:** the switch asks Windows for whole Hz (`ChangeDisplaySettingsExW` takes an integer), so on a
  display that lists both 119.88 and 120 Hz a 23.976 video may land on 120.000 ⇒ verify-mismatch ⇒ restore and upstream
  timing (fail-safe, plays normally). Not reachable on the MO27Q28G (one rate per integer). Fix idea: pick the DEVMODE
  from `EnumDisplaySettings` or use `SetDisplayConfig` with the exact rational.
- **Monitor move of an already-at-target player:** no switch happened, so the watcher does not follow the window; a
  player dragged to a monitor with another rate keeps the old `display-fps-override` until the health check falls back
  (≈ 20 s). Only relevant with two monitors.
- **Player shutdown racing the hook:** if the player shuts down in the few microseconds between the hook arriving and
  the worker taking it, the hook is released by `mpv_terminate_destroy` instead of by us (no hang, no crash).
- **`NewString` out of memory** in one JNI upcall is not checked before the call (the JVM would already be failing).
- **App icon setting (Q44, fixed):** upstream's Settings → App icon would write `%LOCALAPPDATA%\Nuvio\icons` and repoint
  the **official** `Nuvio.lnk` shortcuts. In the fork it does nothing (hook H21); the portable fork has no shortcuts of
  its own, so there is nothing to update.
- Rates are "the same" within 100 ppm (F1): DXGI rounds some modes (this monitor's 120 Hz is listed 12000/100, runs
  119998/1000). Distinct real modes are ≥ 188 ppm apart (143.973 vs 144).
