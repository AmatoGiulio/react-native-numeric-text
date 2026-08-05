"""Tutte le grandezze del loop in un colpo solo, dai pixel."""
import glob, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of

def m(path, tmax=800.0):
    if os.path.isdir(path):
        path = [p for p in sorted(glob.glob(os.path.join(path,"*.json")))
                if os.path.basename(p) != "manifest.json"][0][:-5]
    meta, frames = load(path); marks = meta["marks"]
    y0,y1,x0,x1 = ink_box(frames); win = frames[:, y0:y1, x0:x1].astype(np.float64)
    a,b = columns_of(win[-1])[-1]
    st = win[-1][:, a:b]; ref = st.sum(); ys = np.arange(st.shape[0], dtype=float)
    r0 = st.sum(axis=1); rest = (r0*ys).sum()/r0.sum()
    lit = np.nonzero(win[-1].sum(axis=1) > win[-1].sum(axis=1).max()*0.02)[0]
    h = float(lit[-1]-lit[0])
    zero = marks[1]["t"]; times = np.array(meta["times"])
    YT,YB,IT,IB = [],[],[],[]
    for i,tv in enumerate(times):
        r = tv-zero
        if not (0 <= r <= tmax): continue
        rows = win[i][:, a:b].sum(axis=1); tot = rows.sum()
        if tot<=0: continue
        cum = np.cumsum(rows)/tot
        s,e = int(np.searchsorted(cum,0.05)), int(np.searchsorted(cum,0.95))
        if e-s<8: continue
        sp = s+int(np.argmin(rows[s:e]))
        top,bot = rows[:sp], rows[sp:]
        if top.sum()<=0 or bot.sum()<=0: continue
        YT.append(((top*ys[:sp]).sum()/top.sum()-rest)/h)
        YB.append(((bot*ys[sp:]).sum()/bot.sum()-rest)/h)
        IT.append(top.sum()/ref); IB.append(bot.sum()/ref)
    YT,YB,IT,IB = map(np.array,(YT,YB,IT,IB))
    tot = IT+IB; d = np.abs(np.diff(tot))/np.maximum(tot[:-1],1e-9)
    return dict(osc=tot.std(), ink=tot.mean(), med=np.median(d), p90=np.percentile(d,90),
                mx=d.max(), mid=((YT+YB)/2).std(), lt=YT.mean(), lb=YB.mean(),
                sep=YB.mean()-YT.mean(), comp=float(np.corrcoef(np.diff(IT),np.diff(IB))[0,1]))

hdr = f"  {'':26s} {'osc':>6s} {'ink':>6s} {'med':>6s} {'p90':>6s} {'max':>6s} {'p.med':>6s} {'sep':>6s} {'comp':>6s}"
print(hdr)
for arg in sys.argv[1:]:
    n,p = arg.split("=",1); v = m(p)
    print(f"  {n:26s} {v['osc']:6.3f} {v['ink']:6.3f} {100*v['med']:5.1f}% {100*v['p90']:5.1f}% "
          f"{100*v['mx']:5.1f}% {v['mid']:6.3f} {v['sep']:6.3f} {v['comp']:+6.3f}")
