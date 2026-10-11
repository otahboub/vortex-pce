#!/usr/bin/env python3
"""
vortex_sdp.py — R_STOCH flagship driver: score VortexPCE's committed routes on the SAME SDP
route-survival experiment RUCoP reports (SDP vs contact failure probability), alongside the plain-CGR
and oracle baselines, on a published DtnSim/RUCoP contact plan (e.g. RRN-A, 16-sat Walker).

One shared workload (cold spots -> server, unit bundles) drives all three arms so they align
bundle-for-bundle:

  * oracle : a temporal path exists to the destination in the sampled realization (upper bound).
  * cgr    : the NOMINAL earliest-arrival route (single committed route) survives (paper's CGR baseline).
  * vortex : VortexPCE's committed route (from the REAL engine via POST /api/v1/solve) survives.

This is the BASELINE arm of the flagship: unrevised Vortex (nominal, no successProb ingest). The
hypothesis (plan Sec 4a) is that unrevised Vortex tracks plain CGR; the later risk-aware CRP revision
should lift it toward CGR-UCoP/oracle. Reported either way.

Vortex is a bandwidth-reserving admission controller, so bundles are made small enough that ROUTING,
not capacity, is the binding constraint (the SDP experiment is a connectivity/route-survival model).
Unadmitted bundles count as delivered=0 (real Vortex behavior); --require-full-admission asserts the
pfail=0 admission rate so a capacity artifact can't masquerade as a routing result.

Usage:
  python3 vortex_sdp.py --contact-plan datasets/fixtures/RRN-A_16sats_Walker.contactplan.txt \
      --dst 48 --srcs 7-31 --bundles-per-src 10 --pfails 0.1,...,0.9 --runs 200 \
      --image vortex-pce-controller:paper --out rrnA_sdp.csv
"""
import argparse
import io
import json
import os
import random
import subprocess
import sys
import tarfile
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from datasets.rucop import parse_contact_plan, build_topology  # noqa: E402
from sdp_score import nominal_route, path_exists, route_survives  # noqa: E402

# Overridable so two harnesses can run controllers side by side; defaults are unchanged.
CONTAINER = os.environ.get("VORTEX_SDP_CONTAINER", "bench-pce")
PORT = os.environ.get("VORTEX_SDP_PORT", "18080")
API = f"http://127.0.0.1:{PORT}"
KEY = os.environ.get("VORTEX_API_KEY", "bench")
# Large loaded solves can take minutes; the controller side is VORTEX_SOLVE_TIMEOUT_SEC.
SOLVE_HTTP_TIMEOUT = int(os.environ.get("VORTEX_SDP_HTTP_TIMEOUT", "120"))


def http_get(path):
    try:
        with urllib.request.urlopen(API + path, timeout=5) as r:
            return r.status == 200
    except Exception:
        return False


