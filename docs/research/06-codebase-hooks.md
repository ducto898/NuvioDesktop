# 06 — Codebase hook map for "Match display refresh rate"

Base: upstream `Dev` @ `083921cf` (2026-09-27), fork branch `feature/refresh-rate-matching`.
All line numbers were re-read from the working tree for this document. Anything I inferred rather than read is marked **[inferred]**.

Paths are shortened:
- `PB` = `composeApp/src/desktopMain/native/windows/player_bridge.cpp` (2595 lines)
- `NPB` = `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerBridge.kt` (348)
- `NPC` = `.../player/desktop/NativePlayerController.kt` (1839)
- `PED` = `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/PlayerEngine.desktop.kt`
- `PSR` = `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt`
- `GB` = `composeApp/build.gradle.kts`

---

## 0. Key facts for the design (short version)

| # | Fact | Evidence |
|---|---|---|
| F1 | **The mpv event loop ignores every event except SHUTDOWN.** It has no FILE_LOADED, VIDEO_RECONFIG, END_FILE or PROPERTY_CHANGE handling, and nothing calls `mpv_observe_property`. The symbol is not even loaded. | `PB:1887-1904` (`drainMpvEvents`); `MpvApi` symbol list `PB:564-576`; local enum defines only `MPV_EVENT_NONE/SHUTDOWN` `PB:46-49` |
| F2 | All state goes Kotlin→native by **polling**: `snapshot()` every 500 ms, plus a native `WM_TIMER` every 500 ms that pushes `window.playerUpdate` to the WebView. | `PED:251-256`, `NPC:643-659`; `PB:1421` (`SetTimer 500`), `PB:972-976`, `PB:1765-1786` |
| F3 | **Every source change, including the next episode, creates a new native player.** `attach()` disposes the old handle and calls `create` again. The Kotlin `NativePlayerController` and the AWT `NativePlayerHost` stay the same, because `NativePlayerSurface` is not keyed on the source. | `NPC:162-213` (`attachPending` → `disposePlayerHandle` → `createPlayer`); `PED:122-123` (`remember { NativePlayerHost() }`, `remember(host) { NativePlayerController(host) }`); `PED:191-219` (attach `LaunchedEffect` keyed on sourceUrl, etc.); call site not in a `key()`: `commonMain/.../player/PlayerScreenRuntimeUi.kt:491`; the next episode sets `activeSourceUrl` in `PlayerScreenRuntimeSourceActions.kt:325` |
| F4 | Because of F3, **"don't re-switch between episodes" state must outlive the native player.** It needs a process-global native state or a Kotlin object, not a `WindowsMpvWebPlayer` member. | follows from F3 |
| F5 | Playback "hold" already exists: `create(playWhenReady=false)`. Native code runs `loadfile` first and then `setPaused(!playWhenReady)`, *after* the load command. `pause` is not set as an option before `mpv_initialize`. | `PB:1690-1708` |
| F6 | Threads per player: (a) the **native UI thread** (`uiThread`) owns the message window, the container HWND and WebView2, and pumps `GetMessageW`; (b) the **mpv event thread** (`eventThread`) runs `drainMpvEvents` with `waitEvent(…, 0.5)`; (c) JNI calls arrive on arbitrary Java threads (EDT, the `nuvio-player-create` worker, the `nuvio-player-dispose` worker). `sendPlayerEvent` attaches whichever native thread it runs on and calls `NativePlayerEventSink.onPlayerEvent`, which does `SwingUtilities.invokeLater`. | `PB:867-871`, `PB:1324-1358`, `PB:1710`, `PB:1920-1937`; `NPC:110-114`, `NPC:294`, `NPC:869-879` |
| F7 | `shutdown()` sets `stopping` and wakes mpv, then **`joinOrDetach(eventThread)` gives up after 3 s** (`kShutdownJoinTimeoutMs`), and after that calls `mpv_terminate_destroy`. Anything long-running on the event thread (such as a settle wait) must end early when `stopping` is set. If it doesn't, the thread is detached while mpv is destroyed underneath it (use-after-free). | `PB:68`, `PB:886-902`, `PB:904-959` |
| F8 | **The Windows bridge has no native logging at all.** There is no `fprintf`/`OutputDebugString`, and `hwdecLogged` is declared but never used. The only native→Kotlin channel is `sendPlayerEvent(type, double)`. | grep of `PB`; `PB:1299` |
| F9 | `buildWindowsPlayerBridge` compiles **one TU**, declares only `player_bridge.cpp` as an input, and has `onlyIf { !dll.exists() }`. Fork `scripts/verify.ps1:154-165` already deletes the DLL when any `.cpp/.h/.hpp` under `native/windows` is newer, so an `#include`d file triggers a rebuild through verify.ps1. | `GB:782-787`, `GB:865-892`, `GB:934-949` |
| F10 | `User32.lib` is already linked (`ChangeDisplaySettingsExW`, `EnumDisplaySettingsExW`, `EnumDisplayDevicesW`, `QueryDisplayConfig`, `SetDisplayConfig`, `MonitorFromWindow` all live there). **`dxgi.lib` is not linked.** If DXGI is needed, `#pragma comment(lib, "dxgi.lib")` inside the new file avoids editing Gradle. | `GB:883-892` |

