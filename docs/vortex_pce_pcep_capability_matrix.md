# VortexPCE PCEP Capability Matrix

The PCEP messages, RFCs, and transport features VortexPCE's Java southbound implements, and the
ones it does not. This is the "supported matrix" a general-purpose-PCE claim would need; VortexPCE
is a specialized flow-scheduling PCE, so several conventional PCE facilities are deliberately
absent and listed as such rather than left implied.

Each supported row names the code that provides it and a test that proves it. `PcepCapabilityMatrixTest`
is the executable half of this document: it fails the build if a message this matrix claims as
supported loses its encoder or decoder, so the table cannot silently drift from the code.

## Messages this PCE sends

| Message | Type | RFC | Status | Code / test |
|---|---:|---|---|---|
| Open | 1 | 5440 | ✅ supported | `PcepEncoder.open` · `PcepSessionServerTest` |
| Keepalive | 2 | 5440 | ✅ supported | `PcepEncoder.keepalive` |
| PCErr | 6 | 5440 §7.15 | ✅ supported | `PcepEncoder.pcerr` · `PcepConformanceResponsesTest` |
| PCUpd | 11 | 8231 | ⚠️ encoding only | `PcepEncoder.pcUpdate` · `PcepUpdateEncoderTest` — no operator-triggered update path |
| PCInitiate | 12 | 8281 | ✅ supported | `PcepEncoder.pcInitiate*` · `ScheduleInitiateTest`, `SegmentRoutedInitiateTest` |
| PCReq | 3 | 5440 | ❌ not implemented | VortexPCE initiates; it does not answer computation requests |
| PCRep | 4 | 5440 | ❌ not implemented | — |

## Messages this PCE receives

| Message | Type | RFC | Status | Code / test |
|---|---:|---|---|---|
| Open | 1 | 5440 | ✅ handshake | `PcepSessionServer` |
| Keepalive | 2 | 5440 | ✅ liveness | `PcepSessionServer` |
| PCErr | 6 | 5440 | ✅ decoded, correlated to install state | `PcepErrorDecoder` · `PcepErrorHandlingTest` |
| PCRpt | 10 | 8231 | ✅ decoded into the intent ledger | `PcepReportDecoder` · `PcepReportDecoderTest` |
| State synchronisation | — | 8231 §5.6 | ✅ end-of-sync detected, per-PCC reconciliation | `PcepReportDecoder` |

## Error responses (RFC 5440 §7.15) the PCE emits

Established by an independent probe and locked in by `PcepConformanceResponsesTest`.

| Condition | Error-Type / value | Status |
|---|---|---|
| Non-Open first message | 1 / 1 | ✅ |
| Unsupported PCEP version | 1 / 8 | ✅ |
| Malformed / impossible-length / stalled frame | 1 / 1 | ✅ |
| Unrecognised object, P flag set | 3 / 1 | ✅ |
| Second Open on an established session | 9 / 1 (session preserved) | ✅ |
| Peer's advertised DeadTimer | honoured (RFC 5440 §7.3) | ✅ |

## Transport and session

| Feature | RFC | Status |
|---|---|---|
| Stateful PCE capability | 8231 | ✅ advertised in Open |
| PCE-initiated LSPs | 8281 | ✅ |
| SR-TE (SR-MPLS ERO/SID) | 8664 | ✅ `pcInitiateSegmentRouted` |
| PCEPS mutual TLS | 8253 | ⚠️ implemented, opt-in (off by default) — `PcepTransportSecurity`, `PcepsMutualTlsTest` |
| Peer address allowlist | — | ✅ (not authentication; a network-layer control) |
| Session cap (`VORTEX_PCEP_MAX_SESSIONS`) | — | ✅ operator-tunable |

## Controller facilities a general-purpose PCE would have, and this one does not

| Facility | Status | Note |
|---|---|---|
| PCReq/PCRep computation exchange | ❌ | VortexPCE initiates; it is not a request-answering PCE |
| BGP-LS / IGP TED ingestion | ❌ | topology is supplied, not learned |
| Hierarchical PCE (H-PCE) | ❌ (adapters only) | ONOS/ODL classes are adapters, not a topology/lifecycle integration |
| Delegated-LSP update lifecycle | ❌ | PCUpd is encoded but not driven end to end |
| High availability / shared transactional state | 🧪 experimental | supported deployment is single-active on the local WAL. A `PostgresReservationStore` + `PostgresLeadership` backend exists (ADR-0001) with shared transactional state, a leader lease, and fencing — selectable with `VORTEX_RESERVATION_BACKEND=postgres`, with a lease heartbeat, demotion and a destructive fault harness in `tests/ha/`, but no failover gate in CI, so it is **pre-GA, not HA-qualified** |

## Scope statement

VortexPCE is a **deadline-aware flow-scheduling PCE**: it computes a route *and a rate and a
schedule* against space-time reservation ledgers, and installs the result over stateful PCEP. It is
not a general-purpose request-answering PCE and does not claim the PCReq/PCRep or topology-learning
surface that phrase implies. The rows above marked ❌ are out of scope by design, not gaps pending
completion — with the exception of the PCUpd update lifecycle and HA, which are genuine incomplete
work and are tracked as such.
