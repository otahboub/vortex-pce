#!/usr/bin/env python3
"""
a1_flagship.py — R_DET flagship (campaign §5, A1): VortexPCE vs real ION/CGR on RRN-A under load.

The published RRN-A contact plan (16-sat Walker ring road, cold spots 7-31 -> server 48), with every
contact occurring (R_DET). Bundles of 1 MB are generated at seeded uniform times over the first 12 h;
every 10th is critical with a 4 h deadline, the rest have 12 h. Offered load is a multiple of the
saturated ceiling (max-flow with every cold spot backlogged).

Each system uses its own importance mechanism:
  vortex  the real controller (R_DET defaults: LWEEF / BOUNDED_CGR / EAP / DFE), at utilization headroom
          1.0 (ION's) and at the 0.9 default (vortex:1.0, vortex:0.9). Every task is strict
          class (priority 1), so critical traffic is favoured by its tighter deadline. Scored from the
          committed schedules: under R_DET the committed plan is what is delivered.
  ion     real ION BP/CGR, every node live (ion_multinode.py); critical bundles are expedited, the rest
          normal, lifetime = deadline. Scored on bundles received (ION discards expired bundles).
  ceiling the smaller of two upper bounds: max-flow over the time-expanded contact graph (each contact
          carrying floor(rate x duration / bundle) bundles, bundles entering at their generation time,
          per-bundle deadlines relaxed to the latest one), and the number of bundles that could each
          reach the server by its own deadline with the network to itself.

Usage: a1_flagship.py --loads 0.5,1,2 --seeds 0,1,2 --out results/rrnA_rdet_flagship.csv \
                      --work DIR [--arms vortex,ion] [--image vortex-pce-controller:paper]
"""
import argparse
import csv
import datetime as dt
import json
import os
import random
import re
import subprocess
import sys
import time
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from datasets.rucop import parse_contact_plan, build_topology  # noqa: E402
from flood_vs_crp_load import _Dinic  # noqa: E402
from vortex_sdp import API, KEY, boot_controller, http_post_solve, kill_controller  # noqa: E402
from contention_sim import contact_bits, edges_from_schedules, replay  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
PLAN = os.path.join(HERE, "datasets/fixtures/RRN-A_16sats_Walker.contactplan.txt")
SRCS = [str(s) for s in range(7, 32)]
DST = "48"
B = 1_000_000
GEN_H, CRIT_DL_H, NORM_DL_H = 12.0, 4.0, 12.0


def parse_nodes(spec):
    """Node list from "7-31", "3,5,9" or a mix ("3-5,9")."""
    out = []
    for part in spec.split(","):
        lo, _, hi = part.strip().partition("-")
        out += [str(n) for n in range(int(lo), int(hi or lo) + 1)]
    return out


def ceiling(links, bundles):
    """Max-flow in bundles; bundles = [(src, t_gen, deadline)]."""
    horizon = max(d for _, _, d in bundles)
    events = defaultdict(set)
    for s, t, _ in bundles:
        events[s].add(t)
    present = [(w[0], w[1], u, v, max(0, int(lk["rate_bps"] * (w[1] - w[0]) / 8 / B)))
               for (u, v), lk in links.items() for w in lk["windows"] if w[0] < horizon]
    for ws, we, u, v, _ in present:
        events[u].update((ws, we))
        events[v].update((ws, we))
    idx, times, n = {}, {}, 2
    for node, ts in events.items():
        times[node] = sorted(ts)
        for t in times[node]:
            idx[(node, t)] = n
            n += 1
    k0 = n
    g = _Dinic(n + len(present))
    big = 10 ** 9
    for node, tl in times.items():
        for a, b in zip(tl, tl[1:]):
            g.add(idx[(node, a)], idx[(node, b)], big)
        if node == DST:
            for t in tl:
                if t <= horizon:
                    g.add(idx[(node, t)], 1, big)
    import bisect
    for i, (ws, we, u, v, c) in enumerate(present):
        if c <= 0:
            continue
        tl = times[u]
        j = bisect.bisect_left(tl, we) - 1
        if j < 0:
            continue
        g.add(idx[(u, tl[j])], k0 + i, big)
        g.add(k0 + i, idx[(v, ws)], c)
    count = defaultdict(int)
    for s, t, _ in bundles:
        count[(s, t)] += 1
    for (s, t), m in count.items():
        g.add(0, idx[(s, t)], m)
    return g.maxflow(0, 1)