---

## 1. `player_bridge.cpp` (Windows)

### 1.1 Structure

| Lines | What |
|---|---|
| 1-29 | Includes (`windows.h`, `dwmapi.h`, `WebView2.h`, `jni.h`, STL) |
| 34-57 | Hand-declared libmpv C types: `mpv_format`, `mpv_event_id` (only NONE=0 and SHUTDOWN=1), `mpv_event` |
| 59-608 | anonymous `namespace {`: globals, borderless-fullscreen state, display-sleep inhibit thread (100-160), string helpers, `getMonitorRect` (246-260, uses `MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)`), DWM chrome |
| 486-613 | `struct MpvApi` (dynamic `LoadLibraryExW` of `libmpv-2.dll`, `GetProcAddress` for 13 symbols) and the `mpvApi()` singleton (609-613). The `library` HMODULE is a public member, so new code can `GetProcAddress` extra symbols |
| 615-634 | window-class registration |
| 623-834 | WebView2 warm-up thread |
| ~834-2175 | `class WindowsMpvWebPlayer` (enable_shared_from_this) |
| 2177-2231 | `messageWindowProc` (WM_NUVIO_TASK, WM_TIMER), `containerWindowProc` (F11 and double-click → `toggleFullscreen` event) |
| 2233-2239 | `playerFromHandle`, end of anon namespace |
| 2241-2247 | `DllMain` (stores `gModule` only; no DETACH handling) |
| 2249-2595 | JNI exports |

### 1.2 mpv init block: `startMpv()` `PB:1608-1711`

| Line | Option |
|---|---|
| 1619 | `mpv_create` (under `mpvMutex`) |
| 1625-1631 | `config=no`, `osc=no`, `input-default-bindings=yes`, `input-vo-keyboard=no`, **`keep-open=yes`**, `volume-max=200`, `vo=gpu-next` |
| 1632-1639 | RTX on: `gpu-api=d3d11`, `hwdec=d3d11va`, `d3d11-adapter=NVIDIA`; off: `gpu-api=auto`, `hwdec=auto` |
| 1640 | `hwdec-codecs=all` |
| 1642-1644 | RTX on: `vf=d3d11vpp=scale=2:scaling-mode=nvidia` |
| 1645 | `target-colorspace-hint=yes` |
| 1646-1653 | decoder priority → `hwdec` / `vd-lavc-software-fallback` |
| 1654-1664 | `vd-lavc-threads=4`, `tone-mapping=auto`, `dither-depth=auto`, `deband=yes`, `scale/cscale=spline36`, demuxer cache, `cache-secs=36000`, `hr-seek=no` |
| 1666-1670 | `wid` = `containerHwnd` |
| 1672-1683 | `http-header-fields` |
| 1685 | `mpv_initialize` |
| 1690-1705 | `loadfile <url> [replace -1 start=<s>]` |
| **1708** | `setPaused(!playWhenReady)` (outside the lock, after loadfile) |
| 1710 | starts `eventThread` → `drainMpvEvents()` |

