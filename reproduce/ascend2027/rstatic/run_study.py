"""Paced versus unpaced transmission across a real bottleneck, on one host.

The design constraint is that the result must not be arithmetic. Both arms move the same
number of bytes, for the same flows, under the same deadline, across the same 100 Mbit
bottleneck with the same shallow queue. The only thing that differs is whether each sender
transmits at the rate VortexPCE computed for it, or as fast as it can.

What stays genuinely uncertain, and is therefore worth measuring:
  - whether tc can realise the engine's rates finely enough to actually avoid loss
  - what pacing costs in completion time
  - whether the engine's own completion estimate matches a real network

Ground truth is the router's qdisc counters, not any claim made by a sender.
"""
import json
import subprocess
import sys
import time
import urllib.request

API = "http://127.0.0.1:18080"
KEY = "bench"
N_FLOWS = 4
FLOW_MB = 20
DEADLINE_SEC = 24.0
BOTTLENECK_MBIT = 100
REPS = 3
SENDERS = [f"bench-s{i}" for i in range(1, N_FLOWS + 1)]
PORTS = [5201 + i for i in range(N_FLOWS)]


def sh(*args, check=True):
    return subprocess.run(args, capture_output=True, text=True, check=check).stdout


def docker(container, *cmd, check=True):
    return sh("docker", "exec", container, *cmd, check=check)


def reset_controller():
    """A fresh engine per arm: committed reservations persist and would skew the next solve."""
    sh("docker", "restart", "bench-pce")
    for _ in range(60):
        try:
            urllib.request.urlopen(f"{API}/livez", timeout=2).read()
            return
        except Exception:
            time.sleep(1)
    raise RuntimeError("controller did not become live")


