"""VortexPCE vs contemporaries on the data plane, under contact regimes, on real topologies.

The question is not the published CRP result. It is: on a real data plane, how does VortexPCE
compare to what the other PCEs actually emit -- line-rate (Eager) transmission -- when the network
is intermittent (R_DET) and when it is not (R_STATIC)?

Two regimes, two transports, matched to the physics the maintainer pointed out:

  R_STATIC (Internet2+OARnet, continuous) is run over TCP. A backbone carries TCP, whose congestion
  control is what a paced PCE actually competes with; an unpaced-UDP framing would manufacture a
  microburst win the field never suffers here. VortexPCE should be close -- and its conservative
  admission can even cost it raw throughput.

  R_DET (Amazon Leo, intermittent) is run over UDP. TCP reads a contact blackout as congestion and
  collapses, so R_DET is fundamentally a UDP / custody-transfer regime -- itself a finding. Both
  arms get the same SHALLOW buffer (a normal router, not a DTN relay): store-and-forward is
  VortexPCE's capability, so handing the field a deep buffer would give it VortexPCE's advantage.
  The only variable is contact-awareness -- Eager (the field's line-rate CBR) transmits blind and
  its blackout bytes are dropped; DFE gates transmission into the contact windows.

Two modes: Eager (line rate, what Pola/ODL/Netphony emit -- the six-way interop study measured they
cluster there) and DFE (VortexPCE's real /api/v1/solve: which flows it admits and at what rate).
Ground truth is the router tc qdisc counter (sent + dropped bytes), never a sender's self-report.
"""
import json
import subprocess
import sys
import time
import urllib.request

import os
import run_study as rs   # reuse docker()/sh()/qdisc_stats()/start_servers() and the routed testbed

API = "http://127.0.0.1:18080"
SOLVE_TOPO = {"R_STATIC": "/topo/i2_reduced.json", "R_DET": "/topo/leo_reduced.json"}
KEY = "bench"
RX = "172.31.0.10"
HORIZON = 30.0
REPS = 3
# Which controller to measure, which regimes, and where to write: a re-run against a newer engine
# must not overwrite the published results file.
IMAGE = os.environ.get("VORTEX_BENCH_IMAGE", "vortex-pce-controller:paper")
RUN_REGIMES = os.environ.get("VORTEX_BENCH_REGIMES", "R_STATIC,R_DET").split(",")
OUT_FILE = os.environ.get("VORTEX_BENCH_OUT", "regime_dataplane_results.json")
# Extra controller settings, as comma-separated KEY=VALUE (e.g. the transport margin under test).
PCE_ENV = [kv.strip() for kv in os.environ.get("VORTEX_BENCH_PCE_ENV", "").split(",") if kv.strip()]

# --- regime definitions -----------------------------------------------------------------------
# Capacities are the real topologies' binding tiers, scaled into a congestible range; the ratio
# structure (not the absolute Gbps) is what the data plane turns on.
REGIMES = {
    "R_STATIC": {
        # Internet2+OARnet Ohio slice: the 10G OARnet regional tier is the bottleneck, /1000.
        # TCP, because a real backbone carries TCP and its congestion control is what a paced PCE
        # is really competing with -- an unpaced-UDP framing would manufacture a microburst win the
        # contemporaries never actually suffer here. The pacing benefit for bursty UDP is the
        # separate paced-vs-unpaced study; on a backbone under TCP, VortexPCE should be close.
        "protocol": "tcp",
        "bottleneck_mbit": 10.0,
        "buffer_bytes": 1_000_000,        # a realistic backbone switch buffer, not a shallow ToR
        "contact_plan": None,
        "capacity_mb": 10.0 * HORIZON / 8,
    },
    "R_DET": {
        # Amazon Leo relay downlink: feeder ~1 Gbps /100 = 10 Mbit during a pass. UDP only -- TCP's
        # control loop reads a contact blackout as congestion and collapses (retransmit storms,
        # timeouts), so R_DET is fundamentally a UDP / custody-transfer regime, which is itself a
        # finding rather than a limitation of this test.
        "protocol": "udp",
        "bottleneck_mbit": 10.0,
        # A SHALLOW buffer, for BOTH arms: a normal router, not a DTN relay. Store-and-forward is
        # VortexPCE's capability, not the field's, so handing the field a deep buffer would give it
        # VortexPCE's advantage. With the buffer equal, the only variable is contact-awareness:
        # Eager transmits into the dark and the packets are dropped; DFE transmits only in-window.
        "buffer_bytes": 100_000,
        "contact_plan": [(0, 4), (10, 14), (20, 24)],   # ~40% duty from the computed Leo plan
        "dark_mbit": 0.001,               # link genuinely dark -- no store-and-forward hold
        "capacity_mb": 10.0 * 12 / 8,     # 3 windows x 4s x 10 Mbit = 15 MB carried
    },
}


