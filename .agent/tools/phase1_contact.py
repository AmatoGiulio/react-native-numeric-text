#!/usr/bin/env python3
"""Phase 1: create contact sheets for Family B recordings to verify the pre-state signal."""
import json, glob, os, sys, numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ground_truth import load, ink_box, columns_of

SHEET_DIR = "artifacts/phase1_sheets"
os.makedirs(SHEET_DIR, exist_ok=True)

RECORDINGS = {
    ("+1→−1", 40): "run-1786007588266",
    ("+1→−1", 80): "run-1786007541960",
    ("+1→−1", 120): "run-1786007657418",
    ("+1→−1", 161): "run-1786007611294",
    ("−1→+1", 39): "run-1786007783091",
    ("−1→+1", 76): "run-1786007842398",
    ("−1→+1", 121): "run-1786007825851",
    ("−1→+1", 165): "run-1786007911742",
}

for (direction, approx_delay), run_id in RECORDINGS.items():
    prefix = f"artifacts/gt_ios_familyB/{run_id}"
    meta, frames = load(prefix)
    marks = meta["marks"]
    t1 = marks[0]["t"]
    t2 = marks[1]["t"]
    times = np.array(meta["times"])
    v1 = marks[0].get("value", "?")
    v2 = marks[1].get("value", "?")
    delay = t2 - t1

    y0, y1, x0, x1 = ink_box(frames)
    win = frames[:, y0:y1, x0:x1]
    groups = columns_of(win[-1])

    # Find frames around t2
    rel = times - t2
    before = np.where(rel <= 0)[0]
    after = np.where(rel > 0)[0]
    pre_idx = before[-1] if len(before) > 0 else 0
    post_idx = after[0] if len(after) > 0 else len(times) - 1

    # Show: settled, pre-2, pre-1, trigger-pre, trigger-post, post+1, post+2
    indices = []
    labels = []
    # settled (last frame)
    indices.append(len(frames) - 1)
    labels.append(f"settled ({v2})")
    # frames before trigger
    for i in range(max(0, pre_idx - 2), pre_idx + 1):
        indices.append(i)
        labels.append(f"t={rel[i]:+.0f}ms pre-trig")
    # frames after trigger
    for i in range(post_idx, min(len(frames), post_idx + 3)):
        indices.append(i)
        labels.append(f"t={rel[i]:+.0f}ms post-trig")

    # Draw column boundaries on the settled frame
    settled_colored = np.stack([win[-1], win[-1], win[-1]], axis=-1).astype(np.uint8)
    # highlight last column (units) in red
    li = len(groups) - 1
    a_l, b_l = groups[li]
    settled_colored[:, a_l, 0] = np.minimum(255, settled_colored[:, a_l, 0].astype(int) + 80)
    settled_colored[:, a_l, 1] = np.maximum(0, settled_colored[:, a_l, 1].astype(int) - 40)
    settled_colored[:, a_l, 2] = np.maximum(0, settled_colored[:, a_l, 2].astype(int) - 40)
    # second-to-last in green
    if len(groups) > 1:
        a_2, b_2 = groups[li - 1]
        settled_colored[:, a_2, 0] = np.maximum(0, settled_colored[:, a_2, 0].astype(int) - 40)
        settled_colored[:, a_2, 1] = np.minimum(255, settled_colored[:, a_2, 1].astype(int) + 80)

    # Build contact sheet
    tile_h = y1 - y0
    tile_w = x1 - x0
    n_tiles = len(indices)
    pad = 4
    sheet_w = n_tiles * (tile_w + pad) + pad
    sheet_h = tile_h + 2 * pad + 30
    sheet = Image.new("RGB", (sheet_w, sheet_h), (240, 240, 240))
    d = ImageDraw.Draw(sheet)

    for ti, idx in enumerate(indices):
        tile_data = win[idx]
        tile_rgb = np.stack([tile_data, tile_data, tile_data], axis=-1).astype(np.uint8)
        if idx == len(frames) - 1:
            # Use the colored version for settled
            tile_rgb = settled_colored
        tile_img = Image.fromarray(tile_rgb)
        x_pos = pad + ti * (tile_w + pad)
        sheet.paste(tile_img, (x_pos, pad + 30))
        d.text((x_pos + 2, pad + 4), labels[ti], fill=(40, 40, 40))
        d.text((x_pos + 2, pad + 16), f"frame {idx}", fill=(120, 120, 120))

    d.text((pad, 6), f"{direction}  v1={v1}→v2={v2}  delay={delay:.1f}ms  {len(groups)} cols",
           fill=(0, 0, 0))
    d.text((pad, sheet_h - pad - 16), "red border = units column (changing digit)    green = tens column",
           fill=(100, 100, 100))

    out_path = f"{SHEET_DIR}/{direction.replace('→','_to_')}_{approx_delay}ms.png"
    sheet.save(out_path)
    print(f"  {out_path}")

# Also create a composite: all 8 in one image
print("\n  Sheets in", SHEET_DIR)
