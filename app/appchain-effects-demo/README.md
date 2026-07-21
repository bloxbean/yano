# ADR-013 evidence-effects demo

This directory is the Milestone 1 deployment for ADR-013. It runs one
three-member `evidence-chain`, a single effect owner, Kafka, RustFS, Kubo, and
the deployment-neutral evidence runner. The included inspection certificate
demonstrates the complete no-code path:

1. stage one immutable input in `evidence-staging`;
2. commit an evidence command in the app chain;
3. copy and verify the object in the versioned archive;
4. pin its CID in Kubo;
5. incorporate the signed results under the app-chain state root;
6. publish `evidence.available.v1` to Kafka, either through the default
   idempotent continuation command or directly from the second incorporated
   storage result in the activated profile; and
7. verify state proof, threshold finality, S3 version, exact CID bytes, Kafka
   acknowledgement, and the Cardano state-thread output/inline datum in one
   report.

Devnet is the automatic local profile. Preview and preprod are opt-in public
profiles; they start unanchored unless an operator supplies both a funded key
file and an exact network confirmation. Mainnet requires an additional guard,
forbids demo anchoring, and performs no automatic value movement. The launcher
generates and retains distinct member keys, a devnet anchor key, API keys, and
connector credentials for each instance and deployment. No universal signing
key is checked in or reused by a public profile.

ADR-013 Milestone 1 is **ACCEPTED**. Its connector fault matrix, three-member
devnet failover, retained restart, Compose/host parity, packaging, cleanup, and
independent release reviews passed. Live preview/preprod runs remain
operator-authorized supplemental evidence rather than an automatic test.

## Quick start (Docker Compose)

Prerequisites are JDK 25, Docker with Compose v2, `curl`, `jq`, `openssl`, and
Python 3. From this directory:

```bash
./demo.sh prepare
./demo.sh up
./demo.sh run
./demo.sh status
./demo.sh stop
```

`run` is the safe guided command: it publishes version 1 when the selected ID
is absent, performs read-only verification when retained state matches the
supplied bytes and connector identities, and returns `REPUBLISH_REQUIRED`
before any connector write when the bytes differ. Use the following updated
ADR-018/020 walkthrough for a realistic lifecycle and capacity demonstration
while the same three nodes remain running:

```bash
# Start one retained cluster for every command below.
./demo.sh prepare --instance manual-demo
./demo.sh up --instance manual-demo

# Publish two independent products/evidence identities.
./demo.sh publish --instance manual-demo \
  --evidence-id inspection-product-a \
  --sample-file samples/inspection-certificate.json
./demo.sh publish --instance manual-demo \
  --evidence-id inspection-product-b \
  --sample-file samples/inspection-certificate-product-b.json

# Create the exact next immutable version; version 1 remains queryable.
./demo.sh republish --instance manual-demo \
  --evidence-id inspection-product-a --business-version 2 \
  --sample-file samples/inspection-certificate-product-a-v2.json

# Verify latest/history without writes, then explicitly demonstrate replay.
./demo.sh verify --instance manual-demo --evidence-id inspection-product-a
./demo.sh verify --instance manual-demo \
  --evidence-id inspection-product-a --business-version 1
./demo.sh replay --instance manual-demo \
  --evidence-id inspection-product-a --business-version 2 \
  --sample-file samples/inspection-certificate-product-a-v2.json

# Publish eight more independent evidence records. Lifecycle is the default:
# each worker completes one whole workflow before taking another ID.
./demo.sh load --instance manual-demo \
  --count 8 --concurrency 3 --id-prefix lifecycle-july \
  --sample-file samples/inspection-certificate.json

# Exercise the committed eight-release capacity. Different evidence IDs can
# occupy prepare, prerequisite, approval, release, effects, and verify stages.
./demo.sh load --instance manual-demo --load-mode pipeline \
  --count 8 --concurrency 8 --max-in-flight 8 \
  --id-prefix pipeline-july \
  --sample-file samples/inspection-certificate.json

# Independently re-read connectors and verify the last pipeline item.
./demo.sh verify --instance manual-demo \
  --evidence-id pipeline-july-000008 --business-version 1

# Inspect individual evidence and aggregate load reports.
open http://127.0.0.1:7080/
./demo.sh status --instance manual-demo

./demo.sh stop --instance manual-demo
```

