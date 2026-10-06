# ADR-0001: Shared transactional state, leadership, and fencing for high availability

**Status:** Accepted (Postgres); implemented — all action items complete (see Action Items)
**Date:** 2026-08-24
**Deciders:** Dr. Omar Tahboub (project owner)
**Addresses:** high availability was unsupported (single-instance controller only)

## Context

VortexPCE is single-active. Durable state — link and node reservations (LRIB/NRIB) and the
installation intent ledger — lives in a local write-ahead log, `FileWalReservationStore`, behind
the `ReservationStore` interface. That WAL is careful: framed transactions with CRC32,
fsync-before-commit, a monotonic sequence, and a single writer enforced by a `FileLock` plus writer
epochs (`FORMAT_VERSION 7`).

What it cannot do is survive the loss of its host. There is no shared store, no leader election, no
fencing token, and no coupling between PCEP session ownership and leadership. Two consequences
follow, both essential:

1. A host failure makes the control plane unavailable until the same state is recovered elsewhere.
2. Two independent instances, each with its own WAL, can admit conflicting capacity — there is
   nothing that stops a second instance from reserving bandwidth a first has already committed.

PCEP sessions add a specific difficulty. They are stateful and long-lived: a PCC delegates LSPs to
*a* PCE and expects that PCE to hold their state. Failover is therefore not just "restart the
process" — it is "a new instance must take over the PCC relationships and the reservations behind
them, and the old instance must be certain it has stopped acting."

### Forces

- **Correctness over availability.** A PCE that admits conflicting capacity is worse than one that
  is briefly unavailable. Whatever we choose must make split-brain admission impossible, not merely
  unlikely.
- **Preserve the WAL's semantics.** fsync-before-commit and single-writer are the properties the
  rest of the system already trusts. The distributed store must offer at least as much, not trade
  durability for convenience.
- **The abstraction seam exists.** State already goes through `ReservationStore`. A new backend is
  an implementation of that interface plus a coordination layer, not a rewrite.
- **One operator, research-to-production.** This is not a large SRE team. An option that adds a
  consensus system we must operate and debug is a real, recurring cost.
- **Testable failover.** Failover must be tested at five moments: mid-solve,
  mid-dispatch, mid partial-write, mid-acknowledgement, and mid-compaction. The design has to make
  each of those injectable.

## Decision

**Proposed:** adopt a **PostgreSQL-backed `ReservationStore` that also carries leadership and
fencing**, and couple PCEP session ownership to leadership so that only the leader accepts sessions
and dispatches installs. Keep the file WAL as the supported backend for single-node deployments.

The reasoning is that Postgres is the option that most directly *preserves the properties the WAL
already guarantees* while adding the distributed ones, behind a single dependency the team can
operate:

- The WAL's **atomic framed transaction** becomes a database transaction.
- **fsync-before-commit** becomes `synchronous_commit = on` (optionally `remote_apply` with a
  replica).
- The **single-writer FileLock** becomes a leader lease row taken under `SELECT ... FOR UPDATE` /
  `pg_advisory_lock`.
- The **writer epoch** — already the repository's fencing concept — becomes a monotonic `bigint`
  fencing token stamped on every write and checked on admission, so a partitioned old leader's
  writes are rejected.

## Options Considered

### Option A: PostgreSQL (shared transactional store + coordination) — *proposed*

| Dimension | Assessment |
|---|---|
| Correctness | Strong. Serializable transactions; fencing token as a monotonic column checked on write. |
| WAL-semantics fit | Best. Transaction = framed commit; `synchronous_commit` = fsync; advisory lock = FileLock; sequence = epoch. |
| Operational cost | Moderate. One well-understood dependency; managed Postgres is ubiquitous. |
| Failover testability | Strong. Lease expiry, connection kill, and statement-level fault injection cover all five moments. |
| Team familiarity | High. |

**Pros:** one dependency covers both the reservation store and the coordination primitives; maps
one-to-one onto the properties the WAL already provides; relational schema fits interval
reservations (per-link `[start,end)` rows) and the intent ledger directly; mature tooling for
backup, replication, and failover.
**Cons:** a solve that reads and commits many reservations behind the leader lease adds round-trips
the local WAL never had — solve latency will rise and must be measured; a single Postgres is itself
a failure domain unless run with a replica/HA Postgres, which pushes the HA problem down a layer
(acceptable — that layer is a solved, operable problem).