def solve():
    flows = [{"taskId": f"F{i}", "sourceNodeId": "SRC", "destinationNodeId": "DST",
              "originationTimeSec": 0.0, "deadlineSec": DEADLINE_SEC,
              "taskSizeBytes": FLOW_MB * 1_000_000} for i in range(1, N_FLOWS + 1)]
    req = urllib.request.Request(f"{API}/api/v1/solve", data=json.dumps(flows).encode(),
                                 method="POST",
                                 headers={"X-API-Key": KEY, "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=120).read())


def reset_bottleneck():
    """Re-create the qdisc so counters start at zero for each arm."""
    docker("bench-router", "tc", "qdisc", "del", "dev", "eth1", "root", check=False)
    docker("bench-router", "tc", "qdisc", "add", "dev", "eth1", "root", "handle", "1:",
           "htb", "default", "10")
    docker("bench-router", "tc", "class", "add", "dev", "eth1", "parent", "1:", "classid",
           "1:10", "htb", "rate", f"{BOTTLENECK_MBIT}mbit", "ceil", f"{BOTTLENECK_MBIT}mbit")
    docker("bench-router", "tc", "qdisc", "add", "dev", "eth1", "parent", "1:10", "handle",
           "10:", "bfifo", "limit", "100000")


def qdisc_stats():
    out = docker("bench-router", "tc", "-s", "qdisc", "show", "dev", "eth1")
    block = out.split("qdisc bfifo")[1]
    sent_bytes = int(block.split("Sent ")[1].split()[0])
    sent_pkts = int(block.split("Sent ")[1].split()[2])
    dropped = int(block.split("dropped ")[1].split(",")[0])
    backlog = int(block.split("backlog ")[1].split("b")[0])
    return {"sent_bytes": sent_bytes, "sent_pkts": sent_pkts, "dropped": dropped,
            "backlog": backlog}


def start_servers():
    docker("bench-rx", "pkill", "-f", "iperf3", check=False)
    time.sleep(1)
    for port in PORTS:
        subprocess.Popen(["docker", "exec", "-d", "bench-rx", "iperf3", "-s", "-p", str(port)],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(2)


def run_arm(name, rates_bps, protocol):
    """One arm. rates_bps None means unpaced (send as fast as the sender can)."""
    reset_bottleneck()
    start_servers()

    # Latency probe runs for the whole arm: queue delay is what a user actually feels. It runs
    # from a container that sends no data, so it measures the delay a bystander flow inherits
    # from the queue, end to end across the bottleneck rather than from the router outward.
    # 20 ms spacing, because the unpaced burst lasts about a second and a 200 ms probe caught
    # only a handful of samples -- few enough that the first run reported a lower RTT under
    # congestion than without it, which was a sampling artefact rather than a result.
    ping = subprocess.Popen(
        ["docker", "exec", "bench-probe", "ping", "-i", "0.02", "-c", "3000", "172.31.0.10"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)

    # Sample the queue while traffic is in flight; the end-of-run backlog is always ~0.
    sampler = subprocess.Popen(
        ["docker", "exec", "bench-router", "sh", "-c",
         "for i in $(seq 1 400); do tc -s qdisc show dev eth1 | grep -o 'backlog [0-9]*b' "
         "| grep -o '[0-9]*'; sleep 0.1; done"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)

    started = time.time()
    procs = []
    for index, (sender, port) in enumerate(zip(SENDERS, PORTS)):
        cmd = ["docker", "exec", sender, "iperf3", "-c", "172.31.0.10", "-p", str(port),
               "-n", f"{FLOW_MB}M", "-J"]
        if protocol == "udp":
            cmd += ["-u", "-b", "0" if rates_bps is None else str(int(rates_bps[index]))]
        elif rates_bps is not None:
            cmd += ["-b", str(int(rates_bps[index]))]
        procs.append(subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                      text=True))

    flows = []
    for proc in procs:
        out, _ = proc.communicate(timeout=300)
        try:
            report = json.loads(out)["end"]
        except Exception:
            flows.append(None)
            continue
        summary = report.get("sum") or report.get("sum_received") or report.get("sum_sent")
        if summary is None:
            # A client that failed to connect still emits JSON, just without a summary. Dropping
            # the flow keeps the arm's router-side counters usable instead of losing the whole
            # run to one transient connection failure.
            flows.append(None)
            continue
        flows.append({"seconds": summary.get("seconds"),
                      "bytes": summary.get("bytes"),
                      "bits_per_second": summary.get("bits_per_second"),
                      "lost_percent": summary.get("lost_percent"),
                      "lost_packets": summary.get("lost_packets"),
                      "packets": summary.get("packets")})
    wall = time.time() - started

    stats = qdisc_stats()
    sampler.terminate()
    backlogs = []
    try:
        out, _ = sampler.communicate(timeout=10)
        backlogs = [int(x) for x in out.split() if x.isdigit()]
    except Exception:
        pass
    ping.terminate()
    rtts = []
    try:
        out, _ = ping.communicate(timeout=10)
        for line in out.splitlines():
            if "time=" in line:
                rtts.append(float(line.split("time=")[1].split()[0]))
    except Exception:
        pass

    return {"arm": name, "protocol": protocol, "wall_sec": round(wall, 2),
            "router_dropped_pkts": stats["dropped"], "router_sent_pkts": stats["sent_pkts"],
            "router_sent_bytes": stats["sent_bytes"],
            "max_queue_bytes": max(backlogs) if backlogs else None,
            "rtt_samples": len(rtts),
            "rtt_min_ms": round(min(rtts), 3) if rtts else None,
            "rtt_avg_ms": round(sum(rtts) / len(rtts), 3) if rtts else None,
            "rtt_max_ms": round(max(rtts), 3) if rtts else None,
            "flows": flows}


def main():
    print("=" * 78)
    print("PACED (VortexPCE e*) vs UNPACED, across a real 100 Mbit bottleneck")
    print("=" * 78)

    reset_controller()
    result = solve()
    schedules = result["committedSchedules"]
    if result["committedFlowCount"] != N_FLOWS:
        print(f"engine admitted {result['committedFlowCount']}/{N_FLOWS}; "
              f"both arms need the same bytes. Adjust the workload.")
        return 1
    rates = [s["committedRateBps"] for s in schedules]
    print(f"\nEngine: admitted {result['committedFlowCount']}/{result['offeredFlowCount']}, "
          f"solve {result['computationTimeMs']} ms")
    for s in schedules:
        print(f"  {s['taskId']}: e* = {s['committedRateBps']/1e6:.3f} Mbit/s, "
              f"engine predicts completion {s['completionSec']:.1f}s (deadline {DEADLINE_SEC}s)")
    print(f"  sum e* = {sum(rates)/1e6:.2f} Mbit/s against a {BOTTLENECK_MBIT} Mbit bottleneck")
    print(f"  identical payload per arm: {N_FLOWS} x {FLOW_MB} MB = {N_FLOWS*FLOW_MB} MB\n")

    arms = []
    for rep in range(1, REPS + 1):
        for protocol in ("udp", "tcp"):
            for name, rate in (("unpaced", None), ("paced", rates)):
                print(f"--- rep {rep}  {protocol.upper()} / {name} ---", flush=True)
                arm = run_arm(name, rate, protocol)
                arm["rep"] = rep
                arms.append(arm)
                print(f"    wall {arm['wall_sec']}s  router-dropped {arm['router_dropped_pkts']} "
                      f"pkts  max queue {arm['max_queue_bytes']}B  "
                      f"RTT avg {arm['rtt_avg_ms']}ms max {arm['rtt_max_ms']}ms "
                      f"({arm['rtt_samples']} samples)", flush=True)
                time.sleep(3)

    payload = {"config": {"flows": N_FLOWS, "flow_mb": FLOW_MB, "deadline_sec": DEADLINE_SEC,
                          "bottleneck_mbit": BOTTLENECK_MBIT, "queue_bytes": 100000},
               "engine": {"admitted": result["committedFlowCount"],
                          "offered": result["offeredFlowCount"],
                          "solve_ms": result["computationTimeMs"],
                          "rates_bps": rates,
                          "predicted_completion_sec": [s["completionSec"] for s in schedules]},
               "arms": arms}
    with open("study_results.json", "w") as handle:
        json.dump(payload, handle, indent=2)
    print("\nwrote study_results.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
