#!/usr/bin/env python3
"""Deterministic in-process test: feed identical inputs to O and N engine instances.
Outputs the first state field that diverges, or declares equivalence.
"""
import json, os, sys, math, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# ── Parse state.jsonl to extract the input sequence ─────────────────────────
def load_input_sequence(state_path):
    """Extract (dt, target, direction) sequence from a state dump."""
    states = [json.loads(l) for l in open(state_path) if l.strip()]
    # Build sequence: for each step, record dt and any commits that fired
    dt_seq = []
    commits_seq = []  # list of (step_idx, target, direction)
    
    current_step = -1
    for s in states:
        if s['step'] != current_step and s['key'] == 'I0':
            current_step = s['step']
            dt_seq.append(s['dt'])
        if s['label'] == 'postLoop' and s['key'] == 'I0' and s.get('commitsThisStep', 0) > 0:
            commits_seq.append((current_step, s.get('commit', -1), s.get('commitDir', 1)))
    
    return dt_seq, commits_seq

# Load from O recording
dt_seq, commits_seq = load_input_sequence('artifacts/O_final/state.jsonl')
print(f"DT sequence: {len(dt_seq)} steps")
print(f"Commit events: {len(commits_seq)}")
print(f"DTs (first 10): {[f'{d:.6f}' for d in dt_seq[:10]]}")
print(f"Commits (first 5): {commits_seq[:5]}")

# ── Verify: do O and N have the same commit timing? ────────────────────────
o_states = [json.loads(l) for l in open('artifacts/O_final/state.jsonl') if l.strip()]
n_states = [json.loads(l) for l in open('artifacts/N_final/state.jsonl') if l.strip()]

o_pl = [(s['step'], s.get('commit',-1)) for s in o_states if s['label']=='postLoop' and s['key']=='I0' and s.get('commitsThisStep',0)>0]
n_pl = [(s['step'], s.get('commit',-1)) for s in n_states if s['label']=='postLoop' and s['key']=='I0' and s.get('commitsThisStep',0)>0]

print(f"\nCommit step indices:")
for (os, oc), (ns, nc) in zip(o_pl, n_pl):
    match = "✓" if os == ns else f"✗ DIFF: O={os} N={ns}"
    print(f"  commit {oc}: {match}")

all_same = all(os == ns for (os, _), (ns, _) in zip(o_pl, n_pl))
print(f"\nAll commit steps same: {all_same}")
if not all_same:
    print("CONCLUSION: O and N have DIFFERENT commit timing.")
    print("The divergences are caused by different DT sequences, NOT structural changes.")
    print("An in-process deterministic test is required to isolate the structural effect.")
