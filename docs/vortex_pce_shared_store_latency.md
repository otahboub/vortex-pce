# Shared-store solve latency (ADR-0001 action 7)

ADR-0001 adopted a Postgres-backed `ReservationStore` for high availability and flagged one cost to
measure before trusting it in production: *"a solve that reads and commits many reservations behind
the leader lease adds round-trips the local WAL never had — solve latency will rise and must be
measured."* This is that measurement, and the decision it drives: **a read-model cache in front of
Postgres is not warranted.**

## What a solve actually pays

A solve reads the in-memory LRIB/NRIB and, on commit, calls `ReservationStore.append` exactly once
with the delta it admitted. The read path does not touch the store, so the backend changes only the
cost of that one `append`. On the file WAL that append is a local framed write plus `fsync`; on
Postgres it is one transaction that crosses the network, takes the leadership advisory lock, upserts
the delta, and commits with `synchronous_commit = on`. The added latency is therefore entirely in
`append`, and `restore` is a separate one-time cost a survivor pays at failover.

`ReservationStoreLatencyBenchmark` measures both directly: 200 timed `append`s after 30 warmup, per
delta size, on each backend, and a full-state `restore`.

## Results

A single local run, indicative only: PostgreSQL 16 reached over a local Docker network from the JVM
container (so these are a **lower bound** on a real deployment, where the store sits across a real network — see
caveats). Latencies in milliseconds.

### `append` — the per-solve commit cost

| backend  | reservations | p50  | p95  | p99  | max   |
|----------|-------------:|-----:|-----:|-----:|------:|
| file-wal |            1 | 0.45 | 1.25 | 1.46 |  1.81 |
| postgres |            1 | 3.00 | 3.78 | 4.28 |  7.28 |
| file-wal |           10 | 0.42 | 1.15 | 1.30 |  3.78 |
| postgres |           10 | 3.07 | 3.69 | 4.83 |  5.81 |
| file-wal |           50 | 0.50 | 1.23 | 1.45 |  1.72 |
| postgres |           50 | 3.47 | 3.95 | 5.63 | 10.86 |
| file-wal |          100 | 0.58 | 1.19 | 1.81 |  3.05 |
| postgres |          100 | 4.10 | 4.69 | 6.93 | 14.95 |

### `restore` — the one-time failover read

| backend  |  rows | ms   |
|----------|------:|-----:|
| postgres |  2000 | 16.8 |

## Interpretation

- **The Postgres overhead is fixed, not proportional to the solve.** Going from 1 to 100
  reservations — a 100× larger delta — moves p50 from 3.00 ms to only 4.10 ms. The delta is one
  batched transaction, so the round-trip and `synchronous_commit` fsync dominate and the marginal
  cost per reservation is ~0.01 ms. The file WAL is flat too (~0.5 ms) for the same reason.
- **The absolute cost is small next to a solve.** A commit that settles in ~3–4 ms at p50 and under
  ~7 ms at p99 is small beside CRP path computation, which is the actual work of a solve; on this
  measurement the store is not the bottleneck.
- **Failover restore is cheap.** 2000 committed reservations restore in ~17 ms; the cost is linear,
  so even 10k rows is a ~90 ms one-time cost when a standby promotes — well inside a failover budget.

## Decision: no read-model cache

A read model (a cache of current reservations in front of Postgres) is the optimisation the ADR
reserved the right to add. The measurement says not to:

1. **It cannot help the write.** The added latency is in `append`, and an admitted reservation must
   commit durably before the solve returns — a read cache does nothing for a durable write. Only a
   weaker durability contract would, and correctness-over-availability rules that out.
2. **There is no read hot path to cache.** Solves already read the in-memory RIBs, not the store.
   The store is read only at startup/failover, where ~17 ms/2k-rows is already fine.
3. **The overhead does not grow with load.** Because it is fixed per commit rather than per
   reservation, it does not degrade as solves get larger — the condition that would justify a cache
   never arrives by this axis.

So the shared store needs no cache on this evidence; adding one would add a consistency surface
(cache-vs-store staleness) for no latency it can remove.

## When to revisit

Re-measure and reconsider only if a deployment changes the shape of the cost, not the size of it:

- **Real inter-AZ network latency.** These numbers are loopback-class. If the store sits across a
  region boundary, each commit's round-trip could rise into the tens of milliseconds. If that lands
  on a high solve rate, the answer is connection pooling and co-locating the leader with its
  Postgres, not a read cache — the write still must commit.
- **Very large committed state.** If restore at failover grows into seconds, a streaming or
  paginated restore (not a cache) is the fix.

## Reproduce

```bash
# a Postgres reachable at $POSTGRES_URL, then:
RUN_LATENCY_BENCH=1 POSTGRES_URL=jdbc:postgresql://<host>:5432/postgres \
  POSTGRES_USER=postgres POSTGRES_PASSWORD=postgres \
  mvn -Dtest=ReservationStoreLatencyBenchmark test
```

The benchmark is gated on `RUN_LATENCY_BENCH=1` (and `POSTGRES_URL`), so the ordinary build neither
runs it nor depends on a machine-specific latency threshold; it asserts nothing about absolute
milliseconds and only prints the table above.
