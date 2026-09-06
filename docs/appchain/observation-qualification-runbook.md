# Observation qualification runbook (preview)

Use this to collect evidence, not to waive a gate. Record exact commands,
artifact checksums, source commits, node configurations, generated public
identities, expected roots and failures in the Phase 5 report. Do not record
private signing keys, source credentials or spending-wallet material.

## Exact coordinated CI inputs

Yano's existing `integration.yml` workflow has an opt-in
`stage_inputs_only=true` dispatch mode. On a clean committed checkout it stages
the ordinary Maven publications and matching JVM ZIP, runs the packaged JVM
catalog smoke, and uploads `yano-inputs-<full-host-commit>`. It includes a
version/commit manifest and SHA-256 inventory. It does not publish to Sonatype,
create a GitHub release, or mark the other CI gates successful.

The version is the repository's base version without `-SNAPSHOT`, followed by
the first nine characters of the checked-out commit. Artifacts expire after
the server-reported `expires_at` deadline (the workflow requests 14 days,
but repository policy may shorten this); record that deadline and the run ID. This is temporary
qualification staging, not a permanent published release channel.

For example, dispatch on the reviewed host milestone branch:

```bash
gh workflow run integration.yml --repo bloxbean/yano \
  --ref milestone/adr-037-phase-5 -f stage_inputs_only=true
```

After that run succeeds, independently record its full source commit and run
ID. The Yano X `build.yml` dispatch accepts `scope=all`,
`yano_inputs_run_id=<host-run-id>` and
`yano_inputs_commit=<full-host-commit>`, with an optional matching
`yano_version`. Its preparation script verifies the repository, workflow,
successful completed run, exact commit, artifact name, manifest and checksums
before selecting the downloaded Maven repository and ZIP. It rejects missing
pins, failed/wrong-repository runs, conflicting version/URL inputs, unsafe
checksum paths and modified files. An artifact's own manifest is not an
independent trust pin.

The runner needs authenticated read access to the host Actions artifacts.
An authorization or expiry failure is a real qualification blocker: do not
fall back to an unrelated version, local source checkout, or unsigned proof
shortcut. Normal release/default inputs remain unchanged when no staging run
is selected. Maven Local is not used by this staging path.

## Local regression evidence

Run the host observation codecs, provider/evidence, scheduling/kernel, journal
crash/recovery, API and network tests. Include the 100k-subscription test and
the five-member/four-vote model with one Byzantine assumption. Then run normal
host tests and packaged JVM/native conformance. For Yano X, run all unit tests,
artifact inventory, JVM-only verification and exact-version distribution checks;
also execute repository integration/crypto and required connector/E2E gates.

The scale test is opt-in; ordinary `test` runs skip it. Execute it explicitly
and inspect the test result to confirm that it ran rather than skipped:

```bash
YANO_OBSERVATION_SCALE=1 ./gradlew :runtime:test \
  --tests '*ObservationKernelTest.recoversOneHundredThousandCommittedSubscriptionsWithBoundedOpening' \
  -PskipSigning=true --no-parallel
```

Preserve failed logs and record the actual fixes/reruns. An incremental pass
reuses prior validated tasks; do not describe it as a fresh full execution.
Retained settlement template reproduction uses its original pinned compiler;
current-compiler conformance remains a separate mandatory test dependency.

## Five-node Preprod experiment

Prepare a dedicated, new test directory and nonconflicting ports. Use five
distinct test validator identities, `n=5`, `q=4`, `f=1`, pinned genesis,
state/consensus/observation profiles and identical plugin catalog closure.
Use only offline-consistent copied L1 inputs or a clean sync specified in the
plan; never copy a live RocksDB directory or reset another retained cluster.
The `yano-sync-validation` workflow governs L1 sync/restart/rollback checks.

Source claims may be explicitly synthetic fixtures, but the Preprod L1 feed
must come from actual network sync, not synthetic already-applied test events.
For this ADR's qualification, retain the node's default L1 validation settings
and one public upstream, as selected by the operator. Record the effective
`upstreamValidationLevel`; a run reporting `none` is not evidence of full
header/ledger validation. Do not select `praos-ledger`, change L1 core, or
broaden the experiment into an L1 validator qualification. Check applied body
progress, durable replay and same-epoch nonce parity under those retained settings.
If the experiment needs anchors, settlement or any test-ADA spending,
identify the authorized test wallet and transaction scope first. No generic
observation test authorizes changing an existing anchor or production wallet.

At each finalized checkpoint compare all nodes at the same height: block hash,
root, genesis/profile identities, membership, capability manifest and finality
certificate validity. Verify retrieved state proofs through the SDK under
independent trust pins. Retain evidence of:

1. Baseline success and proposer rotation across all five nodes.
2. Delayed/partitioned reports followed by healing and catch-up.
3. A faulty proposer's omission followed by honest certificate assembly/inclusion.
4. One equivocating reporter and different sufficient honest subsets yielding
   the same result ID; source disagreement/unavailability expiring without a fork.
5. Membership transition between rounds, with each round keeping its opening set.
6. Graceful and abrupt process restarts, retained signing claims, cursor/root
   parity, durable L1 replay and unchanged epoch nonce at the same boundary.
7. Long-running cadence with absent wake hints and measured worker, memory,
   journal, pending-report and certificate-amplification bounds.

Run the recovery drill on a dedicated copy and retain before/after diagnostics.
A deep finalized L1 rollback, missing bodies, nonce mismatch, unexplained root
divergence, unbounded resource growth or lost signing history stops the run.
Do not clear a failure marker simply to resume traffic.

## Graduation decision

The report must link actual evidence for every ADR-037 test-matrix row and
identify source-truth assumptions and remaining limits. Obtain independent
code/protocol review and all required remote CI results. Only then consider a
separate explicit graduation/default-policy change. This runbook and local
successful tests alone do not change preview or disabled-by-default status.