def set_bottleneck(rate_mbit, buffer_bytes):
    rs.docker("bench-router", "tc", "qdisc", "del", "dev", "eth1", "root", check=False)
    rs.docker("bench-router", "tc", "qdisc", "add", "dev", "eth1", "root", "handle", "1:",
              "htb", "default", "10")
    rs.docker("bench-router", "tc", "class", "add", "dev", "eth1", "parent", "1:", "classid",
              "1:10", "htb", "rate", f"{rate_mbit}mbit", "ceil", f"{rate_mbit}mbit")
    rs.docker("bench-router", "tc", "qdisc", "add", "dev", "eth1", "parent", "1:10", "handle",
              "10:", "bfifo", "limit", str(int(buffer_bytes)))


def set_rate(rate_mbit):
    rs.docker("bench-router", "tc", "class", "change", "dev", "eth1", "parent", "1:", "classid",
              "1:10", "htb", "rate", f"{rate_mbit}mbit", "ceil", f"{rate_mbit}mbit", check=False)


def contact_toggler(plan, full_mbit, dark_mbit):
    """Background process: toggle the bottleneck dark/light on the contact schedule."""
    script = []
    t = 0.0
    for start, end in plan:
        if start > t:
            script.append(("dark", start - t))
        script.append(("light", end - start))
        t = end
    if t < HORIZON:
        script.append(("dark", HORIZON - t))
    # Realise as a shell sleep/change sequence inside the router.
    parts = []
    for state, dur in script:
        rate = full_mbit if state == "light" else dark_mbit
        parts.append(f"tc class change dev eth1 parent 1: classid 1:10 htb rate {rate}mbit "
                     f"ceil {rate}mbit; sleep {dur:.2f}")
    return subprocess.Popen(
        ["docker", "exec", "bench-router", "sh", "-c", "; ".join(parts)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def start_pce(regime):
    """Fresh VortexPCE loaded with this regime's topology; a fresh engine per solve."""
    subprocess.run(["docker", "rm", "-f", "bench-pce"], capture_output=True)
    subprocess.run(["docker", "run", "-d", "--name", "bench-pce", "-p", "127.0.0.1:18080:8080",
                    "-e", "VORTEX_API_KEY=bench",
                    "-e", f"VORTEX_TOPOLOGY_FILE={SOLVE_TOPO[regime]}",
                    *[arg for kv in PCE_ENV for arg in ("-e", kv)],
                    "-v", os.path.join(os.path.dirname(os.path.abspath(__file__)), "regime_topologies") + ":/topo:ro", IMAGE],
                   capture_output=True)
    for _ in range(40):
        try:
            urllib.request.urlopen(f"{API}/livez", timeout=2).read(); return
        except Exception:
            time.sleep(1)
    raise RuntimeError("bench-pce did not become live")


def solve(flows_bytes, deadline):
    tasks = [{"taskId": f"F{i+1}", "sourceNodeId": "SRC", "destinationNodeId": "DST",
              "originationTimeSec": 0.0, "deadlineSec": deadline,
              "taskSizeBytes": int(b), "priority": 1} for i, b in enumerate(flows_bytes)]
    req = urllib.request.Request(f"{API}/api/v1/solve", data=json.dumps(tasks).encode(),
                                 method="POST",
                                 headers={"X-API-Key": KEY, "Content-Type": "application/json"})
    d = json.loads(urllib.request.urlopen(req, timeout=120).read())
    # returns list of (flow_index, size_bytes, rate_bps) for admitted flows
    admitted = []
    for sched in d.get("committedSchedules", []):
        idx = int(sched["taskId"][1:]) - 1
        admitted.append((idx, flows_bytes[idx], sched["committedRateBps"]))
    return admitted, d.get("committedFlowCount", 0), d.get("offeredFlowCount", 0)


def offer_tcp(flows_bytes, rates_bps):
    """R_STATIC: TCP, one flow per sender. Eager = unpaced (TCP finds its own rate); DFE = paced at
    e*. Returns delivered bytes (goodput) and completion time, from iperf's own reliable TCP
    accounting."""
    procs = []
    for i, nbytes in enumerate(flows_bytes):
        port = 5201 + i
        cmd = ["docker", "exec", f"bench-s{i + 1}", "iperf3", "-c", RX, "-p", str(port),
               "-n", str(int(nbytes)), "-J"]
        if rates_bps is not None:
            cmd += ["-b", str(int(rates_bps[i]))]
        procs.append(subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                      text=True))
    delivered, worst_wall = 0, 0.0
    for proc in procs:
        try:
            end = json.loads(proc.communicate(timeout=180)[0])["end"]
            summ = end.get("sum_received") or end.get("sum_sent") or end.get("sum")
            delivered += summ.get("bytes", 0)
            worst_wall = max(worst_wall, summ.get("seconds", 0.0))
        except Exception:
            pass
    return delivered, round(worst_wall, 1)


def offer_udp_cbr(flows_bytes):
    """R_DET Eager: a contact-blind plan sizes each flow's rate for the deadline assuming the link
    is continuously up -- rate = bytes / horizon -- and transmits across the whole horizon. The
    ~60% of bytes that land while the link is dark hit the shallow (non-DTN) buffer and are dropped.
    A realistic application CBR, not iperf's unlimited blast."""
    procs = []
    for i, nbytes in enumerate(flows_bytes):
        port = 5201 + i
        rate_bps = int(nbytes * 8 / HORIZON)
        procs.append(subprocess.Popen(
            ["docker", "exec", f"bench-s{i + 1}", "iperf3", "-u", "-c", RX, "-p", str(port),
             "-b", str(rate_bps), "-t", str(int(HORIZON)), "-l", "1200", "-J"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    for proc in procs:
        try:
            proc.wait(timeout=HORIZON + 30)
        except Exception:
            proc.kill()


def offer_udp_windowed(flows_bytes, windows, rate_mbit):
    """R_DET DFE: transmit each admitted flow's bytes only during contact windows, silent in the
    dark. This is the whole of VortexPCE's advantage here -- it knows the schedule and the field
    does not. Bursts are TIME-bounded (-t window_len), never byte-bounded: a byte-bounded send that
    misaligned into a blackout would block for hours pushing bytes through a dark link. Each burst
    is paced so its window carries the flow's per-window share."""
    n_win = len(windows)
    procs = []
    for i, nbytes in enumerate(flows_bytes):
        port = 5201 + i
        per_win = nbytes / n_win
        parts, prev_end = [], 0.0
        for (start, end) in windows:
            wait = max(0.0, start - prev_end)
            dur = end - start
            burst_bps = min(rate_mbit * 1e6, per_win * 8 / dur)
            parts.append(f"sleep {wait:.2f}; iperf3 -u -c {RX} -p {port} -b {int(burst_bps)} "
                         f"-t {dur:.0f} -l 1200 >/dev/null 2>&1")
            prev_end = end
        procs.append(subprocess.Popen(["docker", "exec", f"bench-s{i + 1}", "sh", "-c",
                                       "; ".join(parts)],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    for proc in procs:
        try:
            proc.wait(timeout=HORIZON + 30)
        except Exception:
            proc.kill()


def run_cell(regime, arm, load, n_flows=4):
    cfg = REGIMES[regime]
    capacity_mb = cfg["capacity_mb"]
    per_flow_mb = (load * capacity_mb) / n_flows
    flows_bytes = [int(per_flow_mb * 1e6)] * n_flows

    # DFE: ask VortexPCE's real solve which flows to admit and at what rate; offer exactly those.
    # Eager: offer every flow at line rate -- what the contemporaries emit.
    if arm == "DFE":
        start_pce(regime)
        admitted, committed, offered = solve(flows_bytes, HORIZON)
        offer_bytes = [b for (_, b, _) in admitted]
        rates = [r for (_, _, r) in admitted]
        admission = f"{committed}/{offered} admitted"
    else:
        offer_bytes = flows_bytes
        rates = None
        admission = "all (line rate)"
    if not offer_bytes:
        offer_bytes, rates = [0], None

    rs.start_servers()
    set_bottleneck(cfg["bottleneck_mbit"], cfg["buffer_bytes"])

    toggler = None
    if cfg["contact_plan"]:
        toggler = contact_toggler(cfg["contact_plan"], cfg["bottleneck_mbit"], cfg["dark_mbit"])

    started = time.time()
    wall = 0.0
    if cfg["protocol"] == "tcp":
        # R_STATIC: TCP goodput is the ground truth (TCP delivers reliably or not at all).
        delivered_bytes, wall = offer_tcp(offer_bytes, rates)
        stats = rs.qdisc_stats()
        drops = stats["dropped"]                      # TCP retransmit triggers, for context
        delivered_mb = round(delivered_bytes / 1e6, 1)
    else:
        # R_DET: the router qdisc is the ground truth. DFE gates into windows; Eager blasts blind.
        if arm == "DFE":
            offer_udp_windowed(offer_bytes, cfg["contact_plan"], cfg["bottleneck_mbit"])
        else:
            offer_udp_cbr(offer_bytes)
        wall = round(time.time() - started, 1)
        stats = rs.qdisc_stats()
        drops = stats["dropped"]
        delivered_mb = round(stats["sent_bytes"] / 1e6, 1)   # bytes past the bottleneck = delivered
    if toggler:
        toggler.wait()
    offered_mb = round(sum(offer_bytes) / 1e6, 1)
    return {"regime": regime, "arm": arm, "load": load, "admission": admission,
            "offered_mb": offered_mb, "delivered_mb": delivered_mb,
            "router_dropped": drops, "wall_sec": wall}


def main():
    arms = sys.argv[1:] or ["Eager", "DFE"]
    rows = []
    for regime in [r for r in ("R_STATIC", "R_DET") if r in RUN_REGIMES]:
        print(f"\n===== {regime} (capacity {REGIMES[regime]['capacity_mb']:.1f} MB / {HORIZON:.0f}s) =====",
              flush=True)
        for load in (0.5, 1.0, 1.5):
            for arm in arms:
                reps = [run_cell(regime, arm, load) for _ in range(REPS)]
                drops = sorted(r["router_dropped"] for r in reps)[len(reps) // 2]
                deliv = sorted(r["delivered_mb"] for r in reps)[len(reps) // 2]
                row = reps[0] | {"router_dropped": drops, "delivered_mb": deliv}
                rows.append(row)
                print(f"  load {load:.1f}x {arm:<6}: {row['admission']:<16} offered {row['offered_mb']:>5} MB  "
                      f"delivered {deliv:>5} MB  router-dropped {drops:>7}", flush=True)
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), OUT_FILE)
    with open(out, "w") as f:
        json.dump(rows, f, indent=2)
    print(f"\nwrote {OUT_FILE}")


if __name__ == "__main__":
    sys.exit(main())
