# ADR-013 evidence-effects demo

This directory is the Phase 1.5 deployment for ADR-013. It runs one
three-member `evidence-chain`, a single effect owner, Kafka, MinIO, Kubo, and
the deployment-neutral evidence runner. The included inspection certificate
demonstrates the complete no-code path:

1. stage one immutable input in `evidence-staging`;
2. commit an evidence command in the app chain;
3. copy and verify the object in the versioned archive;
4. pin its CID in Kubo;
5. incorporate the signed results under the app-chain state root;
6. submit the idempotent continuation and publish `evidence.available.v1` to
   Kafka; and
7. verify state proof, threshold finality, S3 version, exact CID bytes, Kafka
   acknowledgement, and the Cardano state-thread output/inline datum in one
   report.

Phase 1.5 is intentionally **devnet-only**. Public-network identity markers,
preview/preprod profiles, mainnet guards, and granular deletion are Phase 1.6.
The launcher therefore has `stop` but no `clean`: stopping always preserves
data.

The three member seeds (`01`, `02`, and `03`, each repeated 32 times) and the
anchor seed (`30` repeated 32 times) are public, deterministic demo identities.
They must never be reused on preview, preprod, mainnet, or for anything of
value. Per-instance API and connector credentials are generated privately.

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

`up` builds the exact working-tree Yano and runner images, starts all services,
waits through the producer warm-up, funds and bootstraps the devnet script
anchor, and executes a read-only readiness probe. `run` then stages and submits
the supplied scenario. Re-running it is safe: the runner and connector
contracts use stable identities and reconcile acknowledged external state.

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
| MinIO S3 API | `http://127.0.0.1:9000` |
| Kubo RPC | `http://127.0.0.1:5001` |
| Prometheus (optional) | `http://127.0.0.1:9090/` |
| Grafana (optional) | `http://127.0.0.1:3000/` |

Ports and the connector subnet can be changed with the `DEMO_*` values listed
in `config/common.env`. Use `--instance <name>` plus distinct host ports and a
distinct `DEMO_CONNECTOR_SUBNET` for parallel environments. `./demo.sh config`
prints the fully resolved Compose model without printing a credential value.
Instance names must match `[a-z0-9][a-z0-9-]{0,31}`; underscores are rejected
so two names cannot normalize to the same Compose project or connector ids.
`DEMO_CHAIN_ID` must match `[a-z][a-z0-9-]{0,62}` and is rendered identically
into the node application config, scenario runner, and host anchor bootstrap.

## Security and ownership model

Only `yano-0` joins the dedicated connector network and only its private node
configuration enables `object.put`, `ipfs.pin`, and `kafka.publish`. Nodes 1
and 2 independently validate the deterministic effects and incorporated
results but hold no connector credential and run no executor.

The local connector profiles deliberately accept only private numeric HTTP
origins. MinIO and Kubo therefore have fixed addresses on a dedicated bridge;
this is not a DNS bypass. Kafka uses its separately validated local broker
name. Connector, evidence-UI, and observability services remain on three
separate bridges. Those bridges intentionally are not Docker `internal`
networks: Docker Desktop cannot route their published ports to the host when
they are internal. Every published port instead binds to `127.0.0.1`; no
gateway, MinIO console, KRaft controller, Cardano N2N port, or Docker socket
is exposed.

Secrets are generated once per instance under:

```text
.demo-secrets/devnet/<instance>/
```

The directory is mode `0700` and secret files are `0600`. Values are never put
in Compose environment variables, launcher/Yano command arguments, committed
app-chain state, or the report UI. The isolated one-shot MinIO initializer
reads mounted secret files and necessarily supplies credentials to its local
`mc` child process; no other container shares its process namespace. Generated
node configuration is private because the current S3 executor SPI accepts
credentials through protected configuration.
The full Yano API key is retained only by the private node configuration and
launcher for privileged anchor bootstrap; scenario, connector initializer,
and report UI containers never receive it. The devnet demo leaves ordinary
read and submit operations unauthenticated.

MinIO has three separate principals:

- the root identity exists only in MinIO and the one-shot initializer;
- the runner identity may write/read the staging prefix and read the archive;
- the executor identity may read staging and conditionally create/read archive
  versions.

Neither application policy grants delete, lifecycle, user administration, or
retention changes. The initializer verifies stored credentials on every run
and fails closed if retained MinIO state no longer matches the private files.

Third-party images are tag-and-digest pinned in `config/images.env`. Services
run with the host's unprivileged UID, no added capabilities,
`no-new-privileges`, read-only root filesystems, bounded logs, explicit
healthchecks, and writable mounts/tmpfs only where required.

## Persistence

Bind data is grouped by network and instance:

```text
.demo-data/devnet/<instance>/
  l1/                 Cardano L1 state and the one shared immutable genesis
  app-chain/          nested app-chain RocksDB mounts
  connectors/         Kafka, MinIO, and Kubo data
  observability/      Prometheus and Grafana data
  logs/               Yano logs
  reports/            runner JSON/HTML evidence
```

All three Compose nodes mount the exact same generated Shelley genesis and
explicit `genesis-timestamp` read-only. L1 and app-chain RocksDB paths are
separate nested bind mounts. `stop` removes containers and networks only.
Do not manually copy one network's directories into another. Phase 1.6 will
add durable identity markers and guarded, granular cleanup commands.

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

Before `up`, provision `evidence-staging` and `evidence-archive`, enable
versioning on both, and apply the least-privilege policies in
`config/services/minio-*-policy.json` (or equivalent provider policies).
Phase 1.5 has no Kafka authentication/TLS settings, so its broker must be a
local or otherwise private, trusted endpoint shared by the runner and
executor; authenticated Kafka profiles are a later extension. Restrict Kubo
RPC to the executor/runner network. Host mode's
`init-connectors` validates these external dependencies and creates or
validates the Kafka topic before it starts Yano. It never calls Yano and cannot
form a startup cycle.

Target ids are immutable identities, not display labels. Compose derives the
same runner/executor ids from deployment, network, and canonical instance
name. Host mode requires the matching `DEMO_HOST_*_TARGET_ID` whenever its
endpoint or bootstrap-server override is supplied; changing an external
target therefore cannot silently reuse an old acknowledgement fingerprint.

The host launcher uses private `node<N>.properties` overlays. `cluster.sh`
continues to own protected membership, signing keys, ports, and topology, so a
connector overlay cannot override those launcher invariants. Host L1 and app
state are separated with managed nested links under the instance root.

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
  -PskipSigning=true
./gradlew :app:nativePluginCatalogSmoke \
  -PincludeFirstPartyPluginBundles=true \
  -PskipSigning=true
```

That flag includes the three connector providers and evidence registry in the
authoritative build-time plugin catalog. Copying these bundle JARs beside an
already-built native executable is intentionally ignored. The runner itself
remains a standalone JVM operator tool.

## Tests and troubleshooting

Run the static deployment checks without starting services:

```bash
./tests/compose-contract.sh
./tests/demo-launcher-test.sh
```

Useful diagnostics are `./demo.sh status`, `docker compose ... logs <service>`,
the per-node bind log directories, and the evidence report directory. A busy
host port is never silently reassigned by this demo; change the corresponding
`DEMO_*_PORT` so external receipts and URLs remain explicit and reproducible.
