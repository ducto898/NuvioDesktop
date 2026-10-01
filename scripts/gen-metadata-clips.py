"""
HDR metadata test (nuvio-rr fork, 2026-10-01): two HDR10 clips with IDENTICAL pixels and different static metadata,
to see whether a player's HDR metadata reaches the monitor (and whether the monitor uses it).

  hdr-meta-A-10000nits.mkv : mastering display 10000 nits, MaxCLL 10000 / MaxFALL 400
  hdr-meta-B-400nits.mkv   : mastering display   400 nits, MaxCLL   400 / MaxFALL 100

Picture (2560x1440, PQ / BT.2020, 30 s still): a 0..10000-nit grey ramp, grey patches 100..10000 nits, near-black
steps 0.005..5 nits, and saturated colour patches at 1000 and 4000 nits, each labelled. If the monitor tone-maps by
the metadata, the bright patches look different between A and B; identical = the metadata is ignored or never
arrives. After encoding, the decoded frames of A and B are compared (must be identical) and the metadata is read back.

Usage: python scripts/gen-metadata-clips.py [out_dir]   (default: testdata). Needs ffmpeg/ffprobe, numpy, Pillow.
"""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 2560, 1440
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "testdata")


def pq(nits):
    """SMPTE ST 2084 inverse EOTF: absolute nits -> signal 0..1."""
    y = np.clip(np.asarray(nits, dtype=np.float64) / 10000.0, 0, 1)
    m1, m2, c1, c2, c3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875
    p = np.power(y, m1)
    return np.power((c1 + c2 * p) / (1 + c3 * p), m2)


def frame():
    nits = np.zeros((H, W, 3))
    # Grey ramp 0..10000 nits, linear in PQ signal (top band).
    ramp_signal = np.linspace(0, 1, W)
    m1, m2, c1, c2, c3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875
    e = np.power(ramp_signal, 1 / m2)
    ramp_nits = 10000 * np.power(np.maximum(e - c1, 0) / (c2 - c3 * e), 1 / m1)
    nits[60:300, :, :] = ramp_nits[None, :, None]
    labels = [(20, 310, "0 nits  ->  grey ramp (PQ-linear)  ->  10000 nits")]
    # Grey patches.
    greys = [100, 203, 400, 600, 800, 1000, 1500, 2000, 3000, 4000, 10000]
    pw = W // len(greys)
    for i, n in enumerate(greys):
        nits[380:680, i * pw + 10:(i + 1) * pw - 10, :] = n
        labels.append((i * pw + 20, 690, f"{n}"))
    # Near-black steps.
    blacks = [0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5]
    bw = W // len(blacks)
    for i, n in enumerate(blacks):
        nits[760:960, i * bw + 10:(i + 1) * bw - 10, :] = n
        labels.append((i * bw + 20, 970, f"{n}"))
    # Saturated colours at 1000 and 4000 nits (BT.2020 primaries / secondaries, peak channel at the level).
    colours = [(1, 0, 0), (0, 1, 0), (0, 0, 1), (1, 1, 0), (0, 1, 1), (1, 0, 1)]
    cw = W // (2 * len(colours))
    for j, level in enumerate([1000, 4000]):
        for i, c in enumerate(colours):
            x0 = (j * len(colours) + i) * cw
            nits[1040:1340, x0 + 10:x0 + cw - 10, :] = np.array(c) * level
        labels.append((j * len(colours) * cw + 20, 1350, f"colours at {level} nits"))
    signal = pq(nits)
    # Labels: 203-nit grey text, drawn the same into both clips.
    mask = Image.new("L", (W, H), 0)
    draw = ImageDraw.Draw(mask)
    font = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 34)
    draw.text((20, 10), "HDR metadata test - same pixels in A and B, only the HDR10 metadata differs", fill=255, font=font)
    for x, y, text in labels:
        draw.text((x, y), text, fill=255, font=font)
    m = np.asarray(mask)[:, :, None] > 127
    signal = np.where(m, pq(203), signal)
    return np.round(signal * 65535).astype("<u2")


def encode(raw: Path, out: Path, master_l_max: int, max_cll: str):
    x265 = (
        "repeat-headers=1:log-level=error:hdr10=1:hdr10-opt=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc"
        f":master-display=G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L({master_l_max * 10000},50)"
        f":max-cll={max_cll}"
    )
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb48le", "-s", f"{W}x{H}", "-framerate", "24000/1001", "-stream_loop", "-1",
        "-i", str(raw), "-t", "30",
        "-vf", "scale=in_range=pc:out_range=tv:out_color_matrix=bt2020nc,format=yuv420p10le,"
               "setparams=range=tv:color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc",
        "-c:v", "libx265", "-preset", "medium", "-crf", "12", "-profile:v", "main10", "-x265-params", x265,
        "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-color_range", "tv",
        str(out),
    ], check=True)


def decoded_hash(path: Path) -> str:
    raw = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path), "-frames:v", "48",
                          "-f", "rawvideo", "-"], check=True, capture_output=True).stdout
    return hashlib.sha256(raw).hexdigest()


def metadata(path: Path) -> list:
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#1",
                            "-show_frames", "-show_entries", "frame=side_data_list", "-of", "json", str(path)],
                           check=True, capture_output=True, text=True).stdout
    frames = json.loads(probe).get("frames", [])
    return frames[0].get("side_data_list", []) if frames else []


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    raw = OUT / "hdr-meta.rgb48"
    raw.write_bytes(frame().tobytes())
    clips = [
        (OUT / "hdr-meta-A-10000nits.mkv", 10000, "10000,400"),
        (OUT / "hdr-meta-B-400nits.mkv", 400, "400,100"),
    ]
    for path, peak, cll in clips:
        encode(raw, path, peak, cll)
    raw.unlink()
    hashes = [decoded_hash(path) for path, _, _ in clips]
    print("decoded frames identical:", hashes[0] == hashes[1])
    for path, _, _ in clips:
        print(path.name, json.dumps(metadata(path)))
    if hashes[0] != hashes[1]:
        sys.exit("FAIL: the two clips decode to different pixels")


if __name__ == "__main__":
    main()
