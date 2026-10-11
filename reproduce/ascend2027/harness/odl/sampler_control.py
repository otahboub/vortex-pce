"""Lifecycle wrapper for the per-cell Docker resource sampler."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional


def start(container: str, output: Path) -> subprocess.Popen:
    interval = float(os.environ.get("MATCHED_RESOURCE_INTERVAL_SECONDS", "1"))
    if os.environ.get("MATCHED_RUN_MODE") == "scored" and interval != 1.0:
        raise RuntimeError("scored resource sampling interval must be exactly one second")
    ready = output / "resource-sampler-ready.json"
    command = [
        sys.executable,
        str(Path(__file__).with_name("resource_sampler.py")),
        "--container", container,
        "--output", str(output / "resource-sample.json"),
        "--ready-file", str(ready),
        "--interval-seconds", str(interval),
    ]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if ready.is_file():
            return process
        if process.poll() is not None:
            stdout, stderr = process.communicate()
            raise RuntimeError(f"resource sampler failed before readiness: {stdout}{stderr}")
        time.sleep(0.05)
    process.terminate()
    stdout, stderr = process.communicate(timeout=10)
    raise RuntimeError(f"resource sampler readiness timeout: {stdout}{stderr}")


def stop(process: subprocess.Popen, output: Path) -> dict:
    process.terminate()
    try:
        stdout, stderr = process.communicate(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        stdout, stderr = process.communicate(timeout=5)
        raise RuntimeError(f"resource sampler failed to terminate: {stdout}{stderr}")
    (output / "resource-sampler.log").write_text(stdout + stderr, encoding="utf-8")
    if process.returncode != 0:
        raise RuntimeError(f"resource sampler exited {process.returncode}: {stdout}{stderr}")
    path = output / "resource-sample.json"
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        summary = value["summary"]
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"resource sampler produced invalid evidence: {error}") from error
    required = {"samples", "cpu_seconds", "peak_memory_usage_bytes", "peak_memory_working_set_bytes"}
    if not required <= set(summary) or summary["samples"] < 2:
        raise RuntimeError("resource sampler summary is incomplete")
    return {"sample": path.name, **summary}


def abort(process: Optional[subprocess.Popen]) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate(timeout=5)
