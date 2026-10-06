# Destructive HA fault harness

Black-box qualification for the PostgreSQL-backed clustered mode. It
stands up **two VortexPCE controllers sharing one PostgreSQL** and injects faults, asserting the
leadership invariants from the outside — turning the fencing/lease/failover design from argued into
demonstrated.

## Run

```bash
tests/ha/run_ha_faults.sh
```

Builds the controller image (`vortex-ha:local`) from the repo, brings up the stack via
`docker-compose.ha.yml`, runs the scenarios, prints `PASS`/`FAIL` per assertion, and tears down. To
reuse a prebuilt image and skip the build: `VORTEX_IMAGE=<tag> tests/ha/run_ha_faults.sh`.

## Scenarios (single host, faithfully reproducible)

| ID | Fault | Invariant asserted |
|----|-------|--------------------|
| **F0** | both controllers start | exactly one leader emerges; never two at once |
| **FG** | graceful stop of the leader (`docker stop` → SIGTERM) | the successor promotes **well inside the lease TTL**, because SIGTERM runs the JVM shutdown hook → `PostgresLeadership.releaseIfHeld()`. Without that release this degrades to the crash timing — so FG is the black-box proof of the shutdown-hook fix. |
| **FC** | crash the leader (`kill -9`) | the successor promotes within the lease TTL + one heartbeat; term is strictly monotonic; exactly one leader after |

Every handover also asserts the leadership **term advances** and that **two leaders are never
observed** during convergence.

Leader state is read from `GET /clusterz` (`leader`, `term`); liveness from `/livez`. The harness
deliberately never keys failover on `/readyz`, which is `started && queueAvailable` and returns 200
on a standby.

Tuning (env): `GRACEFUL_MAX` (default 20s — below the 30s TTL, so it discriminates a released lease
from an expired one), `CRASH_MAX` (55s), `STARTUP_MAX` (60s). The product defaults are a 30s lease
TTL and a 10s heartbeat.

## Deliberately out of scope (separate real-infra track)

These cannot be faithfully reproduced with one host and one PostgreSQL, and the harness does **not**
claim them:

- **Cross-host database clock skew / managed-PostgreSQL primary failover.** One PostgreSQL is one
  clock; the lease is correct by construction here. Qualifying a clustered database's own failover,
  and a bounded DB-host skew assumption, needs a real multi-host database — see
  `docs/ha_leadership_and_dispatch_fencing.md`.
- **Real data-plane blast radius.** The controllers here run with the PCEP listener disabled;
  end-to-end forwarding impact needs a real forwarding plane (the FRR fixture has none).
- **Active-active sharding.** Only the leader serves; standbys hold no sessions by design.

## What building this harness already found

Bringing two controllers up at once surfaced two real production defects that every single-connection
unit test missed, both now fixed:

1. **No lease release on shutdown.** The `--server` path registered no shutdown hook, so SIGTERM never
   reached `PostgresLeadership.releaseIfHeld()` and every rolling upgrade silently degraded to the
   full lease TTL. FG is the regression guard.
2. **Racy schema bootstrap.** `CREATE TABLE IF NOT EXISTS` is not concurrency-safe in PostgreSQL; two
   controllers starting together could crash one with a duplicate-relation error. Schema creation is
   now serialised under the shared advisory lock (`ConcurrentSchemaInitTest`).
