# Slow-motion camera cadence analysis (fork tooling). Usage: see PROGRESS.md "Owner slow-motion videos".
# Input: <dir>/strip<id>.gray (ffmpeg: scale=960:-1,crop=960:400:0:250,format=gray,scale=960:1:flags=area,showinfo)
#        <dir>/pts<id>.txt (pts_time per frame from showinfo). Args: <dir> <id> <display Hz>
import sys, numpy as np
S, vid, hz = sys.argv[1], sys.argv[2], float(sys.argv[3])
W = 960
prof = np.fromfile(f"{S}/strip{vid}.gray", dtype=np.uint8).reshape(-1, W).astype(float)
t = np.array([float(x) for x in open(f"{S}/pts{vid}.txt")]) * 1000.0  # ms
n = min(len(t), len(prof)); prof, t = prof[:n], t[:n]
# pattern period from the average spectrum
spec = np.abs(np.fft.rfft(prof - prof.mean(1, keepdims=True), axis=1)).mean(0)
k = np.argmax(spec[2:60]) + 2
x = np.arange(W)
# refine period with a fine search around W/k
best = max(np.linspace(W/k*0.9, W/k*1.1, 400), key=lambda P: np.abs(((prof - prof.mean(1, keepdims=True)) @ np.exp(-2j*np.pi*x/P))).mean())
P = best
ph = np.angle((prof - prof.mean(1, keepdims=True)) @ np.exp(-2j*np.pi*x/P))
pos = np.unwrap(ph) * P / (2*np.pi)          # pixels, continuous
d = np.diff(pos)
step = np.median(np.abs(d[np.abs(d) > 0.3*np.percentile(np.abs(d), 99)]))  # rough per-transition jump
# plateau detection: cluster positions into levels spaced by the video-frame step
# estimate the step from the histogram of |d| of "moving" samples summed over consecutive moves
moving = np.abs(d) > 1.0
# group consecutive moving samples into transitions
trans = []  # (start_idx, end_idx)
i = 0
while i < len(d):
    if moving[i]:
        j = i
        while j + 1 < len(d) and moving[j+1]: j += 1
        trans.append((i, j + 1)); i = j + 1
    else: i += 1
steps = np.array([pos[e] - pos[s] for s, e in trans])
S0 = np.median(np.abs(steps))
# transition time = time where position crosses the midpoint between the two plateaus
times = []
for s, e in trans:
    a, b = pos[s], pos[e]
    if abs(b - a) < 0.5*S0 or abs(b - a) > 1.5*S0: times.append(None); continue
    mid = (a + b) / 2
    for q in range(s, e):
        if (pos[q]-mid)*(pos[q+1]-mid) <= 0:
            f = (mid - pos[q]) / (pos[q+1] - pos[q]) if pos[q+1] != pos[q] else 0.5
            times.append(t[q] + f*(t[q+1]-t[q])); break
    else: times.append(None)
# hold durations between consecutive clean transitions
per = 1000.0/hz
holds = []
for a, b in zip(times, times[1:]):
    if a is not None and b is not None: holds.append(b - a)
holds = np.array(holds)
ideal = 1000/23.976
print(f"video {vid}: {n} camera frames, {t[-1]-t[0]:.0f} ms; pattern period {P:.1f} px; step/frame {S0:.1f} px; transitions {len(trans)} (odd sizes {sum(x is None for x in times)})")
print(f"hold time: median {np.median(holds):.2f} ms, mean {holds.mean():.2f} (ideal {ideal:.2f}), std {holds.std():.2f}, min {holds.min():.1f}, max {holds.max():.1f}")
r = np.round(holds/per).astype(int)
vals, cnt = np.unique(r, return_counts=True)
print(f"in refreshes of {per:.3f} ms: " + ", ".join(f"{v}x{c}" for v, c in zip(vals, cnt)))
err = np.abs(holds - r*per)
print(f"distance to nearest whole refresh: median {np.median(err):.2f} ms, p90 {np.percentile(err,90):.2f} ms")
seq = "".join(str(v) if v < 10 else chr(ord('A')+v-10) for v in r[:120])
print("first 120 holds (A=10,B=11,C=12):", seq)
