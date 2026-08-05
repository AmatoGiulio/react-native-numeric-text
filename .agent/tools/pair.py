"""Lo scambio di peso fra i due lobi: si compensa? e il totale sta fermo?"""
import glob, os, sys
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of

def track(path, tmax=800.0):
    if os.path.isdir(path):
        path = [p for p in sorted(glob.glob(os.path.join(path,"*.json")))
                if os.path.basename(p) != "manifest.json"][0][:-5]
    meta, frames = load(path); marks = meta["marks"]
    y0,y1,x0,x1 = ink_box(frames); win = frames[:, y0:y1, x0:x1].astype(np.float64)
    a,b = columns_of(win[-1])[-1]
    settled = win[-1][:, a:b]; ref = settled.sum()
    zero = marks[1]["t"]; times = np.array(meta["times"])
    T,IT,IB = [],[],[]
    for i,tv in enumerate(times):
        r = tv-zero
        if not (0 <= r <= tmax): continue
        rows = win[i][:, a:b].sum(axis=1); tot = rows.sum()
        if tot<=0: continue
        cum = np.cumsum(rows)/tot
        s,e = int(np.searchsorted(cum,0.05)), int(np.searchsorted(cum,0.95))
        if e-s<8: continue
        sp = s+int(np.argmin(rows[s:e]))
        if rows[:sp].sum()<=0 or rows[sp:].sum()<=0: continue
        T.append(r); IT.append(rows[:sp].sum()/ref); IB.append(rows[sp:].sum()/ref)
    return np.array(T), np.array(IT), np.array(IB)

sets = [(x.split("=",1)[0], track(x.split("=",1)[1])) for x in sys.argv[2:]]
print(f"  {'':26s} {'totale: oscill.':>16s} {'squilibrio medio':>17s} {'compensazione':>14s}")
for n,(T,IT,IB) in sets:
    tot = IT+IB; diff = np.abs(IT-IB)/np.maximum(tot,1e-9)
    corr = float(np.corrcoef(np.diff(IT), np.diff(IB))[0,1])
    print(f"  {n:26s} {tot.std():16.4f} {diff.mean():17.3f} {corr:+14.3f}")
print("  (compensazione: -1 = cio' che uno perde l'altro lo guadagna esattamente)")

W,H,PAD = 1400, 520, 80
img = Image.new("RGB",(W,H),"white"); d = ImageDraw.Draw(img)
d.text((12,10), "Sopra: inchiostro TOTALE della colonna. Sotto: squilibrio fra i due lobi.", fill="black")
cols=[(0,0,0),(200,60,60),(60,110,200)]
mx = max((IT+IB).max() for _,(T,IT,IB) in sets)*1.05
for panel,(lab,fn,scale) in enumerate((("totale", lambda IT,IB:(IT+IB), mx),
                                       ("squilibrio |alto-basso| / totale", lambda IT,IB: np.abs(IT-IB)/np.maximum(IT+IB,1e-9), 1.0))):
    top = 50+panel*230
    d.text((PAD, top-14), lab, fill=(120,120,120))
    for lv in (0.25,0.5,0.75,1.0):
        y = top+200-lv*200
        d.line([(PAD,y),(W-PAD,y)], fill=(240,240,240))
        d.text((30,y-6), f"{lv*scale:.2f}", fill=(165,165,165))
    for i,(n,(T,IT,IB)) in enumerate(sets):
        v = fn(IT,IB)/scale
        d.line([(PAD+t/800*(W-2*PAD), top+200-min(1,x)*200) for t,x in zip(T,v)],
               fill=cols[i%3], width=3 if i==0 else 2)
        if panel==0: d.text((PAD+10, top+8+i*15), n, fill=cols[i%3])
d.text((W//2-30, H-24), "tempo (ms)", fill=(150,150,150))
img.save(sys.argv[1]); print(f"\n  scritto {sys.argv[1]}")