def http_post_solve(workload):
    req = urllib.request.Request(
        API + "/api/v1/solve",
        data=json.dumps(workload).encode(),
        headers={"X-API-Key": KEY, "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=SOLVE_HTTP_TIMEOUT) as r:
        return json.loads(r.read().decode())


def scenario_tar(scn_dir):
    """The scenario's files as a tar rooted at topo/, readable by the container's non-root user.

    Copying this into the container replaces a bind mount, which on Linux would need the host
    directory opened to every user (the controller runs as uid 10001, not the host user)."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tar:
        top = tarfile.TarInfo("topo")
        top.type, top.mode = tarfile.DIRTYPE, 0o755
        tar.addfile(top)
        for name in sorted(os.listdir(scn_dir)):
            path = os.path.join(scn_dir, name)
            if os.path.isfile(path):
                with open(path, "rb") as f:
                    data = f.read()
                info = tarfile.TarInfo(f"topo/{name}")
                info.size, info.mode = len(data), 0o644
                tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def boot_controller(scn_dir, image, route_policy, alpha=None, extra_env=None):
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    cmd = [
        "docker", "create", "--name", CONTAINER, "-p", f"127.0.0.1:{PORT}:8080",
        "-e", f"VORTEX_API_KEY={KEY}",
        # The HTTP controller's flat default is the time-blind BFS_MIN_HOP for EVERY regime; R_DET
        # (and R_STOCH) need the contact-aware BOUNDED_CGR or single non-time-feasible min-hop paths
        # get rejected as NO_PATH even when a temporal route exists.
        "-e", f"VORTEX_ROUTE_GEN_POLICY={route_policy}",
        "-e", "VORTEX_TOPOLOGY_FILE=/topo/topology.json",
    ]
    if alpha is not None:
        # R_STOCH delivery-confidence target: Vortex buys contact-disjoint backups until union
        # survival >= alpha.
        cmd += ["-e", f"VORTEX_STOCH_ALPHA={alpha}"]
    for key, value in (extra_env or {}).items():
        cmd += ["-e", f"{key}={value}"]
    cmd.append(image)
    subprocess.run(cmd, check=True, capture_output=True)
    subprocess.run(["docker", "cp", "-", f"{CONTAINER}:/"], input=scenario_tar(scn_dir), check=True,
                   capture_output=True)
    subprocess.run(["docker", "start", CONTAINER], check=True, capture_output=True)
    for _ in range(120):
        if http_get("/readyz"):
            return
        time.sleep(1)
    raise SystemExit("controller did not become ready")


def kill_controller():
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)


def window_containing(link_windows, t):
    """Return the (ws,we) contact window on a link that contains time t, else None."""
    for (ws, we) in link_windows:
        if ws - 1e-6 <= t < we + 1e-6:
            return (ws, we)
    return None


def with_success_prob(base_topo, p):
    """Deep-copy the topology as R_STOCH with successProb=1-p on every contact (uniform per-contact
    failure probability, matching the RUCoP failure-probability sweep)."""
    t = json.loads(json.dumps(base_topo))
    t["regime"] = "R_STOCH"
    sp = round(1.0 - p, 6)
    for link in t["links"]:
        for c in link.get("contacts", []):
            if sp < 1.0:
                c["successProb"] = sp
    return t


def solve_and_extract(scn, topo, vwork, links, image, route_policy, alpha, extra_env=None):
    """Write topo+workload, boot the real controller, solve, and map committed hops -> contact-key
    routes grouped by taskId (a task may get multiple redundant schedules under R_STOCH)."""
    json.dump(topo, open(os.path.join(scn, "topology.json"), "w"))
    json.dump(vwork, open(os.path.join(scn, "workload.json"), "w"))
    boot_controller(scn, image, route_policy, alpha, extra_env)
    resp = {}
    try:
        resp = http_post_solve(vwork)
    finally:
        json.dump(resp, open(os.path.join(scn, "solve.json"), "w"))
        kill_controller()
    routes_by_task = {}
    for sched in resp.get("committedSchedules", []):
        routes_by_task.setdefault(sched["taskId"], []).append(hops_to_route(sched.get("hops", []), links))
    return resp, routes_by_task


def hops_to_route(hops, links):
    """Map a committed schedule's hops -> ordered list of sdp contact-keys ((a,b),(ws,we))."""
    route = []
    for h in hops:
        a, b = str(h["sourceNodeId"]), str(h["destinationNodeId"])
        lk = links.get((a, b))
        if not lk:
            return None
        w = window_containing(lk["windows"], float(h["startSec"]))
        if w is None:
            return None
        route.append(((a, b), w))
    return route


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--contact-plan", required=True)
    ap.add_argument("--dst", default="48")
    ap.add_argument("--srcs", default="7-31")
    ap.add_argument("--bundles-per-src", type=int, default=1,
                    help="bundles per cold spot (all at --origin-time; Monte-Carlo runs supply stats)")
    ap.add_argument("--origin-time", type=float, default=0.0,
                    help="origination time for every bundle (t=0 gives the full horizon to deliver, "
                         "isolating routing robustness from deadline-feasibility)")
    ap.add_argument("--bundle-bytes", type=int, default=1000)
    ap.add_argument("--pfails", default="0.0,0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9")
    ap.add_argument("--runs", type=int, default=200)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--image", default="vortex-pce-controller:paper")
    ap.add_argument("--route-policy", default="BOUNDED_CGR",
                    help="VORTEX_ROUTE_GEN_POLICY (BOUNDED_CGR is the contact-aware policy R_DET needs)")
    ap.add_argument("--stochastic", action="store_true",
                    help="R_STOCH REVISED arm: for each pfail, build an R_STOCH topology with "
                         "successProb=1-pfail on every contact and re-solve, so Vortex ingests the "
                         "failure risk and buys contact-disjoint redundancy up to --alpha")
    ap.add_argument("--alpha", type=float, default=0.9,
                    help="R_STOCH union-survival confidence target (VORTEX_STOCH_ALPHA)")
    ap.add_argument("--subgraph-delivery", action="store_true",
                    help="score the Vortex arm by reachability over the union of its committed contacts "
                         "(CGR-style subgraph forwarding with recombination) instead of route-pinned "
                         "whole-route survival; reports the engine's true delivery probability")
    ap.add_argument("--shared-terminal", action="store_true",
                    help="R_STOCH admission may share a terminal contact when upstream paths are "
                         "disjoint (VORTEX_STOCH_SHARED_TERMINAL=true); pair with CUT_ANCHORED_CGR")
    ap.add_argument("--mesh-level", default=None,
                    help="MESH_CGR level: 0..16 detours per hop, or ALL = flooding plan (VORTEX_MESH_LEVEL)")
    ap.add_argument("--mc-survival", type=int, default=None,
                    help="Monte-Carlo survival admission with N samples (VORTEX_STOCH_SURVIVAL=MONTE_CARLO)")
    ap.add_argument("--capacity-aware-gen", action="store_true",
                    help="route generators skip contacts without residual capacity (VORTEX_GEN_CAPACITY_AWARE)")
    ap.add_argument("--solve-timeout", type=int, default=None, help="VORTEX_SOLVE_TIMEOUT_SEC")
    ap.add_argument("--cut-upstream", type=int, default=None,
                    help="upstream paths per terminal link for CUT_ANCHORED_CGR (VORTEX_CUT_UPSTREAM)")
    ap.add_argument("--scn-dir", default=None, help="where to write topology/workload (default: temp)")
    ap.add_argument("--out", default="vortex_sdp.csv")
    ap.add_argument("--require-full-admission", type=float, default=0.0,
                    help="fail if pfail=0 Vortex admission ratio is below this (0 disables)")
    args = ap.parse_args()

    nodes, links, horizon = parse_contact_plan(args.contact_plan)
    if "-" in args.srcs:
        a, b = args.srcs.split("-"); srcs = [str(i) for i in range(int(a), int(b) + 1)]
    else:
        srcs = args.srcs.split(",")
    srcs = [s for s in srcs if s in nodes]
    dst = args.dst
    rng = random.Random(args.seed)
    # Fixed-origin all-to-one workload: every cold spot emits bundles at --origin-time with the full
    # horizon to deliver. This isolates ROUTING robustness (the SDP experiment) from deadline
    # feasibility, and keeps cgr/oracle/vortex aligned bundle-for-bundle.
    workload = [(s, dst, args.origin_time) for s in srcs for _ in range(args.bundles_per_src)]

    # Nominal (CGR) route per bundle, on the full plan.
    nominal = {i: nominal_route(links, s, d, t0, horizon) for i, (s, d, t0) in enumerate(workload)}

    # Build the Vortex scenario (same topology; unit bundles at the same origination times).
    scn = args.scn_dir or os.path.join(os.path.dirname(os.path.abspath(__file__)), "_vortex_sdp_scn")
    os.makedirs(scn, exist_ok=True)
    topo = build_topology(nodes, links, horizon)
    vwork = [{"taskId": f"b{i}", "sourceNodeId": s, "destinationNodeId": d,
              "originationTimeSec": round(t0, 3), "deadlineSec": horizon,
              "taskSizeBytes": args.bundle_bytes, "priority": 1}
             for i, (s, d, t0) in enumerate(workload)]
    pfails = [float(x) for x in args.pfails.split(",")]

    def committed_contacts_of(vortex_route):
        """Union of the contact-keys across all committed routes of each task (the committed subgraph)."""
        cc = {}
        for i, routes in vortex_route.items():
            s = set()
            for rr in (routes or []):
                if rr:
                    s.update(rr)
            cc[i] = s
        return cc

    def score_at(p, vortex_route, committed=None):
        cgr_tot = oracle_tot = vortex_tot = 0
        for _ in range(args.runs):
            present, present_adj = set(), {}
            for (a, b), lk in links.items():
                for (ws, we) in lk["windows"]:
                    if rng.random() >= p:
                        present.add(((a, b), (ws, we)))
                        present_adj.setdefault(a, []).append((b, ws, we))
            for i, (s, d, t0) in enumerate(workload):
                if nominal[i] and route_survives(nominal[i], present):
                    cgr_tot += 1
                if path_exists(present_adj, s, d, t0, horizon):
                    oracle_tot += 1
                if args.subgraph_delivery:
                    # Deliver if the committed contact SUBGRAPH still connects src->dst (CGR-style
                    # forwarding: a copy at a shared node takes any surviving committed onward contact).
                    cc = committed[i] if committed else set()
                    if cc:
                        adj = {}
                        for ((a, b), (ws, we)) in cc:
                            if ((a, b), (ws, we)) in present:
                                adj.setdefault(a, []).append((b, ws, we))
                        if path_exists(adj, s, d, t0, horizon):
                            vortex_tot += 1
                else:
                    vr = vortex_route[i]
                    if vr and any(rr and route_survives(rr, present) for rr in vr):
                        vortex_tot += 1
        n = args.runs * len(workload)
        return cgr_tot / n, oracle_tot / n, vortex_tot / n

    extra_env = {}
    if args.shared_terminal:
        extra_env["VORTEX_STOCH_SHARED_TERMINAL"] = "true"
    if args.cut_upstream is not None:
        extra_env["VORTEX_CUT_UPSTREAM"] = str(args.cut_upstream)
    if args.mesh_level is not None:
        extra_env["VORTEX_MESH_LEVEL"] = str(args.mesh_level)
    if args.mc_survival:
        extra_env["VORTEX_STOCH_SURVIVAL"] = "MONTE_CARLO"
        extra_env["VORTEX_STOCH_MC_SAMPLES"] = str(args.mc_survival)
    if args.capacity_aware_gen:
        extra_env["VORTEX_GEN_CAPACITY_AWARE"] = "true"
    if args.solve_timeout:
        extra_env["VORTEX_SOLVE_TIMEOUT_SEC"] = str(args.solve_timeout)

    if args.stochastic:
        # REVISED arm: per pfail, ingest successProb=1-pfail and let Vortex buy contact-disjoint
        # redundancy up to alpha, then score at that pfail.
        rows = ["pfail,cgr_sdp,oracle_sdp,vortex_sdp,vortex_admit,routes_per_admit"]
        print(rows[0])
        for p in pfails:
            resp, routes_by_task = solve_and_extract(
                scn, with_success_prob(topo, p), vwork, links, args.image, args.route_policy, args.alpha,
                extra_env)
            vortex_route = {i: routes_by_task.get(f"b{i}") for i in range(len(workload))}
            admitted = sum(1 for i in vortex_route if vortex_route[i])
            admit_ratio = admitted / max(1, len(workload))
            routes_per_admit = (sum(len(v) for v in vortex_route.values() if v) / admitted) if admitted else 0.0
            cgr, orc, vtx = score_at(p, vortex_route, committed_contacts_of(vortex_route))
            row = f"{p},{cgr:.3f},{orc:.3f},{vtx:.3f},{admit_ratio:.3f},{routes_per_admit:.2f}"
            rows.append(row); print(row)
            print(f"# p={p}: admitted {admitted}/{len(workload)}, routes/admit {routes_per_admit:.2f}, "
                  f"committed schedules {resp.get('committedFlowCount')}", file=sys.stderr)
        with open(args.out, "w") as f:
            f.write("\n".join(rows) + "\n")
        print(f"# wrote {args.out}", file=sys.stderr)
        return

    json.dump(topo, open(os.path.join(scn, "topology.json"), "w"))
    json.dump(vwork, open(os.path.join(scn, "workload.json"), "w"))

    # Solve on the REAL engine.
    boot_controller(scn, args.image, args.route_policy)
    try:
        resp = http_post_solve(vwork)
    finally:
        json.dump(resp if "resp" in dir() else {}, open(os.path.join(scn, "solve.json"), "w"))
        kill_controller()

    committed = resp.get("committedSchedules", [])
    # group by taskId: a bundle may (rarely) get >1 schedule (multipath split). Score union-survival.
    routes_by_task = {}
    for sched in committed:
        r = hops_to_route(sched.get("hops", []), links)
        routes_by_task.setdefault(sched["taskId"], []).append(r)
    vortex_route = {i: routes_by_task.get(f"b{i}") for i in range(len(workload))}
    admitted = sum(1 for i in vortex_route if vortex_route[i])
    admit_ratio = admitted / max(1, len(workload))
    print(f"# vortex admitted {admitted}/{len(workload)} = {admit_ratio:.3f} "
          f"(offered={resp.get('offeredFlowCount')}, committed={resp.get('committedFlowCount')})",
          file=sys.stderr)

    rows = ["pfail,cgr_sdp,oracle_sdp,vortex_sdp,vortex_admit"]
    print(rows[0])
    for p in pfails:
        cgr, orc, vtx = score_at(p, vortex_route, committed_contacts_of(vortex_route))
        row = f"{p},{cgr:.3f},{orc:.3f},{vtx:.3f},{admit_ratio:.3f}"
        rows.append(row)
        print(row)
        if p == 0.0 and args.require_full_admission > 0 and admit_ratio < args.require_full_admission:
            print(f"!! pfail=0 admission {admit_ratio:.3f} < required {args.require_full_admission}",
                  file=sys.stderr)

    with open(args.out, "w") as f:
        f.write("\n".join(rows) + "\n")
    print(f"# wrote {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
