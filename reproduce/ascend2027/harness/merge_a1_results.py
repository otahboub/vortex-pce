#!/usr/bin/env python3
"""merge_a1_results.py — assemble the A1 result table from the runs that produced it.

Rows are keyed by (load, seed, arm); a later source overrides an earlier one. Rows from an ION run
that never passed its health checks (delivered 0 with no refusal record) are dropped, so a failed
cell shows up as missing rather than as zero delivery.

Usage: python3 merge_a1_results.py OUT.csv SRC.csv [SRC.csv ...] [--keep-arms ion,odl]
       --keep-arms ARM,... restricts the FIRST source to those arms (e.g. its engine-independent
       ion,odl rows when its Vortex rows came from a superseded engine build).
"""
import argparse
import csv

ap = argparse.ArgumentParser()
ap.add_argument("out")
ap.add_argument("srcs", nargs="+")
ap.add_argument("--keep-arms", default="", help="arms kept from the first source (default: all)")
a = ap.parse_args()

keep = set(filter(None, a.keep_arms.split(",")))
merged, header = {}, None
for i, src in enumerate(a.srcs):
    with open(src) as f:
        first = f.readline()
        f.seek(0)
        if first.startswith("load,"):
            header = header or first.strip().split(",")
            reader = csv.DictReader(f)
        else:  # rows appended without a header line
            reader = csv.DictReader(f, fieldnames=header)
        for r in reader:
            if i == 0 and keep and r["arm"] not in keep:
                continue
            if r["arm"].startswith("ion") and r["delivered"] == "0" and not r["refusals"]:
                continue
            merged[(float(r["load"]), int(r["seed"]), r["arm"])] = r

order = lambda k: (k[0], k[1], k[2])  # noqa: E731
with open(a.out, "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=header)
    w.writeheader()
    for k in sorted(merged, key=order):
        w.writerow(merged[k])
cells = {}
for (load, seed, arm) in merged:
    cells.setdefault((load, arm), []).append(seed)
for (load, arm), seeds in sorted(cells.items()):
    print(f"{load:>4} {arm:<22} seeds {sorted(seeds)}")
