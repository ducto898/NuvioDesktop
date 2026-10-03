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
At each start mpv shows a small badge top-right for 2.5 s: a sync symbol + the rate in whole Hz when synced, the rate
alone (dimmed) when not matched or when it falls back during playback. Settings → Playback → Display → "Show refresh
rate badge" (default on, per PC) turns it off from the next video; `NUVIO_RR_OSD=0`/`1` overrides the setting; measure
runs have it off unless `NUVIO_RR_OSD=1`. The NVIDIA driver read starts when the player is
created, and later videos of a session reuse the last read (audit 2026-09-30, #9/E3).

## 2. Files and hooks touched
SPEC.md §2 (hook lines, each tagged `nuvio-rr fork hook Hn`) and §3 (new files). `scripts/verify.ps1 -Full` prints
the current list and line counts against upstream. Phase 9 audit fixes (§11) also change upstream files without hook
tags; each such file is listed with its audit ids in `scripts/fork-fixes.txt` (verify allows untagged lines only there),
and each fix line carries a `nuvio-rr fork, Phase 9 <id>` comment.

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
Since 2026-10-03 the branch is public on origin, so upstream is **merged in, not rebased** (no force-push).
`scripts\sync-upstream.ps1` runs steps 1–5 (`-DryRun` = report + trial merge only; `-SkipVerify`, `-SkipLive`,
`-NoPackage`; `-Target <release commit>` to sync to a release). Sync on each upstream release
(`chore(store): publish x.y.z`). The steps by hand:
1. `git fetch upstream` and read what changed in the hook files (§5): `git diff <old base> upstream/Dev -- <file>`.
   The script also lists `scripts\fork-fixes.txt` files upstream touched: if upstream fixed the same bug, drop the
   fork's fix (fewer modified upstream files means easier merges).
2. Backup branch `backup/pre-merge-<date>`, then `git merge --no-ff upstream/Dev` on `feature/refresh-rate-matching`
   (local; never push without the owner's OK). Check that the fork's +/- lines vs upstream are unchanged. Conflicts
   only happen at the tagged hook lines and the fork-fixes files: `git grep -n "nuvio-rr fork hook"` lists all 21
   (H1–H21, SPEC §2). Keep each hook's line at the same place in the new code; the fork's own files never conflict.
3. `scripts\verify.ps1 -Full` ⇒ green with only the known upstream failures (prune
   `scripts\known-upstream-test-failures.txt` if upstream fixed some) and the hook budget met (every added upstream
   line tagged; code 12, strings 3, Gradle 6).
4. Re-verify on the hardware: §6.
5. `scripts\package-fork.ps1` (new zip) and `scripts\export-patch.ps1` (archive patch in `..\patches`, checked with
   `git apply --check` on the new base).
6. Fallback if a merge gets messy: `git merge --abort` (or `git reset --hard backup/pre-merge-<date>`), or apply
   the last archived patch on a fresh branch from `upstream/Dev`
   (`git apply --3way ..\patches\nuvio-rr-<base>-<head>.patch`) and fix the rejected hunks by the hook tags.
Update SPEC's upstream base line and PROGRESS "Repo facts" after a sync.

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
Bundled third-party: `SSimDownscaler.glsl` by igv, LGPL-3.0-or-later, unmodified (source and revision in
`composeApp/src/desktopMain/resources/licenses/SSimDownscaler.txt`).

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

## 11. Phase 9: audit fixes and desktop enhancements (2026-10-01)
Every bug, robustness, security and performance item of `docs/audit-2026-09-30.md` plus enhancements E1, E4-E9, fixed
in the fork only (no upstream PRs). Per-item status and commits: `docs/phase9-plan.md`. What a user notices:

| Area | What changed | Where / how |
|---|---|---|
| Back navigation (E4) | Esc and Alt+Left go back (dialogs and panels first, then the screen); the mouse Back button as before. Esc on the home tabs does nothing (no "Exit app?") | always on |
| Keyboard (E9) | Arrow keys move focus (cards, rows, buttons, sidebar) with a white focus ring (only while using the keyboard: a mouse click or wheel hides it); Enter opens; Ctrl+F opens Search with the field focused | always on |
| Shelves (E6) | Hovering a horizontal shelf shows left/right arrows; a click scrolls most of a screen width | home and catalog shelves |
| Reduce motion (E5) | No hero auto-advance, no animated GIF cards, no image fade-in | Settings → General → MOTION → Reduce motion (per PC, default off) |
| Scrub preview (E7) | Dragging the seek bar shows the frame under it (keyframe seeks, ~7 per second); release seeks exactly | Windows player |
| Audio (E1) | Channels (automatic / stereo / 5.1 / 7.1), passthrough of AC3, E-AC3, DTS, DTS-HD, TrueHD to a receiver (WASAPI exclusive), output device | Settings → Playback → AUDIO OUTPUT (Windows, per PC, from the next video) |
| Window (E9) | A saved window position on a monitor that is gone (or above the screen top) opens centred on the main screen | always on |
| Data safety, playback, security, performance | atomic preference writes, playback errors shown, async subtitles, exact seeks, encrypted tokens (DPAPI), plugin network sandbox, bounded image caches, faster startup, ... | see the plan |

Phase 9 limits (in addition to §10):
- **White window in the dev build (2026-10-01):** since about 11:40 that day the dev profile's renderer setting
  (`open_gl_enabled=true`, Settings → Advanced) gives an all-white window on this PC; Direct3D and the software
  renderer draw normally, logs and app state are fine. Not caused by a fork change (it stays with the suspect
  change reverted); likely a driver/GPU state. It had cleared by 17:08 the same day without a reboot (OpenGL draws
  normally again). If it comes back: Settings → Advanced → turn OpenGL off, or start with `SKIKO_RENDER_API=DIRECT3D`.
- **Passthrough** needs a device that accepts the bitstream in exclusive mode (HDMI to an AV receiver or TV, S/PDIF).
  Before each video the bridge asks Windows which formats the chosen device takes and sends only those (log line
  `audio passthrough probe mask=<n>`: 1 AC3, 2 E-AC3, 4 DTS, 8 DTS-HD, 16 TrueHD); other tracks are decoded, and a
  device that takes none plays exactly as with the switch off. (Without this check mpv's fallback for a refused
  bitstream left streamed movies stuck on the first frame: owner report, fixed 2026-10-01.) Exclusive mode mutes other apps'
  sound on that device while a video plays. In shared mode Windows keeps the device's own mix format (here 7.1,
  96 kHz); the channel setting then sets mpv's downmix.
- **Scrub preview** lands on keyframes (a 10 s keyframe interval shows frames up to 10 s early while dragging); the
  release is exact. Checked live on the native path; the drag in the player UI was not live-tested (no local stream).
- **Keyboard:** Backspace does not go back (it would from an empty text field). Inside the player the native
  window keeps its own keys.
- **Reduce motion:** GIF cards and the crossfade were not checked live (no GIF collection in the dev profile).
- **P2P/torrent streams:** fork builds DO bundle TorrServer: the prebuilt `TorrServer.exe` comes from Git LFS
  (`composeApp/src/desktopMain/resources/torrserver/windows-amd64/`, SHA-256 equal to upstream's release check) and
  sits inside the app jar of the zip. Only the unmapped `vendor/TorrServer` source submodule is missing, and the build
  does not need it. (An earlier note here said P2P could not work; that was wrong.) A torrent stream was not played live.

## 12. Video quality (2026-10-01)
Settings → Playback → VIDEO QUALITY (Windows, per PC, from the next video). The scaler defaults send no options, so
the player keeps its own scalers (spline36 up, mpv's default dscale = hermite, read from the running player). The HDR
default passes each video's own HDR10 metadata to the monitor (below).

| Setting | Choice | mpv options set at hook H2 (after the bridge's own) |
|---|---|---|
| Scaling quality | Standard | none |
| | High | `scale`/`cscale=ewa_lanczossharp`, `hdr-peak-percentile=99.995`, `hdr-contrast-recovery=0.30`, `allow-delayed-peak-detect=no` |
| Downscaler | Player default | none |
| | Catmull-Rom | `dscale=catmull_rom` |
| | SSimDownscaler | `glsl-shaders=<cache>\shaders\<version>\SSimDownscaler.glsl`, `dscale=mitchell`, `linear-downscaling=no` |
| HDR | Pass through to the monitor (default) | `target-colorspace-hint-mode=source` |
| | Monitor peak (EDID) | `target-peak=<EDID desired max luminance>` (1532 on the MO27Q28G); none if the EDID has no plausible value |
| | Windows HDR calibration | none (mpv's default) |

- The shader ships as a resource and is copied into the app cache (DesktopCache); no usable file = player default.
  It only runs when the video is larger than the screen (its passes are conditional), e.g. 4K on the 1440p monitor.
- HDR (owner compared with MPC + madVR, 2026-10-01): the output is always HDR10 (R10G10B10A2, PQ/BT.2020 swapchain).
  mpv's default `target-colorspace-hint-mode=target` takes the display's peak and primaries from the active Windows
  HDR calibration profile and signals them as the HDR metadata. On this PC the profiles said 8000, later 3500 nits
  (the calibration app lands high when the monitor tone-maps), while the EDID says 1532 (madVR uses the EDID). Hence
  the EDID default: the bridge reads the EDID of the player's monitor at H2 (QueryDisplayConfig target name ->
  `Enum\DISPLAY\<id>\<instance>\Device Parameters\EDID`), Kotlin parses the CTA-861 HDR static metadata block.
  Pass through = madVR's "passthrough HDR to display": the video's metadata (e.g. 1000 / MaxCLL 203, P3) goes to the
  monitor unchanged; SDR is then handed to Windows as SDR (the Windows SDR brightness slider applies). The HIGH
  quality HDR options (peak percentile, contrast recovery) only matter when mpv tone-maps (video brighter than the
  target peak).
- Metadata test (`scripts/gen-metadata-clips.py`: identical pixels, HDR10 metadata 10000 vs 400 nits): the owner
  saw the two clips differ in Nuvio RR with Pass through, so the player's metadata reaches this monitor through
  Windows (the player presents as "Hardware Composed: Independent Flip") and the monitor tone-maps by it. Hence Pass
  through became the default (owner, 2026-10-01). A profile that already stored an HDR choice keeps it.
- Log: `video edid bytes=<n>` (0 = not found) before the option lines.
- Cost measured on this PC (4K HDR, fullscreen, 240 Hz, display-resample, 60 s): Standard 46.1 W / 14 % GPU,
  High + SSimDownscaler 51.2 W / 19 %, both 0 drops / 0 mistimed. The look was not judged by the owner yet.
- Log: `video option <name>=<value> rc=<n>` lines in refresh-rate.log, next to the audio options.

### Subtitle font (2026-10-01)
Settings → Playback → VIDEO QUALITY → Subtitle font (Windows, per PC, store `nuvio_subtitle_font`, from the next video).
Plain-text subtitles only (SRT, WebVTT); styled ASS/SSA keep their own fonts. Default = the player's (mpv's
`sans-serif`, which libass's DirectWrite provider maps to Arial) and sends no option; a choice sends `sub-font=<name>`
with the video options at H2. The dialog offers the installed fonts subtitle enthusiasts recommend: Netflix Sans
(Regular, plus `NetflixSans-Medium` / `NetflixSans-Bold` by PostScript name, because its Bold file's family is
"Netflix Sans " with a stray space and Medium is its own family), Gandhi Sans, Segoe UI, Trebuchet MS, Verdana,
Tahoma, Calibri, Arial, plus the current choice. Fonts are never bundled: Netflix Sans is proprietary and was
installed by the owner for their own Windows account only (`%LOCALAPPDATA%\Microsoft\Windows\Fonts`), which libass
and Java both see. With a Medium/Bold face chosen, keep the app's subtitle Bold switch off (it would embolden again).

Optimised defaults (owner, 2026-10-01): the default is now **Automatic** = the best installed of NetflixSans-Medium,
Gandhi Sans, Segoe UI Semibold (else Arial). Every player also gets `sub-border-size=2.0`, `sub-shadow-offset=1.0`,
`sub-shadow-color=#80000000`, `sub-blur=0.3` (plain-text only). Size: upstream's libass mode set both
`sub-font-size=size` and `sub-scale=size/54`, so plain-text size went with the square of the slider; the bridge now
sets a fixed `sub-font-size=54` in that mode (player_bridge.cpp, fork fix "SUB"), and the desktop default size is 15
(`SubtitleAudioModels.kt`, fork fix "SUB"). Kept from mpv: `sub-hdr-peak=sdr` (203 nits), `blend-subtitles=no`.
