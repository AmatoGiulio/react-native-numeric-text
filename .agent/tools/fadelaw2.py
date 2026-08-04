"""The discarded glyph's fade, measured with its geometry CONSTRAINED.

Fitting a faint, shrunk, blurred glyph with five free parameters is not a measurement — the earlier
free fit returned amplitudes jumping between 0.00 and 0.33 frame to frame. Here the discarded glyph's
offset, scale and blur are pinned to what "it simply keeps running its own transition" predicts
(TRANSITION_MODEL section 3), leaving ONE free number, its amplitude. The other two glyphs stay free.

Then the hypothesis is testable two ways at once: does the constrained fit reconstruct the frame as
well as the free one, and does the amplitude it returns follow 1 - p_alpha(t - its own onset)?
"""
import argparse, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fitroll import Glyph, edge_mask, solve_amplitudes
from ground_truth import load, ink_box, columns_of
from howmany import digit_patches
from snapshot import lift
from interrupt import fit

OFF, SCALE, BLUR = 0.59375, 0.3984, 0.125

def step(t_ms, zeta, wn):
    t = np.maximum(np.asarray(t_ms, float)/1000.0, 0.0)
    if zeta < 1.0:
        wd = wn*np.sqrt(1-zeta**2)
        return 1 - np.exp(-zeta*wn*t)*(np.cos(wd*t) + (zeta*wn/wd)*np.sin(wd*t))
    return 1 - np.exp(-wn*t)*(1 + wn*t)

p_off  = lambda t: step(t, 0.55, 17.8)
p_size = lambda t: step(t, 1.00, 22.7)
p_blur = lambda t: step(t, 0.91, 15.8)

ap = argparse.ArgumentParser()
ap.add_argument("prefix"); ap.add_argument("--digits", required=True)
ap.add_argument("--col", type=int, default=-1); ap.add_argument("--cascade", type=float, default=220.0)
ap.add_argument("--to", type=float, default=640.0)
ap.add_argument("--base", type=int, default=0, help="mark index of the triple's FIRST commit")
a = ap.parse_args()
A,B,C = [d.strip() for d in a.digits.split(",")]
meta, frames = load(a.prefix)
y0,y1,x0,x1 = ink_box(frames); w = frames[:, y0:y1, x0:x1].astype(np.float64)
times=np.array(meta["times"]); marks=meta["marks"]; t0=marks[a.base]["t"]; delta=marks[a.base+1]["t"]-t0
settled=w[-1]; g=columns_of(settled); c=a.col if a.col>=0 else len(g)+a.col
lo_x=int((g[c-1][1]+g[c][0])/2); hi_x=w.shape[2] if c==len(g)-1 else int((g[c][1]+g[c+1][0])/2)
shape=(w.shape[1], hi_x-lo_x)
rows=settled[:,g[c][0]:g[c][1]].sum(axis=1); lit=np.nonzero(rows>rows.max()*0.05)[0]
gpx=float(lit[-1]-lit[0]); cy=(lit[0]+lit[-1])/2.0; cx=(g[c][0]+g[c][1])/2.0-lo_x
mask=edge_mask(meta,y0,shape)
bank={d: lift(p, shape, gpx, cy, cx) for d,p in digit_patches().items()}
onA=a.cascade; onB=delta+a.cascade
sign = +1.0   # counting up: the departing glyph travels DOWN (+dy)
print(f"  gap {delta:.0f} ms  column {c}  {A}->{B}->{C}  onsets {onA:.0f} / {onB:.0f} ms")
print("\n   t   since-drop | a(A) free | a(A) constrained | predicted | resid free | resid constr | resid if FORCED")
for i in range(len(times)):
    rel=times[i]-t0
    if rel < onB-10 or rel > a.to or i%2: continue
    tgt=w[i,:,lo_x:hi_x]; n=max(1.0,float(np.abs(tgt).sum()))
    # free three-glyph fit
    _, amps_f, e_f = fit([bank[A],bank[B],bank[C]], tgt, mask)
    # constrained: A's geometry pinned to "it keeps running its own transition"
    tA = rel-onA
    dyA = sign*OFF*p_off(tA); sA = 1-(1-SCALE)*p_size(tA); blA = BLUR*p_blur(tA)
    mA = mask*bank[A].render(dyA, 0.0, sA, sA, blA)
    psBC, _, _ = fit([bank[B],bank[C]], tgt - 0.0*mA, mask)
    mB = mask*bank[B].render(*psBC[0][:5]); mC = mask*bank[C].render(*psBC[1][:5])
    amps = solve_amplitudes([mA,mB,mC], tgt, hi=1.05)
    e_c = float(np.abs(tgt - sum(x*m for x,m in zip(amps,[mA,mB,mC]))).sum())
    pred = 1.0 - p_size(tA)
    forced = solve_amplitudes([mB,mC], tgt - pred*mA, hi=1.05)
    e_forced = float(np.abs(tgt - pred*mA - sum(x*m for x,m in zip(forced,[mB,mC]))).sum())
    print(f"  {rel:5.0f}  {rel-onB:8.0f}  |   {amps_f[0]:.3f}   |      {amps[0]:.3f}       |   {pred:.3f}   "
          f"|   {e_f/n:.3f}    |    {e_c/n:.3f}     |     {e_forced/n:.3f}")
