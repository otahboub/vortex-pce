# 🛡️ VortexPCE Security & Vulnerability Policy

The **VortexPCE** project takes the security, integrity, and resilience of network control plane software seriously.

This document outlines our security model, threat landscape, supported versions, and vulnerability disclosure procedures.

---

## 🔒 Supported Versions

Only the latest `main` branch currently receives security updates. The project has
not published a stable release yet.

| Version | Supported |
| --- | --- |
| `main` | ✅ Yes |
| Untagged historical commits | ❌ No |

---

## 🎯 Threat Model & Security Architecture

VortexPCE operates as a centralized Path Computation Element (PCE) calculating network paths and rate pacing schedules.

### Trust Boundaries & Protections:

1. **Northbound HTTP REST API (`/api/v1/solve`):**
   * **Authentication:** Startup fails unless `VORTEX_API_KEY` or `VORTEX_API_PRINCIPALS` is configured; solve requests require a matching Bearer token or `X-API-Key`.
   * **Payload Bounding:** Enforces strict 10MB maximum request body size limits.
   * **Validation:** Rejects malformed JSON, negative values, and non-structural payloads using strict DTO deserialization.

2. **Southbound PCEP Protocol (`TCP 4189`):**
   * **Session Identity:** Sessions are keyed on the peer's RFC 8232 SPEAKER-ENTITY-ID when it
     advertises one, degrading to the peer address otherwise. A reconnecting PCC supersedes its own
     session rather than appearing as a new peer. Keying by `IP:Port`, as this document previously
     described, could not survive a reconnect.
   * **Report Attribution:** A PCRpt may only answer for the PCC its operation was sent to. LSP
     names are derived from the task identifier and are therefore predictable, so matching on name
     alone would let any peer that can reach the listener confirm or remove another router's LSP.
     This is attribution by transport-bound session identity. With PCEPS configured, that session
     is mutually authenticated; on plain TCP, attribution alone is not authentication.
   * **Session Handling:** Both implementations complete an OPEN/KEEPALIVE exchange before a
     session is established, bound the handshake, and cap concurrent sessions. Neither implements
     the full RFC 5440 state machine, and this document previously claimed otherwise.
   * **Binary Bounding:** Validates message lengths and object boundaries before processing.
   * **Deployment Boundary:** PCEPS mutual TLS is implemented and should be configured whenever
     the listener is reachable beyond a trusted loopback demonstration. The base Compose profile
     remains plaintext and loopback-only; `docker-compose.secure.yml` enables PCEPS and HTTPS.

3. **Linux Kernel eBPF Data Plane (`ebpf/`):**
   * **Kernel Verification:** The eBPF programs (`tc_edt_pacer.c`, `xdp_rate_shaper.c`) are *intended* to be loaded only through the kernel eBPF verifier, which enforces memory safety at load time. This is a deployment requirement, not retained evidence: CI does not compile, load, or attach these programs, and the Python tests mock the `bpftool` boundary, asserting command construction rather than kernel acceptance. Treat the eBPF path as experimental and verify it yourself on your target kernel.
   * **Least Privilege:** BPF map access is restricted to root/`CAP_NET_ADMIN` processes.

4. **Container Security:**
   * **Non-Root Execution:** Runs under dedicated unprivileged user `vortex` (UID `10001`).
   * **Capability Dropping:** The default controller container drops all Linux capabilities. Experimental eBPF tooling is not enabled in that container.

---

## 🔎 Current Scan Triage

This section records what the container scan's high-severity findings are, so any count is a
statement about a known cause rather than an open backlog.

**Triage date:** 2026-08-21 · **Scanner:** Version: 0.74.0 · **Image:** built from `Dockerfile` on
this branch · **Filter:** `--severity HIGH,CRITICAL --scanners vuln`

**Current result: zero HIGH or CRITICAL findings.**

The previous entry on this page recorded eight HIGH findings — Go standard-library CVEs inside
`/usr/bin/pebble`, a service supervisor that ships in the `ubuntu:26.04` base — and described
their resolution as waiting on a Canonical rebuild. That is no longer how they were resolved. The
`Dockerfile` removes the binary outright:

```dockerfile
RUN rm -f /usr/bin/pebble
```

pebble is not built, installed, invoked, or depended on by this project, so deleting it is
strictly better than waiting for someone else to rebuild it. It no longer appears in the image and
neither do its CVEs.

**No Java or Python dependency reports a HIGH or CRITICAL finding.** The controller's own
dependency tree is clean at those severities.

**Enforcement.** CI fails on **fixable HIGH and CRITICAL** findings, not CRITICAL alone as this
page previously stated (`.github/workflows/ci.yml`, *Enforce Fixable Critical and High
Vulnerabilities*). `ignore-unfixed` stays on: a CVE with no upstream fix cannot be actioned here,
and failing on it would only train reviewers to ignore a permanently red pipeline. Accepted
exceptions live in `.trivyignore` with a reason and a review date, and
`tools/check_vulnerability_exceptions.py` fails the build when one goes stale.

**Scope of this scan.** A local scan of a locally built image, recorded so the claim is
attributable. The authoritative run is the enforcing CI step above, which scans the image built
from the merge commit.

**Re-triage by:** 2026-11-18, or sooner if the severity or count changes.

## 🚨 Reporting a Vulnerability

If you discover a security vulnerability in VortexPCE, please **do NOT report it via public GitHub issues**.

Instead, please submit a private report via:
* **GitHub private vulnerability reporting:** the "Report a vulnerability" button on the repository's Security tab.

### What to Include in Your Report:
1. Type of vulnerability (e.g., DoS, authentication bypass, memory issue).
2. Step-by-step proof-of-concept (PoC) or reproduction script.
3. Affected components (Java REST controller, Python PCEP, eBPF module).

### Response Timeline:
VortexPCE is maintained on a best-effort basis. Reports are acknowledged as soon as practical,
and fixes are prioritised by severity (CVSS v3).
