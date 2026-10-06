# Release Upgrade and Rollback Runbook

This runbook defines the safe operator boundary for a VortexPCE release. It does not turn a local
image into a release: the source tag, published image digest, vulnerability scan, SPDX SBOM and
verified provenance must all identify the same artifact before deployment.

## Supported release boundary

- Deploy only an immutable registry reference of the form `registry/repository@sha256:<digest>`.
- Never use `latest`, a mutable version tag, a local image ID, or an unverified archive for an
  upgrade or rollback decision.
- Record the current and prior release digests **before** maintenance begins.
- Record which state backend is in use. File/WAL and PostgreSQL state are different operational
  boundaries and must not be interchanged during a rollback.
- A release pair is compatible only after the prior release's state has been opened by the current
  release and then reopened by the prior release in a non-production qualification copy.
- Unknown state formats fail closed. Deleting or resetting state is recovery from data loss, not a
  successful rollback.

Version `1.0.0` is the inaugural release candidate. It has no prior VortexPCE release digest and
therefore cannot claim a demonstrated product rollback. After `1.0.0` is qualified and accepted as
known-good, it may be preregistered as the prior digest for a later compatible patch release.

## Required maintenance record

Complete this record before touching the running service:

| Field | Required value |
|---|---|
| Change/request ID | Operator-controlled identifier |
| Current release | Semantic version, source tag and immutable digest |
| Candidate release | Semantic version, source tag and immutable digest |
| Prior rollback release | Semantic version and immutable digest; must already be known-good |
| Attestation verification | Repository/workflow identity and verified subject digest |
| SBOM and scan | Artifact hashes; scanned/SBOM digest must equal candidate digest |
| State backend | `file-wal` or `postgresql` |
| State location | Explicit volume/path or database/cluster identifier |
| Backup identity | Snapshot/backup ID, creation time and integrity hash |
| Compatibility result | Evidence ID for prior → candidate → prior qualification |
| Smoke workload | Topology hash, request hash and expected result hash |
| Abort deadline | Maximum unavailable/validation time |

Stop before deployment if any field is absent, any digest differs, the attestation fails, a fixable
unexcepted HIGH/CRITICAL vulnerability remains, or the release pair has not passed compatibility.

## Pre-upgrade procedure

1. Quiesce new northbound submissions and wait for the declared in-flight-operation policy to
   complete. Record nonterminal intent and reservation counts.
2. Verify `/livez`, `/readyz`, `/clusterz` where applicable, and the authenticated metrics/config
   endpoints. Save responses with the maintenance ID.
3. Execute the canonical smoke request against the current release and save its response hash.
4. Back up the state backend using its supported mechanism:
   - for file/WAL, stop the writer before copying the complete state path, its `.wal`, ownership
     sidecars and pending-dispatch files as one snapshot;
   - for PostgreSQL, use a transactionally consistent database backup and record the database
     system/timeline identity.
5. Verify the backup can be read in an isolated restoration check. A backup that was merely created
   but not verified is not a rollback control.
6. Capture the running container's immutable digest and confirm it equals the preregistered current
   digest. Stop if it does not.

## Upgrade procedure

1. Stop the current process cleanly and retain the original state and backup. Do not run two
   file/WAL writers against one path.
2. Start the candidate by its preregistered immutable digest with the same state backend, topology,
   identity and security configuration. Do not build during deployment.
3. Enforce the abort deadline while polling liveness and readiness. Readiness must not be inferred
   from a running container alone.
4. Verify that the observed running digest equals the candidate digest.
5. Confirm the pre-upgrade committed task/reservation set and canonical state hash.
6. Run the canonical smoke request and require the expected result, no leaked reservation, and no
   unexpected nonterminal intent.
7. If every check passes, reopen submissions and record the upgrade as accepted. Retain the prior
   digest and backup for the declared rollback window.

## Rollback procedure

Rollback is permitted only to the preregistered prior digest. Do not choose a different image after
an upgrade fails.

1. Quiesce submissions and capture candidate logs, health, metrics, running digest and state
   identity before stopping it.
2. Stop the candidate cleanly when possible. If it is unresponsive, record the forced-stop reason
   and preserve the state before proceeding.
3. Start the prior release by its immutable digest against the state produced by the candidate.
   Restore the verified pre-upgrade backup only if the registered compatibility procedure requires
   it; record that as backup restoration rather than in-place compatibility.
4. Enforce the rollback deadline while polling liveness and readiness.
5. Verify the running digest equals the registered prior digest.
6. Require the canonical committed-state hash and smoke result to match their expected values.
7. Confirm zero duplicate/lost reservations, zero unexpected nonterminal intents, and the expected
   topology/config identity before reopening submissions.

If the prior release rejects the state, fails readiness, or changes the canonical result, keep
traffic closed, preserve evidence, and invoke backup restoration. Never delete state to make the
older process start.

## Completion and evidence

An upgrade or rollback is complete only when all of the following are recorded together:

- source tag and commit;
- published, installed, scanned, SBOM and attested digest equality;
- prior and candidate runtime digest observations;
- backup/restore verification result;
- liveness/readiness timeline and recovery time;
- pre/post state and smoke-result hashes;
- intent/reservation leak checks; and
- teardown result for the isolated qualification environment.

Do not report rollback success for the inaugural release, a dry run without the actual prior
digest, a start against empty state, or a recovery that discarded acknowledged state.