Use a fresh `--id-prefix` for every load. To generate a longer functional soak
after the eight-item capacity demonstration, increase `--count` up to 50,000
and choose `--max-in-flight` up to 5,000; concurrency remains bounded to 16.
Active and queued work stays bounded, but completed item summaries and
per-evidence report files grow with `count`. Start small because L1-gated
devnet runs intentionally wait for real effect results, proofs, and anchors
rather than measuring raw HTTP admission.

The maximum single-invocation soak shape is:

```bash
./demo.sh load --instance manual-demo --load-mode pipeline \
  --count 50000 --concurrency 16 --max-in-flight 5000 \
  --id-prefix soak-july \
  --sample-file samples/inspection-certificate.json
```

This can run for a long time and create 50,000 evidence reports plus external
objects. Use a fresh prefix and monitor disk, memory, connector health, block
lag, and anchor lag. It is a workflow soak, not a promise of 5,000 simultaneous
mempool messages.

The launcher bounded-reads the selected file into a private operation copy;
Compose mounts only that exact copy read-only. `verify` takes no sample and
does not submit a message, stage data, add or pin content, publish Kafka data,
or request an anchor. The Evidence Explorer keeps the newest published
business version in a stable overview, lists retained evidence/version entries
in newest-first server-side pages of 20 separately from recent scenario
activity, and opens a selected version in a modal that polling or page changes
cannot replace with a newer record. Search is also evaluated across the
retained catalog rather than only the visible page. Business version is an
explicit chain field; changing a JSON property such as `batchId` does not
increment it.

After the runner has re-downloaded the immutable object version and proved
that the pinned IPFS CID contains the same bytes, it stores an owner-readable,
bounded presentation copy beside the credential-free report. The detail modal
pretty-prints JSON up to 256 KiB and independently checks the displayed UTF-8
bytes against the reported SHA-256 in the browser. The evidence-UI container
still receives no S3, IPFS, Kafka, Yano API, member, or anchor credential.
Evidence reports created before this viewer was installed remain selectable;
run the read-only `verify` command once for that ID/version to materialize its
verified document preview. New `publish`/`republish` runs create the same
verified presentation copy automatically after their normal object-store and
IPFS re-read, so `verify` is not a routine UI prerequisite.

`load` performs normal version-1 publications with IDs such as
`load-july-000001`; it is not a bulk state-machine bypass. Each item waits for
finality, effects, connector re-reads, and proof verification. Count is bounded
to 1..50000 and concurrency to 1..16. The command exits nonzero if any item
fails and writes a credential-free aggregate load report shown in the Evidence
UI. Reusing a completed prefix demonstrates immutable-ID rejection rather than
resuming or overwriting the batch.

`--load-mode lifecycle` is the default: each worker completes one entire item
before taking its next ID. `--load-mode pipeline` uses bounded prepare,
prerequisite, approval, release, effects, and verify queues, so different IDs
occupy different stages simultaneously. `--max-in-flight` bounds all queued and
active pipeline items; its default is derived from worker count and the profile
capacity. A stage failure terminates only that item and is named in the report.
Pipeline mode requires the demo's stock `composite` state machine; it is not
available with the standalone `evidence-registry` compatibility machine.

An in-flight item is an evidence workflow, not one mempool message. Each
workflow submits several dependency-ordered messages and waits for stage
finality. Therefore `--max-in-flight 5000` bounds queued/active workflow
pressure but does not imply 5,000 simultaneous mempool messages.

The stock demo profile commits capacity for eight releases and eight
notifications per block. Its canonical quotas reserve 32 of the configured 128
effect slots. Fair eight-permit release and notification gates are held through
message finality, preventing a client burst from exceeding the authenticated
profile while allowing actual multi-release blocks. Connector execution,
result incorporation, external reads, proof checks, and both lane types still
overlap. L1-gated devnet loads also share a throttled force-anchor coordinator,
so a finite batch does not depend on unrelated future traffic. The ordinary
64-message block cap is unchanged.

The aggregate schema-v2 report separates app messages/second, release and
verified-evidence workflows/second, verified effects/second, per-stage
completion rates and latencies, and end-to-end p50/p95 latency. This is still a
functional capacity/soak runner, not a hardware-independent benchmark claim.

Load is intentionally refused for a public anchor-enabled profile to prevent
accidental preview/preprod transaction spend; the disposable devnet script
anchor remains supported.

The default `explicit` profile preserves the Milestone 1 continuation command.
To start a fresh chain with direct result-to-effect continuation enabled from
height 1, pass the same immutable option to every command:

