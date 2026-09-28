#!/usr/bin/env python3
"""Offline replay of the display-sync health rule (SPEC P5-11, P7-6, P7-14).

Reads the per-second sampler lines (`HH:MM:SS.mmm S time-pos=... pause=... estimated-display-fps=...
frame-drop-count=... mistimed-frame-count=... display-fps=...`) that measure runs write to
`nuvio-rr-*.log`, and applies the same rule as `ResampleHealth.kt`:
  - ignore the first 5 s (IGNORE_FIRST_SECONDS) from the first sample after file-loaded,
  - judge the 10 s window ending at each sample against the last sample at or before its start,
  - skip windows with a pause, a playback advance outside 8..12 s, or a counter going backwards,
  - unhealthy if drops + mistimed > 20 in the window (independent of N),
  - rate error: |estimated-display-fps / target - 1| > 1 %; unhealthy after N judged samples in a row.
The target is the sample's `display-fps-override` if set, else `display-fps`. A change of the target
restarts the rule (the app's watcher restarts it when the display state changes).

Differences to the app (why the P7-6 cross-check exists): the app counts its 5 s from the hook, which is
before file-loaded, and samples on its own 1 s clock; so times may differ by ~1-2 s and a streak by one.

Usage: health-replay.py <run folder or measurements root> [...] [--json out.json] [--table out.txt]
Only runs whose samples show video-sync=display-resample are judged; others are listed as skipped.
"""
import glob
import json
import os
import re
import sys

IGNORE_FIRST = 5.0
WINDOW = 10.0
MIN_ADV, MAX_ADV = 8.0, 12.0
MAX_BAD = 20
MAX_RATE_ERROR = 0.01
N_RANGE = range(1, 11)

