# SPEC — Match display refresh rate (delta spec on top of upstream NuvioDesktop)

Describes ONLY what this patch adds or changes. It's the reference for re-applying and
re-verifying the patch after an upstream update. Keep it in sync with the code.

Upstream base: NuvioMedia/NuvioDesktop `Dev` @ `083921cf` (2026-09-27).

## 1. Behaviour
_TBD after Phase 1 research and plan approval._ Summary of intent:
- Opt-in setting "Match display refresh rate" (Playback settings, Windows), default OFF.
  OFF ⇒ identical to upstream.
- On playback start: fps → target mode (integer multiple, 1000/1001 tolerance, same
  resolution/bit depth, monitor hosting the player) → switch while held → settle →
  display-synced mpv timing → play. Rate constant for the session.
- Restore on end/close/exit/crash/kill. Never persisted as the Windows default.

## 2. Upstream files touched (hooks)
| File | Hook | Lines |
|---|---|---|
| _TBD_ | | |

## 3. New files
| File | Purpose |
|---|---|
| _TBD_ | |

## 4. Acceptance criteria (per step; written before each step's code)
Format: `ID — statement — how checked (auto / [HUMAN])`.

### Phase 0
- P0-1 — Unmodified fork builds `player_bridge.dll` and compiles desktop Kotlin — auto (`verify.ps1 -Full`)
- P0-2 — Existing `desktopTest` suite passes on the unmodified fork — auto
- P0-3 — `verify.ps1 -Fast` and `-Full` exit 0 on the unmodified fork and non-zero on an induced failure — auto
- P0-4 — The app launches and plays a video on the owner's PC — [HUMAN]
- P0-5 — A dev run (`scripts/run-dev.ps1`) writes nothing to the official `%APPDATA%\Nuvio` — auto (file timestamps; verified 2026-09-27: 0 files)

Phase 0 results (2026-09-27): P0-1 PASS, P0-2 PASS (1345 tests; 6 known upstream failures,
`scripts/known-upstream-test-failures.txt`), P0-3 PASS (green clean; exit 1 on an induced test
failure and an induced compile error), P0-5 PASS, P0-4 PASS (owner, 2026-09-28: picture, sound, seek, fullscreen OK).

## 5. Upkeep limits
- Upstream-file diff budget: _TBD_ lines (reported by `verify.ps1 -Full`).
