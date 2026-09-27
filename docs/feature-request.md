# Draft feature request (NOT posted)

Status: draft for owner review. Do not post without explicit approval.
Target: NuvioMedia/NuvioDesktop, "Desktop feature request" template.

---

**Title:** `[Feature]: Match display refresh rate to video frame rate (Windows)`

**Area (tag):** Playback

### Problem statement

I want film and TV content to play without judder on a high-refresh monitor.

Most desktop monitors now run at 144–280 Hz by default, and those rates are rarely an exact
multiple of the video frame rate. For example, 23.976 fps on a 280 Hz panel can't be shown with
even frame timing, so slow pans visibly stutter. Nuvio's Windows player (mpv) currently plays
at whatever refresh rate the desktop is using, and it uses mpv's default audio-clock timing.

OLED monitors add a second problem. If the panel's effective refresh rate changes during
playback (VRR/G-Sync swings while paused, buffering, or while the controls animate), the panel
shows visible gamma flicker. A constant refresh rate during playback avoids that.

The usual workaround is an external player setup (MPC-HC + madVR) that switches the refresh
rate. But desktop external-player support is hidden, and that setup also has its own failure
mode: the monitor stays at the switched rate if the player window is left open.

NuvioTV already has "Auto Frame Rate" matching for Android TV (Off / On start / On
start-stop), so I think the desktop app would benefit from the same idea.

### Proposed solution

An opt-in **"Match display refresh rate"** toggle in Playback settings (Windows only, default
**off**; when off, behaviour is exactly as today). When on:

1. When a video starts, read its frame rate from mpv (container fps, with the estimated fps as
   a fallback). For variable or unknown frame rates, do nothing.
2. On the monitor currently showing the player, at the same resolution, pick the highest
   available refresh rate that is an integer multiple of the frame rate (23.976 ≈ 24,
   29.97 ≈ 30, and 59.94 ≈ 60 count as matches). If no mode fits, do nothing. Example on a
   280 Hz monitor that also offers 240/100 Hz: 24p/30p/60p content → 240 Hz, 25p/50p → 100 Hz.
3. Switch while playback is held, wait for the display (and HDR) to settle, then start. Use
   display-synced timing in mpv (e.g. `video-sync=display-resample`) for as long as the
   switched mode is active.
4. Keep the rate constant for the whole session. Consecutive episodes that need the same rate
   don't switch again.
5. Restore the original mode when playback ends, the player closes, or the app exits. The
   change is temporary (never saved as the Windows default), and it also recovers after a
   crash.
6. Every display call fails safe: if anything fails, playback continues at the current rate.

Implementation notes: the logic would live in new files (a small native helper next to
`player_bridge.cpp`, plus the decision logic in Kotlin with unit tests), with only a few hook
lines in existing files. The setting would follow the same path as the existing RTX Video
Super Resolution toggle. No new dependencies.

### Are you planning to implement this yourself?

Yes, but I understand implementation still needs maintainer approval.

### Alternatives considered (optional)

- **External player (MPC-HC/madVR, mpv with a refresh-rate script):** works, but
  external-player support is hidden on desktop, and you lose Nuvio's subtitles, progress and
  next-episode handling.
- **Custom mpv config (#738):** would let users set `video-sync=display-resample`, but mpv
  can't change the Windows display mode itself.
- **Setting the monitor manually before each film:** easy to forget, and the monitor stays at
  the wrong rate afterwards.
- **VRR/G-Sync alone:** helps with judder, but its refresh-rate swings cause flicker on OLED
  panels.

### Additional context (optional)

- Precedent in this project family: NuvioTV's Auto Frame Rate matching (`FrameRateUtils.kt`).
- Other players with this feature: Kodi ("Adjust display refresh rate"), MPC-HC/madVR display
  mode changer, Plex/Jellyfin desktop apps.
- Test setup: Windows 11, RTX 4070 SUPER, 2560x1440 280 Hz WOLED (HDR10) with 240/144/120/100/60
  Hz modes.
- I'd test SDR, HDR10, Dolby Vision (profiles 5/8) and HDR10+ playback, fullscreen/windowed,
  seeking, audio-device changes, and next-episode autoplay before proposing a PR.