**No `video-sync`, `interpolation`, `display-fps-override` or `tscale` is set.** mpv's defaults apply (`video-sync=audio`) **[inferred from mpv defaults]**.
`setMpvOptionStringLocked` (`PB:1939-1941`) ignores errors.

### 1.3 Event loop: `drainMpvEvents()` `PB:1887-1904`

```cpp
mpv_event *event = mpvApi().waitEvent(current, 0.5);
if (!event) continue;
if (event->event_id == MPV_EVENT_SHUTDOWN) { return; }
```
That is the whole handler. To react to file load you need numeric IDs, which aren't in the local enum. From libmpv `client.h` **[inferred, verify against the bundled headers/ABI]**: START_FILE=6, END_FILE=7, FILE_LOADED=8, VIDEO_RECONFIG=17, PLAYBACK_RESTART=21, PROPERTY_CHANGE=22.
With `keep-open=yes`, EOF leaves the player paused on the last frame (`eof-reached=true`). END_FILE is not raised until the next `loadfile` or destroy **[inferred]**. "End of playback" therefore shows up as player dispose, not as an mpv event.

### 1.4 Load and start playback

- **One file per native player.** `loadfile` is issued only in `startMpv` (`PB:1690`). No JNI call loads a new URL into an existing player.
- **Hold mechanism.** `playWhenReady=false` → `setPaused(true)` at `PB:1708`, right after the async `loadfile`. A short first-frame window before the pause lands is possible **[inferred]**. For a clean hold, set `pause=yes` as an option before `mpv_initialize` (in the 1625-1664 block), and have 1708 respect the hold.
- `setPaused` JNI → `PB:1021-1026` (under `mpvMutex`). The WebView "setPlaybackState" message → `PB:1809-1817`. Kotlin `play()/pause()` → `NPC:895-903`; the `LaunchedEffect(playWhenReady)` in `PED:221-228` calls them only when `playWhenReady` changes. When `handle==0` they are no-ops.

### 1.5 Destroy paths

| Path | Where |
|---|---|
| JNI `dispose(handle)` → `player->shutdown()` | `PB:2326-2333` |
| create failure → `player->shutdown()` | `PB:2299-2302` |
| init failure on UI thread → `cleanupUiResources()` | `PB:1337-1340` |
| `shutdown()`: hides the container async → `sendUiTask(cleanupUiResources + PostQuitMessage)` → `stopping=true` + `mpv_wakeup` → `joinOrDetach(eventThread)` (3 s) → `mpv_terminate_destroy` → `joinOrDetach(uiThread)` → release the JNI global ref | `PB:904-959` |
| `cleanupUiResources()`: KillTimer, WebView2 close, `DestroyWindow(container/message)`, `OleUninitialize` | `PB:1426-1457` |
| `DllMain`: no `DLL_PROCESS_DETACH` handling | `PB:2241-2247` |

### 1.6 Windows

| Item | Where |
|---|---|
| Host HWND = AWT `NativePlayerHost` peer, passed in `create(hostViewPtr)` | `PB:2263`; Kotlin `NPC:274` (`resolveHostView`) |
| Message-only window (`HWND_MESSAGE`), task queue + 500 ms timer | `PB:1377-1393`, `1421` |
| Container child window `WS_CHILD\|WS_VISIBLE\|WS_CLIPSIBLINGS` of the host = mpv `wid` | `PB:1400-1416`, `1666` |
| WebView2 controller as a child of the container (transparent overlay) | `PB:1505-1606` |
| Layout on each timer tick | `PB:1713-1729` |
| **Fullscreen** = borderless emulation on the *top-level* Compose window, via JNI `setWindowBorderlessFullscreen` → `setBorderlessFullscreen` (uses `MonitorFromWindow` of that window) | `PB:309-380`, `PB:2540-2559`; Kotlin `DesktopAppFullscreen.kt` |
| **PiP** = `reparentSurface`: `SetParent(containerHwnd, newHost)` to a separate PiP window (which may be on another monitor) | `PB:1005-1019`; Kotlin `NPC:453-460`, `DesktopPlayerPictureInPicture.kt`, `DesktopPlayerPipWindow.kt` |
| Existing monitor helper usable by the new code: `getMonitorRect(hwnd, workArea, rect)` | `PB:246-260` |

