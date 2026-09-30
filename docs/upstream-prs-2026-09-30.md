# Upstream PR drafts (audit Q46 b) — NOT sent

Four fixes from `docs/audit-2026-09-30.md`, each on a local branch from `upstream/Dev` @ `b1e00724` (fetched
2026-09-30), one commit each, author ducto898 (GitHub no-reply). **Nothing is pushed and no issue is opened**: upstream's
CONTRIBUTING requires a linked bug issue per PR, and pushing needs the owner's OK (Q2). Each branch: full upstream
`desktopTest` run with APPDATA/LOCALAPPDATA redirected — 1386–1389 tests, only the 7 failures that already fail on
upstream `Dev` on Windows (CollectionCardRemoteImage ×3, HomeHeroSection ×1, LinuxUpdateInstall ×3), none new.

To send one later: open the issue below, then `git push origin upstream-pr/<name>` and open the PR against
`NuvioMedia/NuvioDesktop:Dev` with the body below (fill in the issue number).

---

## 1. `upstream-pr/atomic-desktop-storage` — `3e139bbd` fix(desktop): write preference files atomically

**Issue draft — "Desktop: a crash during a settings write can wipe watch progress / logins"**
`DesktopStorage.Store.persist()` (`desktopMain/.../core/storage/DesktopStorage.kt`) opens the `.properties` file with
`Files.newOutputStream` (truncate) and writes in place. If the app is killed, crashes or loses power during that write,
the file is left partial or empty; `ensureLoaded()` ignores load errors, so the next start sees an empty store and the
next write makes it permanent. Affects every store (watch progress, Trakt/debrid tokens, addons, settings).
Repro: start a write of a large store (e.g. watch progress) and kill the process mid-write (Task Manager) → next start
has the store empty/partial.

**PR body**
- Summary: write a sibling `.tmp`, `force()` it, `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`; if the move fails (file
  held open), fall back to the previous direct write.
- PR type: [x] Reproducible bug fix. Desktop scope: desktop shared code (all desktop platforms).
- UI/behavior impact: [x] No UI change, [x] Behavior changed only to fix a documented bug.
- Tests: `DesktopStorageTest` +2 (whole-file replace with no temp left; a stale `.tmp` from a crash never replaces the
  store). Crash safety itself comes from the atomic move; there is no way to inject a crash mid-write in a unit test.

## 2. `upstream-pr/download-progress-throttle` — `ca601034` fix(downloads): report desktop download progress at most 4 times a second

**Issue draft — "Desktop: an active download rewrites the downloads store thousands of times per second"**
`DownloadsPlatformDownloader.desktop.kt` calls `onProgress` after every 8 KB read; `DownloadsRepository` publishes the
state (collected by `MainAppContent`) and `persist()`s the whole item list into `nuvio_downloads.properties` on each
call. At 100 Mbit/s that is ~1500 full file rewrites + state emissions per second. Repro: download a large file, watch
`%APPDATA%\Nuvio\nuvio_downloads.properties` modification time / disk activity.

**PR body**
- Summary: `DownloadProgressThrottle` (250 ms) in the desktop downloader + one report after the last read.
- PR type: [x] Reproducible bug fix (performance). Desktop scope: desktop downloader (all desktop platforms).
- UI/behavior impact: [x] No UI change (progress still updates 4×/s, final size exact).
- Tests: `DownloadProgressThrottleTest` (3).

## 3. `upstream-pr/window-geometry-debounce` — `91b66b03` fix(desktop): save window geometry once the window stops moving

**Issue draft — "Desktop: dragging/resizing the window rewrites the window-mode store on every event (UI thread)"**
`Main.kt` collects `snapshotFlow { Triple(placement, position, size) }` and calls `saveWindowedGeometry` (4 `putFloat`
= 4 synchronous file rewrites) plus `saveWasMaximized` for every event, on the UI thread. Repro: drag the window and
watch `nuvio_window_mode.properties` being rewritten continuously; stutter while dragging on slow disks.

**PR body**
- Summary: `collectLatest` + `delay(500)`: save once the window settles.
- PR type: [x] Reproducible bug fix. Desktop scope: desktop shared (Main.kt).
- UI/behavior impact: [x] No UI change. Trade-off: closing the app within 0.5 s of the last drag keeps the previous
  geometry.
- Tests: none (window/compose runtime); compiles, full suite unchanged.

## 4. `upstream-pr/async-subtitle-add` — `67122a1f` fix(windows): add remote subtitles without blocking the UI thread

**Issue draft — "Windows: choosing an addon subtitle freezes the window while it downloads"**
`NativePlayerController.setSubtitleUri` runs on the UI thread; the bridge's `addSubtitleUrl` runs a synchronous
`sub-add <url> select` under `mpvMutex`. mpv downloads the subtitle inside that call, so the UI thread, the 2 Hz
snapshot and the controls sync all wait (0.2–2 s typical, longer on slow hosts). Repro: pick an addon subtitle from a
slow host while playing; the window stops responding until it loads.

**PR body**
- Summary: `mpv_command_async` for `sub-add` (symbol loaded with the others; the reply event is ignored like others).
- PR type: [x] Reproducible bug fix. Desktop scope: Windows (native bridge); macOS/Linux bridges have their own code
  (not changed; check if they have the same pattern).
- UI/behavior impact: [x] No UI change.
- Tests: native; bridge builds; full suite unchanged. **Not exercised live yet** (needs an addon subtitle in the real UI).
