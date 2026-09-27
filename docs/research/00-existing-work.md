# 00 - Existing work: refresh-rate / frame-rate matching in Nuvio Desktop

- Date: 2026-09-27
- Repo: NuvioMedia/NuvioDesktop (default branch `Dev`), plus a brief look at other NuvioMedia org repos
- Method: read-only `gh` CLI. Nothing was posted, commented on, reacted to, or opened.

## Verdict

**Nothing matching exists or is planned for Nuvio Desktop.** No issue, PR, PR review comment, issue comment or Dev commit requests, implements or plans display refresh-rate matching, frame-rate matching, display mode switching, judder handling, or mpv `video-sync=display-resample` / `display-fps` work. GitHub Discussions are disabled.

The closest item is a single line in a test report on PR #295 (Linux support). It lists "refresh-rate switching" as *not yet tested*. It is not a request or a plan.

Refresh rate does come up in the repo, but only for **UI** (Compose/Skiko) problems on high-refresh and G-Sync displays, never for video. Those are listed below as false positives.

**Maintainer attitude, inferred from a sibling repo:** positive. The maintainer `tapframe` wrote "auto frame rate matching" for **NuvioTV** (Android TV) himself in Feb 2026. It has 37 commits on `FrameRateUtils.kt`, Off / On start / On start-stop modes, and resolution matching.

## Queries run

### Bulk download plus local regex (the main method; avoids the search API's 30 req/min limit)
```
gh api "repos/NuvioMedia/NuvioDesktop/issues?state=all&per_page=100" --paginate     # 538 issues + 234 PRs (titles+bodies), #1..#777
gh api "repos/NuvioMedia/NuvioDesktop/issues/comments?per_page=100" --paginate      # 1191 issue + PR conversation comments
gh api "repos/NuvioMedia/NuvioDesktop/pulls/comments?per_page=100" --paginate       # 20 PR review (inline) comments
gh api "repos/NuvioMedia/NuvioDesktop/commits?sha=Dev&per_page=100&since=2026-06-01T00:00:00Z" --paginate  # 1827 commits (the whole repo history since creation 2026-06-08, plus the older history of the imported NuvioMobile code)
```
The regex was case-insensitive and ran over title and body:
`refresh.?rate|\bhz\b|[0-9]hz|frame.?rate|\bfps\b|judder|stutter|display.?mode|\bvrr\b|g-?sync|freesync|flicker|\boled\b|23\.97|\b24p\b|video-sync|display-resample|display-fps|match.{0,15}(frame|rate|refresh|display)|auto.?refresh|tearing|vsync|v-sync|interpolat|smooth(ness)? playback|choppy|jerky|frame.?drop|dropped frames|monitor|panning`

A second regex ran over commit messages: `refresh|hz|fps|frame.?rate|judder|display|video-sync|resample|vsync|g-?sync|vrr|freesync|flicker|oled|opengl|skiko|render|match`.

### GitHub search API (cross-check)
```
gh search issues --repo NuvioMedia/NuvioDesktop --include-prs -- <term>
  terms: "refresh rate", judder, "frame rate", display-resample, video-sync, 23.976, 24p, hz, vrr, oled, "match"
```
The search results matched the local grep exactly. `judder`, `display-resample`, `23.976`, `24p` and `vrr` returned no results. `video-sync` is split into "video" and "sync" by the search engine, so all of its results are noise.

The first search batch, covering refresh, hz, framerate, fps, stutter, display mode, gsync, g-sync, freesync, flicker and hdr, is **invalid**: a `--` quoting bug turned the flags into query text, and the batch then hit the rate limit. The local grep above replaces it.

### Discussions
```
gh repo view NuvioMedia/NuvioDesktop --json hasDiscussionsEnabled   -> false
gh api graphql search(query:"repo:NuvioMedia/NuvioDesktop refresh rate", type:DISCUSSION) -> discussionCount 0
```

