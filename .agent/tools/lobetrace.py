"""I due lobi, separati e seguiti nel tempo, sui PIXEL — cosi' iOS e noi siamo letti allo stesso modo.

Per fotogramma: il profilo verticale della colonna che cambia viene diviso nel punto piu' vuoto, e
di ciascuna meta' si prendono baricentro e massa. Da li': posizione, velocita', inchiostro, punto
medio fra i due, e cosa succede attraverso ogni commit.
"""
import glob, os, sys
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of

def track(path, tmax=800.0):
    if os.path.isdir(path):
        path = [p for p in sorted(glob.glob(os.path.join(path, "*.json")))
                if os.path.basename(p) != "manifest.json"][0][:-5]
    meta, frames = load(path); marks = meta["marks"]
    y0,y1,x0,x1 = ink_box(frames); win = frames[:, y0:y1, x0:x1].astype(np.float64)
    a,b = columns_of(win[-1])[-1]
    settled = win[-1][:, a:b]; ys = np.arange(settled.shape[0], dtype=float)
    rows0 = settled.sum(axis=1); rest = (rows0*ys).sum()/rows0.sum()
    lit = np.nonzero(win[-1].sum(axis=1) > win[-1].sum(axis=1).max()*0.02)[0]
    h = float(lit[-1]-lit[0]); ref = settled.sum()
    zero = marks[1]["t"]; times = np.array(meta["times"])
    commits = [m["t"]-zero for m in marks[1:]]
    T,YT,YB,IT,IB = [],[],[],[],[]
    for i,tv in enumerate(times):
        r = tv-zero
        if not (0 <= r <= tmax): continue
        rows = win[i][:, a:b].sum(axis=1); tot = rows.sum()
        if tot <= 0: continue
        cum = np.cumsum(rows)/tot
        s,e = int(np.searchsorted(cum,0.05)), int(np.searchsorted(cum,0.95))
        if e-s < 8: continue
        sp = s + int(np.argmin(rows[s:e]))
        top,bot = rows[:sp], rows[sp:]
        if top.sum()<=0 or bot.sum()<=0: continue
        T.append(r)
        YT.append(((top*ys[:sp]).sum()/top.sum() - rest)/h)
        YB.append(((bot*ys[sp:]).sum()/bot.sum() - rest)/h)
        IT.append(top.sum()/ref); IB.append(bot.sum()/ref)
    return (np.array(T), np.array(YT), np.array(YB), np.array(IT), np.array(IB),
            np.array(commits))

def report(name, d):
    T,YT,YB,IT,IB,C = d
    mid = (YT+YB)/2.0
    dT, dB = np.diff(IT), np.diff(IB)
    corr = float(np.corrcoef(dT,dB)[0,1])
    # salti attraverso i commit contro salti normali
    isc = np.array([bool(((T[1:]-c) >= 0).any() and (abs(T[1:]-c) < 12).any()) for c in [0]] ) # placeholder
    near = np.zeros(len(T)-1, bool)
    for c in C:
        near |= np.abs(T[1:]-c) < 12
    def jump(v, m):
        dv = np.abs(np.diff(v))
        return dv[m].mean() if m.any() else float("nan")
    print(f"  {name}")
    print(f"    punto medio dei due lobi: media {mid.mean():+.3f}   oscillazione (dev.st) {mid.std():.4f}")
    print(f"    correlazione fra guadagno di un lobo e perdita dell'altro: {corr:+.3f}   (-1 = scambio perfetto)")
    print(f"    salto di POSIZIONE ai commit {jump(YT,near):.4f} / {jump(YB,near):.4f}   "
          f"altrove {jump(YT,~near):.4f} / {jump(YB,~near):.4f}")
    print(f"    salto di INCHIOSTRO ai commit {jump(IT,near):.4f} / {jump(IB,near):.4f}   "
          f"altrove {jump(IT,~near):.4f} / {jump(IB,~near):.4f}")
    print(f"    inchiostro totale: media {(IT+IB).mean():.3f}   oscillazione {(IT+IB).std():.4f}")

sets = [(a.split("=",1)[0], track(a.split("=",1)[1])) for a in sys.argv[2:]]
for n,d in sets: report(n,d)

W,H,PAD = 1400, 720, 80
img = Image.new("RGB",(W,H),"white"); dr = ImageDraw.Draw(img)
dr.text((12,10), "I due lobi nel tempo. Sinistra: posizione (e in grigio il loro punto medio). Destra: inchiostro di ciascuno.", fill="black")
cw = (W-2*PAD)//2
for r,(name,(T,YT,YB,IT,IB,C)) in enumerate(sets):
    top = 60 + r*((H-90)//len(sets)); hh = (H-120)//len(sets)
    dr.text((10, top+hh//2), name, fill="black")
    for c in C:
        if 0 <= c <= 800:
            x = PAD + c/800*cw
            dr.line([(x,top),(x,top+hh)], fill=(240,240,240))
            dr.line([(PAD+cw+40+c/800*cw,top),(PAD+cw+40+c/800*cw,top+hh)], fill=(240,240,240))
    # posizioni
    py = lambda v: top + hh/2 - v/0.9*(hh/2)
    dr.line([(PAD,py(0)),(PAD+cw,py(0))], fill=(200,200,200))
    dr.line([(PAD+x/800*cw, py(v)) for x,v in zip(T,YT)], fill=(200,60,60), width=2)
    dr.line([(PAD+x/800*cw, py(v)) for x,v in zip(T,YB)], fill=(60,110,200), width=2)
    dr.line([(PAD+x/800*cw, py(v)) for x,v in zip(T,(YT+YB)/2)], fill=(140,140,140), width=3)
    # inchiostro
    mx = max((IT+IB).max(), 1e-6)
    qy = lambda v: top + hh - v/mx*hh
    x0 = PAD+cw+40
    dr.line([(x0+x/800*cw, qy(v)) for x,v in zip(T,IT)], fill=(200,60,60), width=2)
    dr.line([(x0+x/800*cw, qy(v)) for x,v in zip(T,IB)], fill=(60,110,200), width=2)
    dr.line([(x0+x/800*cw, qy(v)) for x,v in zip(T,IT+IB)], fill=(0,0,0), width=2)
dr.text((PAD, H-30), "rosso = lobo alto   blu = lobo basso   grigio = punto medio   nero = inchiostro totale   barre chiare = commit", fill=(120,120,120))
img.save(sys.argv[1]); print(f"\n  scritto {sys.argv[1]}")
