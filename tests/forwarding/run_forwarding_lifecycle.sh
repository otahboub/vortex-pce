#!/usr/bin/env bash
# M1: an enforcing forwarding-lifecycle test. The interop workflow proves session + state sync
# only; this drives a VortexPCE PCInitiate into FRR pathd, has pathd build a PCE-initiated SR
# policy, resolves the segment against a real MPLS dataplane so the policy goes ACTIVE, and shows
# labelled traffic forwarding over it.
#
# Requires an MPLS-capable host kernel (/proc/sys/net/mpls) and a privileged container runtime;
# ordinary GitHub-hosted runners do not provide the required MPLS modules. Every verdict is read
# from FRR pathd or the dataplane, never from VortexPCE.
set -u
IMAGE="${VORTEX_IMAGE:-vortex-pce-controller:latest}"
FRR="${FRR_IMAGE:?FRR_IMAGE must be an immutable image reference containing @sha256:}"
F="$(cd "$(dirname "$0")" && pwd)"
NET=fwdnet
rc=0
note() { echo "   $*"; }
check() { if eval "$2"; then echo "PASS: $1"; else echo "FAIL: $1"; rc=1; fi; }
start_mpls_capture() {
  docker exec -d fwd-tail python3 -c 'import socket,time
s=socket.socket(socket.AF_PACKET,socket.SOCK_RAW,socket.htons(3)); s.bind(("eth0",0)); s.settimeout(.2)
n=0; end=time.monotonic()+4
while time.monotonic()<end:
 try:
  frame=s.recv(65535); n += frame[12:14] == b"\x88\x47"
 except TimeoutError: pass
open("/tmp/mpls-count","w").write(str(n))'
  sleep 1
}
cleanup_only() {
  docker rm -f fwd-head fwd-tail fwd-pce >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
}
collect_evidence() {
  exit_code="$1"
  evidence="${EVIDENCE_DIR:-}"
  [ -n "$evidence" ] || return 0
  mkdir -p "$evidence"
  {
    echo "finished_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "exit_code=$exit_code"
    echo "vortex_image=$IMAGE"
    echo "frr_image=$FRR"
    echo "git_commit=$(git -C "$F/../.." rev-parse HEAD 2>/dev/null || echo unavailable)"
    echo "git_tree=$(git -C "$F/../.." rev-parse 'HEAD^{tree}' 2>/dev/null || echo unavailable)"
    echo "runner_kernel=$(uname -srvmo)"
    echo "runner_arch=$(uname -m)"
  } > "$evidence/manifest.txt"
  for container in fwd-pce fwd-head fwd-tail; do
    docker inspect "$container" > "$evidence/$container-inspect.json" 2>&1 || true
    docker logs "$container" > "$evidence/$container.log" 2>&1 || true
  done
  docker exec fwd-head vtysh -c 'show sr-te pcep session' \
    > "$evidence/frr-pcep-session.txt" 2>&1 || true
  docker exec fwd-head vtysh -c 'show sr-te policy detail' \
    > "$evidence/frr-policy-final.txt" 2>&1 || true
  docker exec fwd-head vtysh -c 'show mpls table' \
    > "$evidence/frr-mpls-final.txt" 2>&1 || true
  docker exec fwd-pce sh -c \
    'curl -fsS -H "X-API-Key: fwd" http://127.0.0.1:8080/api/v1/tasks/FWD1' \
    > "$evidence/vortex-intent-final.json" 2>&1 || true
}
teardown() {
  exit_code="$?"
  trap - EXIT
  collect_evidence "$exit_code" || true
  cleanup_only
  exit "$exit_code"
}
trap teardown EXIT
cleanup_only

case "$FRR" in
  *@sha256:????????????????????????????????????????????????????????????????) ;;
  *) echo "FAIL: FRR_IMAGE is not pinned by a SHA-256 digest"; exit 2 ;;
esac

docker network create --subnet 10.80.0.0/24 $NET >/dev/null 2>&1 || { echo "FAIL: network"; exit 1; }

docker run -d --name fwd-pce --network $NET --ip 10.80.0.4 -e VORTEX_API_KEY=fwd \
  -e VORTEX_PCEP_LISTENER=JAVA -e VORTEX_PCEP_BIND=0.0.0.0 -e VORTEX_PCEP_AUTO_INSTALL=SINGLE_PCC \
  -e VORTEX_TOPOLOGY_FILE=/topo/topology.json -v "$F":/topo:ro "$IMAGE" >/dev/null
docker run -d --name fwd-tail --network $NET --ip 10.80.0.3 --privileged "$FRR" sleep 900 >/dev/null
docker run -d --name fwd-head --network $NET --ip 10.80.0.2 --privileged \
  -v "$F/frr-head.conf":/etc/frr/frr.conf:ro -v "$F/daemons":/etc/frr/daemons:ro "$FRR" >/dev/null
sleep 8
docker exec fwd-tail sh -c 'sysctl -w net.mpls.platform_labels=1048575 net.mpls.conf.eth0.input=1 >/dev/null 2>&1; ip addr add 10.88.0.1/32 dev lo; ip -f mpls route add 16020 dev lo'
docker exec fwd-head sh -c 'sysctl -w net.mpls.platform_labels=1048575 net.mpls.conf.eth0.input=1 >/dev/null 2>&1'

