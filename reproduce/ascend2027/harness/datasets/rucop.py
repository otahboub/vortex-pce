#!/usr/bin/env python3
"""
rucop.py — Phase-1 R_DET flagship adapter: ingest a PUBLISHED DtnSim/RUCoP contact plan (Fraire et al.,
ring-road LEO / binomial / random) into the harness canonical schema, so Vortex, ION and ODL run on the
*exact* published benchmark and we can position against its reported CGR / CGR-UCoP / oracle numbers.

DtnSim and RUCoP use the ION contact-plan text format (the same our harness emits):
    a range   +<start> +<end> <nodeA> <nodeB> <owlt>
    a contact +<start> +<end> <src>   <dst>   <rate_bytes_per_sec>
Nodes are integers. Times are relative seconds. This is a superset of what ion_from_scenario.py needs,
so ingestion is loss-free.

Outputs (identical to gen_grid.py, so the arm runners / collect / plot are reused unchanged):
    topology.json : {regime:"R_DET", nodes[...], links[...contacts]}
    workload.json : [{taskId, sourceNodeId, destinationNodeId, originationTimeSec, deadlineSec,
                      taskSizeBytes, priority}]
    meta.json     : {rho, deliverable_capacity_bytes, offered_bytes, ...}

Workload: either INGEST the benchmark's own traffic file (--traffic, to reproduce their delivery ratio)
or GENERATE a prioritized surface->ground workload swept by oversubscription rho (--rho) for our
head-to-head. Sinks (ground) are inferred as nodes with no outgoing contact unless --dst given.

Instances: drop DtnSim contact-plan files here, or generate ring-road plans with Fraire's tooling
(`pip install "dtn-tvg-util[ring_road]"`; DtnSim repo: bitbucket.org/lcd-unc-ar/dtnsim, centralized-model).
"""
import argparse
import json
import os
import random

DEFAULT_SERVICE_BPS = 10_000_000.0
DEFAULT_RESERVOIR = 500_000_000.0
PRIO_MIX = [(1, 0.15), (2, 0.25), (3, 0.60)]


def parse_contact_plan(path):
    """Parse an ION/DtnSim contact plan -> (nodes, links). links keyed by (src,dst)."""
    nodes = set()
    links = {}  # (src,dst) -> {"rate_bps": float, "owlt": float, "windows": [(s,e)]}
    horizon = 0.0
    with open(path) as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) < 2 or parts[0] != "a":
                continue
            kind = parts[1]
            try:
                if kind == "contact":
                    s = float(parts[2].lstrip("+")); e = float(parts[3].lstrip("+"))
                    src, dst = parts[4], parts[5]
                    rate_bytes = float(parts[6]) if len(parts) > 6 else DEFAULT_SERVICE_BPS / 8.0
                    nodes.update([src, dst])
                    horizon = max(horizon, e)
                    if src == dst:
                        continue  # self-contact (local queueing convention); not a network link
                    lk = links.setdefault((src, dst), {"rate_bps": rate_bytes * 8.0, "owlt": 1.0, "windows": []})
                    lk["windows"].append((s, e))
                    lk["rate_bps"] = rate_bytes * 8.0
                elif kind == "range":
                    s = float(parts[2].lstrip("+")); e = float(parts[3].lstrip("+"))
                    a, b = parts[4], parts[5]
                    owlt = float(parts[6]) if len(parts) > 6 else 1.0
                    nodes.update([a, b])
                    if (a, b) in links:
                        links[(a, b)]["owlt"] = owlt
            except (IndexError, ValueError):
                continue
    return nodes, links, horizon