For "monitor hosting the player", use `MonitorFromWindow(GetAncestor(containerHwnd, GA_ROOT), MONITOR_DEFAULTTONEAREST)` at switch time. This handles PiP because the container is re-parented **[inferred]**.

### 1.7 Threading (per player)

| Thread | Created | Does |
|---|---|---|
| Caller of JNI `create` (`nuvio-player-create`, never the EDT) | `NPC:294-356` | blocks in `initialize()` until the UI thread reports init complete (`PB:873-881`) |
| Native UI thread | `PB:867` | windows, WebView2, `startMpv` (so **mpv init and loadfile run on this thread**), message pump |
| mpv event thread | `PB:1710` | `drainMpvEvents` |
| Dispose caller (`nuvio-player-dispose` / `nuvio-player-release`) | `NPC:867-880`, `NPC:690-732` | `shutdown()` |
| JNI getters/setters | EDT / coroutine | take `mpvMutex` per property (`PB:1943-2000`) |

`mpvMutex` guards the `mpv` pointer. The event thread reads `mpv` under the lock, then calls `waitEvent` without it (`PB:1889-1898`).
Any display-mode change (`ChangeDisplaySettingsExW` broadcasts `WM_DISPLAYCHANGE` synchronously to top-level windows **[inferred]**) must **not** run on the native UI thread while it holds `mpvMutex`, and must not run on the EDT.

### 1.8 Logging (native)
There is none (F8). Options: (a) a new `sendPlayerEvent("refreshRate:<state>", value)`, which is already routed to Kotlin, is logged by `handlePlayerEvent` (`NPC:486-489`, subject to `shouldLogNativeControlEvent`, `NPC:1099`), and falls to `onEvent` → `toPlayerControlsAction()`, which returns null for unknown types (`NPC:521-528`), so it is harmless; (b) `OutputDebugStringW` plus an optional file under `%LOCALAPPDATA%` from inside the new .cpp (zero upstream lines). Recommended tag: `[nuvio-rr]` natively and `Logger.withTag("RefreshRateMatch")` in Kotlin.

### 1.9 How `nvidiaRtxSuperResolutionEnabled` reaches native

`PlayerSettingsRepository.uiState` → `PED:134-136` (collectAsState) → the `LaunchedEffect` key list `PED:191-201` (**toggling the setting re-attaches, i.e. re-creates the player**) → `controller.attach(..., nvidiaRtxSuperResolutionEnabled)` `PED:206-214` → `PendingSource` `NPC:125-133`/`1159-1167` → `nativeCreate(...)` `NPC:297-307` (typealias `NPC:38-48`) → `external fun create` `NPB:37-47` → JNI `PB:2249-2298` → `initialize` `PB:843-871` → `runNativeUiThread` `PB:1324-1336` → `initializeOnNativeUiThread` `PB:1360-1419` → `startMpv` `PB:1608-1644`.
Changing `create()`'s signature touches **13+ sites**: the 3 natives (Windows `PB`, macOS `player_bridge.mm:2716`, Linux `player_bridge.cpp:1531`), the typealias, `PendingSource`, `retry()` `NPC:922-933`, and ~13 call sites in `NativePlayerControllerTeardownTest.kt`. **Avoid it.**

---

## 2. JNI surface and Kotlin lifecycle

