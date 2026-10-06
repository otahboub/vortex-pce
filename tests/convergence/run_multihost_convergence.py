#!/usr/bin/env python3
"""A8/C2 multi-host event-to-forwarding convergence measurement.

The fixture deliberately treats the PCE trigger, traffic source, and forwarding
oracle as separate SSH roles. It does not provision a network or infer forwarding
success from controller state.
"""

from __future__ import annotations

import argparse
import json
import re
import shlex
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


@dataclass(frozen=True)
class Host:
    name: str
    ssh: str


class Runner:
    def __init__(self, ssh_options: list[str], output: Path):
        self.ssh_options = ssh_options
        self.output = output
        self.events: list[dict[str, Any]] = []

    def event(self, kind: str, **fields: Any) -> None:
        item = {"timestamp": now(), "monotonic_ns": time.monotonic_ns(), "kind": kind, **fields}
        self.events.append(item)
        with (self.output / "events.jsonl").open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(item, sort_keys=True) + "\n")

    def run(self, host: Host, command: str, timeout: float = 30) -> subprocess.CompletedProcess[str]:
        argv = ["ssh", *self.ssh_options, host.ssh, "sh", "-lc", shlex.quote(command)]
        self.event("command_start", host=host.name, command=command)
        result = subprocess.run(argv, text=True, capture_output=True, timeout=timeout)
        self.event("command_end", host=host.name, command=command, returncode=result.returncode)
        return result


def require(result: subprocess.CompletedProcess[str], message: str) -> str:
    if result.returncode:
        raise RuntimeError(f"{message}: rc={result.returncode}: {result.stderr.strip()}")
    return result.stdout


