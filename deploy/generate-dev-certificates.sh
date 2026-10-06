#!/usr/bin/env bash
# Generates a development certificate authority and the key stores the secure profile mounts.
#
# Development only, and the script says so in the certificate subjects. A real deployment issues
# these from its own authority with a rotation process; the point here is that the secure profile
# can be brought up and tested by anyone, so "TLS is supported" is a claim that can be checked
# rather than taken on trust.
set -euo pipefail

OUT="${1:-deploy/certs}"
PASS="${VORTEX_CERT_PASSWORD:?Set VORTEX_CERT_PASSWORD to a non-public development password}"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool

mkdir -p "$OUT"
cd "$OUT"

generate() {
  local alias="$1" cn="$2" san="$3"
  [ -f "$alias.p12" ] && return 0
  "$KEYTOOL" -genkeypair -alias "$alias" -keyalg RSA -keysize 2048 \
    -dname "CN=$cn, OU=development-only, O=VortexPCE" -validity 365 \
    ${san:+-ext "SAN=$san"} \
    -storetype PKCS12 -keystore "$alias.p12" -storepass "$PASS" -keypass "$PASS"
  "$KEYTOOL" -exportcert -alias "$alias" -file "$alias.cer" \
    -keystore "$alias.p12" -storepass "$PASS"
}

trust() {
  local store="$1"; shift
  for cert in "$@"; do
    "$KEYTOOL" -importcert -noprompt -alias "$cert" -file "$cert.cer" \
      -keystore "$store.p12" -storetype PKCS12 -storepass "$PASS" >/dev/null
  done
}

generate api          localhost      "dns:localhost,ip:127.0.0.1"
generate pcep-server  vortex-pce     "dns:vortex-pce,ip:127.0.0.1"
generate pcc-alpha    pcc-alpha      ""

[ -f pcep-truststore.p12 ] || trust pcep-truststore pcc-alpha
[ -f pcc-truststore.p12 ]  || trust pcc-truststore  pcep-server

chmod 0600 ./*.p12

cat <<SUMMARY
Wrote development key stores to $(pwd):

  api.p12              northbound HTTPS certificate (CN=localhost)
  pcep-server.p12      controller's PCEPS certificate
  pcep-truststore.p12  the PCC certificates the controller will accept
  pcc-alpha.p12        a client certificate for testing, subject CN=pcc-alpha
  pcc-truststore.p12   the controller certificate a PCC should trust

Permitted peer for this set: CN=pcc-alpha, OU=development-only, O=VortexPCE
SUMMARY