### 2.1 `NativePlayerBridge.kt` (object, shared by all 3 OSes)
- `external fun`s at `NPB:35-112`. `create` is at `NPB:37-47`: `(hostViewPtr, sourceUrl, headerLines, playWhenReady, initialPositionMs, controlsPageUrl, decoderPriority, nvidiaRtxSuperResolutionEnabled, eventSink): Long`.
- `init { loadNativeLibrary() }` `NPB:25-27` → `System.load(player_bridge.dll)` (`NPB:~161-181`).
- `preloadAsync()` adds a shutdown hook `nuvio-webview2-warmup-shutdown` `NPB:135-145`.
- JNI binds lazily, so **a new Kotlin object in the new package can declare its own `external fun`s** (JNI name `Java_com_nuvio_app_features_player_desktop_refreshrate_<Class>_<fn>`), implemented in the new .cpp compiled into the same DLL. It only has to make sure `NativePlayerBridge` is initialised first (touching any member runs `init`). **Zero edits to `NPB`.** macOS and Linux never call these functions, so an unresolved symbol there is harmless **[inferred: UnsatisfiedLinkError only at call time]**.

### 2.2 `NativePlayerController.kt` lifecycle

| Step | Lines |
|---|---|
| `attach()` stores `PendingSource` and calls `attachPending` when the host is displayable | 116-160 |
| `attachPending()` on the EDT: dispose the old handle, wait up to 5 s for old teardown off-EDT, then `createPlayer` | 162-213 |
| `createPlayer()`: resolve host HWND on the EDT, native create on `nuvio-player-create`, publish the handle, then configure volume/controls/resize/subs on the EDT | 267-380 |
| `play()/pause()/seek*` | 895-920 |
| `releaseBeforeNavigation()` (leaving the player screen): terminal dispose on `nuvio-player-release` + 10 s watchdog | 665-774 |
| `dispose()` (Compose `DisposableEffect` key change) → `disposePlayerHandle` → `startTrackedDisposeLocked` (`nuvio-player-dispose`) | 776-789, 866-893 |
| **Next episode / source switch**: same controller, **new native handle** (F3) | — |

Compose wiring (`PED`):
- `DisposableEffect(host)` at `PED:142-163`: lives as long as the player surface (it spans episodes). Its `onDispose` is a clean "player screen gone" hook.
- `DisposableEffect(controller, sourceAvailable, sourceUrl, playbackHeaders) { onDispose { controller.dispose() } }` at `PED:174-176`: per source.
- Screen-level effect: `EnterImmersivePlayerMode(keepScreenAwake)` → `DesktopKeepAwakeController` (`PlayerPlatformEffects.desktop.kt:31-44`, `75-236`), called from `PlayerScreenContent.kt:165-168`. It is also used by `TrailerPlayer.kt:47`, but Windows trailers are external (`AppFeaturePolicy.desktop.kt:18-19`), so a native player never backs them on Windows.

### 2.3 App exit paths

| Path | Where |
|---|---|
| Main window close: `P2pStreamingEngine.shutdown()`, `DiscordPresenceManager.shutdown()`, `SentryInitializer.close()`, `exitApplication()` | `desktopMain/.../Main.kt:123-129` |
| `exitProcess(0)` | `core/ui/PlatformExitApp.desktop.kt:6`, `features/updater/AppUpdaterPlatform.desktop.kt:182`, `features/settings/AppIconPlatform.desktop.kt:48`, `MacAppIconUpdater.kt:38` (macOS) |
| JVM shutdown hooks already present | `NPB:138-144` (WebView2 warm-up), `p2p/P2pStreamingEngine.desktop.kt:62` |
| Crash / Task-Manager kill | no hook exists. Needs a separate mechanism (for example a watchdog process, or restoring the registry-default mode on the next launch) **[inferred, design question for another doc]** |

---

## 3. Settings pattern (`nvidiaRtxSuperResolutionEnabled` end-to-end)

