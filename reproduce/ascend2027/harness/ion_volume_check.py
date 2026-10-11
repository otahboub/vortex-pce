#!/usr/bin/env python3
"""
ion_volume_check.py — does the ION harness keep a contact to its planned volume?

Two nodes, one contact A -> B of D plan-seconds at RRN-A's 12,500 B/s, so its planned volume is
12,500 * D bytes, i.e. floor(12,500 * D / bundle) whole bundles. More bundles than that are offered at
t = 0, each with a lifetime longer than the run, and the harness (ion_multinode.py, same configuration
as the A1 runs) runs it at time compression C. Excess = bundles delivered above the planned count, and
bytes received at B (ION's own counter) against the planned volume.

Without --link-emu this shows the harness's leak; with it, the link emulator should hold each contact to
its planned volume.

Usage: ion_volume_check.py --duration 240 --compress 60 [--link-emu] [--offered 6] [--work DIR] [--out CSV]
"""
import argparse
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import a1_flagship as A  # noqa: E402
from d2_worked_example import ion_counters  # noqa: E402

RATE = 12_500
BUNDLE = 1_000_000


def run(duration, compress, offered, work, image, link_emu=False):
    """The A1 code path (a1_flagship.run_ion) on a two-node plan; only the start-up wait is shorter."""
    os.makedirs(work, exist_ok=True)
    plan = os.path.join(work, "plan.txt")
    horizon = duration + 600
    with open(plan, "w") as f:
        f.write(f"a contact +0 +{duration:g} 1 2 {RATE}\na contact +0 +{duration:g} 2 1 {RATE}\n"
                f"a range +0 +{horizon:g} 1 2 0\n")
    A.DST, A.B = "2", BUNDLE
    bundles = [{"id": f"b{i}", "src": "1", "t": 0.0, "deadline": float(horizon), "critical": False}
               for i in range(offered)]
    arrival = A.run_ion(bundles, work, compress, 2_500_000, image, plan=plan, link_emu=link_emu, lead_s=60)
    gen = os.path.join(work, "ion")
    ctr = ion_counters(gen)
    planned = int(RATE * duration // BUNDLE)
    emu = {}
    if link_emu and os.path.exists(os.path.join(gen, "linkemu_stats.json")):
        emu = json.load(open(os.path.join(gen, "linkemu_stats.json"))).get("1->2", {})
    return {"duration_s": duration, "compress": compress, "link_emu": link_emu, "offered": offered,
            "planned_bundles": planned, "delivered_bundles": len(arrival),
            "excess_bundles": len(arrival) - planned, "b_rcv_bytes": ctr.get("2", {}).get("rcv_bytes", 0),
            "planned_bytes": round(RATE * duration / compress), "emu_fwd_bytes": emu.get("fwd_bytes", ""),
            "emu_drop_closed": emu.get("drop_closed", ""), "emu_drop_cut": emu.get("drop_cut", "")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--duration", type=float, required=True, help="contact length, plan seconds")
    ap.add_argument("--compress", type=float, default=60.0)
    ap.add_argument("--offered", type=int, default=None, help="bundles offered (default planned + 3)")
    ap.add_argument("--work", default=os.path.join(HERE, "_volcheck"))
    ap.add_argument("--image", default="ion-dtn:paper")
    ap.add_argument("--out", default=None)
    ap.add_argument("--link-emu", action="store_true")
    a = ap.parse_args()
    offered = a.offered or int(RATE * a.duration // BUNDLE) + 3
    tag = f"D{a.duration:g}_C{a.compress:g}" + ("_emu" if a.link_emu else "")
    res = run(a.duration, a.compress, offered, os.path.join(a.work, tag), a.image, link_emu=a.link_emu)
    print(json.dumps(res))
    if a.out:
        new = not os.path.exists(a.out)
        with open(a.out, "a", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(res))
            if new:
                w.writeheader()
            w.writerow(res)


if __name__ == "__main__":
    main()
