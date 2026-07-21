# App-chain composite profile governance

This runbook is for operators evolving a long-lived deterministic composite
state machine under ADR-015. The short rule is:

> Deploy the dormant target code everywhere first; authorize one exact profile
> and activation height on-chain second.

Profile governance changes deterministic application composition. It is not a
plugin hot-reload mechanism and it does not dynamically assemble components
from YAML. A reviewed composite bundle owns a bounded catalog of executable
profiles. Governance can select only an exact canonical profile already in that
catalog.

## Start a governed chain

Governed mode is a consensus-affecting choice for a new chain:

```properties
yano.app-chain.chains[0].state-machine=composite
yano.app-chain.chains[0].machines.composite.preset=evidence-v1-gated
yano.app-chain.chains[0].membership.mode=governed
yano.app-chain.chains[0].machines.composite.profile-mode=governed
yano.app-chain.chains[0].machines.composite.profile-governance.min-activation-lag=20
yano.app-chain.chains[0].machines.composite.profile-governance.proposal-ttl-blocks=600
yano.app-chain.chains[0].machines.composite.profile-governance.max-epochs=1024
```

Do not turn a retained `fixed` chain into `governed` by editing this setting.
The authenticated governance configuration and epoch-0 marker make that fail
closed. Start a new chain or, while Yano remains preview and history is
disposable, reset that chain's app-state on every member.

The stock bundle currently contains one executable preset per selected stock
composition. It exercises governed genesis, proofs, status, packaging, and
operations, but it cannot authorize a profile that is not in its catalog. A
domain bundle enables real evolution by packaging the current and dormant
target `CompositeProfileCatalog.Entry` values together.

One v1 bundle may contain 1-64 distinct canonical profiles. `max-epochs` bounds
the total authenticated epoch history, including transitions back to an already
packaged profile; it does not raise that 64-profile catalog bound. Keep every
historical profile in the catalog. V1 has no authenticated replay floor that
would make removing old executable code safe.

## Prepare the target bundle

Before proposing anything:

1. Build a composite provider whose catalog contains both the active profile
   and the target profile, including every component/workflow generation
   present in retained epoch history or needed for late effect results.
2. Run replay, restart, snapshot, effect-result, quota-drain, and profile
   golden-vector tests for that catalog.
3. Export the exact `CompositeProfile.canonicalBytes()` for the target as a
   binary review artifact. Its domain-separated digest is the proposal target.
4. Deploy the same manifested bundle to every JVM member, or include it before
   building every native executable.
5. Rolling-restart one member at a time. A member without the target may keep
   following the old profile, but it cannot honestly attest readiness.

JVM plugin JARs may be copied through the documented plugin-directory flow.
Native applications cannot load a new JAR dynamically; rebuild the native
executable with the bundle included.

## Inspect and encode commands

The distribution includes the dependency-free operator helper:

```bash
cd appchain-cluster
export YANO_APPCHAIN_API_KEY='<member full key>'

./profile-governance.py --url http://127.0.0.1:7070 \
  --chain evidence-chain status
```

Prefer `YANO_APPCHAIN_API_KEY` or `--api-key-file`; do not place a key directly
on the command line. A non-default baked API prefix can be supplied with
`--api-prefix`. The CLI rejects every HTTP redirect so a full administration key
cannot be forwarded to another origin. Use HTTPS for every non-loopback member
endpoint and terminate it only at an operator-trusted proxy.

The helper emits canonical CBOR and can submit or dry-run each command. For
example, prepare `BEGIN` from the exact target profile artifact:

```bash
./profile-governance.py --chain evidence-chain begin --encode-only \
  --proposal-id "$PROPOSAL_ID" \
  --base-digest "$ACTIVE_PROFILE_DIGEST" \
  --membership-digest "$MEMBERSHIP_EPOCH_DIGEST" \
  --profile target-profile.cbor \
  --activation-height 1200 --expiry-height 1500
```

The output contains `targetProfileDigest`, `chunkCount`, `proposalHash`, and
`bodyHex`. Remove `--encode-only` to submit; add `--dry-run` to invoke all local
validation without submitting. For online submit/dry-run, `--membership-digest`
may be omitted and the CLI derives the authoritative
`profileGovernance.currentMembershipDigest` from node status. Offline
`--encode-only` requires the digest explicitly. Always verify that the same
membership epoch remains effective through the planned activation height.