```bash
./demo.sh prepare --instance direct-demo --continuation direct
./demo.sh up --instance direct-demo --continuation direct
./demo.sh load --instance direct-demo --continuation direct \
  --load-mode pipeline --count 8 --concurrency 8 --max-in-flight 8 \
  --id-prefix direct-july \
  --sample-file samples/inspection-certificate.json
./demo.sh verify --instance direct-demo --continuation direct \
  --evidence-id direct-july-000008 --business-version 1
./demo.sh stop --instance direct-demo --continuation direct
```

The continuation mode is part of the retained app-chain identity. It cannot be
changed in place after an instance is prepared; use a new instance and chain
identity to change profiles.

ADR-018 completed republish in the still-unreleased gated v1 workflow and
therefore replaced its preview profile digest. Instances prepared with the
earlier incomplete digest are intentionally not migrated: stop them and use a
fresh instance/chain (or retire their disposable preview data) before testing
these lifecycle commands.

The default for each newly prepared demo instance is the stock
`evidence-v1-gated` deterministic composite workflow. It admits evidence
creation and republish only through the registry + approval + release workflow.
The `evidence.command.v1` topic accepts only the canonical post-publication
notification command. Run it without application code:

```bash
./demo.sh prepare --instance composite-demo
./demo.sh up --instance composite-demo
./demo.sh run --instance composite-demo
./demo.sh stop --instance composite-demo
```

This profile first commits a registry identity, proposes and records an
approval for the exact evidence command, and then submits
`evidence.release.v1`. The composite applies the document-trail append and
evidence submission atomically before the existing S3, IPFS, Kafka, proof, and
anchor checks continue. The marker pins `provider=composite` and
`preset=evidence-v1-gated`; this is a distinct committed profile/digest for new
chains. Switching retained state between standalone, direct, and gated
profiles is rejected. The standalone machine remains explicitly selectable
with `--machine standalone` as a regression/migration fixture.
`--continuation explicit|direct` remains independently selectable for a fresh
composite instance.

The demo's committed `evidence-capacity-per-block=8` is part of the composite
profile digest and immutable instance identity. It is not a live local tuning
knob. A different capacity requires a packaged governed target profile for a
retained production chain, or a fresh disposable preview/demo chain.

## Role-aware evidence and actor recovery

ADR-019 adds the separate `role-evidence` stock machine. It preserves the
evidence/connectors flow but replaces member-count approval with portable
business-actor signatures under a governed policy: manufacturer proposer, two
auditors from distinct organizations, and one regulator. The relay member and
business approver remain distinct in the report and UI.

Use a fresh instance and pass the immutable machine selection to every
command:

```bash
./demo.sh up --instance role-demo --machine role --continuation direct

./demo.sh publish --instance role-demo --machine role --continuation direct \
  --evidence-id role-inspection-001 \
  --sample-file samples/inspection-certificate.json

./demo.sh verify --instance role-demo --machine role --continuation direct \
  --evidence-id role-inspection-001

./demo.sh role-lifecycle --instance role-demo --machine role \
  --continuation direct

./demo.sh stop --instance role-demo --machine role --continuation direct
```

`publish` finalizes wrong-role, wrong-payload, and same-organization controls
as deterministic no-ops before the valid quorum releases the evidence. Its
read-only audit verifies the exact policy, historical actor/organization
revisions, decision signatures, state proofs, effects, connector data,
finality, and configured anchor gate.

`role-lifecycle` is an idempotent recovery exercise on a dedicated
`recovery-probe` actor. It governs onboarding, rotates to a new key, proves a
stale revision cannot authorize while the new revision can, revokes the actor,
proves the revoked credential cannot authorize, cancels both probe proposals,
and verifies all three retained actor revisions plus the proposal trail with
root-matched MPF proofs. It never changes the five normal scenario actors.

Role mode uses five generated owner-only demo actor seed files mounted only
into the scenario runner, never into Yano member containers. This convenience
is for the isolated demo. Production actors sign the frozen contract in their
own application/KMS/HSM/Vault. Pipeline load remains a `composite`-profile
capacity tool and is deliberately not offered for role mode.

The demo starts new composite chains in ADR-015 `governed` profile mode. Its
stock bundle contains only the selected profile, so the demo exercises
authenticated epoch 0, status, proofs, restart, JVM/Compose parity, and
operator observability—not a synthetic hot upgrade. Real evolution is supplied
by a domain composite bundle containing both active and dormant target catalog
entries; see the
[profile-governance runbook](../../docs/APP_CHAIN_PROFILE_GOVERNANCE.md).