def parse_tvg(path):
    """Parse a dtn-tvg-util P-TVG (ring-road) JSON -> (nodes, links, horizon).

    Contact tuple: [src, dst, start_unix, end_unix, [[p_lo, p_hi, [[t, rate_Bps, _]...]]...]].
    Deterministic (R_DET) contacts carry p=[0.0,1.0]; R_STOCH variants carry a real failure bound
    (captured as pmin per link for Phase 3). Absolute unix times are normalized to a 0 epoch.
    """
    d = json.load(open(path))
    nodes = set(d.get("vertices", {}).keys())
    raw, starts = [], []
    for e in d.get("edges", []):
        for c in e.get("contacts", []):
            src, dst, start, end = c[0], c[1], float(c[2]), float(c[3])
            rate_bytes, pavail = 250000.0, 1.0
            try:
                p_lo, p_hi, ratelist = c[4][0][0], c[4][0][1], c[4][0][2]
                rate_bytes = float(ratelist[0][1]); pavail = float(p_hi)
            except (IndexError, TypeError, ValueError):
                pass
            nodes.update([src, dst])
            raw.append((src, dst, start, end, rate_bytes * 8.0, pavail)); starts.append(start)
    epoch = min(starts) if starts else 0.0
    links, horizon = {}, 0.0
    for src, dst, start, end, rate_bps, pavail in raw:
        s, e = start - epoch, end - epoch
        if e <= s:
            continue
        horizon = max(horizon, e)
        lk = links.setdefault((src, dst), {"rate_bps": rate_bps, "owlt": 1.0, "windows": [], "pavail": pavail})
        lk["windows"].append((s, e)); lk["rate_bps"] = rate_bps; lk["pavail"] = min(lk["pavail"], pavail)
    return nodes, links, horizon


def infer_sinks(nodes, links):
    """Ground/sink nodes = have incoming contacts but no outgoing (data terminates there)."""
    has_out = {s for (s, d) in links}
    has_in = {d for (s, d) in links}
    sinks = sorted(n for n in nodes if n in has_in and n not in has_out)
    return sinks or sorted(has_in - set(list(has_out)[: max(0, len(has_out) - 1)]))  # fallback


def build_topology(nodes, links, horizon):
    topo_nodes = []
    for n in sorted(nodes, key=lambda x: (len(x), x)):
        topo_nodes.append({"nodeId": n, "name": f"node {n}",
                           "serviceRateBps": DEFAULT_SERVICE_BPS, "reservoirCapacityBytes": DEFAULT_RESERVOIR})
    topo_links = []
    for (src, dst), lk in sorted(links.items()):
        wins = sorted(lk["windows"])
        topo_links.append({
            "linkId": f"{src}-{dst}", "sourceNodeId": src, "destinationNodeId": dst,
            "baseBandwidthBps": lk["rate_bps"], "propagationDelaySec": lk["owlt"],
            "contacts": [{"startSec": round(s, 1), "endSec": round(e, 1)} for s, e in wins],
        })
    return {"regime": "R_DET", "nodes": topo_nodes, "links": topo_links}


def deliverable_capacity_bytes(links, sinks):
    """Bottleneck proxy: total contact-seconds x rate on the last hop into sink nodes."""
    cap = 0.0
    sinkset = set(sinks)
    for (src, dst), lk in links.items():
        if dst in sinkset:
            up = sum(e - s for s, e in lk["windows"])
            cap += lk["rate_bps"] * up / 8.0
    return cap


def generate_workload(rng, srcs, sinks, horizon, rho, cap_bytes):
    """Prioritized src->sink workload; critical/ops fixed feasible budgets, bulk absorbs rho."""
    n = max(6, min(300, len(srcs) * 3))
    n_crit = max(1, round(0.15 * n)); n_ops = max(1, round(0.25 * n)); n_bulk = max(1, n - n_crit - n_ops)
    crit_b, ops_b = 0.10 * cap_bytes, 0.15 * cap_bytes
    bulk_b = max(0.0, rho * cap_bytes - crit_b - ops_b)
    tasks = []
    tid = [0]

    def emit(count, budget, prio, dl_lo, dl_hi):
        ws = [rng.uniform(0.6, 1.4) for _ in range(count)]
        s = sum(ws) or 1.0
        for w in ws:
            size = max(500_000, int(budget * w / s))
            orig = rng.uniform(0, horizon * 0.7)
            dl = min(orig + rng.uniform(dl_lo, dl_hi), horizon)
            src = rng.choice(srcs)
            dst = rng.choice([x for x in sinks if x != src] or sinks)
            tasks.append({"taskId": f"T{tid[0]}", "sourceNodeId": src,
                          "destinationNodeId": dst,
                          "originationTimeSec": round(orig, 1), "deadlineSec": round(dl, 1),
                          "taskSizeBytes": size, "priority": prio})
            tid[0] += 1

    emit(n_crit, crit_b, 1, horizon * 0.15, horizon * 0.5)
    emit(n_ops, ops_b, 2, horizon * 0.25, horizon * 0.7)
    emit(n_bulk, bulk_b, 3, horizon * 0.4, horizon * 0.95)
    rng.shuffle(tasks)
    return tasks


