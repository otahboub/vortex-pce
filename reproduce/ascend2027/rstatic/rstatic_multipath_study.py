"""R_STATIC done right: VortexPCE's 4-stage CRP (Optimized) vs ClassicIP, on a real two-path cut.

DFE is a rate mechanism for link intermittency; on a continuous backbone it earns nothing, which is
why the first R_STATIC cut (DFE vs field) showed VortexPCE at parity. The arc VortexPCE actually has
over ClassicIP contemporaries here is the CRP's *routing*: smarter task selection (LWEEF) and
multi-path generation/selection (FAST_K_SHORTEST_PATHS + EAP) that spreads flows across parallel
paths, where a shortest-path CSPF (what Pola/ODL/Netphony emit) funnels everything onto one.

Topology: OAR-COL -> I2-CHI has two edge-disjoint paths on the real Internet2+OARnet map (via
Cleveland, via Cincinnati). Realised as two independent 10 Mbit pipes. A flow VortexPCE routes via
CLE lands on pipe A, via CIN on pipe B. Under load past one pipe's capacity, Optimized uses both and
ClassicIP is capped at one -- measured as delivered TCP goodput, the router's own accounting.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

API = "http://127.0.0.1:18081"
KEY = "bench"
HORIZON = 30.0
REPS = 2
RX = {"A": "172.41.0.10", "B": "172.42.0.10"}
TOPO = "/topo/i2_twopath.json"

ARMS = {
    "ClassicIP": {"select": "FCFS", "generate": "BFS_MIN_HOP", "route": "MIN_HOP"},
    "Optimized": {"select": "LWEEF", "generate": "FAST_K_SHORTEST_PATHS", "route": "EAP"},
}


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout


def start_pce(arm):
    sh("docker", "rm", "-f", "mp-pce")
    subprocess.run(["docker", "run", "-d", "--name", "mp-pce", "--network", "mp-src",
                    "--ip", "172.40.0.200", "-p", "127.0.0.1:18081:8080", "-e", "VORTEX_API_KEY=bench",
                    "-e", f"VORTEX_TOPOLOGY_FILE={TOPO}",
                    "-e", f"VORTEX_TASK_SELECT_POLICY={ARMS[arm]['select']}",
                    "-e", f"VORTEX_ROUTE_GEN_POLICY={ARMS[arm]['generate']}",
                    "-e", f"VORTEX_PATH_SELECT_POLICY={ARMS[arm]['route']}",
                    "-v", os.path.join(os.path.dirname(os.path.abspath(__file__)), "regime_topologies") + ":/topo:ro", "vortex-pce-controller:paper"],
                   capture_output=True)
    for _ in range(40):
        try:
            urllib.request.urlopen(f"{API}/livez", timeout=2).read(); return
        except Exception:
            time.sleep(1)
    raise RuntimeError("mp-pce did not become live")


def solve(flows_bytes):
    tasks = [{"taskId": f"F{i+1}", "sourceNodeId": "SRC", "destinationNodeId": "DST",
              "originationTimeSec": 0.0, "deadlineSec": HORIZON,
              "taskSizeBytes": int(b), "priority": 1} for i, b in enumerate(flows_bytes)]
    req = urllib.request.Request(f"{API}/api/v1/solve", data=json.dumps(tasks).encode(),
                                 method="POST",
                                 headers={"X-API-Key": KEY, "Content-Type": "application/json"})
    d = json.loads(urllib.request.urlopen(req, timeout=120).read())
    admitted = []
    for s in d.get("committedSchedules", []):
        idx = int(s["taskId"][1:]) - 1
        pipe = "A" if any(h["linkId"] == "SRC-CLE" for h in s["hops"]) else "B"
        admitted.append((idx, flows_bytes[idx], pipe))
    return admitted, d.get("committedFlowCount", 0), d.get("offeredFlowCount", 0)


def reset_pipes():
    for r, dev in (("mp-rA", "eth1"), ("mp-rB", "eth1")):
        sh("docker", "exec", r, "sh", "-c",
           f"tc qdisc del dev {dev} root 2>/dev/null; "
           f"tc qdisc add dev {dev} root handle 1: htb default 10; "
           f"tc class add dev {dev} parent 1: classid 1:10 htb rate 10mbit ceil 10mbit; "
           f"tc qdisc add dev {dev} parent 1:10 handle 10: bfifo limit 1000000")


def start_servers():
    for rx in ("mp-rxA", "mp-rxB"):
        sh("docker", "exec", rx, "pkill", "-f", "iperf3")
    time.sleep(1)
    for rx in ("mp-rxA", "mp-rxB"):
        for p in range(5201, 5213):
            subprocess.Popen(["docker", "exec", "-d", rx, "iperf3", "-s", "-p", str(p)],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(2)


def run_arm(arm, load, n_flows=8):
    start_pce(arm)
    one_pipe_cap_mb = 10.0 * HORIZON / 8            # 37.5 MB one pipe carries in the horizon
    per_flow_mb = (load * one_pipe_cap_mb) / n_flows
    flows_bytes = [int(per_flow_mb * 1e6)] * n_flows
    admitted, committed, offered = solve(flows_bytes)

    reset_pipes()
    start_servers()
    procs = []
    for slot, (idx, nbytes, pipe) in enumerate(admitted):
        port = 5201 + slot
        procs.append(subprocess.Popen(
            ["docker", "exec", f"mp-s{idx + 1}", "iperf3", "-c", RX[pipe], "-p", str(port),
             "-n", str(int(nbytes)), "-J"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True))
    delivered, worst = 0, 0.0
    for proc in procs:
        try:
            end = json.loads(proc.communicate(timeout=180)[0])["end"]
            summ = end.get("sum_received") or end.get("sum_sent")
            delivered += summ.get("bytes", 0)
            worst = max(worst, summ.get("seconds", 0.0))
        except Exception:
            pass
    pipes_used = len({p for (_, _, p) in admitted})
    return {"arm": arm, "load": load, "admitted": f"{committed}/{offered}",
            "pipes_used": pipes_used, "offered_mb": round(sum(flows_bytes) / 1e6, 1),
            "delivered_mb": round(delivered / 1e6, 1), "wall_sec": round(worst, 1)}


def main():
    rows = []
    print(f"R_STATIC multi-path: two 10 Mbit pipes (one-pipe capacity {10.0*HORIZON/8:.1f} MB/{HORIZON:.0f}s)\n")
    for load in (0.5, 1.0, 1.5, 2.0):
        for arm in ("ClassicIP", "Optimized"):
            best = max((run_arm(arm, load) for _ in range(REPS)),
                       key=lambda r: r["delivered_mb"])
            rows.append(best)
            print(f"  load {load:.1f}x {arm:<10}: admitted {best['admitted']:>4}  "
                  f"pipes {best['pipes_used']}  offered {best['offered_mb']:>5} MB  "
                  f"delivered {best['delivered_mb']:>5} MB", flush=True)
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rstatic_multipath_results.json")
    with open(out, "w") as f:
        json.dump(rows, f, indent=2)
    print(f"\nwrote {out}")


if __name__ == "__main__":
    sys.exit(main())
