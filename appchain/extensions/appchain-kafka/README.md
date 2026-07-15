# Yano App-Chain Kafka Integration

First-party Kafka plugin for Yano app chains. One bundle contributes two
independently configured capabilities:

- `FinalizedStreamSinkFactory` (`kafka`) exports one JSON record per finalized
  app block; and
- `AppEffectExecutorFactory` (`kafka`) executes acknowledged `kafka.publish`
  actions through the ADR-010 effect runtime.

The plugin id, Java package, finalized-sink scheme, and existing sink
configuration are unchanged from the pre-release `appchain-kafka-sink`
artifact. Do not install old and new bundles together: they have the same
plugin identity.

See also:

- [ADR-006 E3.2](../../../adr/app-layer/006-appchain-enterprise-extensions-and-zk.md)
- [ADR-013](../../../adr/app-layer/013-first-party-integration-connectors-and-effect-demo.md)
- [App-chain user guide](../../../docs/APP_CHAIN_USER_GUIDE.md)

## Packaging Model

The stock Yano application intentionally omits this T3 integration. For a JVM
node, build the self-contained drop-in bundle (the ordinary `jar` remains thin
for build-time inclusion):

```bash
./gradlew :appchain-kafka:shadowJar
# appchain/extensions/appchain-kafka/build/libs/
#   yano-appchain-kafka-<version>-bundle.jar
```

Copy only that `*-bundle.jar` into the JVM node's plugin directory
configured by `yaci.plugins.directory`. It contains Kafka dependencies and
merged ServiceLoader descriptors. Its connector-contract implementation is
relocated into a bundle-private namespace so independently installed connector
bundles do not share implementation classes.

The ordinary `yano-appchain-kafka-<version>.jar` remains thin. Native and
build-time inclusion use that artifact together with the host-pinned
`yano-appchain-integration-contracts` dependency. Native images cannot load a
directory JAR; build with `-PincludeFirstPartyPluginBundles=true` to include
this provider (and the other first-party T3 bundles) before native
catalog/reflection generation.

## Finalized-sink configuration

```properties
yano.app-chain.sinks.kafka.bootstrap-servers=broker1:9092,broker2:9092
yano.app-chain.sinks.kafka.topic=my-appchain-blocks

# optional
yano.app-chain.sinks.kafka.acks=all
yano.app-chain.sinks.kafka.max-block-ms=15000
yano.app-chain.sinks.kafka.delivery-timeout-ms=30000
yano.app-chain.sinks.kafka.close-timeout-ms=5000
```

The sink is disabled when either `bootstrap-servers` or `topic` is missing.

## `kafka.publish` effect configuration

Effect payloads carry stable target and topic aliases, never broker endpoints
or credentials. The executor maps those aliases through its own namespace:

```properties
yano.app-chain.effects.executors.kafka.targets.primary.target-id=primary-v1
yano.app-chain.effects.executors.kafka.targets.primary.bootstrap-servers=broker1:9092,broker2:9092
yano.app-chain.effects.executors.kafka.targets.primary.acks=all
yano.app-chain.effects.executors.kafka.targets.primary.security-profile=local-demo
yano.app-chain.effects.executors.kafka.topics.evidence-ready.target=primary
yano.app-chain.effects.executors.kafka.topics.evidence-ready.name=evidence.available.v1
```

`local-demo` is plaintext and restricted to local/private bootstrap hosts. Use
the validated `tls`, `mtls`, or `sasl-tls` profile and its required trust/key
settings for a non-local broker.

For `tls`, an optional custom truststore is configured with
`tls.truststore-path`, `tls.truststore-password`, and optionally
`tls.truststore-type`. `mtls` additionally requires `tls.keystore-path`,
`tls.keystore-password`, and `tls.key-password`. `sasl-tls` requires
`sasl.mechanism` (`PLAIN`, `SCRAM-SHA-256`, or `SCRAM-SHA-512`),
`sasl.username`, and `sasl.password`. Supply secrets through protected runtime
configuration; never put them in an effect payload or committed app-chain
state.

Omitting the executor namespace, or setting its `enabled` key to `false`,
leaves the effect contribution inactive.

The effect executor and finalized sink own separate producers and shutdown
lifecycles. Configuring one does not activate the other. `kafka.publish`
commands and receipts use the frozen canonical-CBOR contracts in
`yano-appchain-integration-contracts`.

## Delivery Semantics

- Blocks are delivered in finalization order.
- The sink cursor is persisted by the app-chain runtime.
- Delivery is at-least-once.
- Kafka idempotence is enabled.
- Records are keyed by stable chain id so every block for a chain stays on one
  ordered Kafka partition, even when the topic has several partitions.
- A broker outage is bounded by Kafka producer timeouts so it does not block the
  main app-chain scheduler.

Consumers should be idempotent by `(chainId, height)`.

Pre-release migration note: the former sink-only artifact keyed records by
decimal block height. V1 keys them by `chainId` to preserve per-chain ordering
on multi-partition topics. The JSON value and `(chainId, height)` dedupe tuple
did not change.

For `kafka.publish`, a broker acknowledgement yields a bounded receipt with
destination fingerprint, partition, and offset. Kafka producer idempotence
does not prevent every cross-process duplicate after an unknown
acknowledgement; consumers must deduplicate by the injected effect id where
duplicates matter.

The executor injects this frozen v1 header set:

| Header | Encoding |
|---|---|
| `yano-effect-id` | lowercase 64-character hex, US-ASCII |
| `yano-chain-id` | UTF-8 |
| `yano-effect-type` | `kafka.publish`, US-ASCII |
| `yano-payload-version` | `1`, US-ASCII |
| `yano-origin-height` | unsigned decimal, US-ASCII |
| `yano-origin-ordinal` | unsigned decimal, US-ASCII |
| `yano-content-type` | validated media type, US-ASCII |

Application headers cannot use the reserved `yano-*` prefix. If optional
detail archival fails after a broker acknowledgement, Yano persists the
receipt and retries only archival; it does not publish the record again.

## Test

```bash
./gradlew :appchain-kafka:check

# Optional real-broker acknowledgement test
JAVA_TOOL_OPTIONS=-Dyano.kafka.integration.bootstrap=localhost:9092 \
  ./gradlew :appchain-kafka:test --tests '*KafkaPublishRealIntegrationTest'
```

## Notes

The finalized-block bridge emits block JSON. Per-message MPF proof attachment
is not implemented in this module.
