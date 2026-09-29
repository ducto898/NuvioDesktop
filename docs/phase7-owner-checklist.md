# Phase 7 owner checklist (SPEC P7-17) — one session, ≈ 40 min

Run it **after** the unattended batch has finished (both use the PC). Note the clock time of each step (hh:mm is
enough); the log proves the rest. Stop and tell Claude if anything crashes or looks wrong.

## Part A — one long capped run (items 1–5, ≈ 20 min)
In a PowerShell window in `C:\Users\vicon\ClaudeProjects\NuvioRate\NuvioDesktop`:

```
pwsh -NoProfile -File scripts\measure.ps1 -Clip soak20-sdr-1080p-23.976 -Setting on -MaxHz 144 -Seconds 1150 -Label p7-human
```

The monitor switches to 144 Hz (measure-only cap), the clip plays windowed for ~19 min, then the window closes by
itself and the monitor goes back to 240. During playback, in any order, leave ~1 min between steps:

1. **Win+Alt+B** (HDR off). Wait until the picture is back and smooth. Then **Win+Alt+B** again (HDR on).
2. **Monitor power off**, wait ~10 s, **on**. Picture comes back at 144, smooth.
3. **Win+Ctrl+Shift+B** (graphics driver reset). Playback continues.
4. **Sleep** (Start → Power → Sleep), wake it after ~30 s. Playback resumes or the player recovers.
5. **Switch the Windows default audio device** to another output and back (sound settings / taskbar speaker).

## Part B — optional PresentMon (item 6, one UAC click, ≈ 2 min)
```
pwsh -NoProfile -File scripts\measure.ps1 -Clip sdr-1080p-23.976 -Setting on -MaxHz 144 -Fullscreen -PresentMon -Actions 'space@20,space@40' -Seconds 90 -Label p7-pm-cap
```
Click **Yes** at the UAC prompt; don't touch anything else.

## Part C — the normal dev build (items 7–9, ≈ 15 min)
Setting "Match display refresh rate" is on in the dev profile.

- For item 8 (next episode, switched) start: `pwsh -NoProfile -File scripts\run-dev.ps1 -MaxHz 144`
- For items 7 and 9 start: `pwsh -NoProfile -File scripts\run-dev.ps1`

7. A **Dolby Vision** title (P5/P8 stream) and an **HDR10+** title: colours/brightness look as with the setting off?
   Smooth? (Monitor stays at 240: already at target.)
8. (with `-MaxHz 144`) A series episode of a 24 fps show: play to near the end and let **next episode** start ⇒ no
   second black flash, still smooth. Close Nuvio ⇒ monitor back at 240.
9. A **25 fps** title (e.g. a European TV show): stays at 240 and plays normally (a slight judder is known, Q17).

Plus one yes/no: **any OLED flicker** in the player or on the browse screens (java.exe Fixed Refresh entry, FORK §9)?

Reply with: the step times from part A, yes/no per item 1–9, the flicker answer, and the titles used in 7–9.