### Code spot-check (not re-done in depth)
```
gh search code --repo NuvioMedia/NuvioDesktop  video-sync | display-resample | interpolation | ChangeDisplaySettings | refreshRate | display-fps
```
- `display-resample`, `ChangeDisplaySettings`, `refreshRate` and `display-fps` returned no results.
- `video-sync` matched only the vendored `mpv/render.h` header (macOS) and a German strings file.
- `interpolation` matched an **iOS-only** mpv toggle: `MPVPlayerBridge.swift:714` sets `interpolation`. The desktop `PlayerSettingsStorage.desktop.kt` only stores and syncs the `ios_interpolation_enabled` key, and nothing reads it on Windows. The iOS bridge does not set `video-sync`. mpv's `interpolation` has no effect without a `display-*` video-sync mode (this is inferred from mpv docs, not verified on device).

### Org repos
```
gh repo list NuvioMedia
gh search code --repo NuvioMedia/{NuvioTV,NuvioMobile,NuvioTVSmart} frameRate | refreshRate | preferredDisplayModeId | setFrameRate | afr
gh api repos/NuvioMedia/NuvioTV/contents/app/src/main/res/values/strings.xml
gh api "repos/NuvioMedia/NuvioTV/commits?path=app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt"
```

## Real hits (relevant or adjacent)

| Item | Relevance |
|---|---|
| https://github.com/NuvioMedia/NuvioDesktop/pull/295#issuecomment-5244354848 | ofers' Linux test report (2026-08-10) says "Not yet claimed/tested: ... HDR, **refresh-rate switching** ...". This is a mention only. There is no plan or request. |
| https://github.com/NuvioMedia/NuvioDesktop/issues/265 (open, `enhancement`) | Request for motion interpolation / 60fps smoothing like SVP4. This is adjacent: it addresses the same 24p smoothness complaint, but by synthesising frames rather than matching the display. No maintainer response. |
| https://github.com/NuvioMedia/NuvioDesktop/issues/32 (open, `enhancement`) | Request for de-interlace plus SVP to force 60 fps (Stremio-Kai Ctrl+F12). Adjacent, same as #265. No maintainer response. |
| https://github.com/NuvioMedia/NuvioDesktop/issues/738 (open, `enhancement`; #737 is a closed duplicate) | Request for a custom mpv.conf / config-dir surface for the internal player. This is adjacent: it would let users set `video-sync=display-resample` themselves, but it does not propose any display mode switching. It states the bridge exposes no config surface today. |
| https://github.com/NuvioMedia/NuvioDesktop/issues/761 (open) | "playback is choppy with lots of tearing" (Windows, libmpv). A maintainer-side reply (ThEgIGN) says desktop supports only mpv, with "Decoder priority" as the only player setting. This is weak evidence of a judder/sync symptom. It could just as well be a decode issue. |
| https://github.com/NuvioMedia/NuvioDesktop/issues/302 (closed by stale bot) | 4K playback "low FPS/stuttering" versus VLC/Stremio. A performance symptom, not refresh matching. |
| NuvioTV: https://github.com/NuvioMedia/NuvioTV/blob/cc27f0028b75d6e33ddb614368c0035f4608e2ee/app/src/main/java/com/nuvio/tv/core/player/FrameRateUtils.kt | **Sibling-repo precedent.** Android TV "Auto Frame Rate & Resolution" setting (strings `playback_auto_frame_rate`, `playback_afr_off/on_start/on_start_stop`, and "Allow display mode changes to match video resolution"). It includes a display-capability warning dialog, `DisplayModeOverlay.kt`, `MatroskaAfrProbe.kt` and `PlayerFrameRateHeuristics.kt`. The first commit is `40ab9b29` "feat: add auto frame rate matching" by **tapframe** on 2026-02-08, followed by work from kernexshadow (overlay and detection). |
| NuvioMobile, NuvioTVSmart | No refresh-rate or frame-rate matching code. The `afr` results are Afrikaans language strings, and the `frameRate` result in TVSmart is AVPlay stream info. |

## False positives (refresh/flicker/G-Sync hits that are UI-only, not video)

