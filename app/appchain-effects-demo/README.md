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

The default `explicit` profile preserves the Milestone 1 continuation command.
To start a fresh chain with direct result-to-effect continuation enabled from
height 1, pass the same immutable option to every command:

```bash
./demo.sh prepare --instance direct-demo --continuation direct
./demo.sh up --instance direct-demo --continuation direct
./demo.sh run --instance direct-demo --continuation direct
./demo.sh stop --instance direct-demo --continuation direct
```

The continuation mode is part of the retained app-chain identity. It cannot be
changed in place after an instance is prepared; use a new instance and chain
identity to change profiles.

The same scenario can exercise the stock deterministic composite workflow
without application code. Use a fresh instance and pass `--machine composite`
to every lifecycle command:

```bash
./demo.sh prepare --instance composite-demo --machine composite
./demo.sh up --instance composite-demo --machine composite
./demo.sh run --instance composite-demo --machine composite
./demo.sh stop --instance composite-demo --machine composite
```

This profile first commits a registry identity, proposes and records an
approval for the exact evidence command, and then submits
`evidence.release.v1`. The composite applies the document-trail append and
evidence submission atomically before the existing S3, IPFS, Kafka, proof, and
anchor checks continue. The marker pins `provider=composite` and
`preset=evidence-v1`; switching between standalone and composite on retained
state is rejected. `--continuation explicit|direct` remains independently
selectable for a fresh composite instance.

`up` builds the exact working-tree Yano and runner images, starts all services,
waits through the producer warm-up, funds and bootstraps the devnet script
anchor, and executes a read-only readiness probe. `run` then stages and submits
the supplied scenario. Re-running it is safe: the runner and connector
contracts use stable identities and reconcile acknowledged external state.
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
Milestone 1 currently has no Kafka authentication/TLS settings, so its broker
must be a local or otherwise private, trusted endpoint shared by the runner
and executor; authenticated Kafka profiles are a later extension. Restrict
Kubo RPC to the executor/runner network. Host mode's
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