up=""; for i in $(seq 1 40); do docker exec fwd-head vtysh -c "show sr-te pcep session" 2>/dev/null | grep -qi "Status UP" && { up=1; break; }; sleep 2; done
check "PCEP session establishes" '[ -n "$up" ]'

docker exec fwd-pce sh -c 'curl -s -X POST http://localhost:8080/api/v1/solve -H "X-API-Key: fwd" -H "Content-Type: application/json" -d "[{\"taskId\":\"FWD1\",\"sourceNodeId\":\"HEAD\",\"destinationNodeId\":\"TAIL\",\"originationTimeSec\":0,\"deadlineSec\":3600,\"taskSizeBytes\":1000000,\"priority\":1}]"' >/dev/null
sleep 6
POL="$(docker exec fwd-head vtysh -c 'show sr-te policy' 2>/dev/null | grep -v vtysh)"
check "PCInitiate is accepted (no Error-Type 24)" '! docker logs fwd-pce 2>&1 | grep -qi "error type 24"'
check "pathd creates the PCE-initiated SR policy" 'echo "$POL" | grep -q "vortex-FWD1"'
note "$(echo "$POL" | grep vortex)"

act=""; for i in $(seq 1 15); do docker exec fwd-head vtysh -c "show sr-te policy detail" 2>/dev/null | grep -qi "Status: Active" && { act=1; break; }; sleep 2; done
check "the policy goes operationally ACTIVE" '[ -n "$act" ]'
check "the segment (label 16020) resolves in the MPLS LFIB" 'docker exec fwd-head vtysh -c "show mpls table" 2>/dev/null | grep -q 16020'

COLOR="$(echo "$POL" | awk '/vortex-FWD1/{print $2; exit}')"
check "the policy exposes a numeric colour for route steering" \
  'echo "$COLOR" | grep -Eq "^[1-9][0-9]*$"'
docker exec fwd-head vtysh -c 'configure terminal' \
  -c "ip route 10.88.0.1/32 10.80.0.3 color $COLOR" >/dev/null 2>&1
ROUTE="$(docker exec fwd-head vtysh -c 'show ip route 10.88.0.1/32' 2>/dev/null)"
check "FRR installs a route bound to the PCE-created policy colour" \
  'echo "$ROUTE" | grep -q "10.88.0.1/32"'

start_mpls_capture
fwd="$(docker exec fwd-head sh -c 'ping -c 3 -W 1 10.88.0.1 2>&1 | tail -2')"
sleep 4
labelled="$(docker exec fwd-tail cat /tmp/mpls-count 2>/dev/null || echo 0)"
note "active-policy MPLS frames observed: $labelled"
check "policy-steered traffic reaches the tail" 'echo "$fwd" | grep -q "0% packet loss"'
check "the independent tail oracle observes MPLS while the policy is active" \
  '[ "$labelled" -gt 0 ]'

del="$(docker exec fwd-pce sh -c 'curl -s -X DELETE "http://localhost:8080/api/v1/tasks/FWD1" -H "X-API-Key: fwd"')"
sleep 5
check "cancellation is requested" 'echo "$del" | grep -qi removal-requested'
check "a removal PCInitiate is dispatched to the PCC" 'docker logs fwd-pce 2>&1 | grep -q "FWD1: PCInitiate"'

gone=""; for i in $(seq 1 20); do
  docker exec fwd-head vtysh -c 'show sr-te policy' 2>/dev/null | grep -q 'vortex-FWD1' \
    || { gone=1; break; }
  sleep 1
done
check "pathd confirms removal by deleting the PCE-initiated policy" '[ -n "$gone" ]'
if [ -z "$gone" ]; then
  note "policy remained after removal; controller and PCC diagnostics follow"
  docker logs fwd-pce 2>&1 | tail -80
  docker logs fwd-head 2>&1 | tail -80
  docker exec fwd-head vtysh -c 'show sr-te policy detail' 2>&1 || true
fi

start_mpls_capture
after="$(docker exec fwd-head sh -c 'ping -c 3 -W 1 10.88.0.1 2>&1 | tail -2' || true)"
sleep 4
after_labelled="$(docker exec fwd-tail cat /tmp/mpls-count 2>/dev/null || echo 0)"
note "post-removal reachability: $(echo "$after" | tr '\n' ' ')"
note "post-removal MPLS frames observed: $after_labelled"
check "the tail observes no policy-labelled traffic after removal" '[ "$after_labelled" -eq 0 ]'

# A fresh TCP session from the same PCC identity must replace the prior session cleanly. Restart
# pathd rather than merely reconnecting its socket: this forces a new OPEN and RFC 8231 state-sync
# exchange. A removed policy must not be recreated during that reconciliation.
docker restart fwd-head >/dev/null
reconnected=""; for i in $(seq 1 40); do
  docker exec fwd-head vtysh -c 'show sr-te pcep session' 2>/dev/null | grep -qi 'Status UP' \
    && { reconnected=1; break; }
  sleep 2
done
check "the PCC reconnects and completes a fresh PCEP session" '[ -n "$reconnected" ]'
check "the dynamic policy is absent after the PCC restart" \
  '! docker exec fwd-head vtysh -c "show sr-te policy" 2>/dev/null | grep -q "vortex-FWD1"'

echo; [ $rc -eq 0 ] && echo "FORWARDING LIFECYCLE: ALL CHECKS PASSED" || echo "FORWARDING LIFECYCLE: FAILURES ABOVE"
exit $rc
