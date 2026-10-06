# VortexPCE architecture

VortexPCE is a control-plane service. It takes a **topology** (nodes, links and, for scheduled
networks, the contact windows in which each link exists) and a batch of **workloads** (flows with a
size, an origination time and a deadline). For each workload it decides whether to admit it, and
if it does, it commits a schedule: a route, a rate, and per-hop transmission slots that hold link
and node capacity until the flow completes. Optionally it installs admitted schedules on a router
over stateful PCEP.

The README is the operator reference (configuration, endpoints, metrics). This page explains how
the pieces fit together.

## Contact regimes

Every topology declares one regime, which decides how time is modelled:

| Regime | Links | Typical use |
|---|---|---|
| `R_STATIC` | always available | terrestrial backbones |
| `R_DET` | available only in scheduled contact windows; data is stored and forwarded between them | satellite and space relay networks with a known contact plan |
| `R_STOCH` | contact windows with a per-contact success probability | contact plans whose contacts may fail |

## A solve, end to end

1. **Northbound request** (`northbound/PCERestServer`, `PCERestController`). `POST /api/v1/solve`
   is authenticated (`config/ApiPrincipals`), checked against a per-principal quota
   (`SolveQuota`), and tagged with an `X-Request-Id` (`RequestContext`). Planning is
   single-flight: one solve holds the planner, bounded by `VORTEX_SOLVE_TIMEOUT_SEC`
   (`crp/SolveDeadline`, `SolveCancellation`).
2. **Four-stage CRP planning** (`crp/CRPEngine`). The Constraint Relaxation Problem planner runs
   one workload at a time through four pluggable stages, each chosen by an environment variable
   and validated at startup (`config/OperatorConfiguration`):

   | Stage | Interface | Default |
   |---|---|---|
   | select the next task | `TaskSelectionPolicy` | `LWEEF`: the most critical task first, i.e. the one with the least estimated earliness against its deadline |
   | generate candidate routes | `RouteGenerationPolicy` | `BOUNDED_CGR` under `R_DET`/`R_STOCH` (bounded contact-graph search), `BFS_MIN_HOP` under `R_STATIC` |
   | pick a route | `PathSelectionPolicy` | `EAP`: earliest available path |
   | assign a rate | `RateAssignmentPolicy` | `DATA_FLOW_EQUILIBRIUM`: the slowest rate that still meets the deadline |

   `SchedulingCapacity` computes residual capacity and earliest completion over the ledgers. A
   task that cannot be routed or rated is refused with a `RefusalCause`, not partially admitted.
   Under `R_STOCH`, `StochasticAdmission` adds contact-disjoint backup routes until the route set's
   survival probability meets the configured confidence.
3. **Reservation** (`rib/LRIB`, `rib/NRIB`). Admitted schedules reserve link capacity (LRIB) and
   node storage for store-and-forward holds (NRIB) over time.
4. **Replay validation** (`crp/CRPScheduleReplayValidator`). Every committed schedule is replayed
   independently for continuity, causality, volume, deadlines and capacity before the solve
   returns; a solve that fails its own replay is rolled back.
5. **Durable commit** (`rib/ReservationStore`). The net change is written before the response is
   sent. The default store is `FileWalReservationStore`, an append-only, fsynced write-ahead log
   (format 7) with atomic compaction; `LRIBStateStore` imports older snapshot files.
6. **Installation** (`install/`, `pcep/`). Each committed task gets an `InstallationIntent` in the
   `IntentLedger`. With `VORTEX_PCEP_AUTO_INSTALL=SINGLE_PCC` the schedule is encoded as a
   PCInitiate (`pcep/ScheduleInitiate`, `PcepEncoder`; segment-routed when every hop has an MPLS
   label) and sent through `northbound/GuardedPcepDispatch`. Schedules committed while no router is
   connected wait in `PendingDispatchStore`.
7. **Feedback from the network.** `pcep/PcepSessionServer` accepts PCC sessions (optionally PCEPS,
   `PcepTransportSecurity`). Reports are decoded (`PcepReportDecoder`, `PcepErrorDecoder`) and
   applied by `InstallationCoordinator`; after state synchronisation, `Reconciliation` compares
   what the controller intended with what the router reports. `InstallationState` tracks each
   task through `PLANNED`, `INSTALLING`, `INSTALLED`, `UNCERTAIN`, `FAILED`, `UPDATING`, `DELETING`
   and `DELETED`.

## Other inputs

- **Topology** (`topology/FileTopologyParser`, or the built-in OARNet topology). A synthetic
  constellation generator (`KuiperConstellationBuilder`) is used by the demo `Main` and tests.
- **Observed capacity** (`topology/ObservedCapacityStore`). `POST /api/v1/links/{id}/observed-capacity`
  replaces a link's declared capacity for later solves; it is refused if it would over-subscribe
  existing reservations.

## High availability

The supported deployment is a single active controller on the local write-ahead log. An
**experimental, pre-GA clustered mode** (`VORTEX_RESERVATION_BACKEND=postgres`) keeps reservations
in PostgreSQL (`PostgresReservationStore`, `PostgresCapacityStore`) behind a leadership lease with a
fencing token (`PostgresLeadership`, `LeadershipLifecycle`). See
[ADR-0001](adr/0001-shared-transactional-state-and-leadership.md) for the design and
[the HA note](ha_leadership_and_dispatch_fencing.md) for its guarantees and residual gaps.

## Python package and eBPF

- `crp_pce/` is a Python reference implementation of the planner, a PCEP codec and a CLI. The
  container can run its PCEP server instead of the Java listener (`VORTEX_PCEP_LISTENER=PYTHON`),
  but it processes no reports, so the Java listener is the one wired to the intent ledger.
- `ebpf/` holds experimental pacing programs that nothing in the controller invokes.

## What VortexPCE does not do

It does not learn topology (no BGP-LS or IGP TED ingestion), answer PCReq/PCRep computation
requests, or drive the delegated-LSP update lifecycle end to end. See the
[PCEP capability matrix](vortex_pce_pcep_capability_matrix.md).