`up` builds the exact working-tree Yano and runner images, starts all services,
waits through the producer warm-up, funds and bootstraps the devnet script
anchor, and executes a read-only readiness probe. `publish` and `republish`
perform explicit writes; `verify` is read-only; `replay` alone intentionally
advances finalized envelopes without changing authenticated business state or
external logical outcomes. Re-running `run` is safe and chooses publish or
verify, never an implicit replay.
`status` and `stop` do not prepare an absent instance or create data, runtime,
or secret directories.

Add the optional metrics stack with:

```bash
./demo.sh up --observability
```

The default loopback-only ports are:

| Surface | URL / address |
|---|---|
| Yano node 0 / 1 / 2 | `http://127.0.0.1:7070`, `:7071`, `:7072` |
| Evidence report UI | `http://127.0.0.1:7080/` |
| Kafka host listener | `127.0.0.1:9092` |
| RustFS S3 API | `http://127.0.0.1:9000` |
| Kubo RPC | `http://127.0.0.1:5001` |
| Prometheus (optional) | `http://127.0.0.1:9090/` |
| Grafana (optional) | `http://127.0.0.1:3000/` |

Ports and the connector subnet can be changed with the `DEMO_*` values listed
in `config/common.env`. Parallel environments require a distinct `--data-dir`
as well as distinct host ports and `DEMO_CONNECTOR_SUBNET`; one data root has a
single active shared-L1 lease per network/deployment. `--instance <name>` alone
provides sequential retained app-chain isolation under that lease. `./demo.sh config`
prints the fully resolved Compose model without printing a credential value.
Instance names must match `[a-z0-9][a-z0-9-]{0,31}`; underscores are rejected
so two names cannot normalize to the same Compose project or connector ids.
`DEMO_CHAIN_ID` must match `[a-z][a-z0-9-]{0,62}` and is rendered identically
into the node application config, scenario runner, and host anchor bootstrap.

Public profiles use the same launcher; do not invoke `docker compose` directly
with a network environment file because that bypasses identity, key, lease,
and consent checks:

```bash
# Public relay profile, APP_FINAL effects, no anchor or value movement
./demo.sh up --network preview --instance preview-demo

# Optional preview/preprod script anchor: both arguments are mandatory
./demo.sh up --network preview --instance preview-anchor \
  --anchor-key-file /secure/operator-funded-preview.seed \
  --confirm-public-anchor preview

# Guarded configuration/startup profile; demo anchoring remains forbidden
./demo.sh config --network mainnet --instance mainnet-check --enable-mainnet
```

The public anchor seed must be a launcher-owned, owner-only file containing
one 32-byte hexadecimal seed. Providing the file alone is not consent to
spend. Preview/preprod startup waits for all three L1 relays to complete their
initial sync before probing or bootstrapping an explicitly enabled anchor. An
enabled public anchor is scheduled after 30 app-chain blocks or, for a quiet
chain, no later than the profile's 60-minute maximum interval. The launcher
reports `WAIT_L1_SYNC` with each member's local/remote tip while synchronizing.
If an anchor wallet has no suitable pure-ADA UTxO, `WAIT_ANCHOR_FUNDS` prints
the address and continues reporting the funding wait instead of appearing to
hang. On preview/preprod, transaction-ready means at least two distinct
pure-ADA UTxOs (one of at least 5 ADA for collateral and another of at least
10 ADA for fees/output selection), with at least 20 ADA total reserve. A
single large UTxO is not sufficient because collateral is excluded from normal
input selection. The funding wait defaults to one hour and can be changed with
`DEMO_ANCHOR_FUND_TIMEOUT_SECONDS` (60–86400); it is intentionally separate
from the public L1 synchronization timeout.

## Security and ownership model

The Yano application already contains the deterministic stdlib, evidence, and
composite providers. Only the Kafka, S3, and IPFS connector implementations are
staged in the external plugin directory. Only `yano-0` joins the dedicated
connector network, and only its private node configuration enables
`object.put`, `ipfs.pin`, and `kafka.publish`. Nodes 1
and 2 independently validate the deterministic effects and incorporated
results but hold no connector credential and run no executor.

The local connector profiles deliberately accept only private numeric HTTP
origins. RustFS and Kubo therefore have fixed addresses on a dedicated bridge;
this is not a DNS bypass. Kafka uses its separately validated local broker
name. Connector, evidence-UI, and observability services remain on three
separate bridges. Those bridges intentionally are not Docker `internal`
networks: Docker Desktop cannot route their published ports to the host when
they are internal. Every published port instead binds to `127.0.0.1`; no
gateway, RustFS console, KRaft controller, Cardano N2N port, or Docker socket
is exposed.

