# Multi-host convergence harness

This harness measures an externally triggered policy transition while continuous probes run from a
second host and a third host supplies an independent forwarding-plane oracle. The three roles must
resolve to distinct SSH targets. Results include trigger acknowledgement time, a poll-bounded oracle
transition interval, packet loss, raw probe output, clock-offset estimates and timestamped events.
The run fails when total probe loss exceeds the configured `max_packet_loss_percent`.

## Run

Prepare routing/MPLS and the oracle outside this harness, copy `config.example.json`, then run:

```bash
python3 tests/convergence/run_multihost_convergence.py \
  --config /secure/path/convergence.json --output results/convergence-$(date -u +%Y%m%dT%H%M%SZ)
```

Use `--plan` first. Plan mode performs SSH, role-separation, clock and configured preflight checks
without issuing policy changes or probes. Commands are ordinary remote shell commands so the same
measurement can drive VMs on AWS, DigitalOcean, Vultr, or a lab without embedding provider secrets.
Never put credentials in the config; use SSH agents and remote secret stores/files.

The oracle must measure the forwarding plane independently—for example, a continuously refreshed
AF_PACKET/eBPF MPLS frame counter on the tail. Controller logs, API responses and PCEP state are not
valid oracle inputs. Capture setup is intentionally testbed-specific and is therefore a precondition.

## Interpretation and limitations

- `oracle_transition_lower_ms` and `oracle_transition_upper_ms` are bounds, not an exact instant.
  They include SSH execution/poll latency; retain `events.jsonl` for audit.
- Packet loss is the loss reported by continuous ICMP probes for the complete run. It is not yet
  attributed to an individual transition; raw timestamped `probe.log` is retained for later analysis.
- Clock checks estimate offset through SSH midpoint sampling. This is a guardrail, not PTP-grade
  synchronization. Report its uncertainty with every result.
- A three-VM run demonstrates a multi-host software dataplane only. It does not establish hardware
  offload, WAN-scale behavior, ECMP correctness, HA, multi-PCC scale, or provider equivalence.
- Run repeated randomized trials and publish distributions/confidence intervals. A single pass is a
  shakedown and must not be presented as a convergence SLA.
