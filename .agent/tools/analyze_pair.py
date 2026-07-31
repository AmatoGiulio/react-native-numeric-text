#!/usr/bin/env python3
"""One-command measurement pipeline: raw captures in, evidence out.

Takes a raw iOS capture and (optionally) a raw Android capture of the SAME scripted
preset, and produces everything a tuning iteration needs, in one artifacts/<name>/ dir:

    norm_*.mp4        60fps CFR normalisations (VFR kills frame arithmetic)
    onset.txt         sync-marker onsets, measured per video — never inferred
    pre_*.png         the frame before each onset (verify WHICH preset was hit)
    end_*.png         the settled last frame (verify where it landed)
    grid.png          time-aligned side-by-side filmstrip (frame_grid_fixed)
    shape.txt         roll_shape_fixed tables for both videos over the window
    REPORT.md         all of the above stitched into one reviewable page

Usage:
    python3 .agent/tools/analyze_pair.py --name human_x12 \
        --ios captures/ios_human.mov --android captures/android_human_1.mp4 \
        --span 230 --grid-stride 2 --grid-count 30

With --ios only it still normalises, finds the onset and measures shape — useful for
banking a new reference. Onsets can be overridden with --onset-ios/--onset-android.
"""
import argparse
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent.parent


def run(cmd, **kw):
    print('  $', ' '.join(str(c) for c in cmd))
    return subprocess.run([str(c) for c in cmd], check=True, **kw)


def normalise(src, dst):
    if dst.exists() and dst.stat().st_mtime >= Path(src).stat().st_mtime:
        print(f'  {dst.name} up to date')
        return
    run(['ffmpeg', '-v', 'error', '-y', '-i', src, '-vf', 'fps=60',
         '-c:v', 'libx264', '-crf', '14', '-pix_fmt', 'yuv420p', dst])


def onset_of(video):
    r = run(['python3', TOOLS / 'sync_onset.py', '--video', video, '--quiet'],
            capture_output=True, text=True)
    return int(r.stdout.strip().splitlines()[-1])


def dump(video, frame, out):
    run(['ffmpeg', '-v', 'error', '-y', '-i', video,
         '-vf', f"select='eq(n\\,{frame})',crop=iw:ih*0.35:0:ih*0.14",
         '-frames:v', '1', out])


def nframes(video):
    r = run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-count_packets',
             '-show_entries', 'stream=nb_read_packets', '-of',
             'default=noprint_wrappers=1:nokey=1', video], capture_output=True, text=True)
    return int(r.stdout.strip())


def shape(video, platform, f0, f1, label):
    r = run(['python3', TOOLS / 'roll_shape_fixed.py', '--video', video,
             '--platform', platform, '--from', f0, '--to', f1, '--label', label],
            capture_output=True, text=True)
    return r.stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--name', required=True, help='artifacts/<name>/ output directory')
    ap.add_argument('--ios', required=True)
    ap.add_argument('--android', default=None)
    ap.add_argument('--onset-ios', type=int, default=None)
    ap.add_argument('--onset-android', type=int, default=None)
    ap.add_argument('--span', type=int, default=60, help='measured window, frames from onset')
    ap.add_argument('--grid-count', type=int, default=24)
    ap.add_argument('--grid-stride', type=int, default=2)
    a = ap.parse_args()

    out = ROOT / 'artifacts' / a.name
    out.mkdir(parents=True, exist_ok=True)

    print('== normalise')
    ios = out / 'norm_ios.mp4'
    normalise(a.ios, ios)
    android = None
    if a.android:
        android = out / 'norm_android.mp4'
        normalise(a.android, android)

    print('== onsets (sync marker)')
    on_i = a.onset_ios if a.onset_ios is not None else onset_of(ios)
    on_a = None
    if android:
        on_a = a.onset_android if a.onset_android is not None else onset_of(android)
    (out / 'onset.txt').write_text(f'ios {on_i}\nandroid {on_a}\n')
    print(f'  ios {on_i}  android {on_a}')

    print('== identity frames — LOOK at these before believing any number below')
    dump(ios, max(0, on_i - 2), out / 'pre_ios.png')
    dump(ios, nframes(ios) - 2, out / 'end_ios.png')
    if android:
        dump(android, max(0, on_a - 2), out / 'pre_android.png')
        dump(android, nframes(android) - 2, out / 'end_android.png')

    print('== shape tables')
    txt = shape(ios, 'ios', on_i, on_i + a.span, f'iOS {a.name}')
    if android:
        txt += shape(android, 'android', on_a, on_a + a.span, f'Android {a.name}')
    (out / 'shape.txt').write_text(txt)
    print(txt)

    if android:
        print('== grid')
        run(['python3', TOOLS / 'frame_grid_fixed.py', '--a', ios, '--b', android,
             '--name-a', 'iOS', '--name-b', 'Android',
             '--onset-a', on_i, '--onset-b', on_a,
             '--count', a.grid_count, '--stride', a.grid_stride,
             '--out', out / 'grid.png'])

    report = [f'# {a.name}', '',
              f'- iOS: `{a.ios}` onset {on_i}',
              f'- Android: `{a.android}` onset {on_a}' if android else '- Android: (none)',
              f'- window: onset .. onset+{a.span} frames',
              '',
              'Verify `pre_*.png` shows the expected starting value and `end_*.png` the',
              'expected settled value BEFORE reading the tables (wrong-preset trap).',
              '', '```', txt.rstrip(), '```', '']
    if android:
        report.append('![grid](grid.png)')
    (out / 'REPORT.md').write_text('\n'.join(report) + '\n')
    print(f'\nreport -> {out / "REPORT.md"}')


if __name__ == '__main__':
    sys.exit(main())