Stage each reported chunk and seal the proposal, waiting for each command to
finalize before the next step:

```bash
./profile-governance.py --chain evidence-chain chunk \
  --proposal-id "$PROPOSAL_ID" --profile target-profile.cbor --index 0

./profile-governance.py --chain evidence-chain seal \
  --proposal-id "$PROPOSAL_ID"
```

Repeat `chunk` for indices `0 .. chunkCount-1`. Conflicting or duplicate
non-identical chunks void the proposal; identical replay is a no-op.

## Approve, attest readiness, and activate

Approval and readiness are deliberately separate:

- threshold member approvals authorize the exact intent;
- every member in the bound membership epoch must attest that the exact target
  digest exists in its local executable catalog.

Each approving member submits with its own node/member key:

```bash
./profile-governance.py --url http://member-a:7070 --chain evidence-chain \
  approve --proposal-hash "$PROPOSAL_HASH"

./profile-governance.py --url http://member-a:7070 --chain evidence-chain \
  ready --proposal-hash "$PROPOSAL_HASH" --target-digest "$TARGET_DIGEST"
```

`READY --dry-run` is the final local diagnostic. It fails when that node's
catalog lacks the target. Once the threshold and all-member readiness are
finalized, status becomes `SCHEDULED`. Every block before `H` uses the old
profile; block `H` uses the target for results, expiries, workflows, and normal
messages. There is no local break-glass override.

Cancel before activation when necessary:

```bash
./profile-governance.py --chain evidence-chain cancel \
  --proposal-hash "$PROPOSAL_HASH"
```

Threshold cancellations void a proposal. It is also voided by expiry, a bound
membership-epoch change, a stale base profile, malformed/conflicting staging,
or invalid profile compatibility/quota rules.

## Observe and verify

The chain status/UI projects the active epoch, digest, proposal state,
activation height, approvals, readiness, local readiness, and retired drains.
Prometheus exports:

- `yano.appchain.composite.profile.epoch`;
- `yano.appchain.composite.governance.proposal.state` (`0` none, `1` staging,
  `2` sealed, `3` scheduled);
- `yano.appchain.composite.governance.approvals`;
- `yano.appchain.composite.governance.readiness`;
- `yano.appchain.composite.governance.local.ready`; and
- `yano.appchain.composite.retired.drains`.

Health reports `{chain}.scheduledProfileMissing=true` and goes down if a node
has a scheduled target absent from its local catalog. This is a last-resort
alarm, not permission to schedule before all members are ready.

Proof clients have two policies:

- strict clients verify an MPF proof for the active marker at a trusted
  finalized root, then pin its expected digest; and
- governance-aware clients verify finality and MPF inclusion for the current
  epoch pointer, every retained epoch record, and the active marker at the same
  root. They then check byte linkage with
  `CompositeProfileEpochChainVerifier.verifyStructure(...)` and independently
  enforce their membership/approval policy from finalized block history.

The dependency-free structural verifier does not fetch proofs or prove member
authorization; it only checks the already-proven bytes supplied by the caller.
For a complete offline trust-boundary check, use
`yano-appchain-composite-client`'s `GovernedCompositeVerifier`: it first verifies
portable block finality against caller-pinned membership, then verifies the
pointer, every epoch, and the marker as MPF inclusions at that exact root,
checks canonical linkage, and finally invokes the caller's mandatory
membership/approval-history policy. Applications with a fully reviewed
proposal schedule can use `requirePinnedProposalHashes(...)`; it fails unless
every non-genesis epoch has exactly the expected proposal hash, with no missing
or extra epoch. Neither policy makes unreviewed component code trustworthy.

## Recovery rules

- Missing bundle before readiness: deploy it and retry local dry-run/`READY`.
- Membership changes before activation: the proposal is void; repropose against
  the new membership epoch.
- Missed activation after falsely claiming readiness: restore the exact bundle
  and catch up from finalized history; do not edit profile state.
- Bad profile already activated: authorize a corrected future epoch. Never
  rewrite the marker, epoch records, or local database to create a hidden fork.
- Lost governance threshold: use the membership-governance recovery policy
  first; profile governance cannot bypass membership authority.

See [ADR-015](../adr/app-layer/015-governed-composite-profile-evolution.md) for
the complete consensus contract.
