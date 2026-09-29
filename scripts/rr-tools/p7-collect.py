#!/usr/bin/env python3
"""Phase 7 judge + rate-off collector (SPEC P7-5, P7-7..P7-13).

  p7-collect.py judge <run folder> [...]    one PASS/FAIL line per run against the P7 limits
  p7-collect.py rateoff <root> [--out f]    every `rate-off` / `resample-unhealthy` line of every run folder

Expected outcome per run, from the clip name and summary.json (maxHz, feature on/off):
  feature off                          -> no feature log, upstream timing
  cap 0:   23.976/24/29.97/59.94/60/vfr -> already-at-target at 239.901, no switch call
           25/50                        -> no-suitable-mode, upstream timing
  cap 144: 23.976/24 -> 143.973; 29.97/59.94 -> 59.951; 60/vfr -> 120.000; 25/50 -> no switch
Desktop default (Q27): 239.901 live, registry 240, before/during/after.
"""
import glob
import json
import os
import re
import sys

DESKTOP_HZ, DESKTOP_REG = 239.901, 240


def load(folder):
    with open(os.path.join(folder, 'summary.json'), encoding='utf-8-sig') as f:
        return json.load(f)


def fps_of(clip):
    m = re.search(r'-(\d+(?:\.\d+)?|vfr)$', clip)
    return m.group(1) if m else None


def expected(clip, max_hz, feature_on):
    fps = fps_of(clip)
    if not feature_on:
        return ('off', None)
    if fps in ('25', '50'):
        return ('none', None)
    if max_hz and max_hz < 239:
        # this monitor under the 144 cap lists 143.973, 120 (12000/100) and 59.951: 120/119.88 is just outside the
        # 1000/1001 tolerance, so 29.97/59.94 take 59.951 (x2 / x1)
        if fps in ('23.976', '24'):
            return ('switch', 143.973)
        return ('switch', 59.951 if fps in ('29.97', '59.94') else 120.0)
    return ('already', DESKTOP_HZ)


def L(v):
    """PowerShell's ConvertTo-Json writes a one-element array as a scalar."""
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def near(a, b, tol):
    return a is not None and b is not None and abs(a - b) <= tol


def exit_wall(folder):
    """Wall time of the feature's app-exit line: samples and underruns from there on show the close/restore."""
    p = os.path.join(folder, 'refresh-rate.log')
    if os.path.isfile(p):
        with open(p, encoding='utf-8', errors='replace') as fh:
            for line in fh:
                if ' K app-exit' in line:
                    return line[:12]
    return None


def late_underruns(folder):
    """Audio underruns from 5 s after file-loaded until app-exit."""
    end = exit_wall(folder) or '99'
    n, loaded = 0, None
    for p in glob.glob(os.path.join(folder, 'nuvio-rr-*.log')):
        with open(p, encoding='utf-8', errors='replace') as fh:
            for line in fh:
                if loaded is None and re.search(r'\bE file-loaded\b', line):
                    h, m, sec = line[:12].split(':')
                    loaded = int(h) * 3600 + int(m) * 60 + float(sec)
                if 'Audio device underrun detected' in line and loaded is not None and line[:12] < end:
                    h, m, sec = line[:12].split(':')
                    if int(h) * 3600 + int(m) * 60 + float(sec) - loaded > 5:
                        n += 1
    return n


def late_speed(folder):
    """Min/max video and audio speed correction over the samples with time-pos >= 5 s, before app-exit."""
    end = exit_wall(folder) or '99'
    vals = {'video': [], 'audio': []}
    for p in glob.glob(os.path.join(folder, 'nuvio-rr-*.log')):
        with open(p, encoding='utf-8', errors='replace') as fh:
            for line in fh:
                if ' S ' not in line[:15] or line[:12] >= end:
                    continue
                d = dict(re.findall(r'(\S+?)=(\S+)', line))
                try:
                    if float(d.get('time-pos', 'x')) < 5:
                        continue
                except ValueError:
                    continue
                for k in vals:
                    try:
                        vals[k].append(float(d.get(k + '-speed-correction', 'x')))
                    except ValueError:
                        pass
    out = {}
    for k, v in vals.items():
        out[k + 'Min'] = min(v) if v else None
        out[k + 'Max'] = max(v) if v else None
    return out


