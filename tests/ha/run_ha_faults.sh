#!/usr/bin/env bash
#
# Destructive HA fault harness. Stands up two VortexPCE controllers
# sharing one PostgreSQL, then injects faults and asserts the leadership invariants black-box —
# turning the fencing/lease/failover design from argued into demonstrated.
#
# Scenarios (single host, faithfully reproducible):
#   F0  election        exactly one leader emerges
#   FG  graceful stop   SIGTERM (docker stop) releases the lease -> standby promotes well inside the
#                       TTL. This is the rolling-upgrade moment and the black-box proof of the
#                       shutdown-hook lease release (without it, this degrades to the crash timing).
#   FC  crash           kill -9 the leader -> the standby promotes within the lease TTL + a heartbeat.
# Across every handover: the term is strictly monotonic and never are there two leaders at once.
#
# Not covered here (separate real-infra track, see README): cross-host DB clock skew, managed
# PostgreSQL primary failover, real data-plane blast radius.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
COMPOSE="docker compose -f $HERE/docker-compose.ha.yml -p vortexha"

A_PORT=18080; B_PORT=18081
A_SVC=vortex-a; B_SVC=vortex-b

# Timing (overridable). The product defaults are a 30s lease TTL and a 10s heartbeat.
GRACEFUL_MAX="${GRACEFUL_MAX:-20}"   # a released lease promotes within a heartbeat; < TTL discriminates
CRASH_MAX="${CRASH_MAX:-55}"         # a crash waits out the TTL, then the next heartbeat
STARTUP_MAX="${STARTUP_MAX:-60}"

pass=0; fail=0
ok()   { echo "  [PASS] $*"; pass=$((pass+1)); }
bad()  { echo "  [FAIL] $*"; fail=$((fail+1)); }
info() { echo "  [ .. ] $*"; }

cleanup() { echo "== teardown =="; $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

port_for() { [ "$1" = "$A_SVC" ] && echo "$A_PORT" || echo "$B_PORT"; }
other()    { [ "$1" = "$A_SVC" ] && echo "$B_SVC" || echo "$A_SVC"; }

# Read one field from a controller's /clusterz. Prints empty on any error (treated as "not leader").
clusterz() { curl -fsS --max-time 2 "http://127.0.0.1:$1/clusterz" 2>/dev/null || true; }
is_leader() { clusterz "$1" | grep -oE '"leader": *(true|false)' | grep -oE '(true|false)$'; }
term_of()   { clusterz "$1" | grep -oE '"term": *[0-9]+' | grep -oE '[0-9]+$'; }
livez_ok()  { curl -fsS --max-time 2 "http://127.0.0.1:$1/livez" >/dev/null 2>&1; }

# Which service (if any) currently claims leadership. Prints the service name or nothing.
current_leader() {
  [ "$(is_leader "$A_PORT")" = "true" ] && { echo "$A_SVC"; return; }
  [ "$(is_leader "$B_PORT")" = "true" ] && { echo "$B_SVC"; return; }
}
# Number of controllers claiming leadership right now (must never exceed 1).
leader_count() {
  local n=0
  [ "$(is_leader "$A_PORT")" = "true" ] && n=$((n+1))
  [ "$(is_leader "$B_PORT")" = "true" ] && n=$((n+1))
  echo "$n"
}

# Wait until exactly one leader exists; assert it was never two along the way. Prints the leader svc.
wait_single_leader() {
  local deadline=$(( SECONDS + ${1:-$STARTUP_MAX} )) split_seen=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    local c; c=$(leader_count)
    [ "$c" -gt 1 ] && split_seen=1
    if [ "$c" -eq 1 ]; then
      [ "$split_seen" -eq 1 ] && bad "two leaders were observed during convergence (split brain)"
      current_leader; return 0
    fi
    sleep 1
  done
  echo ""; return 1
}

echo "== build image =="
if [ -n "${VORTEX_IMAGE:-}" ]; then
  info "using prebuilt image $VORTEX_IMAGE"
else
  docker build -q -t vortex-ha:local "$REPO_ROOT" >/dev/null
  info "built vortex-ha:local"
fi

echo "== bring up two controllers + postgres =="
$COMPOSE up -d >/dev/null
for p in "$A_PORT" "$B_PORT"; do
  d=$(( SECONDS + STARTUP_MAX ))
  until livez_ok "$p"; do [ "$SECONDS" -lt "$d" ] || { bad "controller on $p never became live"; exit 1; }; sleep 1; done
done
info "both controllers live"

echo "== F0: exactly one leader emerges =="
leader=$(wait_single_leader) || { bad "no single leader emerged"; exit 1; }
t0=$(term_of "$(port_for "$leader")")
ok "single leader is $leader at term $t0"

echo "== FG: graceful stop (SIGTERM) releases the lease -> fast handover =="
standby=$(other "$leader")
$COMPOSE stop "$leader" >/dev/null
start=$SECONDS
while [ "$(is_leader "$(port_for "$standby")")" != "true" ]; do
  [ "$(( SECONDS - start ))" -le "$CRASH_MAX" ] || { bad "standby $standby never promoted after graceful stop"; break; }
  sleep 1
done
elapsed=$(( SECONDS - start ))
tg=$(term_of "$(port_for "$standby")")
if [ "$(is_leader "$(port_for "$standby")")" = "true" ]; then
  [ "$elapsed" -le "$GRACEFUL_MAX" ] \
    && ok "graceful handover to $standby in ${elapsed}s (<= ${GRACEFUL_MAX}s): the lease was released" \
    || bad "graceful handover took ${elapsed}s (> ${GRACEFUL_MAX}s): the SIGTERM lease release did not run"
  [ -n "$tg" ] && [ "$tg" -gt "$t0" ] && ok "term advanced $t0 -> $tg" || bad "term did not advance on handover"
fi
$COMPOSE start "$leader" >/dev/null
d=$(( SECONDS + STARTUP_MAX ))
until livez_ok "$(port_for "$leader")"; do [ "$SECONDS" -lt "$d" ] || break; sleep 1; done
info "$leader rejoined as standby"

echo "== FC: crash (kill -9) the leader -> bounded recovery within the TTL =="
leader=$(wait_single_leader) || { bad "no single leader before crash test"; exit 1; }
tc0=$(term_of "$(port_for "$leader")")
standby=$(other "$leader")
$COMPOSE kill -s KILL "$leader" >/dev/null
start=$SECONDS
while [ "$(is_leader "$(port_for "$standby")")" != "true" ]; do
  [ "$(( SECONDS - start ))" -le "$CRASH_MAX" ] || { bad "standby $standby never promoted after crash (> ${CRASH_MAX}s)"; break; }
  sleep 1
done
elapsed=$(( SECONDS - start ))
tc=$(term_of "$(port_for "$standby")")
if [ "$(is_leader "$(port_for "$standby")")" = "true" ]; then
  ok "crash failover to $standby in ${elapsed}s (<= ${CRASH_MAX}s TTL + heartbeat)"
  [ -n "$tc" ] && [ "$tc" -gt "$tc0" ] && ok "term advanced $tc0 -> $tc" || bad "term did not advance on crash failover"
fi
[ "$(leader_count)" -eq 1 ] && ok "exactly one leader after failover" || bad "leader count is $(leader_count) after failover"

echo
echo "==================== HA FAULT HARNESS: $pass passed, $fail failed ===================="
[ "$fail" -eq 0 ]
