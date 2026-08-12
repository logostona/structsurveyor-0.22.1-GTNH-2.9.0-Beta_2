#!/usr/bin/env python3
"""
verify_sweep.py - check a Structure Surveyor sweep against known ground truth.

The sweep replays each generator's placement decision from the world seed. That
replay has to reproduce Minecraft's per-chunk RNG seeding exactly; if it does
not, the output is wrong in a way that still looks entirely plausible.

The world's own .dat files are the oracle. Every structure Minecraft actually
generated MUST appear in the sweep. Any miss means the replay is wrong.

    python verify_sweep.py <survey_dim0.json>

Structure types that don't use the generator's RNG (villages read their own
seeded Random from the world) can pass while RNG-dependent ones (mineshafts)
fail, so read the per-type breakdown rather than the total.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from find_structures import DEFAULT_SAVES, pick_world, scan_world, world_info


def load_sweep(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("sweep", help="survey_dimN.json produced by /survey")
    p.add_argument("--world", help="world folder (default: most recent)")
    p.add_argument("--saves", help="saves folder to search")
    args = p.parse_args(argv)

    if not os.path.isfile(args.sweep):
        raise SystemExit("no such file: %s" % args.sweep)

    sweep = load_sweep(args.sweep)
    dim = sweep.get("dimension", 0)
    cx = sweep.get("center_chunk_x", 0)
    cz = sweep.get("center_chunk_z", 0)
    radius = sweep.get("radius_chunks", 0)

    world = pick_world(args)
    warnings = []
    info = world_info(world, warnings)
    known = [s for s in scan_world(world, warnings) if s.dim == dim]

    seed_match = info.get("seed") == sweep.get("world_seed")

    print()
    print("  sweep : %s" % args.sweep)
    print("  world : %s  (DIM%d)" % (info["name"], dim))
    print("  seed  : %s" % ("matches" if seed_match else
                            "MISMATCH  world=%s sweep=%s"
                            % (info.get("seed"), sweep.get("world_seed"))))
    print("  region: chunks [%d,%d] +/- %d" % (cx, cz, radius))
    print()

    if not seed_match:
        print("  Seed mismatch - the sweep came from a different world. Stopping.")
        return 2

    # Sweep hits, grouped by the generator's save tag.
    found = {}
    for gen in sweep.get("generators", []):
        tag = gen.get("tag")
        found.setdefault(tag, set()).update(
            (s["chunk_x"], s["chunk_z"]) for s in gen.get("structures", []))

    def in_region(s):
        return (abs(s.chunk_x - cx) <= radius) and (abs(s.chunk_z - cz) <= radius)

    # Ground truth, grouped the same way. A generator's save tag is the .dat
    # basename, which is how the two sides line up.
    truth = {}
    for s in known:
        if in_region(s):
            truth.setdefault(s.source[:-4], []).append(s)

    if not truth:
        print("  No known structures inside the swept region - nothing to check.")
        print("  Re-run /survey centred on explored terrain to get a real test.")
        return 1

    total_known = total_hit = 0
    rows = []
    for tag in sorted(truth):
        hits = found.get(tag, set())
        misses = [s for s in truth[tag] if (s.chunk_x, s.chunk_z) not in hits]
        n = len(truth[tag])
        ok = n - len(misses)
        total_known += n
        total_hit += ok
        rows.append((tag, n, ok, misses, tag in found))

    width = max(len(r[0]) for r in rows) + 2
    print("  %-*s %8s %8s   %s" % (width, "type", "known", "found", "verdict"))
    print("  " + "-" * (width + 40))
    for tag, n, ok, misses, swept in rows:
        if not swept:
            verdict = "NOT SWEPT (generator absent)"
        elif ok == n:
            verdict = "PASS"
        else:
            verdict = "FAIL - replay is wrong"
        print("  %-*s %8d %8d   %s" % (width, tag, n, ok, verdict))
        for s in misses[:5]:
            cxx, _, czz = s.center
            print("  %-*s          missed chunk [%d,%d]  approx X %d Z %d"
                  % (width, "", s.chunk_x, s.chunk_z, cxx, czz))
        if len(misses) > 5:
            print("  %-*s          ... and %d more" % (width, "", len(misses) - 5))
    print()

    if total_hit == total_known:
        print("  PASS - all %d known structures reproduced from the seed." % total_known)
        print("  The placement replay is correct; predictions outside explored")
        print("  terrain can be trusted for these structure types.")
        return 0

    print("  FAIL - %d of %d known structures were not reproduced." % (
        total_known - total_hit, total_known))
    print("  The per-chunk RNG seeding does not match vanilla. Predicted")
    print("  positions for the failing types are not trustworthy.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
