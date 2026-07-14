# Yano App-Chain Kafka Sink

Kafka bridge plugin for finalized Yano app-chain blocks.

This module implements `FinalizedStreamSinkFactory` and is discovered through
ServiceLoader when the plugin jar is available to the node. It produces one JSON
record per finalized app block.

See also:

- [ADR-006 E3.2](../../../adr/app-layer/006-appchain-enterprise-extensions-and-zk.md)
- [App-chain user guide](../../../docs/APP_CHAIN_USER_GUIDE.md)

## Packaging Model

The stock Yano application intentionally omits this T3 integration. For a JVM
node, build the self-contained drop-in bundle (the ordinary `jar` remains thin
for build-time inclusion):

```bash
./gradlew :appchain-kafka-sink:shadowJar
# appchain/extensions/appchain-kafka-sink/build/libs/
#   yano-appchain-kafka-sink-<version>-bundle.jar
```

Copy only that `*-bundle.jar` into the JVM node's plugin directory
configured by `yaci.plugins.directory`. It contains Kafka dependencies and
merged ServiceLoader descriptors. Native images cannot load a directory JAR;
build with `-PincludeFirstPartyPluginBundles=true` to include this provider (and
the other first-party T3 bundles) before native catalog/reflection generation.

## Configuration

```properties
yano.app-chain.sinks.kafka.bootstrap-servers=broker1:9092,broker2:9092
yano.app-chain.sinks.kafka.topic=my-appchain-blocks

# optional
yano.app-chain.sinks.kafka.acks=all
yano.app-chain.sinks.kafka.max-block-ms=15000
yano.app-chain.sinks.kafka.delivery-timeout-ms=30000
```

The sink is disabled when either `bootstrap-servers` or `topic` is missing.

## Delivery Semantics

- Blocks are delivered in finalization order.
- The sink cursor is persisted by the app-chain runtime.
- Delivery is at-least-once.
- Kafka idempotence is enabled.
- Records are keyed by block height for stable partitioning.
- A broker outage is bounded by Kafka producer timeouts so it does not block the
  main app-chain scheduler.

Consumers should be idempotent by `(chainId, height)`.

## Test

```bash
./gradlew :appchain-kafka-sink:test
```

## Notes

The current bridge emits block JSON. Per-message MPF proof attachment is not
implemented in this module.