def judge(folder):
    s = load(folder)
    f = []
    clip = s.get('clip', '')
    fps = fps_of(clip)
    vfr = fps == 'vfr'
    feature_on = 'feature' in s
    kind, target = expected(clip, s.get('maxHz') or 0, feature_on)
    feat = s.get('feature') or {}
    mpv = s.get('mpv') or {}
    win = s.get('windows') or {}
    a5 = mpv.get('after5s') or {}
    fault = feat.get('fault') or ''
    early = feat.get('closeAfterSwitchMs') is not None and feat.get('closeAfterSwitchMs') >= 0
    for p in L(s.get('problems')):
        f.append('problem: ' + p)
    if s.get('officialProfileWrites'):
        f.append('official profile written')
    if not near((win.get('after') or {}).get('hz'), DESKTOP_HZ, 0.01):
        f.append('after hz %s' % (win.get('after') or {}).get('hz'))
    if (win.get('before') or {}).get('regHz') != DESKTOP_REG or (win.get('after') or {}).get('regHz') != DESKTOP_REG:
        f.append('registry before/after not 240')
    reg = L((win.get('during') or {}).get('distinctRegHz'))
    if reg and reg != [DESKTOP_REG]:
        f.append('registry during %s' % reg)
    if (s.get('crash') or {}).get('hsErr') or (s.get('crash') or {}).get('werEvents'):
        f.append('crash evidence')
    during = L((win.get('during') or {}).get('distinctHz'))
    switches = L(feat.get('switchCalls'))
    sync = L(mpv.get('videoSync'))
    judged_playback = not early and not fault
    if kind == 'off':
        if (s.get('enable') or {}).get('featureLogExists'):
            f.append('feature log exists with the feature off')
        if sync and sync != ['audio']:
            f.append('video-sync %s' % sync)
    elif kind == 'none':
        if switches:
            f.append('switch call(s) %d' % len(switches))
        if sync and sync != ['audio']:
            f.append('video-sync %s' % sync)
        if any(v not in (None, '', 'na', '0.000000') for v in (L(mpv.get('displayFpsOverride')))):
            f.append('display-fps-override set %s' % mpv.get('displayFpsOverride'))
        if during and not all(near(h, DESKTOP_HZ, 0.01) for h in during):
            f.append('during hz %s' % during)
    elif judged_playback:
        if kind == 'already':
            if switches:
                f.append('switch call(s) %d at an already-at-target rate' % len(switches))
            if 'already-at-target' not in (L(feat.get('reasons'))):
                f.append('no already-at-target reason')
        else:
            if not switches:
                f.append('no switch call')
            if feat.get('switchBeforeFileLoaded') is False:
                f.append('switch after file-loaded')
            if any(ms > 4000 for ms in L(feat.get('settleMs'))):
                f.append('settle > 4 s %s' % feat.get('settleMs'))
            if not L(feat.get('restoreCalls')) and '-p7-kill' not in os.path.basename(os.path.normpath(folder)):
                f.append('no restore call')
        steady = [h for h in during if not near(h, DESKTOP_HZ, 0.01)] if kind == 'switch' else during
        if kind == 'switch' and not steady:
            f.append('never at the target during playback (%s)' % during)
        if steady and not all(near(h, target, 0.01) for h in steady):
            f.append('during hz %s (target %.3f)' % (during, target))
        if sync != ['display-resample']:
            f.append('video-sync %s' % sync)
        if not all(near(float(v), target, 0.001) for v in (L(mpv.get('displayFpsOverride')) or ['0']) if v not in ('na', '')):
            f.append('display-fps-override %s' % mpv.get('displayFpsOverride'))
        est = a5.get('estimatedDisplayFpsMedian')
        if not near(est, target, target * 0.001):
            f.append('estimated-display-fps %s' % est)
        under = late_underruns(folder)
        if under:
            f.append('underruns %s' % under)
        dpm = a5.get('dropsPlusMistimedPerMinute')
        if not vfr and (dpm is None or dpm > 1):
            f.append('drops+mistimed/min %s' % dpm)
        if not vfr:
            # P5-12 / P2b-13: judged after the first 5 s (mpv starts with a larger audio correction)
            for k, v in late_speed(folder).items():
                if v is not None and abs(v - 1.0) > 0.002:
                    f.append('%s %s (after 5 s)' % (k, v))
        if vfr:
            logs = glob.glob(os.path.join(folder, 'nuvio-rr-*.log'))
            if any('desynchronisation' in open(p, encoding='utf-8', errors='replace').read() for p in logs):
                f.append('A/V desync warning')
        if (s.get('health') or {}).get('unhealthy'):
            f.append('resample-unhealthy %s' % s['health']['unhealthy'])
    info = '%s %s dpm=%s est=%s during=%s' % (kind, '' if target is None else '%.3f' % target,
                                               a5.get('dropsPlusMistimedPerMinute'), a5.get('estimatedDisplayFpsMedian'), during)
    return (not f), info, f


def rateoff(root, out):
    rows = []
    for d in sorted(glob.glob(os.path.join(root, '*'))):
        p = os.path.join(d, 'refresh-rate.log')
        if not os.path.isfile(p):
            continue
        with open(p, encoding='utf-8', errors='replace') as fh:
            for line in fh:
                if 'rate-off p' in line or 'resample-unhealthy' in line:
                    rows.append('%-62s %s' % (os.path.basename(d), line.strip()))
    text = '# rate-off / resample-unhealthy lines in %s (SPEC P7-5/P7-13): %d\n%s\n' % (root, len(rows), '\n'.join(rows))
    if out:
        with open(out, 'w', encoding='utf-8') as fh:
            fh.write(text)
    sys.stdout.write(text)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == 'judge':
        bad = 0
        for d in args:
            try:
                ok, info, fails = judge(d)
            except (OSError, ValueError, KeyError) as e:
                ok, info, fails = False, '', ['summary unreadable: %s' % e]
            bad += not ok
            print('%s %-62s %s%s' % ('PASS' if ok else 'FAIL', os.path.basename(os.path.normpath(d)), info,
                                    '' if ok else '\n      ' + '\n      '.join(fails)))
        print('%d runs, %d FAIL' % (len(args), bad))
        sys.exit(1 if bad else 0)
    if cmd == 'rateoff':
        out = args[args.index('--out') + 1] if '--out' in args else None
        rateoff(args[0], out)
        return
    sys.exit(__doc__)


if __name__ == '__main__':
    main()
