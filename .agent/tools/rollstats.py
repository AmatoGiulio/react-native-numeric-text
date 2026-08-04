"""Roll-only scoreboard: the four numbers that say whether a continuous roll matches the reference.

`burst.py` gives sharpness and tail; this adds the two that actually moved during the port — how much
ink is on screen through the burst, and how far the column's ink centroid wanders.
"""
import glob, json, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of

def pick(pat, label='1,000'):
    for p in sorted(glob.glob(pat)):
        try:
            if json.load(open(p))['marks'][0]['label'] != label: continue
            meta, fr = load(p[:-5]); y0,y1,x0,x1 = ink_box(fr)
            if len(columns_of(fr[-1,y0:y1,x0:x1].astype(float))) == 5: return p[:-5]
        except Exception: continue
    return None

def stats(prefix):
    meta, frames = load(prefix)
    y0,y1,x0,x1 = ink_box(frames); w = frames[:, y0:y1, x0:x1].astype(np.float64)
    t = np.array(meta['times']); m0 = meta['marks'][1]['t']; ml = meta['marks'][-1]['t']
    g = columns_of(w[-1]); ink=[]; cen=[]
    for ci in (2,3,4):
        a,b = g[ci]; base = w[-1][:,a:b].sum()
        p = w[:,:,a:b]; s = p.sum(axis=(1,2))/base
        rows = p.sum(axis=2); yy = np.arange(rows.shape[1]); tot = rows.sum(axis=1)
        c = np.where(tot>0, (rows*yy).sum(axis=1)/np.maximum(tot,1e-9), 0)
        st = w[-1][:,a:b].sum(axis=1); lit = np.nonzero(st>st.max()*0.05)[0]
        gh = float(lit[-1]-lit[0]); win = (t>=m0+60)&(t<=ml)
        ink.append(s[win].mean()); cen.append((c[win].max()-c[win].min())/gh)
    return np.mean(ink), np.mean(cen)

if __name__ == "__main__":
    print(f"  {'run':28s} {'ink medio':>10s} {'escursione':>12s}")
    for d in sys.argv[1:]:
        pre = pick(os.path.join(d, "*.json"))
        if not pre:
            print(f"  {os.path.basename(d):28s}   nessun run valido"); continue
        i, c = stats(pre)
        print(f"  {os.path.basename(d):28s} {i:10.3f} {c:12.3f}")
    ios = 'artifacts/gt_ios_bursts/run-1785744941690'
    i, c = stats(ios)
    print(f"  {'SwiftUI':28s} {i:10.3f} {c:12.3f}")

def spread(prefix):
    """Vertical span the ink occupies and how dark the darkest pixel gets, through the burst."""
    meta, frames = load(prefix)
    y0,y1,x0,x1 = ink_box(frames); w = frames[:, y0:y1, x0:x1].astype(np.float64)
    t = np.array(meta['times']); m0 = meta['marks'][1]['t']; ml = meta['marks'][-1]['t']
    g = columns_of(w[-1]); ext=[]; peak=[]
    for ci in (2,3,4):
        a,b = g[ci]
        st = w[-1][:,a:b].sum(axis=1); lit = np.nonzero(st>st.max()*0.05)[0]
        gh = float(lit[-1]-lit[0]); es=[]; pk=[]
        for i in np.nonzero((t>=m0+60)&(t<=ml))[0]:
            rows = w[i,:,a:b].sum(axis=1); tot = rows.sum()
            if tot <= 0: continue
            cum = np.cumsum(rows)/tot
            es.append((np.searchsorted(cum,0.95)-np.searchsorted(cum,0.05))/gh)
            pk.append(w[i,:,a:b].max()/255.0)
        ext.append(np.mean(es)); peak.append(np.mean(pk))
    return np.mean(ext), np.mean(peak)
