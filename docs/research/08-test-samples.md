# 08 — Real Dolby Vision / HDR10+ samples for the manual checks (SPEC P2-8)

Checked 2026-09-28. The generated clips (`scripts/gen-testclips.ps1`) cover SDR and static HDR10 only; these are for
the Phase 7 manual checks on the HDR10-only MO27Q28G (DV P5 must be reshaped to correct PQ/BT.2020, P8 plays its
HDR10 base layer, HDR10+ falls back to static metadata). Download into `testdata\real\` (git-ignored).

| Need | Source | What's there | Terms |
|---|---|---|---|
| **DV profile 5** | [Jellyfin test videos](https://repo.jellyfin.org/test-videos/) → `HDR/Dolby Vision/` (mirror: [fra1.mirror.jellyfin.org](https://fra1.mirror.jellyfin.org/test-videos/)) | P5 at 1080p / 4K / 8K | CC BY-SA 4.0, courtesy of Gnattu (stated on the page) |
| **DV profile 8** | same folder | P8.1 (HDR10-compatible base) and P8.4 (HLG base) at 1080p / 4K / 8K. Use **P8.1** for the "plays as HDR10" check | CC BY-SA 4.0 |
| **HDR10+** | [FF Pictures "1 minute HDR10+ system test"](https://ff.de/hdr10plus-metadata-test/) | 1-min looped MP4 with deliberately extreme HDR10+ dynamic metadata; download via a WeTransfer link on the page, no registration seen | "free" test file; no explicit licence stated — personal testing only |
| HDR10+ (alternative) | [DVB HDR test content with dynamic mapping metadata](https://dvb.org/specifications/verification-validation/hdr-test-content/) | HEVC streams with SMPTE 2094-40 SEI | DVB terms on the page; not checked in detail |
| Any of the above (fallback) | the owner's own streams | DV P5/P8 and HDR10+ are common in the owner's sources | own use |

Uncertain / not verified: the exact file names inside the Jellyfin `Dolby Vision` subfolders (listed only by profile
and resolution when fetched), and whether the WeTransfer link is still live.

## Verify a sample before using it
```powershell
# Dolby Vision: expect side data "DOVI configuration record" with dv_profile=5 or 8 (dv_bl_signal_compatibility_id=1 for 8.1)
ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,profile,pix_fmt,color_transfer:stream_side_data -of compact <file>
# HDR10+: expect frame side data "HDR Dynamic Metadata SMPTE2094-40 (HDR10+)"
ffprobe -v error -select_streams v:0 -read_intervals "%+#3" -show_entries frame=side_data_list -of compact <file>
```
Play each with `scripts\measure.ps1 -Clip <path>`; the sampler log records `gamma`/`primaries` and mpv's own log lines about
Dolby Vision reshaping (libplacebo v7.357 in the bundled libmpv, see 03).