Secrets are generated once per instance under:

```text
.demo-secrets/networks/<network>/<instance>/<deployment>/
```

The directory is mode `0700` and secret files are `0600`. Values are never put
in the rendered Compose environment, launcher/Yano command arguments,
committed app-chain state, or the report UI. The isolated one-shot S3
bootstrap process
reads mounted secret files and uses bounded SigV4 requests directly against
the private RustFS admin API; it has no shell or child credential-bearing
process. Generated node configuration is private because the current S3
executor SPI accepts credentials through protected configuration.
The generated member material is retained under `member-keys/`; raw seeds and
public keys are validated before reuse and bound into the immutable instance
marker. The devnet anchor seed is retained as `anchor.seed`. The full Yano API
key is retained only by the private node configuration and launcher for
privileged anchor bootstrap; scenario, connector initializer, and report UI
containers never receive it. The devnet demo leaves ordinary read and submit
operations unauthenticated.

RustFS has three effective principals:

- the built-in root is full administrator, but its credential is mounted only
  into RustFS and the one-shot bootstrap on the private connector network;
- the runner identity may write/read the staging prefix and read the archive;
- the executor identity may read staging and conditionally create/read archive
  versions.

There is deliberately no canned policy attached to the built-in root: RustFS
does not enforce such a policy on its owner identity, so presenting one would
create a false least-privilege boundary. The root remains a full provider
administrator inside the RustFS process; outside that process its credential
is mounted only into the fixed one-shot bootstrap on the private connector
network. The generated IAM specification contains only the two policies that
RustFS actually enforces for the managed runner and executor identities.
Neither policy grants delete, lifecycle, user administration, or retention
changes. On every run the fixed bootstrap code verifies those exact policies,
exactly the runner and executor users, and an empty service-account/STS
inventory, then exercises positive and negative S3 operations before accepting
retained provider state. A separate generated IAM master key encrypts persisted
IAM records; connector and report bind trees are non-symlink, owner-only `0700`
state, and the release test scans retained state without printing values to
ensure credential bytes were not persisted.

Third-party runtime images are official, tag-and-index-digest pinned references
in `config/images.env`: Apache Kafka 4.3.1, RustFS 1.0.0-beta.9, and Kubo
v0.42.0. Each published index contains native `linux/amd64` and `linux/arm64`
manifests. Yano does not compile or publish these dependencies. `prepare` pulls
the image selected for the Docker server architecture and builds only the Yano
node and scenario-runner images.

RustFS is a local S3-compatible demo fixture, not a production storage
recommendation. Normal deployments configure an operator-managed endpoint such
as AWS S3 or another compatible service. Likewise, the bundled Kubo daemon is a
demo target; a normal deployment may point the same `ipfs.pin` executor at an
operator-managed compatible Kubo API.

Compose disables RustFS update checks and the Kubo daemon runs offline with
telemetry disabled. Services run with the host's unprivileged UID, no added
capabilities, `no-new-privileges`, read-only root filesystems, bounded logs,
explicit health checks, and writable mounts/tmpfs only where required.

The `milestone-1-release-acceptance` CI job is the release-evidence join. It
requires the JVM build/bundle smoke, native plugin catalog, real connector fault
matrix, and fenced devnet plus Compose/host E2E gates in the same full run.

## Persistence

Bind data is grouped first by network. App-chain and connector state is then
grouped by instance and deployment, while the L1 store is shared sequentially
by instances using the same network and deployment:

```text
.demo-data/networks/<network>/
  network-identity.json
  l1/
    shared/                         generated devnet genesis/timestamp
    <deployment>/                   shared L1 store and active lease
      node0/ node1/ node2/          Compose L1 stores
      host-cluster/                 host cluster L1 stores, for host mode
      demo-owner.json               present only while one instance owns L1
  instances/<instance>/<deployment>/
    appchain-identity.json
    anchor-binding.json             after script-anchor adoption
    app-chain/node0/ node1/ node2/
    connectors/kafka/ rustfs-v1/ ipfs/
    observability/prometheus/ grafana/
    logs/node0/ node1/ node2/
    reports/
  retired/<deployment>/             retired-instance records
  reservations/<deployment>/        replacement identity reservations

.demo-runtime/networks/<network>/<instance>/<deployment>/
  plugins/ runner.jar yano.jar generated deployment configuration

.demo-secrets/networks/<network>/<instance>/<deployment>/
  member-keys/ nodes-<deployment>/ cluster-private-config/ credentials
```

