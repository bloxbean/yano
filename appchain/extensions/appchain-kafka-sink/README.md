# Yano App-Chain Kafka Sink

Kafka bridge plugin for finalized Yano app-chain blocks.

This module implements `FinalizedStreamSinkFactory` and is discovered through
ServiceLoader when the plugin jar is available to the node. It produces one JSON
record per finalized app block.

See also:

- [ADR-006 E3.2](../../../adr/app-layer/006-appchain-enterprise-extensions-and-zk.md)
- [App-chain user guide](../../../docs/APP_CHAIN_USER_GUIDE.md)

## Packaging Model

This is a T3 plugin module. The node provides `core-api`; the Kafka sink jar
brings Kafka client dependencies.

Build the jar:

```bash
./gradlew :appchain-kafka-sink:jar
```

Then place it in the node plugin directory configured by
`yaci.plugins.directory`.

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