SAMPLE = re.compile(r'^(\d\d):(\d\d):(\d\d)\.(\d{3}) S (.*)$')
KV = re.compile(r'(\S+?)=(\S+)')


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def samples_of(log_path, close_wall=None):
    out, loaded = [], False
    with open(log_path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if re.search(r'\bE file-loaded\b', line):
                loaded = True
            m = SAMPLE.match(line)
            if not (loaded and m):
                continue
            wall = line[:12]
            if close_wall and wall >= close_wall:
                continue
            h, mi, s, ms = (int(x) for x in m.groups()[:4])
            d = dict(KV.findall(m.group(5)))
            t = h * 3600 + mi * 60 + s + ms / 1000.0
            if out and t < out[-1]['at'] - 43200:
                t += 86400  # midnight
            target = num(d.get('display-fps-override'))
            if not target:
                target = num(d.get('display-fps'))
            out.append(dict(at=t, wall=wall, pos=num(d.get('time-pos')), paused=d.get('pause') == 'yes',
                            est=num(d.get('estimated-display-fps')), drops=num(d.get('frame-drop-count')),
                            mistimed=num(d.get('mistimed-frame-count')), target=target, sync=d.get('video-sync')))
    return out


def wall_seconds(wall):
    h, m, rest = wall.split(':')
    return int(h) * 3600 + int(m) * 60 + float(rest)


def hook_wall(folder):
    """The app starts its health clock at the first watcher tick after the hook; the hook line is the best proxy."""
    p = os.path.join(folder, 'refresh-rate.log')
    if not os.path.isfile(p):
        return None
    with open(p, encoding='utf-8', errors='replace') as f:
        for line in f:
            if re.search(r'hook p\d+ continued', line):
                return line[:12]
    return None


def close_wall(folder):
    """The app stops judging at app-exit / screen-gone (the restore bends the estimate); None if no feature log."""
    p = os.path.join(folder, 'refresh-rate.log')
    if not os.path.isfile(p):
        return None
    with open(p, encoding='utf-8', errors='replace') as f:
        for line in f:
            if re.search(r' K (app-exit|screen-gone)', line):
                return line[:12]
    return None


def observer_states(folder):
    """(seconds, hz, hdr) at every observer tick whose state differs from the previous one."""
    p = os.path.join(folder, 'observer.csv')
    out = []
    if not os.path.isfile(p):
        return out
    with open(p, encoding='utf-8', errors='replace') as f:
        next(f, None)
        for line in f:
            c = line.strip().split(',')
            if len(c) < 9 or c[2] != 'tick':
                continue
            try:
                st = (wall_seconds(c[0]), float(c[3]), c[8])
            except ValueError:
                continue
            if not out or out[-1][1:] != st[1:]:
                out.append(st)
    return out


def replay(samples, hook=None, states=None):
    """Runs the rule with no fallback limit. Returns (streaks, bad_frame_events, judged)."""
    streaks, bad, judged = [], [], 0
    start, window, cur_target = None, [], None
    hook_at = wall_seconds(hook) + 0.5 if hook else None
    run_len, worst, run_start = 0, 0.0, None

    def close_streak(end_wall):
        nonlocal run_len, worst, run_start
        if run_len:
            streaks.append(dict(samples=run_len, worst=worst, target=cur_target, start=run_start, end=end_wall))
        run_len, worst, run_start = 0, 0.0, None

    states = states or []
    si, last_state = 0, None
    for s in samples:
        # The app judges only a steady display at the target and restarts the rule on every display change.
        while si < len(states) and states[si][0] <= s['at']:
            si += 1
        state = states[si - 1] if si else None
        if state is not None and state != last_state and last_state is not None:
            close_streak(s['wall'])
            start, window = s['at'], []
        last_state = state
        if state is not None and s['target'] and abs(state[1] / s['target'] - 1.0) > 0.001:
            close_streak(s['wall'])
            start, window = s['at'], []
            continue
        if s['target'] != cur_target:  # restart, as the app does on a display change
            close_streak(s['wall'])
            first_target = cur_target is None
            cur_target, start, window = s['target'], s['at'], []
            if first_target and hook_at is not None and hook_at <= s['at']:
                start = hook_at
        if s['at'] < start + IGNORE_FIRST:
            continue
        window.append(s)
        while len(window) > 2 and window[1]['at'] <= s['at'] - WINDOW:
            window.pop(0)
        base = window[0]
        if base['at'] > s['at'] - WINDOW:
            continue
        if s['paused'] or base['paused'] or s['pos'] is None or base['pos'] is None:
            continue
        adv = s['pos'] - base['pos']
        if adv < MIN_ADV or adv > MAX_ADV:
            continue
        if None in (s['drops'], base['drops'], s['mistimed'], base['mistimed']):
            continue
        dr, mt = s['drops'] - base['drops'], s['mistimed'] - base['mistimed']
        if dr < 0 or mt < 0:
            continue
        judged += 1
        if dr + mt > MAX_BAD:
            bad.append(dict(wall=s['wall'], drops=dr, mistimed=mt))
        off = s['est'] is not None and cur_target and abs(s['est'] / cur_target - 1.0) > MAX_RATE_ERROR
        if off:
            if run_len == 0 or abs(s['est'] - cur_target) > abs(worst - cur_target):
                worst = s['est']
            if run_len == 0:
                run_start = s['wall']
            run_len += 1
        else:
            close_streak(s['wall'])
    close_streak(samples[-1]['wall'] if samples else None)  # a stretch still open at the end counts as one
    return streaks, bad, judged


def verdict_for(n, streaks, bad):
    """First fallback time (wall text) at N, or None, and the streaks that would be logged as recovered."""
    events = [(b['wall'], 'bad-frames') for b in bad]
    recovered = []
    for st in streaks:
        if st['samples'] >= n:
            # the app falls back at the N-th sample of the streak; approximate its wall time by the streak start
            events.append((st['start'], 'rate'))
        else:
            recovered.append(st)
    if not events:
        return None, recovered
    first = min(events)
    recovered = [st for st in recovered if st['start'] < first[0]]
    return first, recovered


def run_folders(args):
    for a in args:
        if os.path.isfile(os.path.join(a, 'summary.json')) or glob.glob(os.path.join(a, 'nuvio-rr-*.log')):
            yield a
        else:
            for d in sorted(glob.glob(os.path.join(a, '*'))):
                if os.path.isdir(d) and glob.glob(os.path.join(d, 'nuvio-rr-*.log')):
                    yield d


def classify(summary, samples):
    """healthy / bad / other, from the run's own summary (P5-12 style)."""
    if not summary:
        return 'other'
    mpv = summary.get('mpv') or {}
    a5 = mpv.get('after5s') or {}
    rate = a5.get('dropsPlusMistimedPerMinute')
    if rate is None:
        rate = mpv.get('dropsPlusMistimedPerMinute')
    under = (mpv.get('audioUnderruns') or {}).get('afterFirst5s')
    problems = summary.get('problems') or []
    fault = (summary.get('feature') or {}).get('fault')
    if rate is None:
        return 'other'
    if rate > 60:
        return 'bad'
    if rate <= 1 and not under and not problems and not fault:
        return 'healthy'
    return 'other'


def main():
    argv = sys.argv[1:]
    json_out = table_out = None
    if '--json' in argv:
        i = argv.index('--json'); json_out = argv[i + 1]; del argv[i:i + 2]
    if '--table' in argv:
        i = argv.index('--table'); table_out = argv[i + 1]; del argv[i:i + 2]
    runs = []
    for d in run_folders(argv or ['measurements']):
        summary = None
        sp = os.path.join(d, 'summary.json')
        if os.path.isfile(sp):
            try:
                with open(sp, encoding='utf-8-sig') as f:
                    summary = json.load(f)
            except (OSError, ValueError):
                summary = None
        samples = []
        end = close_wall(d)
        for lp in sorted(glob.glob(os.path.join(d, 'nuvio-rr-*.log'))):
            samples += samples_of(lp, end)
        if end is None and samples:
            samples = samples[:-1]  # no feature log: the last sample may already see the close
        name = os.path.basename(os.path.normpath(d))
        if not any(s['sync'] == 'display-resample' for s in samples):
            runs.append(dict(run=name, status='skipped (no display-resample samples)'))
            continue
        ds = [s for s in samples if s['sync'] == 'display-resample']
        streaks, bad, judged = replay(ds, hook_wall(d), observer_states(d))
        per_n = {}
        for n in N_RANGE:
            first, rec = verdict_for(n, streaks, bad)
            rate_first, _ = verdict_for(n, streaks, [])
            per_n[n] = dict(fallback=(None if first is None else dict(wall=first[0], cause=first[1])),
                            rateFallback=(None if rate_first is None else rate_first[0]),
                            recovered=[st['samples'] for st in rec])
        runs.append(dict(run=name, status='judged', cls=classify(summary, ds), judged=judged,
                         streaks=streaks, badFrameWindows=len(bad), firstBad=(bad[0] if bad else None), perN=per_n))

    judged = [r for r in runs if r['status'] == 'judged']
    lines = ['# health-replay (SPEC P7-6/P7-14): %d run folders, %d judged (display-resample), %d skipped'
             % (len(runs), len(judged), len(runs) - len(judged)), '']
    lines.append('## Off-rate stretches (> 1 %) seen in any judged run (no limit applied)')
    longest = {'healthy': 0, 'other': 0}
    for r in judged:
        for st in r['streaks']:
            if r['cls'] in longest:
                longest[r['cls']] = max(longest[r['cls']], st['samples'])
            lines.append('  %-60s %-7s %2d samples  worst %.3f vs %.3f (%+.2f %%)  %s..%s'
                         % (r['run'], r['cls'], st['samples'], st['worst'], st['target'],
                            (st['worst'] / st['target'] - 1) * 100, st['start'], st['end']))
    lines.append('  longest stretch: healthy runs %d samples, other (not known-bad) runs %d samples'
                 % (longest['healthy'], longest['other']))
    lines.append('')
    lines.append('## Bad-frame windows (> %d drops + mistimed in 10 s; independent of N)' % MAX_BAD)
    for r in judged:
        if r['badFrameWindows'] and r['cls'] != 'bad':
            lines.append('  %-60s %-7s %d window(s), first %s' % (r['run'], r['cls'], r['badFrameWindows'], r['firstBad']))
    lines.append('')
    lines.append('## Per N (rate rule only): false fallbacks in healthy runs / known-bad runs caught')
    healthy = [r for r in judged if r['cls'] == 'healthy']
    badruns = [r for r in judged if r['cls'] == 'bad']
    lines.append('  healthy runs: %d, known-bad runs: %d, other: %d' % (len(healthy), len(badruns),
                 len(judged) - len(healthy) - len(badruns)))
    for n in N_RANGE:
        false_fb = [r['run'] for r in healthy if r['perN'][n]['rateFallback']]
        caught = [r for r in badruns if r['perN'][n]['rateFallback']]
        lines.append('  N=%2d  false fallbacks %d%s  bad caught %d/%d' % (
            n, len(false_fb), (' (' + ', '.join(false_fb) + ')') if false_fb else '', len(caught), len(badruns)))
    lines.append('')
    lines.append('## Known-bad runs: first bad-frame window and first rate stretch')
    for r in badruns:
        first_rate = r['streaks'][0] if r['streaks'] else None
        lines.append('  %-60s bad-frames at %s; first off-rate stretch %s' % (
            r['run'], r['firstBad']['wall'] if r['firstBad'] else 'none',
            ('%s, %d samples' % (first_rate['start'], first_rate['samples'])) if first_rate else 'none'))
    text = '\n'.join(lines) + '\n'
    if table_out:
        with open(table_out, 'w', encoding='utf-8') as f:
            f.write(text)
    if json_out:
        with open(json_out, 'w', encoding='utf-8') as f:
            json.dump(runs, f, indent=1)
    sys.stdout.write(text)


if __name__ == '__main__':
    main()