| File | Lines (RTX) | What a new boolean needs | Est. lines |
|---|---|---|---|
| `commonMain/.../player/PlayerSettingsRepository.kt` | UiState field 99; var 170; reset 246; load 397; setter 811-817; publish 1057 | same 6 touch points | ~12 |
| `commonMain/.../player/PlayerSettingsStorage.kt` (`expect object`) | 161-162 | load/save decl | 2 |
| `desktopMain/.../player/PlayerSettingsStorage.desktop.kt` | key 98; `syncKeys` 106; load/save 338-339; export 432; replace 514 | key + load/save (+3 if synced) | 3-6 |
| `androidMain/.../PlayerSettingsStorage.android.kt` | 99, 108, 1215-1229, 1308, 1391 | **required actual** (expect object) | ~10 |
| `iosMain/.../PlayerSettingsStorage.ios.kt` | 97, 106, 1010-1022, 1098, 1179 | **required actual** | ~10 |
| `commonMain/.../settings/PlaybackSettingsPage.kt` | `if (isWindows)` section 998-1012; `import com.nuvio.app.isWindows` 90 | a `SettingsSwitchRow` in its own `if (isWindows)` section, or in the RTX one | ~8-14 |
| `commonMain/composeResources/values/strings.xml` | 2064-2066 | 2-3 strings. **Only `values/` has the RTX strings**; the 24 locale folders fall back to English | 2-3 |
| `PED` (consumer) | 134-136 | read setting (do **not** add it to the `LaunchedEffect` keys, or toggling re-creates the player) | 1-3 |

Platform gate: `internal expect val isWindows` (`commonMain/.../Platform.kt:11`; desktop actual `Platform.desktop.kt:11`).

**Sync.** `ProfileSettingsSync` pushes the blob from `PlayerSettingsStorage.exportToSyncPayload()` (`core/sync/ProfileSettingsSync.kt:253`, `299`) to Supabase RPC `sync_push_profile_settings_blob` (`:237`). Any change to `PlayerSettingsRepository.uiState` triggers a push (`:198`, signature `:392`). A key is synced **only** if it is added to `syncKeys`, the export and the replace. Leaving it out keeps it device-local, and `replaceFromSyncPayload` only `removeAll(syncKeys)` (`desktop:436`), so an unlisted key survives a pull. Keys are per-profile through `ProfileScopedKey` (`desktop:341`). Recommendation: **don't sync**. Refresh-rate matching is specific to each monitor and machine.

**Lower-conflict alternative [design option].** Put a new `commonMain` repository file (e.g. `features/player/desktop/refreshrate/...` is desktop-only, so the common part would need its own package) with its own `expect object` storage and **new** actual files in android/ios/desktop. Then `PlayerSettingsRepository` and `PlayerSettingsStorage` (19 and 18 upstream commits in the last 2 months) stay untouched, and the upstream edits shrink to `PlaybackSettingsPage.kt` + `strings.xml`. There is no multiplatform key-value library in the project (no russhwolf settings or DataStore in `gradle/libs.versions.toml`), so the new storage actuals would each wrap the platform store, as the existing ones do.

---

## 4. Kotlin logger
- Kermit `co.touchlab:kermit` 2.0.5 (`gradle/libs.versions.toml:19,75`). Usage: `private val log = Logger.withTag("…")`, then `log.d { }` / `log.w(error) { }` (`NPC:4,63`; `DesktopPlayerPictureInPicture.kt:18` → tag `DesktopPlayerPiP`).
- No `setLogWriters` or `setMinSeverity` anywhere, so Kermit defaults apply: JVM platform writer to stdout, all severities **[inferred]**.
- Tag convention: PascalCase component names (`NativePlayerControls`, `DesktopPlayerPiP`, `P2pStreamingEngine`). Suggested: `RefreshRateMatch`.

---

## 5. Build (`GB`)

| Item | Lines |
|---|---|
| Source/output paths (`player_bridge.cpp` → `build/native/windows/player_bridge.dll/.lib/.pdb/.obj`, `build-player-bridge.bat`) | 782-787 |
| arch → vcvars | 776-781, 834-841 |
| `cl /nologo /EHsc /std:c++17 /LD /DUNICODE /D_UNICODE /DNOMINMAX /DWIN32_LEAN_AND_MEAN /permissive- player_bridge.cpp /I jdk/include /I jdk/include/win32 /I webview2/include` | 865-882 |
| Link: `/NOLOGO /INCREMENTAL:NO /IMPLIB WebView2Loader.dll.lib Ole32.lib User32.lib Gdi32.lib Dwmapi.lib Shell32.lib` | 883-892 |
| PowerShell writes the `.bat` (vcvars + cl) and runs it | 894-932 |
| Task: `inputs.file(player_bridge.cpp)` only; **`onlyIf { !dll.exists() }`** | 934-949 |