All three devnet nodes mount the exact same generated Shelley genesis and
explicit `genesis-timestamp` read-only. L1 and app-chain RocksDB paths remain
separate. An owner lease permits only one instance to use a network/deployment
L1 store at a time; stop releases that lease without deleting state. Network
and app-chain identity markers are created atomically and must match exactly
on reuse, including the selected standalone/composite machine and composite
preset. `prepare` and `config` acquire that same non-reentrant lease for the
whole mutation/build window and release it only after validation; `up` retains
it until a proven stop. Active identities, retirement fences, and replacement
reservations are scanned together under one network lock. A chain id is a
network-wide permanent claim, including after retirement, so concurrent or
later instances cannot silently adopt an existing history name.

Complete mutating/runtime commands are additionally serialized per
network/deployment. A detached watchdog retains that lock until a failed or
killed command group is gone; successful services never inherit its file
descriptor. In host mode the evidence UI has a durable gated-launch fence and
private canonical process record binding PID, kernel start token, owner, and
exact observed argv. `status`, `stop`, and failed-start rollback reconcile its
known crash boundaries, while an untrusted record blocks signaling and cleanup.

The configured data, runtime, and secret base roots must be pairwise
disjoint: equal, parent/child, and symlink-resolved overlaps fail before state
is prepared. Secret files are also rejected below either cleanup-managed data
or runtime root. Do not copy one network's directories into another.

An anchored instance has a strict, private `anchor-binding.json`. Retained
anchored history without that binding fails closed. A retained binding must be
a canonical owner-only regular file, match the selected network, instance,
deployment, and chain, and accompany all three members' app-chain history.
After startup the launcher accepts or updates it only when all three members
report the same thread policy, script hash/address, and adopted height.

If `up` fails after acquiring the shared-L1 lease, startup rollback first stops
the partial Compose/host deployment. It releases the lease only after shutdown
is proven; otherwise it deliberately preserves the lease and directs the
operator to run `stop` with the same profile.

### Cleanup

Every deletion requires a stopped deployment, one explicit scope, and
`--yes`. Secrets are always preserved:

For disposable local Compose devnets, reset the complete managed devnet in one
command:

```bash
./demo.sh reset-devnet --yes
```

This stops every managed `yano-effects-devnet-*` Compose project and deletes
the shared devnet L1, all devnet app-chain and connector state, generated
runtime data, reports, reservations, retirement fences, and the time-bound
generated devnet network identity. The next launch therefore creates a fresh
`systemStart`. It preserves credential/key secrets and Docker images, refuses
public networks, and allows previous devnet instance and chain IDs to be
reused. Use the granular commands below when retained-state lifecycle behavior
is under test.

```bash
# Independently disposable categories
./demo.sh clean --instance default --scope observability --yes
./demo.sh clean --instance default --scope reports --yes
./demo.sh clean --instance default --scope runtime --yes

# App-chain journals and connector durability form one effect-instance boundary.
# Retire them together, preserve L1/secrets, and reserve a distinct replacement.
./demo.sh clean --instance default --scope instance \
  --new-instance next --new-chain-id evidence-chain-next --yes
./demo.sh up --instance next --chain-id evidence-chain-next

# L1 deletion is separate and refuses every retained app-chain attachment.
# Run it only after retiring every instance attached to this deployment.
./demo.sh clean --network devnet --instance default --scope l1 --yes

# Retire the selected instance and delete its otherwise-unattached L1 store.
./demo.sh clean --instance default --scope all \
  --new-instance next --new-chain-id evidence-chain-next --yes
```

Pre-guard preview installations may already have a non-empty RustFS build
context without its managed-context sentinel; this is intentionally not
auto-adopted. Do not delete an arbitrary context path. Stop the deployment,
then run `./demo.sh clean --instance default --scope runtime --yes` followed by
`./demo.sh prepare --instance default` (including the same non-default network,
deployment, data-directory, and instance options used originally).

Supported scopes are only `observability`, `reports`, `runtime`, `instance`,
`l1`, and `all`. `appchain` and `connectors` are intentionally rejected even
for an unanchored instance: deleting either side alone can destroy effect
reconciliation evidence or cause external work to be repeated. `instance`
retires and deletes the selected app-chain, connector, report, observability,
log, and runtime state while preserving L1 and all secrets. `instance` and
`all` require a distinct `--new-instance`; the replacement chain id must also
differ and defaults to `evidence-chain-<new-instance>`.

