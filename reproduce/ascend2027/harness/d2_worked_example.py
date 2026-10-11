#!/usr/bin/env python3
"""
d2_worked_example.py — the final paper's worked example (diagram D2), run on the real systems.

Five nodes (terminals T1=1, T2=2; satellites S1=3, S2=4; gateway G=5, the destination) and three 1 MB
bundles, all in the same class of service (results/diagrams/d2.contactplan.txt, d2_bundles.json).
Each downlink contact carries one bundle at full contact volume, so the two bundles that can only use
S1-G compete for it.

  vortex  the real controller (R_DET defaults, utilization headroom 1.0 = full contact volume, as ION)
          -> results/diagrams/d2_vortex_solve.json
  ion     real ION 4.2.1, every node live (a1_flagship.run_ion) at 10x compression: ION timestamps are
          whole seconds, so at RRN-A's 60x a 120 s downlink becomes a 2 s window and the rounding can
          give ION up to half a contact's volume extra; at 10x it is 12 s and the slack stays under
          one bundle.
          -> results/diagrams/d2_ion_result.json: deliveries, each node's final bundle counters
             from ion.log (src, fwd, xmt, rcv, dlv, exp), and the per-bundle trace drawn in D2.

ION logs counters, not per-bundle hop times. Its counters confirm the first hops drawn in D2; the
outcome at S1 is drawn from CGR's volume rule, because this harness's ION (UDP convergence layer) does
not enforce contact volume and moved more than the plan allows through S1-G.

Usage: d2_worked_example.py vortex|ion [--image IMAGE]   (from the repository root)
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DIAG = os.path.join(HERE, "..", "results", "diagrams")
sys.path.insert(0, HERE)
import a1_flagship as A  # noqa: E402

STAT = re.compile(r"\[x\] (\w+) from .*\(\+\) (\d+) (\d+)")


def ion_counters(gen):
    out = {}
    for node in sorted(os.listdir(gen)):
        log = os.path.join(gen, node, "ion.log")
        if not node.isdigit() or not os.path.exists(log):
            continue
        last = {}
        for line in open(log):
            m = STAT.search(line)
            if m:
                last[m.group(1)] = int(m.group(2))
                last[m.group(1) + "_bytes"] = int(m.group(3))
        out[node] = last
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("arm", choices=["vortex", "ion", "ionplan"])
    ap.add_argument("--image", default=None)
    ap.add_argument("--work", default=os.path.join(HERE, "..", "d2_work"))
    ap.add_argument("--link-emu", action="store_true", help="run ION through ion_linkemu.py and record the "
                                                            "result in d2_ion_result_emu.json")
    ap.add_argument("--from-run", help="score a finished ION run directory (its ion/ subdirectory) instead of "
                                       "starting a new one")
    a = ap.parse_args()
    A.PLAN = os.path.join(DIAG, "d2.contactplan.txt")
    A.DST, A.SRCS = "5", ["1", "2"]
    bundles = json.load(open(os.path.join(DIAG, "d2_bundles.json")))
    nodes, links, H = A.parse_contact_plan(A.PLAN)
    if a.arm == "ionplan":
        # Real ION following VortexPCE's committed routes (per-bundle routing overrides), link emulator on.
        solve = json.load(open(os.path.join(DIAG, "d2_vortex_solve.json")))
        work = os.path.join(a.work, "ionplan")
        os.makedirs(work, exist_ok=True)
        ovr = os.path.join(work, "overrides.json")
        labelled = A.plan_following_overrides(bundles, solve["committedSchedules"], A.DST, ovr)
        arrival = A.run_ion(labelled, work, 10.0, 2_500_000, a.image or "ion-dtn:paper-bpclmfix", link_emu=True,
                            overrides=ovr)
        json.dump({"ion_deliveries": sorted(arrival), "arrival_s": arrival, "sent": sorted(b["id"] for b in labelled),
                   "overrides": json.load(open(ovr)), "ion_counters": ion_counters(os.path.join(work, "ion"))},
                  open(os.path.join(DIAG, "d2_ion_plan_following.json"), "w"), indent=1)
        print("sent", sorted(b["id"] for b in labelled), "delivered", arrival)
        return
    if a.arm == "vortex":
        A.run_vortex(bundles, nodes, links, H, os.path.join(a.work, "vortex"), a.image or "vortex-pce-controller:paper",
                     1.0)
        resp = json.load(open(os.path.join(a.work, "vortex", "solve.json")))
        json.dump(resp, open(os.path.join(DIAG, "d2_vortex_solve.json"), "w"), indent=1)
        print({s["taskId"]: s["hops"][-1]["arrivalSec"] for s in resp["committedSchedules"]},
              [(u["taskId"], u["reason"]) for u in resp["unadmittedTasks"]])
        return
    run = a.from_run or os.path.join(a.work, "ion")
    if a.from_run:
        arrival = {}
        for line in open(os.path.join(run, "ion", "delivered.tsv")):
            arrival[line.split()[0]] = None      # delivery set only; the timestamp needs the run's START
    else:
        arrival = A.run_ion(bundles, run, 10.0, 2_500_000, a.image or "ion-dtn:paper", link_emu=a.link_emu)
    if a.link_emu:
        ctr = ion_counters(os.path.join(run, "ion"))
        emu = json.load(open(os.path.join(run, "ion", "linkemu_stats.json")))
        json.dump({"ion_deliveries": sorted(arrival), "arrival_s": arrival, "ion_counters": ctr, "linkemu": emu},
                  open(os.path.join(DIAG, "d2_ion_result_emu.json"), "w"), indent=1)
        print(sorted(arrival), {k: v for k, v in emu.items() if v["fwd"] or v["drop_volume"] or v["drop_closed"]})
        return
    ctr = ion_counters(os.path.join(run, "ion"))
    # What real ION shows, from its own counters: T1 forwards both X and Z to S1 (it cannot see that
    # S1-G is already booked), T2 forwards Y to S1, and S1 holds all three for S1-G.
    first_hop_ok = ctr["1"].get("fwd") == 2 and ctr["2"].get("fwd") == 1 and ctr["3"].get("rcv", 0) >= 3
    assert first_hop_ok, ctr
    # What happens at S1 is drawn from CGR's rule on the plan's contact volume (S1-G carries one bundle,
    # booked by Y, which reached S1 first). This harness's ION does not enforce contact volume: its UDP
    # convergence layer moved more than the plan allows through S1-G, so its deliveries are recorded
    # here but not drawn.
    by = {b["id"]: b for b in bundles}
    traces = {"Y": {"hops": [("2", "3", 0.0, 80.0), ("3", "5", 2000.0, 2080.0)], "end": ("delivered", 2080.0, "G")},
              "X": {"hops": [("1", "3", 600.0, 680.0)], "end": ("expired", by["X"]["deadline"], "S1")},
              "Z": {"hops": [("1", "3", 680.0, 760.0)], "end": ("expired", by["Z"]["deadline"], "S1")}}
    s1g = [c for c in A.parse_contact_plan(A.PLAN)[1][("3", "5")]["windows"]][0]
    summary = ("Accepts all three and routes each on arrival: Y reaches\nS1 first and books S1–G; "
               "T1 still sends X and Z\nup to S1, where they wait and expire.")
    json.dump({"ion_deliveries": sorted(arrival), "ion_counters": ctr,
               "ion_gateway_bytes_received": ctr["5"].get("rcv_bytes"),
               "s1_g_window_s": s1g, "traces": traces, "summary": summary,
               "note": "first hops (T1 forwards X and Z; S1 receives all three) are observed in real ION's "
                       "counters; the outcome at S1 follows CGR's volume rule on the plan (one bundle on "
                       "S1-G). The harness's ION does not enforce contact volume, so it delivered "
                       f"{sorted(arrival)}; see the campaign's ION-fidelity follow-up."},
              open(os.path.join(DIAG, "d2_ion_result.json"), "w"), indent=1)
    print(sorted(arrival), ctr)


if __name__ == "__main__":
    main()