**Unity include vs compile list.**
- `#include "display_mode_matcher.cpp"` (or `.h` with inline definitions) from `PB` costs **1 upstream line**. The same flags and link libs apply automatically, and `scripts/verify.ps1:154-165` handles the rebuild. Put it **after `mpvApi()` (`PB:613`)** if the glue needs `mpvApi()` / `mpv_handle`, or after line 57 for pure Win32. Note that `WIN32_LEAN_AND_MEAN` and `NOMINMAX` are in effect, so write `(std::min)` if needed.
- Adding it to the compile list costs ≥3 `GB` edits (source list, obj naming since `/Fo` is a single file, inputs), and `GB` churns (49 commits / 2 months). **The unity include is less invasive.**
- Libs: `User32.lib` is already there. For DXGI/`d3dkmt` use `#pragma comment(lib, "dxgi.lib")` in the new file, which avoids a Gradle edit.

---

## 6. Existing mpv property use / stats
Only on-demand `mpv_get_property` calls exist, via `doubleProperty` / `int64Property` / `flagProperty` / `stringProperty` (`PB:1943-2000`). Properties read: `pause`, `duration`, `time-pos`, `speed`, `volume`, `core-idle`, `paused-for-cache`, `eof-reached`, `track-list/*`, cache and demuxer state (`PB:1100-1124`, `1765-1786`, track JSON ~2058-2090).
**Nothing reads `container-fps`, `estimated-vf-fps`, `display-fps`, `video-params`, `vsync-jitter` or `frame-drop-count`.** `mpv_observe_property` is not loaded. The new code can `GetProcAddress(mpvApi().library, "mpv_observe_property")` itself, or just poll `container-fps` / `estimated-vf-fps` after FILE_LOADED.

---

## 7. Proposed hook points (minimum upstream edit set)

Strategy: all logic in new files. Native glue reacts to mpv events and does the switch. Kotlin supplies the enabled flag and handles restore when the player screen goes away. Across episodes, state lives in a process-global in the new .cpp (F4).

