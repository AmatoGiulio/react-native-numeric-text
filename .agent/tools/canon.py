#!/usr/bin/env python3
"""Il banco canonico: un solo modo di generare uno scenario, e un manifest che lo prova.

    python3 .agent/tools/canon.py render <outdir> --preset=alt60 [--env FLIP_X=1 ...]
    python3 .agent/tools/canon.py verify <a> <b>     # sono lo stesso esperimento? e i pixel?
    python3 .agent/tools/canon.py why    <a> <b>     # primo fotogramma divergente

## Perche' esiste

Due render correnti risultavano identici fra loro e diversi da un baseline scritto poco prima con
lo stesso comando. Un `.bin` non porta con se' NIENTE di cio' che lo ha prodotto — non il sorgente,
non le costanti del motore, non le variabili d'ambiente, non le catture da cui il simulatore
ritaglia i glifi — quindi due file qualsiasi si lasciano confrontare in silenzio anche quando sono
esperimenti diversi. E `sim.py` scrive il `.bin` prima del `.json`, quindi un disco pieno lascia un
file TRONCATO che `cmp` dichiara semplicemente "diverso".

Da qui in avanti ogni render scrive `manifest.json` accanto a se', con la lunghezza attesa del
`.bin` verificata dopo la scrittura, e `verify` RIFIUTA di confrontare due render i cui manifest
non descrivono lo stesso esperimento.

Non tocca il motore ne' le leve: legge, registra, confronta.
"""

import argparse
import datetime
import glob
import hashlib
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SIM = os.path.join(HERE, ".agent/tools/sim.py")
ENGINE = os.path.join(HERE, "android/src/main/java/com/numerictext/NumericTextTimeline.kt")


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def git(*args):
    try:
        return subprocess.run(["git", *args], cwd=HERE, capture_output=True,
                              text=True, check=True).stdout.strip()
    except Exception:
        return "?"


def _sim():
    sys.path.insert(0, os.path.dirname(SIM))
    import sim
    return sim


def atlas_fingerprint():
    """Le catture da cui il simulatore ritaglia i glifi e prende la geometria.

    Fanno parte dell'esperimento quanto il codice: se una cambia, ogni render cambia con lei, e
    nulla lo direbbe."""
    sim = _sim()
    out = {}
    for pattern, settled, label in sim.ATLAS_SOURCES:
        for p in sorted(glob.glob(os.path.join(HERE, pattern))):
            if p.endswith("reference.json"):
                continue
            out[os.path.relpath(p, HERE)] = sha(p)
    layout = os.path.join(HERE, sim.LAYOUT_SOURCE + ".json")
    out[os.path.relpath(layout, HERE)] = sha(layout)
    return out


# Cio' che DEVE coincidere perche' due render siano lo stesso esperimento. `quando`, `comando` e
# `outdir` sono contorno; il resto no — e l'atlante in particolare, che e' l'ingrediente che
# nessuno guarda mai.
IDENTITY = ("sha_sim_py", "sha_engine_kt", "modello", "preset", "flip_env",
            "costanti_motore", "atlante", "diff_hash")


def do_render(args):
    outdir = os.path.abspath(args.outdir)
    os.makedirs(outdir, exist_ok=True)
    env = dict(os.environ)
    for kv in args.env or []:
        k, v = kv.split("=", 1)
        env[k] = v
    flip = {k: v for k, v in sorted(env.items()) if k.startswith("FLIP_")}
    cmd = [sys.executable, SIM, outdir, f"--preset={args.preset}", "--model=kotlin"]
    r = subprocess.run(cmd, cwd=HERE, env=env, capture_output=True, text=True)
    if r.returncode != 0:
        print("  RENDER FALLITO:\n" + (r.stdout + r.stderr)[-2000:])
        return 1

    js = [p for p in glob.glob(os.path.join(outdir, "*.json"))
          if os.path.basename(p) != "manifest.json"]
    if not js:
        print("  RENDER FALLITO: nessun .json prodotto")
        return 1
    meta = json.load(open(js[0]))
    binp = js[0][:-5] + ".bin"
    atteso = meta["frames"] * meta["width"] * meta["height"]
    reale = os.path.getsize(binp) if os.path.exists(binp) else 0
    if reale != atteso:
        # Il difetto che ha prodotto tutto questo: su disco pieno il .bin resta a meta' e nulla lo
        # dice. Meglio un render che si dichiara rotto di uno che si lascia confrontare.
        print(f"  RENDER TRONCATO: {os.path.basename(binp)} ha {reale:,} byte, "
              f"ne servono {atteso:,} ({100 * reale / atteso:.1f}%). Disco pieno?")
        return 1

    m = {
        "comando": " ".join(cmd),
        "cwd": HERE,
        "commit": git("rev-parse", "HEAD")[:12],
        "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
        "tree_sporco": bool(git("status", "--porcelain")),
        "diff_hash": hashlib.sha256(git("diff").encode()).hexdigest()[:16],
        "sha_sim_py": sha(SIM),
        "sha_engine_kt": sha(ENGINE),
        "modello": "kotlin",
        "preset": args.preset,
        "flip_env": flip,
        "costanti_motore": {k: round(v, 6) for k, v in sorted(_sim().engine_constants().items())},
        "atlante": atlas_fingerprint(),
        "quando": datetime.datetime.now().isoformat(timespec="seconds"),
        "outdir": os.path.relpath(outdir, HERE),
        "frames": meta["frames"],
        "bytes_bin": reale,
        "sha_bin": {os.path.basename(p): sha(p)
                    for p in sorted(glob.glob(os.path.join(outdir, "*.bin")))},
    }
    with open(os.path.join(outdir, "manifest.json"), "w") as fh:
        json.dump(m, fh, indent=2)
    print(f"  reso {m['outdir']}  preset={args.preset}  frame={m['frames']}  "
          f"bin={list(m['sha_bin'].values())[0]}  flip={flip or '—'}")
    return 0