Retirement is crash-resumable. Before deleting data, the launcher durably
publishes a `retiring` fence for the old identity and a replacement reservation.
Re-run the exact cleanup command after interruption; it validates the same
plan, finishes deletion, and changes the fence to `retired`. The old identity
cannot be reused once the fence is present. `all` performs that retirement and
also deletes the shared L1 store, but only when no other retained instance is
attached. Plain `l1` refuses any retained attachment. `l1` and `all` require
`--confirm-public-l1-delete <network>` on a public profile. Every mainnet
cleanup also requires `--enable-mainnet`. Every cleanup requires a stopped
deployment, one exact scope, and `--yes`; all secrets remain.
The plan, retirement/reservation, exact host-link removal, attachment check,
quarantine deletion, and completion execute as one journalled transaction
under the network lock. An interrupted transaction accepts only the identical
plan; a different cleanup or deployment start remains fenced until recovery.

## Normal / host deployment

Host mode uses the same plugin bundles, runner JAR, scenario fixture, strict
properties schema, and proof verifier as Compose. It starts Yano through the
standard cluster launcher but expects Kafka, S3-compatible storage, and Kubo
to be independently provisioned:

```bash
export DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
export DEMO_HOST_KAFKA_TARGET_ID=kafka-host-devnet-local
export DEMO_HOST_S3_ENDPOINT=http://127.0.0.1:9000
export DEMO_HOST_S3_TARGET_ID=s3-host-devnet-local
export DEMO_HOST_IPFS_API_URL=http://127.0.0.1:5001
export DEMO_HOST_IPFS_TARGET_ID=ipfs-host-devnet-local
export DEMO_HOST_S3_RUNNER_ACCESS_KEY_FILE=/secure/runner-access-key
export DEMO_HOST_S3_RUNNER_SECRET_KEY_FILE=/secure/runner-secret-key
export DEMO_HOST_S3_EXECUTOR_ACCESS_KEY_FILE=/secure/executor-access-key
export DEMO_HOST_S3_EXECUTOR_SECRET_KEY_FILE=/secure/executor-secret-key

./demo.sh prepare --mode host
./demo.sh up --mode host
./demo.sh run --mode host
./demo.sh stop --mode host
```

Before `up`, provision a versioned `evidence-staging` bucket **without** Object
Lock and a versioned `evidence-archive` bucket **with** Object Lock enabled but
with **no default retention rule**. Retention is selected per action/profile;
this demo deliberately uses `none-v1`, which applies no per-object lock.
Bootstrap rejects a provider-level default retention rule. Install equivalent
least-privilege runner and executor
policies. The Compose
profile generates an instance-bound private RustFS IAM specification and its
one-shot bootstrap applies and verifies it; host deployments must provide the
same capability split through their provider's IAM mechanism.
The Compose demo deliberately uses the bounded plaintext `local-demo` Kafka
profile on its isolated private network. Host and production deployments can
select the validated `tls`, `mtls`, or `sasl-tls` profiles and provide their
truststore, keystore, and SASL secrets through protected runtime configuration;
effect payloads never carry broker endpoints or credentials. Restrict Kubo RPC
to the executor/runner network. Host mode's
`init-connectors` validates these external dependencies and creates or
validates the Kafka topic before it starts Yano. It never calls Yano and cannot
form a startup cycle.

Target ids are immutable identities, not display labels. Compose derives the
same runner/executor ids from deployment, network, and canonical instance
name. Host mode requires the matching `DEMO_HOST_*_TARGET_ID` whenever its
endpoint or bootstrap-server override is supplied; changing an external
target therefore cannot silently reuse an old acknowledgement fingerprint.
Host S3/IPFS locators must be bounded `http`/`https` origins with an explicit
host and port and no user-info, path, query, or fragment. Kafka locators are a
bounded list of plain `host:port` entries and reject credential/URL syntax.
Credentials are accepted only through the separate owner-only files shown
above, so immutable identity markers remain credential-free.

The host launcher writes private node overlays to
`.demo-secrets/networks/<network>/<instance>/host/nodes-host/`, member seeds to
the sibling `member-keys/` directory, and cluster-owned private configuration
to `cluster-private-config/`. Generated non-secret host files and the staged
JARs live under `.demo-runtime/networks/<network>/<instance>/host/`.
`cluster.sh` receives the member-key directory and optional anchor key through
dedicated file-path inputs and continues to own protected membership, signing
keys, ports, and topology, so a connector overlay cannot override those
launcher invariants. Host L1 state lives under
`.demo-data/networks/<network>/l1/host/host-cluster/`; managed links point its
app-chain paths at the selected instance's separate app-chain directories.