### Option B: etcd (coordination-native)

| Dimension | Assessment |
|---|---|
| Correctness | Strong for coordination; leases and `mod_revision` are purpose-built fencing tokens. |
| WAL-semantics fit | Partial. Excellent for the lease/leader/fencing primitives, poor as the reservation store. |
| Operational cost | Moderate–High. A Raft cluster to run, separate from anything else here. |
| Failover testability | Strong for the lease path. |
| Team familiarity | Low–Moderate. |

**Pros:** the cleanest primitives for exactly the coordination gap — a lease with a TTL for
leadership, and `mod_revision` as a natural fencing token; designed for this.
**Cons:** etcd is a metadata store, not a place to keep a growing set of interval reservations and
an intent ledger (values are size-bounded and it is not meant for bulk transactional data). Choosing
it means *two* stores — etcd for coordination, something else for reservations — and a
cross-store consistency problem between them. That is more moving parts than Option A, for a project
with one operator.

### Option C: Embedded Raft (e.g. Apache Ratis)

| Dimension | Assessment |
|---|---|
| Correctness | Strong in principle; the state machine is replicated and linearizable. |
| WAL-semantics fit | Good conceptually — a replicated log is a natural generalisation of the WAL. |
| Operational cost | No external dependency, but VortexPCE now *owns* a consensus implementation. |
| Failover testability | Hard. Consensus edge cases (snapshots, membership change) are notoriously subtle to test. |
| Team familiarity | Low. |

**Pros:** no external service; the replicated log is philosophically the closest thing to "the WAL,
but distributed"; keeps everything inside the JVM.
**Cons:** the project becomes responsible for operating and debugging a consensus system. For a
one-maintainer research-to-production controller, embedding Raft is the highest-risk path: the
failure modes are the hardest to reason about and the five failover moments become five
consensus-corner-case test suites. High reward only if HA is the product; here it is a requirement,
not the product.

### Option D: Redis

| Dimension | Assessment |
|---|---|
| Correctness | Weak for the fencing path. |
| WAL-semantics fit | Poor. Not a durable transactional store by default. |
| Operational cost | Low. |
| Failover testability | Moderate. |
| Team familiarity | Moderate. |

**Pros:** fast and simple to stand up.
**Cons:** distributed locking on Redis (Redlock) is contested for exactly the fencing use we need —
a locked-out old leader can still believe it holds the lock across a pause. For a store whose entire
job is to make conflicting admission *impossible*, a lock primitive that is only probably-safe is
disqualifying. Not recommended for the correctness-critical path.

## Trade-off Analysis

The decision turns on one question: **do we want one store or two?**

- etcd (B) is the best at coordination but cannot hold the reservations, so it forces a second
  store and a consistency problem between them.
- Postgres (A) is slightly less elegant at the pure coordination primitives — a lease row and a
  heartbeat rather than a native TTL lease — but it holds *both* the coordination state and the
  reservation data in one transactional system, which collapses the consistency problem entirely.

Given a single operator and a hard "no conflicting admission" requirement, one transactional store
that does both is worth more than the cleanest-possible lease primitive. Embedded Raft (C) trades
the external dependency for a much larger internal one, and Redis (D) fails the correctness bar.

So: **Postgres**, with the file WAL retained for single-node use. If the coordination primitives
later prove awkward on Postgres, etcd-for-coordination-only is the fallback — but only if the
reservation store stays transactional and the two are reconciled by a fencing token that spans them.

## Consequences

**Easier:**
- A host can fail and another instance takes over without conflicting admission — the stated goal.
- Backup, point-in-time recovery, and read replicas come from Postgres rather than bespoke WAL code.
- The intent ledger and reservations become queryable for operations and debugging.

**Harder:**
- Solve latency rises (network round-trips to the store under the lease); it must be measured and
  budgeted, and the planner may need to batch reads.
- Deployments now need a Postgres (or accept single-node with the file WAL). The HA burden moves to
  operating Postgres — a solved problem, but a new one for this project.
- Every write path must carry and check the fencing token; missing one reintroduces split-brain.