def individually_deliverable(links, bundles):
    """Bundles that could reach the server by their deadline if they had the network to themselves:
    earliest arrival with each hop fitting whole inside one contact."""
    out = defaultdict(list)
    for (u, v), lk in links.items():
        for ws, we in lk["windows"]:
            out[u].append((ws, we, v, B * 8 / lk["rate_bps"], lk["owlt"]))
    n = 0
    for s, t0, dl in bundles:
        best, pq = {s: t0}, [(t0, s)]
        import heapq
        hit = False
        while pq:
            t, u = heapq.heappop(pq)
            if u == DST:
                hit = t <= dl
                break
            if t > best.get(u, 1e18) or t > dl:
                continue
            for ws, we, v, tx, owlt in out[u]:
                st = max(ws, t)
                fin = st + tx + owlt
                if st + tx <= we and fin < best.get(v, 1e18):
                    best[v] = fin
                    heapq.heappush(pq, (fin, v))
        n += hit
    return n


def make_bundles(n_per_src, seed):
    rng = random.Random(seed)
    out = []
    for s in SRCS:
        for _ in range(n_per_src):
            out.append([s, round(rng.uniform(0, GEN_H * 3600), 1)])
    out.sort(key=lambda b: (b[1], int(b[0])))
    rows = []
    for i, (s, t) in enumerate(out):
        crit = i % 10 == 0
        rows.append({"id": f"b{i}", "src": s, "t": t, "critical": crit,
                     "deadline": t + (CRIT_DL_H if crit else NORM_DL_H) * 3600})
    return rows


def run_vortex(bundles, nodes, links, H, scn, image, headroom, extra_env=None):
    os.makedirs(scn, exist_ok=True)
    topo = build_topology(nodes, links, H)
    work = [{"taskId": b["id"], "sourceNodeId": b["src"], "destinationNodeId": DST,
             "originationTimeSec": b["t"], "deadlineSec": b["deadline"], "taskSizeBytes": B, "priority": 1}
            for b in bundles]
    json.dump(topo, open(os.path.join(scn, "topology.json"), "w"))
    json.dump(work, open(os.path.join(scn, "workload.json"), "w"))
    boot_controller(scn, image, "BOUNDED_CGR", None,
                    dict({"VORTEX_SOLVE_TIMEOUT_SEC": "3600", "VORTEX_UTILIZATION_HEADROOM": str(headroom)},
                         **(extra_env or {})))
    t0 = time.time()
    try:
        resp = http_post_solve(work)
    finally:
        kill_controller()
    json.dump(resp, open(os.path.join(scn, "solve.json"), "w"))
    arrival = {}
    for s in resp.get("committedSchedules", []):
        arrival[s["taskId"]] = min(arrival.get(s["taskId"], 1e18), s["hops"][-1]["arrivalSec"])
    reasons = defaultdict(int)
    for u in resp.get("unadmittedTasks", []):
        reasons[u.get("reason")] += 1
    return arrival, dict(reasons), time.time() - t0


def replay_arrivals(bundles, links, schedules):
    """Arrival per bundle when its committed hops share real contact capacity, first come first served
    (contention_sim.replay; every contact occurs under R_DET). A capacity-feasible plan replays to
    itself; plans made without reservations contend."""
    present = [(w[0], w[1], u, v, ((u, v), w)) for (u, v), lk in links.items() for w in lk["windows"]]
    present_set = {p[4] for p in present}
    by_task = edges_from_schedules(schedules, links)
    edges = [by_task.get(b["id"], []) for b in bundles]
    reached = [{b["src"]: b["t"]} for b in bundles]
    priority = [1 if b["critical"] else 3 for b in bundles]
    replay(present_set, contact_bits(present, links), reached, edges, priority, DST, 0.0, float("inf"),
           float("inf"))
    return {b["id"]: r[DST] for b, r in zip(bundles, reached) if DST in r}


