# Secure deployment profile

The base `docker-compose.yml` publishes both ports in plaintext on host loopback. That is
defensible for a local demonstration and unsuitable for a management plane anything else can
reach: the API key authorises solving, cancellation and capacity mutation, and it crosses the
network on every request.

This profile turns on TLS for both planes.

```bash
export VORTEX_CERT_PASSWORD="$(openssl rand -base64 32)"
./deploy/generate-dev-certificates.sh          # development stores only
export VORTEX_API_KEY=...                      # or set VORTEX_API_PRINCIPALS instead
docker compose -f docker-compose.yml -f docker-compose.secure.yml up -d
export VORTEX_SECURE_PROBE_API_KEY="$VORTEX_API_KEY"
./deploy/verify-secure-profile.sh
```

## Certificates are generated, never committed

Set a non-public `VORTEX_CERT_PASSWORD`, then run `deploy/generate-dev-certificates.sh` before
first use. It writes `deploy/certs/`, which is gitignored. The generator and Compose overlay refuse
to run without an explicit password; there is no reusable default credential.

The stores are deliberately absent from the repository. An earlier version of this profile
committed them — development certificates with a published password, which sounds harmless and is
not: a keystore in the history is one somebody will reuse, and a private key that has been public
cannot be made private again by deleting the file. They are cheap to regenerate and expensive to
un-publish, so the generator is the only source.

## What it changes

| | base | secure profile |
|---|---|---|
| Northbound API | HTTP; `X-API-Key` in the clear | HTTPS, plain HTTP refused |
| PCEP | plain TCP, peer asserts its identity | PCEPS, peer must present a trusted client certificate |
| Peer allowlist | source addresses | JSON array of canonicalized certificate subjects |

`deploy/verify-secure-profile.sh` verifies the built stack rather than inferring security from a
listening port: HTTPS answers `200` with certificate validation, plain HTTP is refused, the
generated trusted and permitted PCC receives a PCEP OPEN over mutual TLS, and a PCC presenting no
certificate receives no PCEP session. `VORTEX_SECURE_PROBE_API_KEY` must name one credential already
configured on the API. The container health check is deliberately HTTPS process liveness only; it
does not claim that an external PCC can authenticate.

With PCEPS, `VORTEX_PCEP_PERMITTED_PEERS` is a JSON array because an X.500 distinguished name
contains commas. For example:

```bash
export VORTEX_PCEP_PERMITTED_PEERS='["CN=pcc-alpha,OU=development-only,O=VortexPCE"]'
```

Without PCEPS, the same variable retains its comma-separated IP-address syntax.

## Certificates

`generate-dev-certificates.sh` writes development stores whose subjects say
`OU=development-only`. A real deployment issues them from its own authority.

The stores are mounted read-only. Passwords are read once at startup, so any mechanism that lands
them in the process environment works — a Compose `env_file`, a secret manager's injector, or a
mounted file sourced by an entrypoint. Do not commit real stores or passwords to the repository.

**Rotation.** Certificates and API credentials are read at startup. The supported deployment is a
single controller, so either configuration change requires a controlled restart. Several API
principals may overlap, preventing a client lockout while clients move from the old key to the new
one; this does not eliminate the controller restart interval.

For a principal-only deployment, leave `VORTEX_API_KEY` unset and provide at least one named
principal:

```bash
unset VORTEX_API_KEY
export VORTEX_API_PRINCIPALS='old:ADMIN::old-secret,new:ADMIN::new-secret'
docker compose -f docker-compose.yml -f docker-compose.secure.yml up -d --force-recreate vortex-controller
```

Verify both keys, move clients, remove the old entry from `VORTEX_API_PRINCIPALS`, recreate the
single controller, and verify that the old key returns `401` while the new key succeeds. Supply
real secrets through an environment file or secret-manager injector rather than command history.

For development certificate replacement, generate into a new directory rather than reusing the
existing one (the generator intentionally preserves stores already present):

```bash
./deploy/generate-dev-certificates.sh deploy/certs.next
```

Validate the new stores, retain the current directory for rollback, replace `deploy/certs`, recreate
the controller, and run `deploy/verify-secure-profile.sh`. If qualification fails, restore the prior
directory and recreate the controller. Production issuance, renewal and revocation remain external
responsibilities; this development procedure is not a certificate lifecycle service.

## What to back up

State lives in the `vortex-state` volume, mounted at `/var/lib/vortex`:

| File | Holds | If lost |
|---|---|---|
| `state.json.wal` | reservations and installation intents, transactionally | the controller starts with an empty ledger and re-admits capacity that is already committed on the network. **This is the file that matters.** |
| `observed-capacity.json` | measured link capacities with provenance | links revert to their declared capacity, so a degraded link is planned against optimistically |
| `pending-dispatch.json` | route and rate for installations awaiting a PCC | those intents stay `PLANNED` holding capacity and can never be installed; an administrator must cancel them |

Back up the volume as a unit while the controller is stopped, or from a filesystem snapshot. The
sidecars are **not** transactional with the WAL, so a backup that captures them at different
moments can restore a ledger that disagrees with its capacities, its pending work or its
ownership. None of those disagreements can over-commit a link — an observation below committed
capacity is refused, and unknown ownership denies — but they can strand work that needs an
administrator to clear.

## What this profile does not provide

- **No high availability in this profile.** One active controller. The reservation log takes a
  local file lock that is explicitly not leader election; the experimental PostgreSQL clustered
  mode (see the README) is not part of this profile. Two replicas over one volume are refused by the lock; two over
  separate volumes would admit against separate ledgers and double-book the network.
- **No Kubernetes manifests.** Supplying untested manifests for an application with no HA story
  would imply a clustered deployment that is not supported. The single-replica shape this profile
  describes translates directly to a `StatefulSet` with one replica, a `ReadWriteOnce` volume and
  the stores as mounted secrets, but that has not been tested here and is not shipped as though it
  had been.
- **No certificate lifecycle.** No issuance, renewal or revocation. Expiry is a restart-time
  failure, and the controller fails closed rather than falling back to plaintext.