| # | File:line | Hook | Est. lines |
|---|---|---|---|
| H1 | `PB` after 613 | `#include "display_mode_matcher.cpp"` (Win32 matcher + mpv glue, `namespace nuvio_rr`) | 1 |
| H2 | `PB` 1625-1664 (options block) | `if (nuvio_rr::enabled()) setMpvOptionStringLocked("pause","yes");` (clean hold before init) | 1-2 |
| H3 | `PB:1708` | `setPaused(!playWhenReady \|\| nuvio_rr::holdRequested());`, and give the glue `playWhenReady` for the later resume | 1-2 |
| H4 | `PB:1899-1902` (`drainMpvEvents`) | `nuvio_rr::onMpvEvent(current, event->event_id, containerHwnd, stopping);`. The glue on FILE_LOADED/VIDEO_RECONFIG reads fps, picks/switches the mode, waits (**interruptibly, < 3 s total, F7**), sets `video-sync=display-resample` (+ optional interpolation), then unpauses. Better: hand the switch/settle to a glue-owned worker so the event thread never blocks | 1-3 |
| H5 | `PB:~905` (`shutdown()` start) | `nuvio_rr::onPlayerShutdown(this)`: cancel any in-flight settle for this player. It does **not** restore (F3/F4) | 1 |
| H6 | `PED:157-162` (`DisposableEffect(host).onDispose`) | `RefreshRateMatch.onPlayerSurfaceGone()` → JNI restore (debounced, so a quick re-entry doesn't flap) | 1 |
| H7 | `PED:~134-138` | push the setting into native: `SideEffect { RefreshRateMatch.setEnabled(playerSettings.<new>) }`. **Not** a `LaunchedEffect` key | 1-3 |
| H8 | `desktopMain/.../Main.kt:124-128` (`onCloseRequest`) | `RefreshRateMatch.restoreNow()`. A JVM shutdown hook registered from the new object covers `exitProcess` paths with **no** upstream edit | 1 |
| H9 | settings (§3) | new boolean + UI row + strings | ~15 (alt. path) to ~55 (RTX-style) |
| — | `NPB`, `NPC`, `create()` signature, `GB` | **not touched** (own `external fun`s in the new package; unity include) | 0 |

Native total ≈ 6-9 lines in `PB`; Kotlin total ≈ 3-7 lines in `PED`/`Main.kt`, plus the settings edits.

### Conflict-risk hot spots (upstream commits touching the file: last 2 months / all time)

| File | 2 mo / total | Hot regions (from `git log -p` hunk ranges) |
|---|---|---|
| `NativePlayerController.kt` | **40 / 81** | Lifecycle 80-900 rewritten repeatedly (teardown/create races: hunks at 162-360, 579 (+240 lines), 665-900); `toControlsJson`. **Stay out.** |
| `strings.xml` | **100 / 292** | append-only; conflicts are usually trivial, so add at a distinctive spot, not the end |
| `build.gradle.kts` | 49 / 163 | mostly release/macOS; Windows bridge block touched once |
| `PlaybackSettingsPage.kt` | 23 / 83 | around the platform sections |
| `PlayerSettingsRepository.kt` / `PlayerSettingsStorage.kt` | 19 / 54, 18 / 45 | every new setting touches these |
| `Main.kt` | 18 / 36 | `onCloseRequest` is stable-ish |
| `player_bridge.cpp` (Win) | 11 / 34 | class public section ~986-1040 (PiP, volume), subtitle style 1150-1290, JNI exports 2184-2530, window procs; **`startMpv` 1601-1718 touched twice** (volume-max, play/pause fix `a938caf5` at 1703); `drainMpvEvents` untouched |
| `PlayerEngine.desktop.kt` | 11 / 32 | the attach `LaunchedEffect` |
| `NativePlayerBridge.kt` | 9 / 34 | the external list |
| `PlayerPlatformEffects.desktop.kt` | 7 / 13 | keep-awake |

---

## 8. Later-phase notes (brief)

| Item | Where |
|---|---|
| `AppFeaturePolicy` desktop actual, `inAppUpdaterEnabled = true` | `composeApp/src/desktopMain/kotlin/com/nuvio/app/core/build/AppFeaturePolicy.desktop.kt:21` |
| Sentry entry point: `SentryInitializer.start()` in `main()` / `close()` on window close | `Main.kt:59`, `Main.kt:127`; object at `core/diagnostics/SentryInitializer.kt:16` (DSN `SentryConfig.DESKTOP_DSN` generated from `SENTRY_DESKTOP_DSN`, `GB:95-96`, `GB:620`); tags `app.package_name=com.nuvio.media.desktop` (`SentryInitializer.kt:67`) |
| `desktopSentry` module (Sentry JVM Gradle plugin, org `nuviomedia`, project `nuvio-desktop`, source-context upload only with `SENTRY_AUTH_TOKEN`) | `desktopSentry/build.gradle.kts:1-30`; `settings.gradle.kts:37`; wired at `GB:553`, `1237`, `1316` |
| MSI identity: `packageName = "Nuvio"`, `vendor = "Nuvio Media"`, `upgradeUuid = windowsMsiUpgradeUuid = "395990ee-9b8a-3548-922c-e7a23a495b8d"`, `menuGroup = "Nuvio"`, `mainClass = com.nuvio.app.MainKt` | `GB:532`, `1321`, `1339-1341`, `1389-1395` |
| Hard-coded libmpv fallback path `C:\Program Files (x86)\Nuvio\app\native\libmpv-2.dll` (a fork with a different install dir would still find the upstream install's DLL) | `PB:547` |
| WebView2 user-data dir `%LOCALAPPDATA%\Nuvio\WebView2` (shared with upstream installs) | `PB:478` |
