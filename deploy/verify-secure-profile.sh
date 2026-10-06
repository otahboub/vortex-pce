#!/usr/bin/env bash
# Qualifies the development secure profile end to end. This is deliberately separate from the
# container health check: liveness must not require a PCC private key, while PCEPS readiness cannot
# honestly be inferred from a raw TCP connect.
set -euo pipefail

CERT_DIR="${VORTEX_CERT_DIR:-deploy/certs}"
CERT_PASSWORD="${VORTEX_CERT_PASSWORD:?Set VORTEX_CERT_PASSWORD used to generate the development stores}"
PROBE_API_KEY="${VORTEX_SECURE_PROBE_API_KEY:?Set VORTEX_SECURE_PROBE_API_KEY to one configured API credential}"
API_HOST="${VORTEX_SECURE_API_HOST:-localhost}"
API_PORT="${VORTEX_SECURE_API_PORT:-8080}"
PCEP_HOST="${VORTEX_SECURE_PCEP_HOST:-127.0.0.1}"
PCEP_PORT="${VORTEX_SECURE_PCEP_PORT:-4189}"

for command in keytool openssl python3 curl; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "Required command not found: $command" >&2
    exit 2
  }
done
for store in api.p12 pcep-server.p12 pcc-alpha.p12; do
  [ -r "$CERT_DIR/$store" ] || {
    echo "Required development store is missing: $CERT_DIR/$store" >&2
    exit 2
  }
done

PROBE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/vortex-secure-probe.XXXXXX")"
cleanup() {
  find "$PROBE_TMP" -type f -delete 2>/dev/null || true
  rmdir "$PROBE_TMP" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

keytool -exportcert -rfc -alias api -keystore "$CERT_DIR/api.p12" \
  -storepass "$CERT_PASSWORD" -file "$PROBE_TMP/api.pem" >/dev/null
keytool -exportcert -rfc -alias pcep-server -keystore "$CERT_DIR/pcep-server.p12" \
  -storepass "$CERT_PASSWORD" -file "$PROBE_TMP/pcep-server.pem" >/dev/null
openssl pkcs12 -in "$CERT_DIR/pcc-alpha.p12" -clcerts -nokeys \
  -passin "pass:$CERT_PASSWORD" -out "$PROBE_TMP/pcc-alpha.crt" >/dev/null 2>&1
openssl pkcs12 -in "$CERT_DIR/pcc-alpha.p12" -nocerts -nodes \
  -passin "pass:$CERT_PASSWORD" -out "$PROBE_TMP/pcc-alpha.key" >/dev/null 2>&1
chmod 0600 "$PROBE_TMP/pcc-alpha.key"

curl -fsS --max-time 5 --cacert "$PROBE_TMP/api.pem" \
  "https://$API_HOST:$API_PORT/livez" >/dev/null
curl -fsS --max-time 5 --cacert "$PROBE_TMP/api.pem" \
  -H "X-API-Key: $PROBE_API_KEY" "https://$API_HOST:$API_PORT/api/v1/config" >/dev/null
if curl -fsS --max-time 5 "http://$API_HOST:$API_PORT/livez" >/dev/null 2>&1; then
  echo "FAIL: secure API accepted plaintext HTTP" >&2
  exit 1
fi

VORTEX_PROBE_TMP="$PROBE_TMP" VORTEX_PROBE_HOST="$PCEP_HOST" \
VORTEX_PROBE_PORT="$PCEP_PORT" python3 - <<'PY'
import os
import socket
import ssl

probe_dir = os.environ["VORTEX_PROBE_TMP"]
host = os.environ["VORTEX_PROBE_HOST"]
port = int(os.environ["VORTEX_PROBE_PORT"])


def receive_exact(connection, size):
    data = b""
    while len(data) < size:
        chunk = connection.recv(size - len(data))
        if not chunk:
            raise RuntimeError("PCEPS peer closed before the controller PCEP OPEN")
        data += chunk
    return data


trusted = ssl.create_default_context(cafile=f"{probe_dir}/pcep-server.pem")
trusted.load_cert_chain(f"{probe_dir}/pcc-alpha.crt", f"{probe_dir}/pcc-alpha.key")
with socket.create_connection((host, port), timeout=5) as raw:
    with trusted.wrap_socket(raw, server_hostname="vortex-pce") as connection:
        connection.settimeout(5)
        header = receive_exact(connection, 4)
        version = header[0] >> 5
        message_type = header[1]
        length = int.from_bytes(header[2:4], "big")
        if version != 1 or message_type != 1 or length < 4:
            raise RuntimeError(
                f"expected PCEP v1 OPEN, got version={version} type={message_type} length={length}"
            )
        receive_exact(connection, length - 4)

without_client = ssl.create_default_context(cafile=f"{probe_dir}/pcep-server.pem")
received_open = False
try:
    with socket.create_connection((host, port), timeout=5) as raw:
        with without_client.wrap_socket(raw, server_hostname="vortex-pce") as connection:
            connection.settimeout(5)
            received_open = bool(connection.recv(4))
except (ssl.SSLError, ConnectionError, OSError):
    pass
if received_open:
    raise RuntimeError("PCEPS listener sent a PCEP OPEN to a client with no certificate")
PY

echo "PASS: HTTPS verified, plaintext refused, trusted pcc-alpha received PCEP OPEN, certificate-less PCC refused"
