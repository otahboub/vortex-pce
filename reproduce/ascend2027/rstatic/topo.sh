#!/bin/bash
# Three-tier testbed: senders -> router (bottleneck) -> receiver, all on one host.
set -euo pipefail
N_SENDERS=${N_SENDERS:-4}

teardown() {
  for i in $(seq 1 8); do docker rm -f bench-s$i >/dev/null 2>&1 || true; done
  # bench-probe belongs here too. Leaving it out kept bench-a alive, so the network could not be
  # removed, the re-create then failed, and set -e aborted the script after teardown had already
  # deleted the router -- leaving a half-built testbed that failed later and further away.
  docker rm -f bench-router bench-rx bench-probe >/dev/null 2>&1 || true
  docker rm -f bench-pce >/dev/null 2>&1 || true
  # Every container attached to these networks has to go first, or the removal silently fails and
  # the re-create below aborts the script. Failing loudly here beats failing obscurely later.
  docker network rm bench-a bench-b >/dev/null 2>&1 || true
  for net in bench-a bench-b; do
    if docker network inspect "$net" >/dev/null 2>&1; then
      echo "error: network $net still exists; attached:" >&2
      docker network inspect "$net" --format '{{range .Containers}}{{.Name}} {{end}}' >&2
      exit 1
    fi
  done
}
teardown
docker network create --subnet 172.30.0.0/24 bench-a >/dev/null
docker network create --subnet 172.31.0.0/24 bench-b >/dev/null

docker run -d --name bench-router --privileged --network bench-a --ip 172.30.0.2 vortex-bench:node >/dev/null
docker network connect --ip 172.31.0.2 bench-b bench-router
docker run -d --name bench-rx --privileged --network bench-b --ip 172.31.0.10 vortex-bench:node >/dev/null
for i in $(seq 1 "$N_SENDERS"); do
  docker run -d --name bench-s$i --privileged --network bench-a --ip 172.30.0.1$i vortex-bench:node >/dev/null
done

docker exec bench-router sysctl -qw net.ipv4.ip_forward=1
docker exec bench-rx ip route replace 172.30.0.0/24 via 172.31.0.2
for i in $(seq 1 "$N_SENDERS"); do
  docker exec bench-s$i ip route replace 172.31.0.0/24 via 172.30.0.2
done
docker run -d --name bench-probe --privileged --network bench-a --ip 172.30.0.99 vortex-bench:node >/dev/null
docker exec bench-probe ip route replace 172.31.0.0/24 via 172.30.0.2

echo "topology up: $N_SENDERS senders + prober -> router -> receiver"
