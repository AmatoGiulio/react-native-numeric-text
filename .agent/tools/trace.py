"""Per-frame trajectory of a column through a tap burst, with the digit pair known in advance.

`interrupt.py` established that at a 220 ms cadence a column holds exactly two glyphs. This prints
what those two glyphs DO, frame by frame, so the interrupted transition's curves can be laid beside
the isolated ones in TRANSITION_MODEL.md section 3.

The question it exists to answer: when a change lands mid-transition, does the arriving glyph start
again from the full +0.59375 entry amplitude, or does it pick up from where the previous arriving
glyph had got to?
"""
import argparse, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fitroll import Glyph, edge_mask, solve_amplitudes
from ground_truth import load, ink_box, columns_of
from howmany import digit_patches
from snapshot import lift, DY, SS, BB
from interrupt import fit

ap = argparse.ArgumentParser()
ap.add_argument("prefix", nargs="?", default="artifacts/gt_ios_taps/run-1785830945530")
ap.add_argument("--col", type=int, default=-1)
ap.add_argument("--cascade", type=float, default=220.0)
ap.add_argument("--from", dest="lo", type=float, default=600)
ap.add_argument("--to", dest="hi", type=float, default=1350)
ap.add_argument("--every", type=int, default=2)
a = ap.parse_args()

meta, frames = load(a.prefix)
y0,y1,x0,x1 = ink_box(frames)
w = frames[:, y0:y1, x0:x1].astype(np.float64)
times = np.array(meta["times"]); marks = meta["marks"]; t0 = marks[0]["t"]
settled = w[-1]; groups = columns_of(settled)
c = a.col if a.col >= 0 else len(groups)+a.col
lo_x = int((groups[c-1][1]+groups[c][0])/2)
hi_x = w.shape[2] if c==len(groups)-1 else int((groups[c][1]+groups[c+1][0])/2)
shape=(w.shape[1], hi_x-lo_x)
rows = settled[:, groups[c][0]:groups[c][1]].sum(axis=1)
lit = np.nonzero(rows>rows.max()*0.05)[0]; gpx=float(lit[-1]-lit[0])
mask = edge_mask(meta, y0, shape); own = Glyph(settled[:, lo_x:hi_x], shape, gpx)
bank = {d: lift(p, shape, gpx, own.cy, own.cx) for d,p in digit_patches().items()}
nd = len(groups)-c
seq = [(m["t"]-t0, m["label"].replace(",","")[-nd]) for m in marks]
print(f"  column {c}  glyph {gpx:.0f}px  commits {[(round(t),d) for t,d in seq]}")
print("      t   u(ms)  pair |  OLD dy    s    blur    a  |  NEW dy    s    blur    a  | resid")
for i in range(len(times)):
    rel = times[i]-t0
    if rel < a.lo or rel > a.hi or i % a.every: continue
    active=[k for k,(tm,_) in enumerate(seq) if tm+a.cascade<=rel]
    if len(active)<2: continue
    k=active[-1]; u=rel-(seq[k][0]+a.cascade)
    d1,d2 = seq[k-1][1], seq[k][1]
    if bank.get(d1) is None or bank.get(d2) is None: continue
    tgt = w[i,:,lo_x:hi_x]; n=max(1.0,float(np.abs(tgt).sum()))
    ps, amps, e = fit([bank[d1],bank[d2]], tgt, mask)
    o,nw = ps
    print(f"  {rel:5.0f}  {u:5.0f}   {d1}+{d2} | {o[0]:+.3f} {(o[2]+o[3])/2:5.3f} {o[4]:5.3f} {amps[0]:5.3f}  |"
          f" {nw[0]:+.3f} {(nw[2]+nw[3])/2:5.3f} {nw[4]:5.3f} {amps[1]:5.3f}  | {e/n:.3f}")