def load_manifest(d):
    p = os.path.join(os.path.abspath(d), "manifest.json")
    return json.load(open(p)) if os.path.exists(p) else None


def do_verify(args):
    ma, mb = load_manifest(args.a), load_manifest(args.b)
    for name, m in ((args.a, ma), (args.b, mb)):
        if m is None:
            print(f"  RIFIUTO: {name} non ha manifest.json — non so cosa sia, non lo confronto.")
            print("  Rigeneralo con: python3 .agent/tools/canon.py render <dir> --preset=...")
            return 2
    diffs = [k for k in IDENTITY if ma.get(k) != mb.get(k)]
    if diffs:
        print("  RIFIUTO: i due render NON sono lo stesso esperimento. Differiscono per:")
        for k in diffs:
            if isinstance(ma.get(k), dict) and isinstance(mb.get(k), dict):
                for kk in sorted(set(ma[k]) | set(mb[k])):
                    if ma[k].get(kk) != mb[k].get(kk):
                        print(f"    {k}.{kk}:  A={ma[k].get(kk)}   B={mb[k].get(kk)}")
            else:
                print(f"    {k}:  A={ma.get(k)}   B={mb.get(k)}")
        return 2
    same = ma["sha_bin"] == mb["sha_bin"]
    print(f"  stesso esperimento — pixel {'IDENTICI' if same else 'DIVERSI'}")
    return 0 if same else 1


def do_why(args):
    import numpy as np
    sys.path.insert(0, os.path.dirname(SIM))
    from ground_truth import load
    ma, mb = load_manifest(args.a), load_manifest(args.b)
    if ma and mb:
        for k in IDENTITY:
            if ma.get(k) != mb.get(k):
                print(f"  attenzione: i manifest differiscono su «{k}» — vedi `verify`")
    def one(d):
        js = [p for p in glob.glob(os.path.join(os.path.abspath(d), "*.json"))
              if os.path.basename(p) != "manifest.json"]
        return js[0][:-5]
    _, fa = load(one(args.a))
    _, fb = load(one(args.b))
    if fa.shape != fb.shape:
        print(f"  forme diverse: {fa.shape} contro {fb.shape} — uno dei due e' troncato")
        return 1
    for i in range(len(fa)):
        d = np.abs(fa[i].astype(int) - fb[i].astype(int))
        if d.max() > 0:
            print(f"  primo fotogramma divergente: {i}   differenza max {d.max()}/255   "
                  f"pixel diversi {int((d > 0).sum())} su {d.size}")
            return 1
    print("  nessun fotogramma differisce")
    return 0


ap = argparse.ArgumentParser(description=__doc__,
                             formatter_class=argparse.RawDescriptionHelpFormatter)
sub = ap.add_subparsers(dest="cmd", required=True)
r = sub.add_parser("render"); r.add_argument("outdir"); r.add_argument("--preset", required=True)
r.add_argument("--env", action="append"); r.set_defaults(fn=do_render)
v = sub.add_parser("verify"); v.add_argument("a"); v.add_argument("b"); v.set_defaults(fn=do_verify)
w = sub.add_parser("why"); w.add_argument("a"); w.add_argument("b"); w.set_defaults(fn=do_why)
a = ap.parse_args()
raise SystemExit(a.fn(a))
