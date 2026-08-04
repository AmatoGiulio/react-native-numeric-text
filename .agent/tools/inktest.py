"""Does the discarded glyph's ink stay on screen? A forward model, no fitting.

Per-glyph fitting cannot settle this: right after being dropped the glyph is faint, and five free
parameters on a faint blob return amplitudes that jump between 0.00 and 0.33 frame to frame. Total
ink is the opposite kind of measurement — one number per frame, noise floor 0.001, nothing fitted.

Two forward models are rendered from the analytic curves of TRANSITION_MODEL section 3 and the
capture's own settled glyphs, and their ink is compared with the frame's:

  PAIR   the change discards the old outgoing glyph outright; only the new pair is drawn
  KEEP   the discarded glyph is not cancelled, it keeps running its own transition's curves

They differ by exactly the ink of one leftover glyph, which is what the question is.
"""
import argparse, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fitroll import Glyph, edge_mask
from ground_truth import load, ink_box, columns_of
from howmany import digit_patches
from snapshot import lift

OFF, SCALE, BLUR = 0.59375, 0.3984, 0.125
def step(t, z, wn):
    t = np.maximum(np.asarray(t, float)/1000.0, 0.0)
    if z < 1.0:
        wd = wn*np.sqrt(1-z**2)
        return 1 - np.exp(-z*wn*t)*(np.cos(wd*t) + (z*wn/wd)*np.sin(wd*t))
    return 1 - np.exp(-wn*t)*(1 + wn*t)
p_off  = lambda t: step(t, 0.55, 17.8)
p_size = lambda t: step(t, 1.00, 22.7)
p_blur = lambda t: step(t, 0.91, 15.8)

ap = argparse.ArgumentParser()
ap.add_argument("prefix"); ap.add_argument("--digits", required=True)
ap.add_argument("--col", type=int, default=-1); ap.add_argument("--cascade", type=float, default=220.0)
ap.add_argument("--to", type=float, default=620.0)
ap.add_argument("--base", type=int, default=0)
a = ap.parse_args()
A,B,C = [d.strip() for d in a.digits.split(",")]
meta, frames = load(a.prefix)
y0,y1,x0,x1 = ink_box(frames); w = frames[:, y0:y1, x0:x1].astype(np.float64)
times=np.array(meta["times"]); marks=meta["marks"]; t0=marks[a.base]["t"]; D=marks[a.base+1]["t"]-t0
settled=w[-1]; g=columns_of(settled); c=a.col if a.col>=0 else len(g)+a.col
lo_x=int((g[c-1][1]+g[c][0])/2); hi_x=w.shape[2] if c==len(g)-1 else int((g[c][1]+g[c+1][0])/2)
shape=(w.shape[1], hi_x-lo_x)
rows=settled[:,g[c][0]:g[c][1]].sum(axis=1); lit=np.nonzero(rows>rows.max()*0.05)[0]
gpx=float(lit[-1]-lit[0]); cy=(lit[0]+lit[-1])/2.0; cx=(g[c][0]+g[c][1])/2.0-lo_x
mask=edge_mask(meta,y0,shape)
bank={d: lift(p, shape, gpx, cy, cx) for d,p in digit_patches().items()}
unit = float((mask*bank[C].render(0,0,1,1,0)).sum())   # one settled glyph's ink
onA=a.cascade; onB=D+a.cascade

def arriving(dig, t):
    "glyph entering from above (counting up), t since its own onset"
    return bank[dig].render(-OFF*(1-p_off(t)), 0.0, SCALE+(1-SCALE)*p_size(t),
                            SCALE+(1-SCALE)*p_size(t), BLUR*(1-p_blur(t)))
def departing_from(dig, dy0, s0, t):
    "glyph leaving downward from where it currently sits"
    return bank[dig].render(dy0 + OFF*p_off(t), 0.0, s0-(s0-SCALE)*p_size(t),
                            s0-(s0-SCALE)*p_size(t), BLUR*p_blur(t))

print(f"  gap {D:.0f} ms  column {c}  {A}->{B}->{C}  onsets {onA:.0f}/{onB:.0f} ms")
print("\n   t  since-drop | measured ink | PAIR model | KEEP model | leftover a(A) | closer")
for i in range(len(times)):
    rel=times[i]-t0
    if rel < onB-10 or rel > a.to or i%2: continue
    meas = float((w[i,:,lo_x:hi_x]*(mask>0)).sum())/unit
    tB, tA = rel-onB, rel-onA
    dyB0 = -OFF*(1-p_off(D)); sB0 = SCALE+(1-SCALE)*p_size(D)
    aC = p_size(tB); aB = 1.0-aC
    mB = departing_from(B, dyB0, sB0, tB); mC = arriving(C, tB)
    pair = float((mask*(aB*mB + aC*mC)).sum())/unit
    aA = 1.0-p_size(tA)
    mA = departing_from(A, 0.0, 1.0, tA)
    keep = pair + aA*float((mask*mA).sum())/unit
    which = "KEEP" if abs(meas-keep) < abs(meas-pair) else "PAIR"
    print(f"  {rel:5.0f} {rel-onB:8.0f}  |    {meas:.3f}     |   {pair:.3f}    |   {keep:.3f}    |"
          f"     {aA:.3f}     | {which}")