- https://github.com/NuvioMedia/NuvioDesktop/issues/16 (closed, stale): Windows UI flicker attributed to G-Sync. Users suggest a workaround via an NVIDIA Profile Inspector profile.
- https://github.com/NuvioMedia/NuvioDesktop/issues/141 (closed, stale): "Gsync causes interface to be extremely laggy". This is the UI, not video.
- https://github.com/NuvioMedia/NuvioDesktop/issues/269 (closed, unlabeled-bot): UI flicker and roughly 30fps pacing on a 360Hz Windows display. It explicitly says "Video playback itself is unaffected".
- https://github.com/NuvioMedia/NuvioDesktop/pull/275 (closed, not merged): an opt-in Skiko OpenGL renderer setting. The maintainer later landed the same idea as commit `7e753650` "feat: add Windows OpenGL renderer option" (tapframe, 2026-08-10).
- https://github.com/NuvioMedia/NuvioDesktop/issues/596 (open): the homescreen does not feel 120Hz on an LG C1. The answer given is Advanced, then "Use OpenGL renderer".
- https://github.com/NuvioMedia/NuvioDesktop/issues/691: a Linux HDR bug. It mentions a 165 Hz display in the hardware info only.
- Linux overlay flicker: #507, #535, #579, #702, #704. PR #512 mentions "full frame rate" for the controls overlay.
- https://github.com/NuvioMedia/NuvioDesktop/issues/353: OLED letterbox black level, not refresh-related.
- https://github.com/NuvioMedia/NuvioDesktop/issues/63: "display mode" here means aspect/stretch mode for 21:9.
- Others: #218 (Discord share flicker); #379/#380 (spinner stutter); #525/#526 (list stutter); #354/#355 (GIF fps); #425 (JNI polling fps); #685 (PiP resize); #227/#274 (Hz in hardware info); #58/#142 comments (Linux choppy/dropped frames during bring-up); #643 (CPU); many "monitor" hits in the sense of an event monitor.
- Commits matching "match", "refresh" or "display" are all unrelated: i18n refreshes, UI "match TV" commits, `2dfb02ce` "inhibit Windows display sleep during playback", `621f8d69` "maximize to the screen the window is on", and `1bee399a` display *scaling*.

## Feature-request rules (CONTRIBUTING.md + .github/ISSUE_TEMPLATE/feature_request.yml on Dev)

- Blank issues are disabled (`config.yml`), so the template must be used. Title prefix: `[Feature]: `. The `enhancement` label is applied automatically. A bot closes unlabeled issues (`close-unlabeled-issues.yml`); #737 was re-filed as #738 for exactly this reason.
- Fields:
  1. **Area (tag)**, required dropdown. "Playback" or "Platform-specific (Windows / macOS / Linux)" fits.
  2. **Problem statement**, required.
  3. **Proposed solution**, required.
  4. **Are you planning to implement this yourself?**, required. Options: "No, only a proposal" / "Maybe, but only if approved first" / "Yes, but I understand implementation still needs maintainer approval". The template notes "Major features are usually implemented in-house unless approved first".
  5. **Alternatives considered**, optional.
  6. **Additional context**, optional (mockups, examples from other apps).
- One feature per issue. "The more real-world your use case is, the easier it is to evaluate". Requests are reviewed "as product proposals first".
- Approval process: any large or non-bug-fix change **must** first be a feature request, then **wait for explicit maintainer approval on that issue**, then link the approved issue in the PR. Without that link the PR is "not reviewed at all" and closed immediately. Being open, popular or labeled `enhancement` does **not** count as approval.
- Refresh-rate matching is a playback **behavior** change and a new setting, so a PR for it needs an approved feature request first. Behavior PRs must describe the old behavior, the unwanted behavior, the new behavior, and how it was tested. The PR template has an "Approved larger or directional change" checkbox, and `pr-template-check.yml` enforces the template. Dependency additions and architecture changes also need prior approval.

## Uncertain / not checkable

- The GitHub code search indexes only the default branch (`Dev`). Unmerged branches and forks were not searched, except through the PR code check that someone else already did.
- Deleted or transferred issues cannot be seen. Issue numbers go up to #777 while the API returned 772 items; the gaps are probably deleted items or unused numbers, which was not verified.
- The maintainers may use private channels (Discord and similar) for roadmap discussion. The repo points only to Issues, but a private plan cannot be ruled out.
- Reactions and emoji votes were not examined.
- The claim that the iOS `interpolation` toggle has no effect without `video-sync=display-*` is inferred from mpv documentation and was not tested.
- #761's "choppy + tearing" could be judder from a refresh mismatch or a decode/render problem. Nobody diagnosed it in the thread.
