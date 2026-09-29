# Phase 8 owner checklist (SPEC P8-13) — plus the open Phase 7 checklist (Q43)

One sitting, about 1 hour in total. Note clock times (hh:mm) where asked; the logs prove the rest.

## Part 1 — the fork app next to the official Nuvio (≈ 20 min)
1. **Unzip** `C:\Users\vicon\ClaudeProjects\NuvioRate\dist\Nuvio-RR-1.1.26-<commit>.zip` to a folder that stays put
   (e.g. `C:\Apps\`). You get `C:\Apps\Nuvio RR\Nuvio RR.exe`.
2. **Profile** (optional, with both apps closed): in PowerShell in the repo folder run
   `pwsh -File scripts\import-profile.ps1`. It copies your official profile into `%APPDATA%\Nuvio RR` once and never
   changes the official one.
3. **NVIDIA Control Panel** → Manage 3D settings → Program settings → Add → `C:\Apps\Nuvio RR\Nuvio RR.exe`:
   Monitor Technology = **Fixed Refresh** (the OLED flicker fix). Max Frame Rate and Power management are global
   already, so nothing else is needed.
4. **Start `Nuvio RR.exe` and the official Nuvio at the same time.** Both should work. Change one harmless setting in
   one app (e.g. a theme option): the other app doesn't change.
5. In **Nuvio RR**: Settings → Playback → Display → turn **Match display refresh rate** on (it's per PC, so the
   imported profile doesn't carry it). Play a 24 fps title: smooth? any flicker on the browse screens or in the player?
6. **No update banner** in Nuvio RR (the official app may still show one).
7. Close both.

## Part 2 — the Phase 7 checklist (≈ 40 min)
Everything in `docs/phase7-owner-checklist.md`, unchanged: part A (one long capped run: HDR toggle, monitor off/on,
driver reset, sleep, audio device), part B (optional PresentMon, one UAC click), part C (Dolby Vision, HDR10+, next
episode, 25 fps, flicker). Part C may use Nuvio RR instead of the dev build, except item 8 (next episode under the cap),
which needs `pwsh -File scripts\run-dev.ps1 -MaxHz 144`.

## Reply with
Part 1: yes/no per item 4–6, anything odd. Part 2: as its own file asks (step times, yes/no per item, titles used).
