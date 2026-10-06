# VortexPCE: Flow-Aware CRP Planner and Stateful PCEP Controller

[![Open Source](https://img.shields.io/badge/Open%20Source-vortex--pce-blue.svg)](https://github.com/otahboub/vortex-pce)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

VortexPCE is a research-grade controller prototype for scheduling deadline-bearing
flows over residual link and node resources. It combines a Java CRP scheduling
engine and HTTP API with a Python RFC 5440/8231/8281 PCEP implementation.

## Tested Quick Start

Requirements: Docker with Compose, or OpenJDK 17, Maven, and Python 3.10+.

```bash
git clone https://github.com/otahboub/vortex-pce.git
cd vortex-pce

mvn -B clean verify
python3 -m unittest discover -v

export VORTEX_API_KEY='replace-with-a-secret'
docker compose up --build vortex-controller
```

The container exposes:

- HTTP readiness: `http://localhost:8080/readyz`
- HTTP liveness: `http://localhost:8080/livez`
- Effective configuration: `GET http://localhost:8080/api/v1/config` (authenticated)
- Prometheus metrics: `GET http://localhost:8080/metrics` (authenticated)
- Scheduling API: `POST http://localhost:8080/api/v1/solve`
- Task installation state: `GET http://localhost:8080/api/v1/tasks/{taskId}` (authenticated)
- Stateful PCEP: TCP port `4189`

The HTTP server and Compose deployment require at least one of `VORTEX_API_KEY` or
`VORTEX_API_PRINCIPALS`. Compose binds
REST and PCEP to loopback by default, runs as a non-root user with a
read-only root filesystem, and persists LRIB/NRIB reservation state in a named
volume. Reusing a committed `taskId` returns HTTP `409` without changing the
ledgers. `DELETE /api/v1/tasks/{taskId}` cancels a reservation, and reservations
that have completed by the next request's logical origination time are pruned
automatically. Restored snapshots are validated against the active topology and
effective link capacities; an incompatible pre-fix snapshot fails startup and
must be migrated or reset deliberately. Stop it with:

```bash
docker compose down
```

For a host build, run `./install.sh`, set `VORTEX_API_KEY`, and launch the Java
command printed by the script. The visualizer is a static, synthetic prototype
under `visualizer/`; its animation and KPI values are not controller telemetry,
and it is not started by the controller.

Release qualification, digest-pinned upgrades, state compatibility, and rollback
are covered by [the release upgrade and rollback runbook](docs/release_upgrade_rollback.md).
An inaugural release has no honest rollback result until a later compatible release
can use it as a preregistered prior version.

## Operator Configuration

Server mode reads its topology and all four CRP policy stages from the environment. Every
value is parsed and validated **before** the listener binds: an unrecognized policy name, an
unreadable topology file, or a policy that cannot produce routes under the topology's contact
regime fails startup rather than silently reverting to a default or reporting every workload
as unadmitted.

| Variable | Default | Accepted values |
| --- | --- | --- |
| `VORTEX_TOPOLOGY_FILE` | built-in OARNet topology | path to a topology JSON file |
| `VORTEX_TASK_SELECT_POLICY` | `LWEEF` | `LWEEF`, `FCFS`, `EWOF`, `LWF` |
| `VORTEX_ROUTE_GEN_POLICY` | by the topology's regime: `BOUNDED_CGR` under `R_DET`/`R_STOCH` (including the built-in OARNet topology), `BFS_MIN_HOP` under `R_STATIC` | `BFS_MIN_HOP`, `FAST_K_SHORTEST_PATHS`, `BOUNDED_PATH_ENUMERATION`, `K_MAX_EXHAUSTIVE`, `BOUNDED_CGR`, `DISJOINT_CGR`, `CUT_ANCHORED_CGR`, `MESH_CGR` (the last four serve `R_DET`/`R_STOCH` only) |
| `VORTEX_PATH_SELECT_POLICY` | `EAP` | `EAP`, `OCC`, `CGR`, `MIN_HOP` |
| `VORTEX_RATE_ASSIGN_POLICY` | `DATA_FLOW_EQUILIBRIUM` | `DATA_FLOW_EQUILIBRIUM` (aliases `EQUILIBRIUM`, `EQM`), `LINE_RATE`, `FAIRCAP`, `RESIDUAL_BOTTLENECK_HEADROOM` |
| `VORTEX_STATE_PATH` | in-memory only | path to the reservation snapshot |
| `VORTEX_UTILIZATION_HEADROOM` | `0.90` | a number in `(0, 1]`: the largest fraction of a link's residual capacity one flow may take |
| `VORTEX_HEADROOM_ENFORCEMENT` | `WINDOW_PEAK` | `WINDOW_PEAK`: checked against the most residual capacity at any instant in the task's window (long-standing; on continuous routes it rarely binds, because the window almost always has an unreserved sliver). `TRANSMISSION_INTERVAL`: checked against every slot the flow actually transmits in |
| `VORTEX_TRANSPORT_MARGIN` | `NONE` | `NONE`, `RATE_FACTOR` (send at the policy's rate × `VORTEX_TRANSPORT_MARGIN_VALUE`, a factor ≥ 1: for overhead that grows with bytes, such as headers), `DEADLINE_GUARD` (send fast enough to finish `VORTEX_TRANSPORT_MARGIN_VALUE` seconds early: for fixed overhead, such as connection start-up). A margin only speeds a flow up, never past the headroom-safe ceiling, and never moves its deadline |
| `VORTEX_TRANSPORT_MARGIN_VALUE` | unset | required with `RATE_FACTOR` or `DEADLINE_GUARD`; rejected with `NONE` |
| `VORTEX_SOLVE_TIMEOUT_SEC` | `60` | `0`–`3600`; `0` disables the bound |
| `VORTEX_PCEP_LISTENER` | `DISABLED` | `DISABLED` (no southbound port), `JAVA` (intent ledger and reconciliation), `PYTHON` (legacy codec; encodes OPEN/KEEPALIVE/PCInitiate but does **not** process PCRpt or PCUpd, so peer reports never update installation intent) |
| `VORTEX_PCEP_BIND` / `VORTEX_PCEP_PORT` | `127.0.0.1` / `4189` | listener address, when enabled |
| `VORTEX_API_PRINCIPALS` | unset (single key) | named credentials as `name:role:tenant:secret`, comma separated. Roles: `VIEWER` (read only), `OPERATOR` (solve, and cancel its own tenant's tasks), `ADMIN` (everything, any tenant, plus link capacity). Several may overlap during a controlled rotation. Configuration is startup-loaded, so the supported single-instance deployment restarts when the set changes; overlap prevents client lockout but does not make that restart zero-downtime. `VORTEX_API_KEY` remains valid as an unrestricted `ADMIN`; at least one credential mode is required. |
| `VORTEX_API_SOLVE_QUOTA_PER_MINUTE` | unset (no limit) | solves per minute each principal may run, as a token bucket, so an idle caller keeps a burst. Planning is single-flight, so without this one client issuing solves back to back holds the planner and every other caller sees an unbroken run of `503`s — authentication does not help, because the monopolist is a legitimate client. Over-quota answers `429` with `Retry-After`, distinct from the `503` that means the planner is busy for everyone |
| `VORTEX_API_TLS_KEYSTORE` / `_PASSWORD` | unset (plain HTTP) | PKCS#12 store with the certificate the northbound API serves HTTPS with. Without it `X-API-Key` — the one credential authorising solving, cancellation and capacity mutation — crosses the network in cleartext on every request. If the store cannot be loaded, startup fails rather than falling back to cleartext |
| `VORTEX_PCEP_TLS_KEYSTORE` / `_PASSWORD` | unset (plain TCP) | PKCS#12 store with this controller's certificate and key. Setting it enables **PCEPS (RFC 8253)**: mutual TLS, peers must present a certificate the trust store vouches for |
| `VORTEX_PCEP_TLS_TRUSTSTORE` / `_PASSWORD` | unset | PKCS#12 store of the authority legitimate PCC certificates are signed by. Must be set together with the keystore — a listener with a certificate and no trust store would offer TLS while authenticating no one, so that combination fails at startup |
| `VORTEX_PCEP_PERMITTED_PEERS` | unset (any authenticated peer with PCEPS; any address without it) | **With PCEPS**: a JSON array of X.500 certificate subjects, e.g. `["CN=pcc-alpha,O=Example"]`, canonicalized and checked against the certificate the peer proved possession of. JSON is required because subjects contain commas. **Without PCEPS**: comma-separated source addresses, `addr:10.0.0.5` or bare `10.0.0.5`, enforced at accept before any PCEP exchange. `speaker:` entries are **refused at startup**: the speaker entity ID arrives in the peer's own OPEN, so naming one admitted anybody who sent that name and let them supersede the genuine session. A plaintext address allowlist is not authentication; configure PCEPS for a reachable management plane. |
| `VORTEX_PCEP_AUTO_INSTALL` | `DISABLED` | `DISABLED`, `SINGLE_PCC`; dispatches computed schedules only while exactly one PCC session is established, and only when every node on the route has an `ipv4` address. Nodes may also carry an `mplsLabel`; when every hop has one the request is encoded as a segment-routed PCInitiate, which is what an SR-TE PCC such as FRR `pathd` accepts |
| `VORTEX_PCEP_ACK_TIMEOUT_SEC` | `30` | `1`–`3600`; an operation unacknowledged for this long becomes `UNCERTAIN` and keeps its capacity |
| `VORTEX_PCEP_MAX_SESSIONS` | `64` | `1`–`100000`; concurrent PCC sessions the listener accepts. The cap bounds resource use on an unauthenticated port; raise it only on a trusted management network |

**Task ownership is committed with the reservation it guards.** The owning tenant is recorded on
the installation intent inside the same durable transaction as the reservation, so a crash cannot
leave a committed task belonging to nobody. A task whose owner is unknown — admitted before
tenants existed, or restored from a format 5 log — is reachable by an `ADMIN` only, because an
authorization check has to fail closed: a tenant losing access to its own task is recoverable, a
cross-tenant cancellation is not.

> **Upgrade note.** Ownership written by the previous release into `task-owners.json` is **not
> migrated**. That file is now ignored, and the tasks it covered become administrator-only until
> they are re-created. Tenancy shipped one release earlier, so the affected window is small.


`POST /api/v1/links/{linkId}/observed-capacity` with `{"observedBps": N}` tells the
controller what a link's capacity turned out to be, so the next solve plans against it
rather than the value declared at startup. It answers `409` when the observation would
leave existing reservations over-subscribed: commitments already made are not re-rated,
and entering that state makes the following solve fail its own replay validation.
Release the affected tasks first.

A capacity observation is also excluded from a solve in flight: the solve holds the topology it
started with, and an observation arriving mid-solve returns `503` with `Retry-After` rather than
applying. Without that, an observation could pass its committed-capacity check while a solve
still planning against the old capacity had not yet committed, and that solve would then reserve
bandwidth the link no longer has.

**Observations survive a restart** when `VORTEX_STATE_PATH` is set. They are written to
`observed-capacity.json` beside the reservation log, with what the capacity was before and when it
changed, and re-applied at startup. An entry naming a link the topology no longer contains is
logged and skipped, and an unreadable file is ignored rather than fatal — it refines the declared
topology rather than recording commitments, so a damaged one should not become an outage.

They are deliberately **not** in the write-ahead log. That log exists so reservations and
installation intents commit as one transaction; a capacity observation has no such partner, and
losing one restores the declared capacity, which cannot produce an over-subscribed ledger because
an observation below what is already committed is refused before it is recorded. With no state
path configured the endpoint remains a running-process correction only.

**Report shifts, not weather.** Send an observation when a link's capacity is believed to have
genuinely changed — a re-rated radio, a degrading fibre, a contact worse than the plan. Do not
wire this to a periodic telemetry poll: on a link that merely *varies* around a stable mean, a spot
reading can refuse work the planner would have completed, and a rolling average dilutes the genuine
step it would need to detect. Prefer leaving the declared capacity alone unless something is known
to have changed.

`BOUNDED_CGR` expands contact-graph labels and is supported under the `R_DET` and `R_STOCH`
contact regimes. Configuring it against an `R_STATIC` topology is rejected at startup. The
external-file CLI (`java ... Main <topology.json> <workloads.json>`) selects the route policy
from the file's own regime, so `R_STATIC` and `R_STOCH` inputs remain routable.

Names are case-insensitive and reported back under their canonical spelling:

```bash
curl -H "X-API-Key: $VORTEX_API_KEY" http://localhost:8080/api/v1/config
```

That endpoint returns the effective configuration, the active topology's regime and size, and
the accepted values for each variable. `/readyz` also carries the effective route and rate
policy so a probe records which configuration served a given request.

### Solve budget

Planning is single-flight: one solve at a time holds the planner, and concurrent requests
receive `503`. An unbounded solve therefore does not merely slow the controller down, it
removes it from service until restart. `VORTEX_SOLVE_TIMEOUT_SEC` bounds a single solve; on
expiry the request returns `504`, the ledgers are rolled back to their pre-solve state, and no
reservations are committed. Cancellation happens between workloads, so a partially planned
workload is never left behind. Set `0` to restore unbounded behavior for long benchmark runs.

## Reservation state

`VORTEX_STATE_PATH` names the durable reservation state. The controller writes an append-only
write-ahead log at `<VORTEX_STATE_PATH>.wal`: each committed transaction appends only its net
effect, and records are fsynced before the request returns, so a transaction the caller saw
commit survives a crash.

The log is compacted once it grows past roughly four times live state, which bounds both file
size and restore time without making the common path pay for it. Compaction writes a new file
and moves it into place atomically, so an interruption leaves either the old log or the new one
intact — never a partial one.

**Upgrading:** a pre-existing snapshot at `VORTEX_STATE_PATH` is imported automatically on first
start and converted to a log. No manual migration is needed. A log written by a newer format
version fails startup rather than starting with an empty ledger, since silently discarding
reservations would re-admit capacity that is already committed.

Storage sits behind a `ReservationStore` interface. Replacing the local log with a shared
transactional backend is the change that makes more than one replica correct — admission has to
serialize in the store rather than in one JVM's monitor — and the interface is what makes that
substitution possible without touching the engine. That backend now exists: VortexPCE has an **experimental, pre-GA clustered mode**: `VORTEX_RESERVATION_BACKEND=postgres` keeps reservations in PostgreSQL behind a leadership lease with a fencing token (ADR-0001). A heartbeat renews the lease, a standby takes over when it expires, and a demoted instance stops admitting and closes its PCEP sessions. Real-database tests cover five failover moments and `tests/ha/run_ha_faults.sh` is a destructive two-controller fault harness, but neither runs in CI and the residual gaps are documented in [the HA note](docs/ha_leadership_and_dispatch_fencing.md). The supported default remains a single instance on the local write-ahead log.

The reservation log now also carries a writer **epoch**. Taking ownership increments a counter
beside the lock file, and every write re-checks it: a controller that has been superseded refuses
its next append instead of interleaving transactions with a peer it cannot see. This matters
because the file lock is weakest exactly where shared state would live — `FileLock` is advisory,
and over NFS or EFS its behaviour ranges from unreliable to silently absent, so two controllers
can each believe they hold it and the damage surfaces later as a sequence gap.

**On the local log this is not leader election and does not make two controllers safe.** It
converts an undetected corruption into a loud refusal; running more than one controller needs the
experimental PostgreSQL backend described above.

### Request correlation

Every API response carries `X-Request-Id`, and the log lines produced while serving that request
are tagged with the same id. A caller reporting a problem can quote it, and the receipt, the
computed result and each install dispatch can be read back as one sequence rather than three
unrelated lines.

It covers the northbound request path only. A PCEP report or error arrives on its own session
thread with no originating request, so those are correlated by task id and LSP name instead —
inventing an id for them would suggest a link that does not exist. There is no correlation across
the Java and Python services; they share no request path.

### Southbound listener

**PCEPS (RFC 8253).** Set `VORTEX_PCEP_TLS_KEYSTORE` and `VORTEX_PCEP_TLS_TRUSTSTORE` and the
listener requires mutual TLS: a peer must present a certificate signed by the configured
authority, and `VORTEX_PCEP_PERMITTED_PEERS` then holds a JSON array of canonicalized certificate
subjects rather than addresses. This is the difference between restricting *where* a connection may come from and
establishing *who* is at the other end — an address is forgeable by anyone on-path, and automatic
dispatch programs paths onto whichever peer holds the single established session.

Off by default, because enabling it requires certificates every existing deployment predates.
When off the listener logs a warning at startup saying peers are not authenticated, rather than
implying protection it does not have. If TLS is configured but a store cannot be loaded, startup
fails rather than falling back to plain TCP: an operator who believes peers are authenticated and
has no authentication is in the worst of both positions.

`VORTEX_PCEP_LISTENER=JAVA` starts the Java PCEP listener. It is **off by default**, so a
deployment asks for it rather than acquiring it by upgrading.

> **Behaviour change.** `DISABLED` now opens no southbound port. It previously started the
> experimental Python server, so an unconfigured container listened on 4189 with an implementation
> that processes no reports. If you were relying on that implicitly, set
> `VORTEX_PCEP_LISTENER=PYTHON` to keep it — but prefer `JAVA`, which is the one wired to the
> intent ledger and reconciliation.
>
> The Compose deployment publishes 4189 and health-checks it, so it now sets
> `VORTEX_PCEP_LISTENER=JAVA` explicitly rather than inheriting a listener. Override it if you
> want a different one.

When enabled, reports from a connected PCC update installation state — an operational report moves
a task to `INSTALLED`, a removal report retires it, and an unacknowledged operation becomes
`UNCERTAIN` while continuing to hold its capacity.

**Committed schedules are dispatched automatically when an install policy names a target.** Set
`VORTEX_PCEP_AUTO_INSTALL=SINGLE_PCC` and a committed schedule is encoded as a PCInitiate — SR-TE
when the topology supplies MPLS labels, RSVP-TE otherwise — and sent to the one established
session. With no policy configured, or when selection is ambiguous, nothing is sent: which PCC owns
a given task is unresolved beyond the single-session case, and guessing it would install paths on a
router nobody chose. Records are made durable before anything reaches a socket.

**The solve response says what became of each installation.** A committed schedule carries an
`installationState` of the form `STATE/DELIVERY` — for example `INSTALLING/SENT`,
`INSTALLING/UNCERTAIN` when a write may have half-arrived, or `PLANNED` when nothing was sent
because no PCC was available. A 200 previously meant only that the plan was computed and its
capacity reserved, and a client had to poll every task to learn whether the network had been asked
at all. The field is absent entirely when no dispatch path is configured, since reporting
`PLANNED` everywhere would imply an installation was attempted and declined.

**A request a PCC refuses is learned from the PCC, not from a timer.** A PCErr carrying the
SRP-ID of a rejected `PCInitiate` fails that intent immediately and releases its capacity, rather
than leaving it `INSTALLING` until the acknowledgement deadline expires into `UNCERTAIN`. A
refused *removal* deliberately does the opposite and changes nothing: the LSP is probably still
installed and carrying traffic, so releasing its capacity would hand that bandwidth to another
flow while a router is using it. An error quoting a request another session owns is ignored.

**A schedule committed while no PCC is connected is dispatched when one arrives.** It was
previously left `PLANNED` forever, holding its reservations for a deadline that could not be met
while the router was never asked. The schedule is retained so it can be encoded later, and sent
when a session finishes synchronising — but only if the intent is still `PLANNED`, so a task
cancelled or already installed in the meantime is untouched.

Only operations that provably never left the controller are retried. An `UNCERTAIN` operation —
one whose write may have reached the router — is deliberately **not** retried, because a duplicate
PCInitiate for an LSP that already exists is worse than a stranded one. Those still require an
operator, and now have a way to reach one: `POST /api/v1/tasks/{taskId}/resolve` with
`{"resolution": "NOT_INSTALLED"}` releases the capacity of an uncertain operation on the
operator's assertion that the LSP is absent from the router. It is refused for any task that is
not `UNCERTAIN`, and only the release direction exists — confirming an LSP *is* installed is what
state synchronisation already does from the router's own report, which is better evidence than an
assertion over HTTP. The action is logged as an operator decision, because releasing capacity for
an LSP that does exist double-books that bandwidth. The retained requests survive a restart when `VORTEX_STATE_PATH` is set: the route and rate are
written to `pending-dispatch.json` beside the reservation log, so a controller that restarts
before its router connects still installs the work once one arrives. A request whose route no
longer exists in the topology is dropped with a reason rather than retried for ever — sending a
path over a link that is gone is worse than not sending, since the reservation stays visible and
cancellable instead of becoming an LSP nobody can account for.

They are not in the write-ahead log. That log exists so reservations and installation intents
commit as one transaction; a dispatch request is a planning output kept so a later attempt can be
encoded, and losing one degrades convergence rather than correctness — the intent stays `PLANNED`,
its capacity stays held, and an operator can still cancel it. Putting a route array on every
intent record would also enlarge every reservation transaction on the hot path to serve the small
subset of intents waiting for a router.

An enforcing CI gate confirms this against FRRouting `pathd`, which is not this repository's code:
it establishes the session, synchronises, accepts the PCInitiate and creates the LSP. That fixture
has no forwarding plane, so the policy stays `DOWN` and nothing there proves traffic, an
operational `PCRpt`, removal, or reconnect.

The transport carries no authentication or confidentiality **unless PCEPS is configured** — see
`VORTEX_PCEP_TLS_KEYSTORE` above. Without it the listener is plain TCP, which is why it binds to
loopback by default and warns at startup.

## Metrics

`GET /metrics` returns Prometheus text exposition (`version=0.0.4`) and **requires the same
credential as the scheduling API** — the payload reports ledger depth and admission state.
Configure your scraper with a bearer token:

```yaml
scrape_configs:
  - job_name: vortex-pce
    authorization:
      credentials: <VORTEX_API_KEY>
    static_configs:
      - targets: ['vortex:8080']
```

| Series | Type | Meaning |
| --- | --- | --- |
| `vortex_solve_requests_total{outcome}` | counter | `success`, `duplicate`, `invalid`, `timeout`, `rejected_busy`, `error` |
| `vortex_workloads_offered_total` | counter | Workloads submitted for planning |
| `vortex_workloads_committed_total` | counter | Workloads admitted with a schedule |
| `vortex_workloads_unadmitted_total` | counter | Workloads rejected by admission control |
| `vortex_deadlines_met_total` / `vortex_deadlines_missed_total` | counter | Deadline outcome of committed schedules |
| `vortex_solve_duration_seconds` | histogram | Planning wall-clock latency |
| `vortex_link_reservations` / `vortex_node_reservations` | gauge | Live LRIB / NRIB depth |
| `vortex_planner_busy` | gauge | `1` while the single-flight planner is occupied |
| `vortex_installation_intents{state}` | gauge | Tasks per installation state; `UNCERTAIN` is capacity held without confirmed installation |
| `vortex_build_info{version,artifact}` | gauge | Build identity, always `1` |

Every `outcome` label is pre-registered at zero, so a `rate()` alert evaluates before the first
occurrence rather than silently matching no series.

## Architecture

### Java scheduling engine

The Java service under `src/main/java/net/dcn/pce/` performs the staged CRP
planning workflow, maintains temporal LRIB/NRIB reservations, validates committed
hop schedules by replay, persists a versioned reservation snapshot, and serves the
northbound API on port 8080.

### Python PCEP service

The Python package under `crp_pce/` provides protocol codecs, a CLI planner, and a
stateful PCEP server. The server performs a complete OPEN/KEEPALIVE exchange,
negotiates the RFC 8281 LSP-instantiation capability, and emits validated
PCInitiate frames.

The container runs **one** PCEP implementation, named by `VORTEX_PCEP_LISTENER`; it does not
supervise both. `JAVA` is the one wired to the intent ledger, PCRpt decoding and reconciliation,
and it dispatches committed schedules automatically under `VORTEX_PCEP_AUTO_INSTALL=SINGLE_PCC`.
The Python service is selected with `PYTHON` and remains a codec: it encodes OPEN, KEEPALIVE and
PCInitiate but processes no reports, so nothing behind it updates installation intent. There is no
Java-to-Python delivery path and none is planned — the Java listener replaced the need for one.

### Experimental eBPF components

The `ebpf/` directory contains experimental TC/EDT and XDP programs and a loader.
The loader updates an existing pinned map through `bpftool`; compilation, attachment, and pinning
still require a separate privileged Linux environment. The eBPF path is not built into or
activated by the default controller image.

**Nothing connects a committed schedule to it.** The controller never invokes the loader, no
`e*` reaches a kernel map, and CI does not compile, attach or verify these programs. **Pacing
measurements of VortexPCE have used Linux `tc` HTB, not these programs** — so none of them is
evidence that VortexPCE enforces rates in the kernel.

## Current Capabilities

- CoS-aware staged flow selection, path generation, residual-calendar route selection, and rate assignment
- Per-hop store-and-forward timelines with final-hop deadline accounting
- Explicit periodic and finite non-periodic R-det contacts with multi-window transmission
- Bounded, loop-free CGR candidate search in residual earliest-arrival order
- Deadline-window DFE rates with propagation-aware final arrival
- Residual link-capacity and actual inter-hop holding-interval admission checks
- Independent replay checks for continuity, causality, volume, deadlines, and capacity
- Atomic, versioned LRIB/NRIB persistence and restore
- Logical-time reservation expiry and authenticated cancellation
- Strict workload and external-topology JSON parsing with bounded request cardinality
- Bounded HTTP concurrency with planner-saturation readiness
- RFC-framed PCEP OPEN, KEEPALIVE, and PCInitiate encoding
- Hardened non-root container packaging and real Maven/Python CI gates

This remains a prototype rather than a production-ready PCE. Interoperability
with a router-grade PCC is demonstrated and gated in CI: FRRouting `pathd`
establishes a stateful session with the Java listener and completes RFC 8231
state synchronisation, a committed schedule is dispatched automatically as an
SR-TE PCInitiate that `pathd` accepts and turns into a PCEP-originated LSP, and
cancelling an installed LSP dispatches a removal to the PCC that owns it.
Automatic dispatch requires `VORTEX_PCEP_AUTO_INSTALL=SINGLE_PCC` and refuses
when the target is ambiguous, because choosing among several PCCs is still an
unresolved policy question. What the gate does not show is an LSP reaching a
forwarding plane: the fixture has none, so the created policy stays `DOWN`, and
nothing there carries traffic, confirms an operational `PCRpt`, or exercises
removal and reconnect end to end. Unrestricted CGR optimality,
distributional R-stoch planning, high availability, and eBPF host enforcement
also need broader implementation and integration testing.

## Deployment

`docker-compose.yml` is a local demonstration and publishes both ports in plaintext on loopback.
For anything reachable, [the secure profile](deploy/README.md) enables northbound HTTPS and
PCEPS, and documents what to back up and what it still does not provide — most importantly that
there is no high availability.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [ADR-0001: shared state, leadership and fencing](docs/adr/0001-shared-transactional-state-and-leadership.md)
- [HA leadership, lease safety and dispatch fencing](docs/ha_leadership_and_dispatch_fencing.md)
- [PCEP capability matrix](docs/vortex_pce_pcep_capability_matrix.md)
- [Release, upgrade and rollback runbook](docs/release_upgrade_rollback.md)
- [Interoperability evidence](tests/interop/README.md) — what an independent PCC and dissector prove, and what they do not
- [eBPF notes](ebpf/README.md)

## Citation

If you use VortexPCE, please cite the software:

```bibtex
@software{tahboub_vortexpce,
  author = {Tahboub, Omar Y.},
  title  = {VortexPCE: A Flow-Aware CRP Planner and Stateful PCEP Controller},
  url    = {https://github.com/otahboub/vortex-pce},
  year   = {2026}
}
```

The CRP planner and Data-Flow Equilibrium rate assignment are based on O. Y. Tahboub and J. I. Khan,
"Forwarding on Froggers Networks: Principle of Data Flow Equilibrium," Proc. 8th Int. Conf. on
Information Technology: New Generations (ITNG), 2011, pp. 125–130, doi:10.1109/ITNG.2011.29.

Distributed under the MIT License. See [LICENSE](LICENSE).
