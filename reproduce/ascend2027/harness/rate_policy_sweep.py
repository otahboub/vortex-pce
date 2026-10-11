#!/usr/bin/env python3
"""
rate_policy_sweep.py — how VortexPCE's rate policy affects delivery on scheduled contacts (RRN-A, R_DET).

The A1 workloads (a1_flagship.make_bundles, both loads, seeds 0-4) are solved by one controller image
with only VORTEX_RATE_ASSIGN_POLICY and the utilization headroom varied:

  DATA_FLOW_EQUILIBRIUM            the slowest rate that meets the deadline, judged on the contacts
  DATA_FLOW_EQUILIBRIUM_AVERAGED   the same before the scheduled-contact fix (refuses on an averaged estimate)
  RESIDUAL_BOTTLENECK_HEADROOM     the route's residual rate within headroom (sends at the available rate)

Every committed schedule is replayed on shared contact capacity (a1_flagship.replay_arrivals), so the
delivered count is what the plan carries; replay_mismatches must be 0.

Usage: rate_policy_sweep.py --image vortex-pce-controller:paper --work DIR [--headrooms 1.0,0.9]
       [--policies DATA_FLOW_EQUILIBRIUM,...] [--out results/...csv]
"""
import argparse
import csv
import json
import os
import sys
import time
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
# vortex_sdp reads its HTTP timeout at import; a full-load RRN-A solve takes minutes, not its 120 s default.
os.environ.setdefault("VORTEX_SDP_HTTP_TIMEOUT", "3600")
import a1_flagship as A  # noqa: E402

POLICIES = ["DATA_FLOW_EQUILIBRIUM", "DATA_FLOW_EQUILIBRIUM_AVERAGED", "RESIDUAL_BOTTLENECK_HEADROOM"]
SATURATED = 411     # a1_flagship's saturated ceiling on RRN-A (bundles of 1 MB)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--loads", default="0.5,1")
    ap.add_argument("--seeds", default="0,1,2,3,4")
    ap.add_argument("--headrooms", default="1.0,0.9")
    ap.add_argument("--policies", default=",".join(POLICIES))
    ap.add_argument("--out", default=os.path.join(HERE, "results/rrnA_rate_policy_sweep.csv"))
    a = ap.parse_args()
    nodes, links, H = A.parse_contact_plan(A.PLAN)
    new = not os.path.exists(a.out)
    with open(a.out, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["load", "seed", "offered", "ceiling", "headroom", "rate_policy", "admitted", "delivered",
                        "share_of_ceiling_pct", "critical_on_time_pct", "replay_mismatches", "refusals", "wall_s",
                        "image"])
        for load in [float(x) for x in a.loads.split(",")]:
            for seed in [int(x) for x in a.seeds.split(",")]:
                bundles = A.make_bundles(max(1, round(load * SATURATED / len(A.SRCS))), seed)
                triples = [(b["src"], b["t"], b["deadline"]) for b in bundles]
                cap = min(A.ceiling(links, triples), A.individually_deliverable(links, triples))
                for headroom in [float(x) for x in a.headrooms.split(",")]:
                    for pol in a.policies.split(","):
                        scn = os.path.join(a.work, f"L{load}_s{seed}_h{headroom}_{pol}")
                        t0 = time.time()
                        planned, _, _ = A.run_vortex(bundles, nodes, links, H, scn, a.image, headroom,
                                                     {"VORTEX_RATE_ASSIGN_POLICY": pol})
                        resp = json.load(open(os.path.join(scn, "solve.json")))
                        replay = A.replay_arrivals(bundles, links, resp.get("committedSchedules", []))
                        sc = A.score(bundles, replay, tol=1e-6)
                        mism = sum(1 for k in planned if k not in replay or replay[k] > planned[k] + 1e-6)
                        refusals = dict(Counter(u.get("reason") for u in resp.get("unadmittedTasks", [])))
                        row = [load, seed, len(bundles), cap, headroom, pol, len(planned), sc["delivered"],
                               round(100 * sc["delivered"] / max(1, cap), 1), sc["critical_on_time_pct"], mism,
                               json.dumps(refusals, sort_keys=True), round(time.time() - t0), a.image]
                        w.writerow(row)
                        f.flush()
                        print(",".join(str(x) for x in row), flush=True)


if __name__ == "__main__":
    main()