def ingest_traffic(path):
    """Ingest a DtnSim traffic file (best-effort). Expected rows: src dst start size [priority]."""
    tasks = []
    with open(path) as f:
        for i, raw in enumerate(f):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split()
            if len(p) < 4:
                continue
            src, dst, start, size = p[0], p[1], float(p[2]), int(float(p[3]))
            prio = int(p[4]) if len(p) > 4 else 3
            tasks.append({"taskId": f"T{i}", "sourceNodeId": src, "destinationNodeId": dst,
                          "originationTimeSec": round(start, 1),
                          "deadlineSec": round(start + 1e9, 1), "taskSizeBytes": size, "priority": prio})
    return tasks


def main():
    ap = argparse.ArgumentParser(description="RUCoP/DtnSim contact-plan -> canonical scenario.")
    ap.add_argument("--contact-plan", default=None, help="ION/DtnSim contact plan text file")
    ap.add_argument("--tvg", default=None, help="dtn-tvg-util P-TVG JSON (ring-road)")
    ap.add_argument("--traffic", default=None, help="benchmark traffic file (else generate)")
    ap.add_argument("--rho", type=float, default=1.0)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--dst", default=None, help="comma-separated sink node ids (else inferred)")
    ap.add_argument("--outdir", default=".")
    args = ap.parse_args()

    if args.tvg:
        nodes, links, horizon = parse_tvg(args.tvg)
        gs = sorted(n for n in nodes if str(n).startswith("gs"))
        sinks = args.dst.split(",") if args.dst else (gs or infer_sinks(nodes, links))
        srcs = gs or sorted(nodes)          # ring-road: gs-to-gs traffic through the sat mesh
        src_name = os.path.basename(args.tvg)
    elif args.contact_plan:
        nodes, links, horizon = parse_contact_plan(args.contact_plan)
        sinks = args.dst.split(",") if args.dst else infer_sinks(nodes, links)
        srcs = sorted(n for n in nodes if n not in set(sinks)) or sorted(nodes)
        src_name = os.path.basename(args.contact_plan)
    else:
        raise SystemExit("provide --tvg (P-TVG JSON) or --contact-plan (ION/DtnSim text)")
    if not links:
        raise SystemExit("no contacts parsed")
    topo = build_topology(nodes, links, horizon)
    cap = deliverable_capacity_bytes(links, sinks) or 1.0

    rng = random.Random(args.seed * 100003 + int(args.rho * 1000))
    work = ingest_traffic(args.traffic) if args.traffic else \
        generate_workload(rng, srcs, sinks, horizon, args.rho, cap)

    os.makedirs(args.outdir, exist_ok=True)
    json.dump(topo, open(os.path.join(args.outdir, "topology.json"), "w"))
    json.dump(work, open(os.path.join(args.outdir, "workload.json"), "w"))
    offered = sum(t["taskSizeBytes"] for t in work)
    meta = {"regime": "R_DET", "source": "rucop", "contact_plan": src_name,
            "rho": args.rho, "seed": args.seed, "horizon": horizon,
            "nodes": len(nodes), "links": len(links), "sinks": sinks, "flows": len(work),
            "deliverable_capacity_bytes": int(cap), "offered_bytes": int(offered),
            "offered_over_capacity": round(offered / cap, 2)}
    json.dump(meta, open(os.path.join(args.outdir, "meta.json"), "w"), indent=2)
    print(json.dumps({k: meta[k] for k in ("nodes", "links", "sinks", "flows", "horizon",
                                           "deliverable_capacity_bytes", "offered_bytes",
                                           "offered_over_capacity")}, indent=2))


if __name__ == "__main__":
    main()