def run_vortex_isolated(bundles, nodes, links, H, scn, image, headroom):
    """Ablation: VortexPCE's routes without reservation or admission against other traffic. Each bundle
    is planned alone on an empty ledger (solve, then cancel to release its reservations), so every
    routable bundle gets its earliest route as if the network were idle; the plans are then replayed
    together on shared capacity."""
    import urllib.request
    os.makedirs(scn, exist_ok=True)
    topo = build_topology(nodes, links, H)
    json.dump(topo, open(os.path.join(scn, "topology.json"), "w"))
    json.dump([], open(os.path.join(scn, "workload.json"), "w"))
    boot_controller(scn, image, "BOUNDED_CGR", None,
                    {"VORTEX_SOLVE_TIMEOUT_SEC": "3600", "VORTEX_UTILIZATION_HEADROOM": str(headroom)})
    schedules, unrouted = [], 0
    try:
        for b in bundles:
            task = {"taskId": b["id"], "sourceNodeId": b["src"], "destinationNodeId": DST,
                    "originationTimeSec": b["t"], "deadlineSec": b["deadline"], "taskSizeBytes": B, "priority": 1}
            resp = http_post_solve([task])
            got = resp.get("committedSchedules", [])
            schedules.extend(got)
            unrouted += not got
            if got:
                req = urllib.request.Request(f"{API}/api/v1/tasks/{b['id']}", method="DELETE",
                                             headers={"X-API-Key": KEY})
                urllib.request.urlopen(req, timeout=60).read()
    finally:
        kill_controller()
    json.dump(schedules, open(os.path.join(scn, "schedules.json"), "w"))
    return replay_arrivals(bundles, links, schedules), {"unrouted_alone": unrouted}


def plan_following_overrides(bundles, schedules, dst, out_path, first_label=1000):
    """Per-bundle ION routing overrides that make each admitted bundle follow VortexPCE's committed route:
    the bundle gets a data label, and every node on its route forwards that label to the planned next hop
    (ipnadmin 'a rtovrd'). Returns the admitted bundles, labelled."""
    route = {}
    for sched in schedules:
        route.setdefault(sched["taskId"], [(str(h["sourceNodeId"]), str(h["destinationNodeId"]))
                                           for h in sched.get("hops", [])])
    labelled, ovrds = [], []
    for k, b in enumerate(b for b in bundles if b["id"] in route):
        label = first_label + k
        labelled.append(dict(b, label=label))
        ovrds += [{"node": u, "label": label, "dest": dst, "src": b["src"], "neighbor": v} for u, v in route[b["id"]]]
    json.dump(ovrds, open(out_path, "w"))
    return labelled


def vortex_controlled_plan(schedules, out_path):
    """The contact plan restricted to the contact windows VortexPCE reserved for the bundles it admitted:
    the PCE allocates contacts, and each ION node's CGR then forwards within that allocation."""
    keep = set()
    for sched in schedules:
        for hop in sched.get("hops", []):
            for slot in hop.get("transmissionSlots") or [{"startSec": hop["startSec"]}]:
                keep.add((str(hop["sourceNodeId"]), str(hop["destinationNodeId"]), float(slot["startSec"])))
    kept = 0
    with open(PLAN) as src, open(out_path, "w") as out:
        for line in src:
            p = line.split()
            if len(p) >= 7 and p[0] == "a" and p[1] == "contact":
                s, e, u, v = float(p[2].lstrip("+")), float(p[3].lstrip("+")), p[4], p[5]
                if not any(ku == u and kv == v and s <= kt < e for ku, kv, kt in keep):
                    continue
                kept += 1
            out.write(line)
    return kept


# The node-log signatures of an ION SDR failure; a run that shows one is discarded by the health check.
SDR_FAILURE = re.compile(r"Assertion failed|Transaction aborted|can.t begin transaction")


def _run_ion_container(cmd, gen, poll_s=5.0, name="a1-ion"):
    """Run the ION container, but stop it at the first SDR-failure signature in any node's ion.log: the run
    is already invalid (the health check would discard it), so waiting for the end of the plan only wastes
    the rest of its wall clock. A run that never shows one runs to completion exactly as before."""
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    logs = [os.path.join(gen, n.strip(), "ion.log") for n in open(os.path.join(gen, "nodes.txt")) if n.strip()]
    offsets = {}
    aborted = None
    while proc.poll() is None:
        time.sleep(poll_s)
        for log in logs:
            try:
                with open(log, errors="replace") as fh:
                    fh.seek(offsets.get(log, 0))
                    chunk = fh.read()
                    offsets[log] = fh.tell()
            except OSError:
                continue
            m = SDR_FAILURE.search(chunk)
            if m:
                aborted = f"{log}: {m.group(0)}"
                break
        if aborted:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True)
            break
    out, _ = proc.communicate()
    if aborted:
        out += f"\nABORTED EARLY on SDR failure ({aborted})\n"
    return subprocess.CompletedProcess(cmd, proc.returncode, out, "")


LINKEMU_IMAGE = "vortex-pce-controller:paper"  # any local image with python3; runs ion_linkemu.py


