#!/bin/bash
# Two independent shaped pipes for the R_STATIC multi-path experiment:
#   senders -> routerA -> rxA   (the Cleveland path)
#   senders -> routerB -> rxB   (the Cincinnati path)
# A flow routed via CLE lands on pipe A, via CIN on pipe B. ClassicIP funnels all onto one pipe;
# Optimized spreads across both. Each pipe's egress is shaped identically, so the only difference
# is how many pipes the arm can use.
set -euo pipefail
N=${N_SENDERS:-8}
teardown() {
  for i in $(seq 1 12); do docker rm -f mp-s$i >/dev/null 2>&1 || true; done
  docker rm -f mp-rA mp-rB mp-rxA mp-rxB >/dev/null 2>&1 || true
  docker network rm mp-src mp-a mp-b >/dev/null 2>&1 || true
}
teardown
docker network create --subnet 172.40.0.0/24 mp-src >/dev/null
docker network create --subnet 172.41.0.0/24 mp-a >/dev/null
docker network create --subnet 172.42.0.0/24 mp-b >/dev/null
# routers straddle src and their egress net
docker run -d --name mp-rA --privileged --network mp-src --ip 172.40.0.2 vortex-bench:node >/dev/null
docker network connect --ip 172.41.0.2 mp-a mp-rA
docker run -d --name mp-rB --privileged --network mp-src --ip 172.40.0.3 vortex-bench:node >/dev/null
docker network connect --ip 172.42.0.2 mp-b mp-rB
docker run -d --name mp-rxA --privileged --network mp-a --ip 172.41.0.10 vortex-bench:node >/dev/null
docker run -d --name mp-rxB --privileged --network mp-b --ip 172.42.0.10 vortex-bench:node >/dev/null
for i in $(seq 1 "$N"); do
  docker run -d --name mp-s$i --privileged --network mp-src --ip 172.40.0.1$(printf '%02d' "$i") vortex-bench:node >/dev/null
done
docker exec mp-rA sysctl -qw net.ipv4.ip_forward=1
docker exec mp-rB sysctl -qw net.ipv4.ip_forward=1
docker exec mp-rxA ip route replace 172.40.0.0/24 via 172.41.0.2
docker exec mp-rxB ip route replace 172.40.0.0/24 via 172.42.0.2
for i in $(seq 1 "$N"); do
  docker exec mp-s$i ip route replace 172.41.0.0/24 via 172.40.0.2   # rxA via routerA (pipe A)
  docker exec mp-s$i ip route replace 172.42.0.0/24 via 172.40.0.3   # rxB via routerB (pipe B)
done
# shape each router's egress toward its rx at 10mbit, realistic backbone buffer
for r in mp-rA mp-rB; do
  dev=$([ "$r" = mp-rA ] && echo eth1 || echo eth1)
  docker exec $r tc qdisc del dev $dev root 2>/dev/null || true
  docker exec $r tc qdisc add dev $dev root handle 1: htb default 10
  docker exec $r tc class add dev $dev parent 1: classid 1:10 htb rate 10mbit ceil 10mbit
  docker exec $r tc qdisc add dev $dev parent 1:10 handle 10: bfifo limit 1000000
done
echo "two-path topology up: $N senders -> {routerA->rxA, routerB->rxB}, each 10mbit"
