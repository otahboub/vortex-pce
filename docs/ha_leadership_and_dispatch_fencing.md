# HA leadership, lease safety, and dispatch fencing

This note states exactly what VortexPCE's clustered mode guarantees, and — just as important — what
it does **not** yet guarantee. It is the boundary for the experimental, pre-GA
PostgreSQL clustered mode described in the README. It covers the leadership lease, the fencing of durable state, the fencing of
outbound PCEP dispatch, and the two residual gaps (network-effect atomicity, and cross-host
database clock skew) that only later work or an explicit operating assumption can close.

## Leadership and the lease

A single `leadership` row in PostgreSQL holds the current `term` (a monotonically increasing
`bigint`), the `leader_id` that owns it, and a `lease_expiry`. Under a transaction-scoped advisory
lock (`pg_advisory_xact_lock(4711)`), an instance may take leadership only when no live lease exists
— either no row, or `lease_expiry < clock_timestamp()`. Taking it bumps the term (so a new leader
always fences its predecessor) and stamps a fresh expiry.

Three properties make this safe, each with a test against a real PostgreSQL:

1. **PostgreSQL is the only wall clock.** Expiry comparison and extension run in the database using
   `clock_timestamp()` and an interval; no JVM `Instant.now()` participates. Cross-node JVM skew
   cannot make takeover early or late. (`PostgresLeadership.readLease`/`writeLease`.)
2. **An expired holder cannot renew its old term.** `renew()` refuses once the row is already lapsed
   by the database clock; the instance must demote and reacquire under a strictly higher term.
3. **A resumed-but-superseded leader fails closed locally, before the next heartbeat.** Each
   successful acquire/renew arms a conservative **local monotonic** deadline
   (`System.nanoTime()` at request start `+ TTL − margin`). `leaseLocallyLive()` combines "promoted"
   with "still inside that monotonic deadline". A stop-the-world pause that outruns the lease makes
   `nanoTime()` jump past the deadline on resume, so admission and dispatch refuse immediately rather
   than waiting for the heartbeat to notice.

## Fencing of durable state (closed)

Every reservation/intent/capacity write goes through `PostgresReservationStore.append`, which holds
the same advisory lock across term validation, the writes, and the commit, and guards every upsert
with `WHERE term <= EXCLUDED.term`. A superseded leader's durable write is rejected atomically. This
is the strong guarantee: **a superseded leader cannot overwrite shared state.**

## Fencing of outbound dispatch (narrowed; see the first residual gap)

Every dispatched PCEP frame — install and removal — leaves the process through the single
`GuardedPcepDispatch` point. The leadership guard (`leaseLocallyLive()`) is defined once and is the
**last statement before the socket write**, with nothing between. A lease lost between admission and
the write fails closed: the intent stays `PLANNED`/durable and the successor inherits it. On
demotion the PCEP listener is closed, so a straggler that resumes after a full heartbeat finds no
session to send on.

### Residual gap: check-to-effect is not atomic

A stop-the-world pause that lands **strictly between** the guard returning true and the kernel
accepting the bytes can still outlive the lease. The check has already passed; on resume the write
proceeds. Database fencing stops any *durable* consequence (a stale leader's subsequent reservation
write is rejected), but it cannot retract bytes already handed to the socket.

This residual **cannot be closed in-process.** Closing it requires the *peer* to reject a stale
operation — a per-PCC leadership epoch/token the PCC (or a session-owning proxy) validates and that a
successor's takeover invalidates. Stock PCEP (RFC 8231/8281) carries no such term, so full closure is
a protocol/topology change, tracked as future work (term-fenced per-PCC ownership). `GuardedPcepDispatchTest`
asserts this residual explicitly rather than hiding it: a pause up to and including the guard fails
closed; a pause strictly after the guard still sends.

**Operational mitigation today:** keep the lease TTL comfortably larger than the maximum expected GC
pause, run the JVM with a low-pause collector, and alarm on pauses approaching the TTL. This makes
the residual window rare, not impossible.

## Residual gap: cross-host database clock skew

Making PostgreSQL the sole clock removes JVM skew, but a production PostgreSQL HA cluster can fail
over between hosts. Absolute `lease_expiry` values written by the old primary are interpreted by the
new primary's `clock_timestamp()`. If the two database hosts disagree materially, a lease can be read
as expired early or late across a database failover.

**Assumption and requirement:** database hosts must keep bounded wall-clock skew — synchronised time
(NTP/chrony) or a managed PostgreSQL whose SLA states the bound. The safe lease TTL must exceed the
maximum assumed DB-host skew plus one heartbeat. Operators should monitor DB-host time offset and
alarm before it approaches that budget. VortexPCE does not yet test PostgreSQL primary failover under
injected skew; that belongs to the clustered-qualification program below.

## Not yet done: destructive HA qualification

The failover evidence to date is real-database integration tests (`FiveMomentFailoverTest`,
`LeaseSafetyTest`, the leadership/store suites) plus the destructive two-controller harness in
`tests/ha/`, which is not yet run in CI. Before dropping the "pre-GA" label, the following must be run and its SLOs published:
kill -9 mid-dispatch, network partition, database restart, database primary failover with injected
skew, long GC pause across a real PCEP session boundary, and rolling upgrade. Until then the claim
is **single-active operation, with an experimental, pre-GA clustered mode.**

## Cluster status surface and graceful release

Graceful shutdown performs a **term-and-holder-conditional lease release**
(`PostgresLeadership.releaseIfHeld()`, called from `LeadershipLifecycle.close()`): it expires the
lease in place only while this instance still owns it, so a planned stop lets a successor promote at
once instead of waiting out the TTL — while a crash, which never runs `close()`, still falls back to
TTL expiry for the same safety. The release preserves the row (and thus the term history), so the
successor's next term is still strictly higher.

The `GET /clusterz` endpoint reports the leadership state operators need: `clustered`, `instanceId`
(holder), `term`, `leader`, `leaseLocallyLive` (the conservative local guard), `servingReady`,
`leaseExpiry` (database-authoritative), and `lastDemotionReason` (`lease-lost` / `heartbeat-error` /
`graceful-shutdown`). On the single-active file-WAL path it reports `clustered: false`. It is
unauthenticated like the other operational probes and exposes no secrets.