def run_ion(bundles, scn, compress, heap_words, image, plan=None, link_emu=False, emu_image=LINKEMU_IMAGE,
            lead_s=300, overrides=None):
    """Real ION with every node live. link_emu: links exist only inside their contact windows and at
    their rate (ion_linkemu.py, in a sidecar container whose network ION shares); without it the nodes'
    loopback never goes down and bundles queued for a neighbour still cross after a contact ends."""
    os.makedirs(scn, exist_ok=True)
    inject = [{"src": b["src"], "dst": DST, "t": b["t"], "bytes": B, "ttl": b["deadline"] - b["t"],
               "cos": 2 if b["critical"] else 1, "id": b["id"], "label": b.get("label")} for b in bundles]
    json.dump(inject, open(os.path.join(scn, "inject.json"), "w"))
    t1 = max(b["deadline"] for b in bundles)
    # Starting 48 nodes takes ~160 s; the plan's clock must not begin before they are all up.
    start = int(time.time()) + lead_s
    utc = dt.datetime.fromtimestamp(start, dt.timezone.utc).replace(tzinfo=None).isoformat()
    gen = os.path.join(scn, "ion")
    subprocess.run([sys.executable, os.path.join(HERE, "ion_multinode.py"), "--plan", plan or PLAN, "--out", gen,
                    "--t0", "0", "--t1", str(t1), "--compress", str(compress), "--start-utc", utc,
                    "--inject", os.path.join(scn, "inject.json"), "--heap-words", str(heap_words)]
                   + (["--link-emu"] if link_emu else [])
                   + (["--overrides", overrides] if overrides else []), check=True, capture_output=True)
    subprocess.run(["cp", os.path.join(HERE, "ion_multinode_run.sh"), os.path.join(gen, "run.sh")], check=True)
    wall = int(t1 / compress) + 30
    subprocess.run(["docker", "rm", "-f", "a1-ion", "a1-linkemu"], capture_output=True)
    net = []
    if link_emu:
        subprocess.run(["cp", os.path.join(HERE, "ion_linkemu.py"), gen], check=True)
        subprocess.run(["docker", "run", "-d", "--name", "a1-linkemu", "-v", f"{gen}:/work", "--entrypoint", "python3",
                        "-e", f"LINKEMU_TRACE={os.environ.get('LINKEMU_TRACE', '0')}",
                        emu_image, "/work/ion_linkemu.py", "/work/linkemu.json", "--stats",
                        "/work/linkemu_stats.json"], check=True, capture_output=True)
        net = ["--network", "container:a1-linkemu"]
    try:
        r = _run_ion_container(["docker", "run", "--rm", "--name", "a1-ion", "--ipc=host", *net, "-v", f"{gen}:/work",
                                "-e", f"START={start}", "-e", f"WALL={wall}", image, "bash", "/work/run.sh"], gen)
    finally:
        if link_emu:
            subprocess.run(["docker", "stop", "-t", "20", "a1-linkemu"], capture_output=True)
            emu_log = subprocess.run(["docker", "logs", "a1-linkemu"], capture_output=True, text=True)
            subprocess.run(["docker", "rm", "-f", "a1-linkemu"], capture_output=True)
    open(os.path.join(scn, "ion_run.log"), "w").write(r.stdout + r.stderr
                                                     + (emu_log.stdout + emu_log.stderr if link_emu else ""))
    health = {}
    hp = os.path.join(gen, "health.txt")
    if os.path.exists(hp):
        for line in open(hp):
            k, v = line.split()
            health[k] = int(v)
    if not health or health.get("start_slack_s", -1) < 0 or health.get("send_failures", 1) > 0 \
            or health.get("sdr_failures", 1) > 0:
        raise RuntimeError(f"invalid ION run {scn}: {health}")
    arrival = {}
    path = os.path.join(gen, "delivered.tsv")
    if os.path.exists(path):
        for line in open(path):
            bid, mtime = line.split()
            arrival[bid] = (int(mtime) - start) * compress
    return arrival


ODL_IMAGE = "odl-crossover-amd64:42ce950"  # pinned BGPCEP 42ce950, the matched-ODL baseline build