## Plugin installation and native builds

The Compose and host demos are JVM deployments. `prepare` builds each
self-contained `*-bundle.jar` and stages exactly these plugin ids:

```text
com.bloxbean.cardano.yano.appchain.kafka
com.bloxbean.cardano.yano.appchain.objectstore.s3
com.bloxbean.cardano.yano.appchain.ipfs
com.bloxbean.cardano.yano.appchain.evidence-registry
```

Native executables cannot load directory JARs. Build the thin first-party
providers into the app before native catalog/reflection generation:

```bash
./gradlew :app:yanoNativeDistZip \
  -PincludeFirstPartyPluginBundles=true \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true
./gradlew :app:nativePluginCatalogSmoke \
  -PincludeFirstPartyPluginBundles=true \
  -PskipSigning=true
```

That flag includes the three connector providers and evidence registry in the
authoritative build-time plugin catalog. Copying these bundle JARs beside an
already-built native executable is intentionally ignored. The runner itself
remains a standalone JVM operator tool.

The release-only configured-client smoke also includes the native conformance
fixture. It starts an isolated app-chain process, constructs and closes one
real Kafka, S3, and Kubo client with unreachable loopback endpoints, and keeps
the disposable S3 credential in a private temporary configuration file:

```bash
./gradlew :app:yanoNativeDistZip \
  -PincludeFirstPartyPluginBundles=true \
  -PincludeNativePluginConformanceFixture=true \
  -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true
./gradlew :app:nativePluginCatalogSmoke \
  -PincludeFirstPartyPluginBundles=true \
  -PincludeNativePluginConformanceFixture=true \
  -PskipSigning=true
```

The conformance fixture and its double-opt-in client-construction seam are not
part of the production command above and perform no external mutation.

## Tests and troubleshooting

Run the static deployment checks without starting services:

```bash
./tests/compose-contract.sh
./tests/demo-launcher-test.sh
./tests/lifecycle-tool-test.sh
./tests/operation-lock-test.sh
./tests/managed-process-test.sh
./tests/key-material-test.sh
./tests/secret-file-test.sh
./tests/anchor-funding-test.sh
```

Run the release-only connector and ownership gates explicitly:

```bash
# Real pinned Kafka, RustFS, and Kubo: success, restart, reconciliation,
# unavailability, plus deterministic production-adapter fault boundaries.
./tests/connector-fault-matrix.sh

# Three-node post-ack crash and fenced executor handoff/requeue. This always
# creates and removes its own temporary roots, ports, and Compose project.
YANO_RUN_EFFECT_FAILOVER_E2E=true ./tests/effect-failover-e2e.sh

# The identical scenario through Compose and normally started host processes.
YANO_RUN_DEPLOYMENT_PARITY_E2E=true ./tests/deployment-parity-e2e.sh

# Role governance, actor-signed release, rotation/revocation, current-pointer
# proof material, and one-member retained restart/catch-up.
YANO_RUN_ROLE_WORKFLOW_E2E=true ./tests/role-workflow-e2e.sh
```

The live gates parse their JUnit/API evidence and fail if an opted-in case
silently skips. They use isolated temporary state and do not reuse or remove a
developer's normal demo instance.

The focused lifecycle suites cover immutable identity installation,
mismatches, concurrent creation, network-wide same-chain contention, exact
cleanup boundaries, leases, symlink
and path escape rejection, key derivation, key persistence, permissions,
hardlinks, incomplete/tampered key sets, concurrent key creation, disjoint root
enforcement, strict anchor binding, crash-resumable retirement, retained-L1
attachment refusal (including incomplete sibling retirement), and
lease-preserving startup rollback when stopped/orphan Compose containers remain.
Public profile files
define the same safe preview/preprod policy; anchor funding readiness has a
separate behavioral test for collateral/spend UTxO shape and total reserve.
The focused launcher suite renders
preview, preprod, and guarded mainnet models without starting a public network
sync. Live preview/preprod smoke runs remain opt-in and are not part of the
offline test command above.

Useful diagnostics are `./demo.sh status`, `docker compose ... logs <service>`,
the `WAIT_L1_SYNC`, `WAIT_ANCHOR_FUNDS`, `WAIT_ANCHOR_ADOPTION`, and
`ROLLBACK_STARTUP` launcher states, the per-node bind log directories, and the
evidence report directory. A busy host port is never silently reassigned by
this demo; change the corresponding `DEMO_*_PORT` so external receipts and URLs
remain explicit and reproducible.
