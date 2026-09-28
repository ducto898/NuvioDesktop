#!/usr/bin/env python3
"""Phase 7 judge + rate-off collector (SPEC P7-5, P7-7..P7-13).

  p7-collect.py judge <run folder> [...]    one PASS/FAIL line per run against the P7 limits
  p7-collect.py rateoff <root> [--out f]    every `rate-off` / `resample-unhealthy` line of every run folder

Expected outcome per run, from the clip name and summary.json (maxHz, feature on/off):
  feature off                          -> no feature log, upstream timing
  cap 0:   23.976/24/29.97/59.94/60/vfr -> already-at-target at 239.901, no switch call
           25/50                        -> no-suitable-mode, upstream timing
  cap 144: 23.976/24 -> switch to 143.973; 29.97/59.94/60/vfr -> switch to 120.000; 25/50 -> no switch
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
        return ('switch', 143.973 if fps in ('23.976', '24') else 120.0)
    return ('already', DESKTOP_HZ)


def near(a, b, tol):
    return a is not None and b is not None and abs(a - b) <= tol


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
    early = (feat.get('closeAfterSwitchMs') or -1) >= 0
    for p in s.get('problems') or []:
        f.append('problem: ' + p)
    if s.get('officialProfileWrites'):
        f.append('official profile written')
    if not near((win.get('after') or {}).get('hz'), DESKTOP_HZ, 0.01):
        f.append('after hz %s' % (win.get('after') or {}).get('hz'))
    if (win.get('before') or {}).get('regHz') != DESKTOP_REG or (win.get('after') or {}).get('regHz') != DESKTOP_REG:
        f.append('registry before/after not 240')
    reg = (win.get('during') or {}).get('distinctRegHz') or []
    if reg and reg != [DESKTOP_REG]:
        f.append('registry during %s' % reg)
    if (s.get('crash') or {}).get('hsErr') or (s.get('crash') or {}).get('werEvents'):
        f.append('crash evidence')
    during = (win.get('during') or {}).get('distinctHz') or []
    switches = feat.get('switchCalls') or []
    sync = mpv.get('videoSync') or []
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
        if any(v not in (None, '', 'na', '0.000000') for v in (mpv.get('displayFpsOverride') or [])):
            f.append('display-fps-override set %s' % mpv.get('displayFpsOverride'))
        if during and not all(near(h, DESKTOP_HZ, 0.01) for h in during):
            f.append('during hz %s' % during)
    elif judged_playback:
        if kind == 'already':
            if switches:
                f.append('switch call(s) %d at an already-at-target rate' % len(switches))
            if 'already-at-target' not in (feat.get('reasons') or []):
                f.append('no already-at-target reason')
        else:
            if not switches:
                f.append('no switch call')
            if feat.get('switchBeforeFileLoaded') is False:
                f.append('switch after file-loaded')
            if any(ms > 4000 for ms in feat.get('settleMs') or []):
                f.append('settle > 4 s %s' % feat.get('settleMs'))
            if not feat.get('restoreCalls') and not s.get('actions', '').count('kill'):
                f.append('no restore call')
        steady = [h for h in during if not near(h, DESKTOP_HZ, 0.01)] if kind == 'switch' else during
        if kind == 'switch' and not steady:
            f.append('never at the target during playback (%s)' % during)
        if steady and not all(near(h, target, 0.01) for h in steady):
            f.append('during hz %s (target %.3f)' % (during, target))
        if sync != ['display-resample']:
            f.append('video-sync %s' % sync)
        if not all(near(float(v), target, 0.001) for v in (mpv.get('displayFpsOverride') or ['0']) if v not in ('na', '')):
            f.append('display-fps-override %s' % mpv.get('displayFpsOverride'))
        est = a5.get('estimatedDisplayFpsMedian')
        if not near(est, target, target * 0.001):
            f.append('estimated-display-fps %s' % est)
        under = (mpv.get('audioUnderruns') or {}).get('afterFirst5s')
        if under:
            f.append('underruns %s' % under)
        dpm = a5.get('dropsPlusMistimedPerMinute')
        if not vfr and (dpm is None or dpm > 1):
            f.append('drops+mistimed/min %s' % dpm)
        sc = mpv.get('speedCorrection') or {}
        if not vfr:
            for k in ('videoMin', 'videoMax', 'audioMin', 'audioMax'):
                v = sc.get(k)
                if v is not None and abs(v - 1.0) > 0.002:
                    f.append('%s %s' % (k, v))
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