def clock_offset_ms(runner: Runner, host: Host, samples: int = 5) -> tuple[float, float]:
    offsets = []
    errors = []
    for _ in range(samples):
        before = time.time_ns()
        output = require(runner.run(host, "date +%s%N", timeout=10), f"clock read failed on {host.name}")
        after = time.time_ns()
        remote = int(output.strip())
        offsets.append((remote - ((before + after) // 2)) / 1_000_000)
        errors.append((after - before) / 2_000_000)
    return statistics.median(offsets), min(errors)


def parse_ping(output: str) -> tuple[int, int, list[float]]:
    transmitted = received = 0
    summary = re.search(r"(\d+) packets transmitted, (\d+) received", output)
    if summary:
        transmitted, received = map(int, summary.groups())
    epochs = [float(value) for value in re.findall(r"^\[([0-9.]+)\].*icmp_seq=", output, re.MULTILINE)]
    return transmitted, received, epochs


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--plan", action="store_true", help="validate without changing remote state")
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    args.output.mkdir(parents=True, exist_ok=True)
    runner = Runner(config.get("ssh_options", ["-o", "BatchMode=yes"]), args.output)
    hosts = {name: Host(name, value) for name, value in config["hosts"].items()}
    required = {"trigger", "probe", "oracle"}
    if set(hosts) != required or len({host.ssh for host in hosts.values()}) != 3:
        raise ValueError("hosts must contain three distinct SSH targets: trigger, probe, oracle")

    max_offset = float(config.get("max_clock_offset_ms", 10))
    clock: dict[str, Any] = {}
    for host in hosts.values():
        require(runner.run(host, "command -v sh >/dev/null && command -v date >/dev/null"), "preflight failed")
        offset, uncertainty = clock_offset_ms(runner, host)
        clock[host.name] = {"offset_ms": offset, "measurement_uncertainty_ms": uncertainty}
        if abs(offset) + uncertainty > max_offset:
            raise RuntimeError(f"{host.name} clock uncertainty exceeds {max_offset} ms: {clock[host.name]}")

    for item in config.get("preflight", []):
        require(runner.run(hosts[item["host"]], item["command"]), f"preflight command failed: {item}")

    metadata = {
        "schema": "vortex-a8-convergence/v1",
        "started_at": now(),
        "config": config,
        "clock": clock,
        "plan": args.plan,
    }
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
    if args.plan:
        print("PLAN PASSED: three distinct hosts, SSH, clocks, and preflight commands validated")
        return 0

    probe = config["probe"]
    interval = float(probe.get("interval_sec", 0.05))
    duration = int(probe.get("duration_sec", 30))
    target = shlex.quote(probe["target"])
    ping_cmd = f"ping -D -n -O -i {interval} -w {duration} {target}"
    ping_argv = ["ssh", *runner.ssh_options, hosts["probe"].ssh, "sh", "-lc", shlex.quote(ping_cmd)]
    runner.event("probe_start", target=probe["target"], interval_sec=interval)
    ping = subprocess.Popen(ping_argv, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(float(config.get("warmup_sec", 2)))

    results = []
    for action in config["actions"]:
        if ping.poll() is not None:
            raise RuntimeError("probe ended before all actions were triggered")
        sent_ns = time.monotonic_ns()
        runner.event("trigger_sent", action=action["name"])
        trigger = runner.run(hosts["trigger"], action["command"], timeout=float(action.get("timeout_sec", 30)))
        ack_ns = time.monotonic_ns()
        require(trigger, f"trigger failed for {action['name']}")
        pattern = re.compile(action["oracle_regex"])
        deadline = time.monotonic() + float(action.get("oracle_timeout_sec", 20))
        poll_sec = float(action.get("poll_interval_sec", 0.05))
        previous_poll_ns = ack_ns
        matched_ns = None
        oracle_output = ""
        while time.monotonic() < deadline:
            oracle = runner.run(hosts["oracle"], action["oracle_command"], timeout=10)
            require(oracle, f"oracle failed for {action['name']}")
            oracle_output = oracle.stdout
            present = bool(pattern.search(oracle_output))
            if present == bool(action.get("oracle_present", True)):
                matched_ns = time.monotonic_ns()
                break
            previous_poll_ns = time.monotonic_ns()
            time.sleep(poll_sec)
        if matched_ns is None:
            raise RuntimeError(f"oracle did not reach expected state for {action['name']}: {oracle_output!r}")
        result = {
            "action": action["name"],
            "trigger_ack_ms": (ack_ns - sent_ns) / 1_000_000,
            "oracle_transition_lower_ms": max(0, (previous_poll_ns - sent_ns) / 1_000_000),
            "oracle_transition_upper_ms": (matched_ns - sent_ns) / 1_000_000,
            "oracle_poll_interval_ms": poll_sec * 1000,
        }
        results.append(result)
        runner.event("oracle_transition", **result)
        time.sleep(float(action.get("settle_sec", 1)))

    stdout, stderr = ping.communicate(timeout=duration + 10)
    (args.output / "probe.log").write_text(stdout + stderr, encoding="utf-8")
    transmitted, received, receive_epochs = parse_ping(stdout)
    if transmitted == 0:
        raise RuntimeError("ping produced no parseable packet summary")
    loss_percent = 100 * (transmitted - received) / transmitted
    max_loss_percent = float(config["max_packet_loss_percent"])
    summary = {
        "schema": "vortex-a8-convergence-result/v1",
        "completed_at": now(),
        "actions": results,
        "probes_transmitted": transmitted,
        "probes_received": received,
        "packet_loss_count": transmitted - received,
        "packet_loss_percent": loss_percent,
        "max_packet_loss_percent": max_loss_percent,
        "first_receive_epoch": min(receive_epochs) if receive_epochs else None,
        "last_receive_epoch": max(receive_epochs) if receive_epochs else None,
        "verdict": "PASS" if received and loss_percent <= max_loss_percent else "FAIL",
    }
    (args.output / "result.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0 if summary["verdict"] == "PASS" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KeyError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"A8 ERROR: {error}", file=sys.stderr)
        raise SystemExit(2)
