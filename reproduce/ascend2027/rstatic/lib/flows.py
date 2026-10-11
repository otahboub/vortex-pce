"""Offering deadline-bearing flows and finding out whether they arrived in time.

The metric this exists to produce is not throughput. A flow counts only if every byte arrived at
the receiver before its deadline; a transfer that is 90% delivered is not 90% useful when the
thing being delivered has a deadline.

Completion is timed at the receiver. A sender's view of "sent" is not arrival, and on a real
network there is no queue in the middle to ask.
"""
import json
import time
from dataclasses import dataclass
from typing import Dict, List, Optional

from .executor import Executor


@dataclass(frozen=True)
class Flow:
    """One deadline-bearing transfer."""
    flow_id: str
    source_role: str
    destination_role: str
    size_bytes: int
    deadline_sec: float
    port: int

    def required_bps(self, hops: int = 1) -> float:
        """The naive rate that would just meet the deadline, ignoring queueing."""
        return (self.size_bytes * 8.0) / self.deadline_sec * max(1, hops)


@dataclass
class FlowResult:
    flow_id: str
    admitted: bool
    committed_rate_bps: Optional[float]
    bytes_received: int
    completion_sec: Optional[float]
    deadline_sec: float
    lost_packets: int = 0

    @property
    def complete(self) -> bool:
        return self.bytes_received > 0 and self.lost_packets == 0

    @property
    def on_time(self) -> bool:
        """Complete, and finished before its deadline.

        An unadmitted flow is not on time. Refusing work is a legitimate strategy and it must not
        be scored as success -- an arm that admits nothing would otherwise attain a perfect rate.
        """
        return (self.admitted and self.complete and self.completion_sec is not None
                and self.completion_sec <= self.deadline_sec)


def start_receiver(host: Executor, port: int) -> None:
    host.run(["sh", "-c", f"nohup iperf3 -s -p {port} -1 >/dev/null 2>&1 &"])


def stop_receivers(host: Executor) -> None:
    host.kill_matching("iperf3")


def send(host: Executor, destination: str, flow: Flow, rate_bps: Optional[float]):
    """Starts one transfer and returns the handle.

    A committed rate paces the sender; `None` sends as fast as the transport will go, which is what
    an unpaced arm does. Pacing is applied at the sender's own egress, which is the one place a
    host can shape traffic on a network it does not own.
    """
    argv = ["iperf3", "-c", destination, "-p", str(flow.port),
            "-n", str(flow.size_bytes), "-J", "--connect-timeout", "5000"]
    if rate_bps is not None:
        argv += ["-b", str(int(rate_bps))]
    return host.popen(argv)


def collect(handle, flow: Flow, admitted: bool, rate_bps: Optional[float],
            timeout: float) -> FlowResult:
    """Reads the transfer's own report; a transfer with no report is a failure, not a zero."""
    try:
        stdout, _ = handle.communicate(timeout=timeout)
    except Exception:
        handle.kill()
        stdout = ""
    try:
        report = json.loads(stdout)
        summary = report["end"]["sum_received"] if "sum_received" in report.get("end", {}) \
            else report["end"]["sum"]
        return FlowResult(
            flow_id=flow.flow_id,
            admitted=admitted,
            committed_rate_bps=rate_bps,
            bytes_received=int(summary.get("bytes", 0)),
            completion_sec=float(summary.get("seconds", 0.0)),
            deadline_sec=flow.deadline_sec,
            lost_packets=int(summary.get("lost_packets", 0) or 0),
        )
    except Exception:
        # No usable report: the transfer did not demonstrably arrive, so it is not counted as
        # having done so. Recording a zero-byte success here is how a harness invents attainment.
        return FlowResult(flow.flow_id, admitted, rate_bps, 0, None, flow.deadline_sec)


def attainment(results: List[FlowResult]) -> Dict[str, float]:
    """The headline metric, with the parts that explain it."""
    offered = len(results)
    admitted = sum(1 for r in results if r.admitted)
    on_time = sum(1 for r in results if r.on_time)
    late = sum(1 for r in results
               if r.admitted and r.complete and r.completion_sec is not None
               and r.completion_sec > r.deadline_sec)
    completions = sorted(r.completion_sec for r in results if r.completion_sec is not None)
    return {
        "offered": offered,
        "admitted": admitted,
        "refused": offered - admitted,
        "on_time": on_time,
        # Late and incomplete are separated: one is a scheduling failure, the other a delivery
        # failure, and an arm can be bad at exactly one of them.
        "late": late,
        "incomplete": sum(1 for r in results if r.admitted and not r.complete),
        "attainment": round(on_time / offered, 4) if offered else 0.0,
        "median_completion_sec": round(completions[len(completions) // 2], 3) if completions else None,
        "p95_completion_sec": round(completions[int(0.95 * (len(completions) - 1))], 3)
        if completions else None,
    }


def measure_path_capacity_bps(sender: Executor, destination: str, port: int,
                              seconds: int = 5) -> Optional[float]:
    """Discovers what the path actually carries, rather than trusting a declared figure.

    Scarcity has to be defined against real capacity. On a bridge the number is whatever the qdisc
    was configured with; between two cloud regions nobody knows it in advance, and a study that
    assumed one would be measuring a guess.
    """
    handle = sender.popen(["iperf3", "-c", destination, "-p", str(port), "-t", str(seconds), "-J",
                           "--connect-timeout", "5000"])
    try:
        stdout, _ = handle.communicate(timeout=seconds + 20)
        report = json.loads(stdout)
        return float(report["end"]["sum_received"]["bits_per_second"])
    except Exception:
        return None
