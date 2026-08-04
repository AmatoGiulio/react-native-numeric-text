"""The three pictures to LOOK at after any change to the roll, side by side with the reference.

A kymograph (time across, ink position down) shows whether the column translates or stands still.
An envelope (the two edges plus the centroid) shows whether it stretches or slides. A metric can
tell you a number moved; only these say what KIND of motion changed.
"""
import os, sys
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of
from rollstats import pick

IOS = "artifacts/gt_ios_bursts/run-1785744941690"

def column(prefix, t0=200, t1=760, col=4):
    meta, frames = load(prefix)
    y0,y1,x0,x1 = ink_box(frames)
    w = frames[:, y0:y1, x0:x1].astype(np.float64)
    times = np.array(meta['times']) - meta['marks'][1]['t']
    g = columns_of(w[-1]); a,b = g[col]
    st = w[-1][:,a:b].sum(axis=1); lit = np.nonzero(st>st.max()*0.05)[0]
    cy=(lit[0]+lit[-1])/2.0; gh=float(lit[-1]-lit[0])
    sel=np.nonzero((times>=t0)&(times<=t1))[0]
    prof=np.stack([w[i,:,a:b].sum(axis=1) for i in sel],axis=1)
    return prof, cy, gh, times[sel]

def draw(out_path, ours, title):
    W,H,L = 900, 210, 100
    im = Image.new('L',(W+130, H*4+170), 255); d = ImageDraw.Draw(im)
    d.text((18,10), title, fill=0)
    d.text((18,26), "kymografo: tempo ->, scuro = inchiostro.   inviluppo: bordi (grigio) e baricentro (nero).", fill=120)
    for k,(prefix,lab) in enumerate(((IOS,"SwiftUI"),(ours,"Android"))):
        prof, cy, gh, t = column(prefix)
        lo=max(0,int(cy-1.2*gh)); hi=min(prof.shape[0],int(cy+1.2*gh))
        crop=prof[lo:hi]; crop=crop/max(1e-9,crop.max())
        img=Image.fromarray((255-np.clip(crop*255*1.6,0,255)).astype(np.uint8)).resize((W,H),Image.LANCZOS)
        top=52+k*2*(H+22)
        d.text((16,top+H//2-6), lab+" kymo", fill=0)
        im.paste(img,(L,top)); d.rectangle([L,top,L+W,top+H],outline=200)
        rest=(cy-lo)/crop.shape[0]*H
        for x in range(L,L+W,6): d.point((x,top+rest),fill=170)
        # envelope
        top2=top+H+22
        d.text((16,top2+H//2-6), lab+" inviluppo", fill=0)
        d.rectangle([L,top2,L+W,top2+H],outline=200)
        ylo,yhi=-1.1,1.1
        def ypix(v): return top2+H*(np.clip(v,ylo,yhi)-ylo)/(yhi-ylo)   # dy positivo = piu' in basso
        d.line([L,ypix(0),L+W,ypix(0)],fill=140)
        e_top=[];e_bot=[];e_cen=[]
        for j in range(prof.shape[1]):
            c=prof[:,j]; tot=c.sum()
            if tot<=0: e_top.append(np.nan);e_bot.append(np.nan);e_cen.append(np.nan); continue
            cum=np.cumsum(c)/tot
            e_top.append((np.searchsorted(cum,0.10)-cy)/gh)
            e_bot.append((np.searchsorted(cum,0.90)-cy)/gh)
            e_cen.append(((c*np.arange(len(c))).sum()/tot-cy)/gh)
        def curve(vals,shade,width):
            pts=[(L+W*(t[j]-t[0])/(t[-1]-t[0]), ypix(v)) for j,v in enumerate(vals) if not np.isnan(v)]
            for a_,b_ in zip(pts,pts[1:]): d.line([a_,b_],fill=shade,width=width)
        curve(e_top,150,2); curve(e_bot,150,2); curve(e_cen,0,3)
    im.save(out_path)
    return out_path

if __name__ == "__main__":
    ours = pick(sys.argv[1] + "/*.json")
    print(draw(sys.argv[2], ours, sys.argv[3] if len(sys.argv)>3 else "rullo"))
