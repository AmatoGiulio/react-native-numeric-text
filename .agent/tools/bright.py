"""Luminosita' totale della colonna che cambia, nel tempo. Nessun modello: solo i pixel."""
import glob, os, sys
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of
W, H, PAD = 1400, 480, 70
GRID = np.arange(0, 900, 5.0)

def series(path):
    if os.path.isdir(path):
        path = [p for p in sorted(glob.glob(os.path.join(path, "*.json")))
                if os.path.basename(p) != "manifest.json"][0][:-5]
    meta, frames = load(path); marks = meta["marks"]
    y0,y1,x0,x1 = ink_box(frames); win = frames[:, y0:y1, x0:x1].astype(np.float64)
    a,b = columns_of(win[-1])[-1]
    ref = win[-1][:, a:b].sum()
    zero = marks[1]["t"]; times = np.array(meta["times"])
    t, v = [], []
    for i, tv in enumerate(times):
        r = tv - zero
        if 0 <= r <= 900: t.append(r); v.append(win[i][:, a:b].sum()/ref)
    return np.interp(GRID, t, v)

names = [a.split("=",1)[0] for a in sys.argv[2:]]
data  = [series(a.split("=",1)[1]) for a in sys.argv[2:]]
print(f"  {'':22s} {'salto mediano':>14s} {'90° perc':>10s} {'massimo':>9s}")
for n, y in zip(names, data):
    d = np.abs(np.diff(y))/np.maximum(y[:-1],1e-6)
    print(f"  {n:22s} {100*np.median(d):13.1f}% {100*np.percentile(d,90):9.1f}% {100*d.max():8.1f}%")

img = Image.new("RGB", (W,H), "white"); dr = ImageDraw.Draw(img)
dr.text((12,10), "Luminosita' totale della colonna che cambia (1.0 = cifra ferma)", fill="black")
top = max(y.max() for y in data)*1.1
px = lambda x,y: (PAD + x/900*(W-2*PAD), H-PAD - y/top*(H-2*PAD-30))
for lvl in (0.25,0.5,0.75,1.0):
    dr.line([px(0,lvl),px(900,lvl)], fill=(234,234,234)); dr.text((20,px(0,lvl)[1]-6), f"{lvl:.2f}", fill=(160,160,160))
cols = [(0,0,0),(200,60,60),(60,110,200)]
for i,(n,y) in enumerate(zip(names,data)):
    dr.line([px(x,v) for x,v in zip(GRID,y)], fill=cols[i%3], width=3 if i==0 else 2)
    dr.text((PAD+10, 40+i*16), n, fill=cols[i%3])
for x in range(0,901,150): dr.text((px(x,0)[0]-10, H-PAD+8), str(x), fill=(150,150,150))
dr.text((W//2-30, H-24), "tempo (ms)", fill=(150,150,150))
img.save(sys.argv[1]); print(f"  scritto {sys.argv[1]}")
