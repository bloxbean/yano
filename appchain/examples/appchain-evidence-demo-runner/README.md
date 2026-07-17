# App-chain evidence demo runner

This standalone Java 25 application runs the ADR-013 evidence workflow against
the same endpoints in Compose or a normal deployment. It is not a Yano plugin
and cannot activate a state machine or connector.

```bash
./gradlew :appchain-evidence-demo-runner:shadowJar
java -jar build/libs/yano-appchain-evidence-demo-runner-*-all.jar \
  init-connectors --config /absolute/path/demo.properties
java -jar build/libs/yano-appchain-evidence-demo-runner-*-all.jar \
  init --config /absolute/path/demo.properties
java -jar build/libs/yano-appchain-evidence-demo-runner-*-all.jar \
  run --config /absolute/path/demo.properties
java -jar build/libs/yano-appchain-evidence-demo-runner-*-all.jar \
  serve --config /absolute/path/ui.properties
```

`init-connectors` is the pre-Yano one-shot: it waits only for S3, Kubo, and
Kafka, validates the pre-provisioned buckets/versioning, and creates the topic.
It makes no Yano call, so Compose can run it before the nodes. `probe` waits,
under the configured deadline, for a produced L1 block, every
configured app-chain node, S3-compatible object storage, Kubo, and Kafka. `init` additionally
creates the Kafka topic idempotently. S3 buckets and their least-privilege
policy are operator/Compose responsibilities; `init` only validates that both
buckets exist and archive versioning is enabled.

The scenario intentionally accepts exactly three distinct Yano endpoints. It
verifies three distinct status `memberKey` identities, a common
three-member/threshold-two group, and the exact `demo.state-machine` selection
(`evidence-registry` by default or the stock `composite` profile). Both
portable finality bundles must carry that exact member set and at least two
valid certificate signatures. The exact keys and threshold are independently
pinned in the runner configuration and compared with every node status and
bundle; member keys and threshold never become trust-on-first-use inputs.
The demo's script address and thread-policy id are cluster-observed values;
all three configured nodes must report the same values before they are used
to audit the exact Cardano output and inline datum.

Copy `src/main/resources/demo-config.example.properties` and adjust endpoint
and non-secret target identities. The broad-auth demo omits
`demo.yano.api-key-file`, so the runner sends no Yano API-key header. A future
scoped READ/SUBMIT key may be supplied through that optional file property;
the full bootstrap/admin key must never be mounted into the runner. S3
credentials and any optional scoped key are read only from regular,
non-symlink secret files. Because the runner properties select both connector
endpoints and secret-file paths, the properties file and every secret file
must not be group- or world-accessible on POSIX systems. The launcher creates
them with mode `0600`. Secret values are never accepted on the command line or
in the properties file.

When `demo.state-machine=composite`, `run` uses only the lightweight
`appchain-composite-contracts` client artifact. It commits the stock workflow's
registry and approval prerequisites, waits for threshold finality after each,
then submits the canonical evidence-release command. The runner JAR is checked
to contain no runtime, state-machine, or connector plugin implementation.

The destructive failover E2E also uses the runner's internal read-only
`audit-kafka` command. It accepts only an expected record count (at most 16)
and one effect-id hash, then fails closed unless the fresh demo topic has
exactly partition 0 and offsets `[0,count)`. Every physical record must carry
the exact unique reserved-header set, fixed `kafka.publish`/v1/CBOR values,
canonical chain/origin values, and the expected `yano-effect-id`. A bounded
SHA-256 fingerprint over partition, key, value, and ordered headers must match
across retries. The command exposes only offsets, effect IDs, and fingerprints;
it does not decode the scenario's CBOR event semantics.

The `run` command succeeds only after it verifies authenticated registry
state, MPF state proofs on every member, portable threshold-finality evidence,
effect proofs bound to those certified block roots, S3 bytes/version identity,
Kubo pin state, and the exact Kafka record at its acknowledged partition and
offset. When anchoring is required, both portable evidence bundles and every
member's observed anchor frontier must cover the scenario. The runner then
fetches the anchor transaction and UTxOs from every node and requires one exact
state-thread output: the expected script address and thread-token unit, plus a
canonical inline datum matching chain, height, block hash, state root, pinned
members, and threshold. The report also
states `BUSINESS_CLAIM_NOT_EVALUATED`: consensus proves what participants
approved, not whether the real-world certificate itself is true.

`serve` uses a separate strict config containing only `ui.report-directory`,
`ui.bind-address`, and `ui.port` (see `ui-config.example.properties`). It never
loads the scenario config or any secret file. It exposes only static assets,
`/healthz`, and the sanitized latest JSON report; it does not proxy Yano or
accept an API key.
