#!/usr/bin/env python3
"""Fail CI when a completed CodeQL analysis produced findings.

Private repositories may be unable to publish SARIF to GitHub code scanning. The analysis output
still exists locally, so its findings must be enforced before the best-effort upload step.
"""

from __future__ import annotations

import json
import pathlib
import sys
from collections.abc import Iterable


def sarif_files(paths: Iterable[str]) -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for raw_path in paths:
        path = pathlib.Path(raw_path)
        if path.is_dir():
            files.extend(sorted(path.rglob("*.sarif")))
        elif path.is_file():
            files.append(path)
    return files


def findings(path: pathlib.Path) -> list[str]:
    document = json.loads(path.read_text(encoding="utf-8"))
    found: list[str] = []
    for run in document.get("runs", []):
        for result in run.get("results", []):
            rule = result.get("ruleId", "unknown-rule")
            message = result.get("message", {}).get("text", "no message")
            locations = result.get("locations", [])
            physical = locations[0].get("physicalLocation", {}) if locations else {}
            artifact = physical.get("artifactLocation", {}).get("uri", "unknown-file")
            line = physical.get("region", {}).get("startLine", 0)
            found.append(f"{path}: {rule} at {artifact}:{line}: {message}")
    return found


def main(argv: list[str] | None = None) -> int:
    arguments = sys.argv[1:] if argv is None else argv
    files = sarif_files(arguments)
    if not files:
        print("No CodeQL SARIF files found; refusing to report a clean analysis.", file=sys.stderr)
        return 2

    all_findings: list[str] = []
    try:
        for path in files:
            all_findings.extend(findings(path))
    except (OSError, json.JSONDecodeError, TypeError) as error:
        print(f"Could not read CodeQL SARIF: {error}", file=sys.stderr)
        return 2

    if all_findings:
        print(f"CodeQL produced {len(all_findings)} finding(s):", file=sys.stderr)
        for finding in all_findings:
            print(f"- {finding}", file=sys.stderr)
        return 1

    print(f"CodeQL produced no findings across {len(files)} SARIF file(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