**To revisit:**
- Whether the file WAL stays a first-class local backend long-term or becomes a dev-only default.
- Whether solve throughput under the shared store needs a caching read-model in front of Postgres.

## Action Items

1. [x] Backend choice confirmed (this ADR); status is Accepted (Postgres).
2. [x] Add a `fencingToken()` to the `ReservationStore` contract; the file WAL returns its writer
       epoch (`FencingTokenContractTest`). Admission-side checking lands with the shared backend.
3. [x] `PostgresReservationStore` implemented: schema for link/node reservations (interval rows)
       and the intent ledger; every delta committed in one transaction with `synchronous_commit
       = on`; leadership term as the fencing token, checked on write. The check is atomic: `append` holds the leadership
       advisory lock across validate+write+commit and guards every upsert with `WHERE term <=
       EXCLUDED.term`, closing the check-then-commit race; readers use
       `readOnly()` and never acquire leadership. Roundtrip and fencing proven
       by `PostgresReservationStoreTest` against a real Postgres (gated on `POSTGRES_URL`; skips
       otherwise, so the ordinary build stays hermetic). Observed-capacity rows and connection
       pooling remain for a follow-up.
4. [x] Leadership module implemented: `PostgresLeadership` — a lease row with a monotonic term,
       taken under `pg_advisory_xact_lock` only when no live lease exists, extended by `renew()`
       while the instance still owns its term, and yielding a strictly higher term on takeover
       (the fencing token). `PostgresLeadershipTest` proves exclusion, expiry-takeover, and that a
       superseded leader loses its next renewal. Wiring the PCEP listener/dispatch to it is action
       5; scheduling the heartbeat is the caller's.
5. [x] Backend selection wired: `VORTEX_RESERVATION_BACKEND=postgres` with `VORTEX_POSTGRES_URL`
       / `_USER` / `_PASSWORD` (password never logged) constructs `PostgresLeadership` +
       `PostgresReservationStore` in `PCERestServer`; the instance takes the lease at startup and
       refuses to start if another holds it. This is deployable single-active on shared storage.
       The leadership lifecycle in `PCERestServer` now renews the lease on a heartbeat, opens the PCEP
       listener on promotion, gates dispatch on `isLeader()` (`GuardedPcepDispatch`), and demotes —
       stops admitting and closes sessions — on lease loss.
6. [x] Failover is exercised by `LeadershipLifecycleTest` (single-promotion, expiry-takeover,
       demote-on-loss) and an end-to-end two-controller run: the leader admits, a standby returns
       503, and killing the leader promotes the standby, which then inherits the committed
       reservations from Postgres and rejects the duplicate task — shared-state continuity across
       failover. The formal five-moment fault-injection matrix is now
       `FiveMomentFailoverTest` (gated on `POSTGRES_URL`), one test per moment against a real
       Postgres: **mid-solve** — a leader that lost its lease mid-solve is fenced on commit with
       nothing written, and the survivor proceeds; **mid-dispatch** — a standby inherits the
       committed reservation and the INSTALLING intent, so it neither loses the capacity nor
       re-admits it; **mid partial-write** — a transaction that never reaches commit surfaces no
       rows (injected at the store's own transaction layer, since the domain rejects a partial
       delta); **mid-acknowledgement** — a re-sent install report replays idempotently across the
       failover boundary without duplicating the intent or its reservation; **mid-compaction** —
       compaction is a no-op on the shared backend (current-state tables, not a growing log), so
       the test pins that no committed row can be lost and forces any future real compaction to
       bring its own fault injection.
7. [x] Solve latency against the shared store measured (`ReservationStoreLatencyBenchmark`, gated on
       `RUN_LATENCY_BENCH`): the Postgres backend adds a *fixed* ~2.5–3.5 ms per commit over the file
       WAL (p50 3.00 ms at one reservation, 4.10 ms at a hundred — one batched transaction, so the
       round-trip and `synchronous_commit` dominate, not the row count), and a 2000-row failover
       restore is ~17 ms. Decision: **no read-model cache** — the overhead is in the durable write a
       solve must commit before returning, which a read cache cannot remove; solves already read the
       in-memory RIBs, so there is no read hot path; and the cost does not grow with delta size. Full
       write-up and the revisit conditions in `docs/vortex_pce_shared_store_latency.md`.