def run_odl(bundles, nodes, links, scn, image=ODL_IMAGE):
    """Real OpenDaylight BGPCEP as a contact-agnostic PCE (campaign §5, A1 third arm).

    At every change in which contacts are up, ODL's graph is updated over RESTCONF (graph YANG) and
    each pending bundle's path is requested over PCEP (PCReq/PCRep), reusing the matched-ODL adapter's
    transport. Updates are per link (DELETE the links that went down, PUT the ones that came up, each
    with a fixed edge id) and wait for ODL's operational graph to match: replacing the whole graph
    leaves stale edges in this BGPCEP build's routing graph. Delivery follows the ODL arm's fair model (odl_score.py): data
    moves only while ODL's whole path is up, cut-through, no store-and-forward in the network, no
    admission, first come first served on shared links; a bundle waits at its source between paths.
    A path is requested only when one exists in the snapshot (a breadth-first check); a sample of
    the skipped requests is still sent to ODL to confirm it finds no path there either."""
    sys.path.insert(0, os.path.join(HERE, "odl"))
    import odl_adapter as oa  # noqa: E402
    import base64
    import urllib.error
    import urllib.parse
    import urllib.request
    os.makedirs(scn, exist_ok=True)
    order = sorted(nodes, key=int)
    ip = {n: f"10.0.{int(n) // 250}.{int(n) % 250 + 1}" for n in order}
    by_ip = {v: k for k, v in ip.items()}
    t_end = max(b["deadline"] for b in bundles)
    bounds = {0.0, t_end}
    for lk in links.values():
        for ws, we in lk["windows"]:
            if ws < t_end:
                bounds.update((max(0.0, ws), min(t_end, we)))
    bounds = sorted(bounds)

    name = "a1-odl"
    subprocess.run(["docker", "rm", "-f", name], capture_output=True)
    boot = ("set -eu; sed -i -E 's/^(featuresBoot = .*)$/\\1," + ",".join(oa.PINNED_BOOT_FEATURES) +
            "/' /opt/opendaylight/etc/org.apache.karaf.features.cfg; exec /usr/local/bin/odl-entrypoint")
    subprocess.run(["docker", "run", "-d", "--platform", "linux/amd64", "--name", name, "-p", "127.0.0.1:18182:8181",
                    "-p", "127.0.0.1:14190:4189", "--entrypoint", "/bin/sh", image, "-c", boot],
                   check=True, capture_output=True)
    stats = {"snapshots": 0, "graph_loads": 0, "requests": 0, "paths": 0, "skipped_no_path": 0,
             "checked_skips": 0, "mismatches": 0}
    try:
        seed = None
        for _ in range(300):
            try:
                if seed not in (200, 201, 204):
                    seed, _ = oa.rest_put(18182, oa.graph_seed_payload(), 3)
                if seed in (200, 201, 204):
                    break
            except Exception:
                pass
            time.sleep(1)
        else:
            raise RuntimeError("OpenDaylight did not become ready")

        graph_url = "http://127.0.0.1:18182/rests/data/graph:graph-topology/graph=" + urllib.parse.quote(
            oa.GRAPH_NAME, safe="")
        auth = {"Authorization": "Basic " + base64.b64encode(b"admin:admin").decode(),
                "Accept": "application/yang-data+json"}

        def call(method, url, body=None):
            headers = dict(auth, **({"Content-Type": "application/yang-data+json"} if body is not None else {}))
            req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                         method=method, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    return r.status, r.read()
            except urllib.error.HTTPError as e:
                return e.code, e.read()

        vid = {n: i + 1 for i, n in enumerate(order)}
        eid = {k: i + 1 for i, k in enumerate(sorted(links))}
        base = oa.convert_topology({"nodes": [{"node_id": n, "ipv4": ip[n]} for n in order], "links": []})
        status, _ = oa.rest_put(18182, base, 30)
        if status not in (200, 201, 204):
            raise RuntimeError(f"vertex load failed: {status}")

        def edge(u, v):
            bw = f'{links[(u, v)]["rate_bps"] / 8:.2f}'
            return {"edge-id": eid[(u, v)], "name": f"{u}-{v}", "local-vertex-id": vid[u],
                    "remote-vertex-id": vid[v], "edge-attributes": {
                        "local-address": ip[u], "remote-address": ip[v], "metric": 1,
                        "te-metric": {"metric": 1, "max-link-bandwidth": bw, "max-resv-link-bandwidth": bw,
                                      "unreserved-bandwidth": [{"class-type": c, "bandwidth": bw} for c in range(8)]}}}

        current = set()

        def load(up_links):
            target = set(up_links)
            for u, v in sorted(current - target):
                call("DELETE", graph_url + f"/edge={eid[(u, v)]}")
            for u, v in sorted(target - current):
                status, body = call("PUT", graph_url + f"/edge={eid[(u, v)]}", {"graph:edge": [edge(u, v)]})
                if status not in (200, 201, 204):
                    raise RuntimeError(f"edge load failed: {status} {body[:200]}")
            current.clear()
            current.update(target)
            deadline = time.time() + 30
            while time.time() < deadline:
                status, body = call("GET", graph_url + "?content=nonconfig")
                have = body.count(b'"edge-id"') if status == 200 else 0
                if have == len(target):
                    break
                time.sleep(0.02)
            else:
                raise RuntimeError(f"ODL graph did not converge to {len(target)} edges")
            stats["graph_loads"] += 1

        sock = oa.establish("127.0.0.1", 14190, 60)
        seq = [0]
        last_send = [time.time()]

        def ask(src):
            seq[0] += 1
            sock.sendall(oa.request_message(seq[0], ip[src], ip[DST]))
            last_send[0] = time.time()
            while True:
                kind, reply = oa.recv_message(sock, 60)
                if kind != 2:
                    break
            status, path = oa.parse_reply(reply, ip[src], by_ip, seq[0])
            stats["requests"] += 1
            return path if status == "PATH" else None

        rng = random.Random(0)
        remaining = {b["id"]: float(B) for b in bundles}
        arrival = {}
        prev_up = None
        for t0, t1 in zip(bounds, bounds[1:]):
            if t1 - t0 <= 1e-9:
                continue
            stats["snapshots"] += 1
            if time.time() - last_send[0] > 20:  # PCEP keepalive so ODL's dead timer never fires
                sock.sendall(oa.message(2))
                last_send[0] = time.time()
            mid = (t0 + t1) / 2
            up_links = sorted(k for k, lk in links.items() if any(ws <= mid < we for ws, we in lk["windows"]))
            adj = defaultdict(list)
            for u, v in up_links:
                adj[u].append(v)
            pending = [b for b in bundles if remaining[b["id"]] > 0 and b["t"] < t1 and b["deadline"] > t0]
            if not pending:
                continue
            if up_links != prev_up:
                load(up_links)
                prev_up = up_links
            residual = {k: links[k]["rate_bps"] * (t1 - t0) / 8 for k in up_links}
            reach = {}
            for b in pending:  # first come, first served: bundles are sorted by generation time
                src = b["src"]
                if src not in reach:
                    seen, stack = {src}, [src]
                    while stack:
                        u = stack.pop()
                        for v in adj[u]:
                            if v not in seen:
                                seen.add(v)
                                stack.append(v)
                    reach[src] = DST in seen
                if not reach[src]:
                    stats["skipped_no_path"] += 1
                    if rng.random() < 0.01:
                        stats["checked_skips"] += 1
                        if ask(src) is not None:
                            stats["mismatches"] += 1
                    continue
                path = ask(src)
                if path is None or path[0] != src or path[-1] != DST \
                        or any(h not in residual for h in zip(path, path[1:])):
                    stats["mismatches"] += 1
                    continue
                stats["paths"] += 1
                hops = list(zip(path, path[1:]))
                start = max(t0, b["t"])
                room = min(residual[h] for h in hops) * (t1 - start) / (t1 - t0)
                send = min(room, remaining[b["id"]])
                if send <= 0:
                    continue
                for h in hops:
                    residual[h] -= send
                remaining[b["id"]] -= send
                if remaining[b["id"]] <= 1e-6:
                    rate = min(links[h]["rate_bps"] for h in hops) / 8
                    arrival[b["id"]] = start + send / rate + sum(links[h]["owlt"] for h in hops)
        sock.close()
    finally:
        logs = subprocess.run(["docker", "logs", name], capture_output=True, text=True)
        open(os.path.join(scn, "odl_controller.log"), "w").write(logs.stdout[-200000:] + logs.stderr[-200000:])
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)
    json.dump(stats, open(os.path.join(scn, "odl_stats.json"), "w"), indent=1)
    if stats["mismatches"]:
        raise RuntimeError(f"ODL disagreed with path existence {stats['mismatches']} times: {stats}")
    return arrival, stats


