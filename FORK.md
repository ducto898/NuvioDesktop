# FORK.md — ducto898/NuvioDesktop (refresh-rate matching fork)

Private fork of [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) (GPL-3.0).
It adds an opt-in "Match display refresh rate" feature to the Windows mpv player.
Behaviour and acceptance criteria: [`SPEC.md`](SPEC.md). Status: [`PROGRESS.md`](PROGRESS.md).

> Sections marked _TBD_ get filled in as phases complete (the files and hooks touched, the
> update runbook, conflict hot spots, updater, app identity).

## 1. What the patch does
_Being built (Phases 3–6)._ See SPEC.md §1. State after Phase 4: with `NUVIO_RR_ENABLE=1` (dev knob;
`scripts/run-dev.ps1 -Feature`) the player switches the monitor to the best integer multiple of the video fps before
mpv creates its video output, verifies it, and restores it when the player screen goes away, the window closes or the
JVM exits (Windows itself reverts it on a crash or kill). mpv timing is not changed yet (Phase 5); there is no settings
toggle yet (Phase 6). Log: `%LOCALAPPDATA%\Nuvio\Cache\refresh-rate.log` (dev profile in dev runs).

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
| libmpv | bundled, `libmpv-2.dll` (Git LFS, ~115 MB) | `composeApp/src/desktopMain/native/windows/runtime/` | Comes with `git clone` (needs Git LFS). Version: _TBD (Phase 1)_. |
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
_TBD (Phase 8)._

## 5. Known conflict hot spots
_TBD._

## 6. Verifying after an update
_TBD._ Includes: re-run `scripts/measure.ps1` after **every NVIDIA driver update**, not only
after Nuvio updates.

## 7. Updater, crash reporting, app identity
_TBD (Phase 8)._ Crash reporting is already off in local builds (blank `SENTRY_DESKTOP_DSN`).

## 8. Licence
GPL-3.0 (inherited). A private fork is fine. If binaries are ever shared, the corresponding
source must be available.
