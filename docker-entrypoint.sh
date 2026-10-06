#!/bin/bash
set -euo pipefail

PCEP_PID=""
JAVA_PID=""

shutdown() {
  status=$?
  trap - EXIT INT TERM
  [[ -n "$PCEP_PID" ]] && kill -TERM "$PCEP_PID" 2>/dev/null || true
  [[ -n "$JAVA_PID" ]] && kill -TERM "$JAVA_PID" 2>/dev/null || true
  [[ -n "$PCEP_PID" ]] && wait "$PCEP_PID" 2>/dev/null || true
  [[ -n "$JAVA_PID" ]] && wait "$JAVA_PID" 2>/dev/null || true
  exit "$status"
}
trap shutdown EXIT INT TERM

# Exactly one PCEP implementation owns 4189. The Java listener defaults to the same port, so
# starting both means the second fails to bind -- and it binds after the HTTP server is already
# up, leaving a half-started controller. VORTEX_PCEP_LISTENER=JAVA hands the port to the Java
# listener, which PCERestServer starts before it reports ready.
case "${VORTEX_PCEP_LISTENER:-DISABLED}" in
  JAVA)
    echo "[VortexPCE] PCEP owned by the Java listener; not starting the Python server."
    ;;
  PYTHON)
    # The package default is loopback-only. This container listens on all container interfaces
    # intentionally; Compose restricts the published host port to 127.0.0.1 by default.
    #
    # Opt-in, and worth understanding before opting in: this server encodes OPEN, KEEPALIVE and
    # PCInitiate but does not process PCRpt or PCUpd, so nothing a router reports updates
    # installation intent and no reconciliation runs behind it. VORTEX_PCEP_LISTENER=JAVA is the
    # implementation with the intent ledger.
    echo "[VortexPCE] Starting experimental Python PCEP server on 0.0.0.0:4189 (no PCRpt/PCUpd" \
         "processing; installation intent will not be updated by peer reports)..."
    python3 -m crp_pce.cli serve --host 0.0.0.0 --port 4189 &
    PCEP_PID=$!
    ;;
  *)
    # DISABLED now disables. It was the default and it started the Python server anyway, so a
    # container nobody had configured exposed 4189 with an implementation that cannot process
    # reports -- a southbound port with no intent ledger or reconciliation behind it, which is
    # the opposite of what the documented control loop describes.
    echo "[VortexPCE] PCEP listener disabled; no southbound port is open."
    ;;
esac

echo "[VortexPCE] Starting HTTP API on TCP port 8080..."
# The JVM defaults to 25% of the container limit, so a 2 GiB cap yielded a ~512 MiB heap with
# 1.5 GiB unused. The planner holds the whole topology and both ledgers in heap. The heap dump
# is what makes an OOM diagnosable after the fact; /tmp is the writable tmpfs mount.
JAVA_OPTS="${JAVA_OPTS:--XX:MaxRAMPercentage=75 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -XX:+ExitOnOutOfMemoryError}"
# shellcheck disable=SC2086
java ${JAVA_OPTS} -cp '/app/classes:/app/lib/*' net.dcn.pce.Main --server &
JAVA_PID=$!

# A required child exiting makes the container exit and lets the orchestrator restart it.
if [ -n "$PCEP_PID" ]; then
  wait -n "$PCEP_PID" "$JAVA_PID"
else
  wait -n "$JAVA_PID"
fi
