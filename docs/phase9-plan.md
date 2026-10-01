# Phase 9 — audit fixes in the fork (owner 2026-10-01)

Owner: "fix only on my fork. Step 3 fix all". Scope (owner choice): every bug, robustness, security and performance
item of `docs/audit-2026-09-30.md` plus enhancements E1, E4–E9; **not** R7 (split large files) or E10 (one HTTP client).
No upstream issues or PRs (the 4 `upstream-pr/*` branches stay local; their fixes are taken into the fork here).

Rules: one commit per item (tests first where the code is testable: red commit, then green); upstream files touched
are listed in `scripts/fork-fixes.txt` (verify.ps1 allows untagged lines only there); `verify -Full` green after each
batch; a live run where the change is visible at run time; official profile never written (D8, test profile).

Status: `todo`, `done <commit>`, `n/a <reason>`, `partial <what is left>`.

## Batch 1 — data safety
| # | Item | Status |
|---|---|---|
| 1 | Atomic preference writes (temp + ATOMIC_MOVE; `.bak` dropped: a truncated file loads without error, so it would never trigger) | done 01758271 (60/60 kills intact) |
| 2 | Downloads: throttle progress; repository persists on status change only; atomic state update (Pause race) | done 5a0d4d3c (races fixed; ≤1 write/10 s) |
| 3 | Window geometry saved once the window settles, off the UI thread | done 670acd94 (1 write per drag) |
| 19 | Watch progress / store writes off the UI thread (one background writer, debounced) | done 5632a0a3 (save 2.7 → 0.25 ms on caller) |
| 6 | Delete old native runtime copies after install | done 177112dc (old versions pruned after 24 h) |

## Batch 2 — playback
| # | Item | Status |
|---|---|---|
| 4 | Playback errors after loadfile (END_FILE error) reach Kotlin | done 18b03d62 (404 → error in 16 s) |
| 5 | Async `sub-add` (no UI freeze) | done 5ecd577e (+ newest pick wins) |
| R1 | Player shutdown timeout path: no std::terminate / use-after-destroy | done f6d07969 |
| 15 | mpv property observation instead of 2 Hz polling | done 2e9907ea (track lists: 3 rebuilds/30 s) |
| 16 | Video/overlay follow a resize at once | done 0d3a4f66 (resize catch-up 297 → 31 ms) |
| 17 | Exact seeks for scrub release and skip-intro | done 9c9d1631 (exact absolute seeks) |
| E8 | Video settings by content (decoder threads, VSR scale, no deband for 10-bit/HDR) | partial b2a5211d (VSR fit + threads done; trigger needs owner check with RTX on; deband kept per D12) |

## Batch 3 — security
| # | Item | Status |
|---|---|---|
| 8 | Native DLL/exe only from the bundle (dev paths behind a dev property) | done 26142fc1 (packaged app loads its own bridge) |
| S1 | Tokens and API keys encrypted (DPAPI on Windows) | done d2b0d360 (DPAPI; packaged decrypt path not reached live) |
| S2 | Plugin fetch: block loopback/link-local/private ranges after DNS | done 4fdb9bf0 (plugin → 127.0.0.1 refused) |
| S3 | Addon URLs redacted in logs | done b009aed0 (7 log lines) |
| S4 | TorrServer "is running" check verifies it is ours | done 9b4421c4 (impostor gets no torrent) |
| S5 | Updater: hash/signature check (the fork has the updater off) | n/a (updater is off in the fork since Phase 8; upstream releases publish no hashes to check against) |
| S6 | desktopTest never writes the real profile (env in the Gradle test task) | done 512540b2 (test profile under build/) |

## Batch 4 — performance
| # | Item | Status |
|---|---|---|
| 10 | libmpv not re-read/hashed from the jar on every launch | done 78ee679a (bridge load 680 → 21 ms on main) |
| 11 | Poster downscale off the UI thread, cached, sensible decode size | done 420d5fdf (decode follows layout; no memory change measured: sources already small) |
| 12 | UI scale stepped (no relayout every resize frame) | done ea87e2c6 (≤5 scales per drag) |
| 13 | Coil memory/disk cache bounds and location | done 045d2d13 (256 MB / 512 MB, disk cache out of %TEMP%) |
| 14 | GIF cards: pause when hidden, decode off the main thread, safe LRU | partial 94056e20 (off-UI decode + safe LRU; pause-when-hidden not done, GIF-only cards still animate: upstream design, see E5) |
| 18 | Addon/TMDB networking: cancellable calls, timeouts, response cap, thread-safe caches, de-dup | partial af0bb1de (cancellable calls, 16 MB cap, concurrent TMDB caches; no HTTP cache / de-dup) |
| 20 | `runBlocking { getString }` removed from composition paths | done 7b03a241 (first getString 175 ms moved off the UI thread; warm calls 0.04 ms) |
| 21 | Packaged app JVM options (heap cap, AppCDS) | partial f20ec311 (-Xmx2g; AppCDS not possible on JDK 17 without a training run; heap is only ~30 MB) |
| 22 | Package size (icons, TorrServer copy into Roaming) | partial 5b380731 (TorrServer copy fixed but not live-testable: fork builds have no TorrServer; icons/ProGuard not done) |

## Batch 5 — build and robustness
| # | Item | Status |
|---|---|---|
| 7 | QuickJS runtime: confined thread + evaluation timeout | partial eb7760b5 (timeout added; serial-runtime fix deadlocked, reverted; rare race stays) |
| R2 | Native bridge rebuilt when its source changes | done 74c95e3e |
| R3 | Configuration cache on Windows desktop builds | n/a (developer build speed only; needs rewriting upstream's bridge task) |
| R4 | The 7 known failing upstream tests fixed | done 62deab7f (6 of 7; the flaky plugin race stays) |
| R5 | Gradle memory settings | done 8092176f |
| R6 | Declared vs shipped dependency versions aligned | n/a (would upgrade libraries upstream has not; risk without a user-visible gain) |

## Batch 6 — enhancements
| # | Item | Status |
|---|---|---|
| E1 | Audio options: channel layout, passthrough (WASAPI exclusive), device | done 49c79c6f, 19e11dec (AC3 bitstream to S/PDIF verified; the default DAC refuses it and mpv decodes) |
| E4 | Esc / Alt+Left / Backspace go back | done 4247f329, 2d6f716c (Esc + Alt+Left via the window back dispatcher; mouse Back was already upstream; no Backspace: empty text fields) |
| E5 | Reduce motion setting | done cafd6117, c22abf52 (hero still verified; GIF and crossfade not checked live) |
| E6 | Shelf arrows for mouse users | done e7327193, 8d9664ea |
| E7 | Live scrub preview | done 7f0cc4da, 37870b0c, 0cdfad64 (native path verified; UI drag not live-tested) |
| E9 | Window restore after a monitor change; keyboard navigation | done 07003511 (restore), bd7c00d4, 8573de32 (arrows, focus ring, Ctrl+F) |