def score(bundles, arrival, tol=0.0):
    by = {b["id"]: b for b in bundles}
    ok = [i for i, t in arrival.items() if i in by and t <= by[i]["deadline"] + tol]
    crit = [b for b in bundles if b["critical"]]
    return {"delivered": len(ok),
            "critical_on_time_pct": round(100 * sum(by[i]["critical"] for i in ok) / max(1, len(crit)), 1),
            "on_time_pct": round(100 * len(ok) / len(bundles), 1)}


def main():
    global PLAN, SRCS, DST, B  # read at call time by ceiling, make_bundles and the arm runners
    ap = argparse.ArgumentParser()
    ap.add_argument("--loads", default="0.5,1,2", help="offered load as a multiple of the saturated ceiling")
    ap.add_argument("--seeds", default="0,1,2")
    ap.add_argument("--arms", default="vortex:1.0,vortex:0.9,ion")
    ap.add_argument("--image", default="vortex-pce-controller:paper")
    ap.add_argument("--ion-image", default="ion-dtn:paper")
    ap.add_argument("--ion-attempts", type=int, default=3,
                    help="ION runs per cell before giving up (each failed health check is discarded)")
    ap.add_argument("--ion-link-emu", action="store_true",
                    help="run ION's links through ion_linkemu.py (contact windows and rates enforced)")
    ap.add_argument("--compress", type=float, default=60.0)
    ap.add_argument("--heap-words", type=int, default=2_500_000)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", default=os.path.join(HERE, "results/rrnA_rdet_flagship.csv"))
    ap.add_argument("--plan", default=PLAN, help="ION contact plan (default: RRN-A)")
    ap.add_argument("--srcs", default="7-31", help='source nodes, e.g. "7-31" or "3,5,9"')
    ap.add_argument("--dst", default=DST, help="destination node")
    ap.add_argument("--bundle-bytes", type=int, default=B,
                    help="bundle size (default 1 MB); the controller accepts at most 1,000 tasks per solve")
    a = ap.parse_args()
    PLAN, SRCS, DST, B = a.plan, parse_nodes(a.srcs), a.dst, a.bundle_bytes
    nodes, links, H = parse_contact_plan(PLAN)
    arms = a.arms.split(",")

    # Saturated ceiling: every cold spot backlogged (far more bundles than any contact plan carries).
    sat = ceiling(links, [(s, t, t + NORM_DL_H * 3600) for s, t, *_ in
                          [(b["src"], b["t"]) for b in make_bundles(200, 999)]])
    print(f"saturated ceiling: {sat} bundles of 1 MB", flush=True)
    new = not os.path.exists(a.out)
    with open(a.out, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["load", "seed", "offered", "ceiling", "arm", "delivered", "share_of_ceiling_pct",
                        "critical_on_time_pct", "on_time_pct", "refusals", "wall_s"])
        for load in [float(x) for x in a.loads.split(",")]:
            per_src = max(1, round(load * sat / len(SRCS)))
            for seed in [int(x) for x in a.seeds.split(",")]:
                bundles = make_bundles(per_src, seed)
                triples = [(b["src"], b["t"], b["deadline"]) for b in bundles]
                # Both are upper bounds; the max-flow relaxes per-bundle deadlines, the other contention.
                cap = min(ceiling(links, triples), individually_deliverable(links, triples))
                cell = os.path.join(a.work, f"L{load}_s{seed}")
                for arm in arms:
                    t0 = time.time()
                    refusals = ""
                    if arm.startswith("vortex-isolated"):
                        headroom = float(arm.split(":")[1]) if ":" in arm else 0.9
                        arrival, info = run_vortex_isolated(bundles, nodes, links, H,
                                                            os.path.join(cell, arm.replace(":", "_h")), a.image, headroom)
                        refusals = json.dumps(info, sort_keys=True)
                        sc = score(bundles, arrival, tol=1e-6)
                    elif arm.startswith("vortex-fcfs") or arm.startswith("vortex-replay"):
                        # vortex-fcfs: reservation and admission, first-come-first-served selection;
                        # vortex-replay: full VortexPCE, scored by the same replay as the ablation arms.
                        headroom = float(arm.split(":")[1]) if ":" in arm else 0.9
                        env = {"VORTEX_TASK_SELECT_POLICY": "FCFS"} if arm.startswith("vortex-fcfs") else {}
                        scn_dir = os.path.join(cell, arm.replace(":", "_h"))
                        planned, reasons, _ = run_vortex(bundles, nodes, links, H, scn_dir, a.image, headroom, env)
                        resp = json.load(open(os.path.join(scn_dir, "solve.json")))
                        arrival = replay_arrivals(bundles, links, resp.get("committedSchedules", []))
                        mism = sum(1 for k in planned if k not in arrival or arrival[k] > planned[k] + 1e-6)
                        refusals = json.dumps(dict(reasons, replay_mismatches=mism), sort_keys=True)
                        sc = score(bundles, arrival, tol=1e-6)
                    elif arm.startswith("vortex-ionplan"):
                        # VortexPCE's plan executed by real ION: only admitted bundles are sent, each with a
                        # data label, and every node on its committed route has a routing override to the
                        # planned next hop (ion_multinode --overrides), so ION follows the plan's routes
                        # hop by hop over the full contact plan.
                        headroom = float(arm.split(":")[1]) if ":" in arm else 1.0
                        scn_dir = os.path.join(cell, arm.replace(":", "_h"))
                        planned, reasons, _ = run_vortex(bundles, nodes, links, H, os.path.join(scn_dir, "plan"),
                                                         a.image, headroom)
                        resp = json.load(open(os.path.join(scn_dir, "plan", "solve.json")))
                        ovr = os.path.join(scn_dir, "overrides.json")
                        labelled = plan_following_overrides(bundles, resp.get("committedSchedules", []), DST, ovr)
                        for attempt in range(a.ion_attempts):
                            try:
                                arrival = run_ion(labelled, os.path.join(scn_dir, f"ion_try{attempt}"), a.compress,
                                                  a.heap_words, a.ion_image, link_emu=a.ion_link_emu,
                                                  overrides=ovr)
                                break
                            except RuntimeError as e:
                                print(e, flush=True)
                        else:
                            raise SystemExit(f"ION failed {a.ion_attempts} times in {cell}")
                        refusals = json.dumps(dict(reasons, admitted=len(labelled), planned_on_time=len(planned)),
                                              sort_keys=True)
                        sc = score(bundles, arrival, tol=a.compress)
                    elif arm.startswith("vortex-ion"):
                        # VortexPCE as the controller of a real ION network: only the bundles it admits
                        # are injected, and ION's contact plan is restricted to the contacts it reserved.
                        headroom = float(arm.split(":")[1]) if ":" in arm else 0.9
                        scn_dir = os.path.join(cell, arm.replace(":", "_h"))
                        planned, reasons, _ = run_vortex(bundles, nodes, links, H, os.path.join(scn_dir, "plan"),
                                                         a.image, headroom)
                        resp = json.load(open(os.path.join(scn_dir, "plan", "solve.json")))
                        plan_path = os.path.join(scn_dir, "vortex_allocated.contactplan.txt")
                        kept = vortex_controlled_plan(resp.get("committedSchedules", []), plan_path)
                        admitted = [b for b in bundles if b["id"] in planned]
                        for attempt in range(a.ion_attempts):
                            try:
                                arrival = run_ion(admitted, os.path.join(scn_dir, f"ion_try{attempt}"),
                                                  a.compress, a.heap_words, a.ion_image, plan=plan_path,
                                                  link_emu=a.ion_link_emu)
                                break
                            except RuntimeError as e:
                                print(e, flush=True)
                        else:
                            raise SystemExit(f"ION failed {a.ion_attempts} times in {cell}")
                        refusals = json.dumps(dict(reasons, admitted=len(admitted), contacts_allocated=kept,
                                                   planned_on_time=len(planned)), sort_keys=True)
                        sc = score(bundles, arrival, tol=a.compress)
                    elif arm.startswith("vortex"):
                        # vortex:<headroom>; ION uses all of each contact, Vortex's default keeps 10% back.
                        headroom = float(arm.split(":")[1]) if ":" in arm else 0.9
                        arrival, reasons, _ = run_vortex(bundles, nodes, links, H,
                                                         os.path.join(cell, arm.replace(":", "_h")), a.image, headroom)
                        refusals = json.dumps(reasons, sort_keys=True)
                        sc = score(bundles, arrival, tol=1e-6)
                    elif arm == "odl":
                        arrival, stats = run_odl(bundles, nodes, links, os.path.join(cell, "odl"))
                        refusals = json.dumps(stats, sort_keys=True)
                        sc = score(bundles, arrival, tol=1e-6)
                    else:
                        for attempt in range(a.ion_attempts):
                            try:
                                arrival = run_ion(bundles, os.path.join(cell, f"ion_try{attempt}"),
                                                  a.compress, a.heap_words, a.ion_image,
                                                  link_emu=a.ion_link_emu)
                                break
                            except RuntimeError as e:
                                print(e, flush=True)
                        else:
                            raise SystemExit(f"ION failed {a.ion_attempts} times in {cell}")
                        sc = score(bundles, arrival, tol=a.compress)
                    row = [load, seed, len(bundles), cap, arm, sc["delivered"],
                           round(100 * sc["delivered"] / max(1, cap), 1), sc["critical_on_time_pct"],
                           sc["on_time_pct"], refusals, round(time.time() - t0)]
                    w.writerow(row)
                    f.flush()
                    print(",".join(str(x) for x in row), flush=True)


if __name__ == "__main__":
    main()
