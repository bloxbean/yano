# Yano App-Chain Testkit

JUnit 5 extension for testing applications against an embedded, multi-node Yano
app-chain cluster. It also contains the provider-neutral ADR-013 effect
executor conformance suite.

The testkit starts in-process nodes with:

- generated member keys
- temporary ledgers
- full-mesh socket connections
- node 0 as the sequencer
- configurable chain id, state machine, threshold, and block interval

See also:

- [App-chain tutorial](../../docs/APP_CHAIN_TUTORIAL.md)
- [ADR-006 E1.2](../../adr/app-layer/006-appchain-enterprise-extensions-and-zk.md)

## Usage

```java
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainCluster;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainClusterHandle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@AppChainCluster(nodes = 3)
class OrdersAppChainTest {

    @Test
    void ordersReplicate(AppChainClusterHandle cluster) throws Exception {
        String messageId = cluster.node(1)
                .submit("orders", "order-1".getBytes());

        cluster.awaitFinalized(messageId);

        assertThat(cluster.node(2)
                .stateProof(HexUtil.decodeHexString(messageId)))
                .isPresent();
    }
}
```

## Annotation Options

```java
@AppChainCluster(
    nodes = 3,
    chainId = "testkit-chain",
    stateMachine = "ordered-log",
    threshold = 0,
    blockIntervalMs = 300
)
```

Fields:

- `nodes`: number of member nodes. Node 0 is the sequencer.
- `chainId`: app-chain id.
- `stateMachine`: built-in or ServiceLoader-provided state-machine id.
- `threshold`: finality threshold. `0` means all nodes must co-sign.
- `blockIntervalMs`: proposer tick interval.

## Cluster Handle

`AppChainClusterHandle` exposes:

- `node(index)`: an `AppChainGateway` for a node.
- `proposer()`: node 0.
- `nodes()`: all gateways.
- `memberPublicKeyHex(index)`: generated member key.
- `awaitFinalized(messageIdHex)`: wait for a message on all nodes.
- `awaitTip(height)`: wait for every node to reach a tip.

## Test

```bash
./gradlew :appchain-testkit:test
```

## Effect executor conformance

Connector modules expose one JUnit dynamic-test factory:

```java
@TestFactory
Stream<DynamicNode> connectorContract() {
    return EffectExecutorConformance.tests(new MyConnectorConformanceSpec());
}
```

`ExecutorConformanceSpec` supplies fresh fake/real provider fixtures for the
scenarios the connector supports. The common suite verifies exact action
routing, invalid-payload no-I/O, receipt bounds, stable idempotency identity,
transient retry, unknown acknowledgements, existing match/conflict behavior,
fresh lifecycle products, bounded/idempotent close, blocked-call shutdown,
normalized error dispositions, durable detail-hash/effect binding, recursive
secret canaries (including events from a fixture-installed test log appender),
receipt/detail/command semantic consistency, and non-empty bounded Effect
Runtime snapshot labels. The
mandatory blocked-call scenario requires `close()` itself to terminate the
in-flight call; the suite's explicit `unblock()` is cleanup-only and cannot
make the assertion pass.

`ExecutorFixture.capturedLogs()` is mandatory. Connector fixtures install a
real test appender before constructing the executor and return bounded event
snapshots (for example logger, level, rendered message, and throwable). The
appender must remain active while `ExecutorFixture.close()` releases provider
resources: the suite scans cleanup events after close and only then calls the
separate, bounded `closeLogCapture()` teardown hook. Failed-construction
observations likewise include logs captured through partial-resource cleanup.
Merely returning a synthetic empty list without active capture defeats the
purpose and is not a conformant connector test.

Secret inspection is deliberately bounded. Fixtures use a small set of
non-empty synthetic canaries and expose bounded text/byte leaves and bounded
container graphs; oversized or unsupported diagnostic values fail closed
instead of invoking an unbounded `toString()` or redaction scan.

Kafka declares `STABLE_DEDUPE_TOKEN` because broker acknowledgement loss may
duplicate a record across process restart. S3 declares
`PROBE_SINGLE_MUTATION`; IPFS declares `IDEMPOTENT_SET`. Both reconcile
external state before reporting a second logical mutation.

Connector integration tests can additionally exercise the actual runtime:

```java
try (EffectRuntimeHarness runtime = EffectRuntimeHarness.start(
        "kafka.publish", factory, executorSettings, tempDirectory)) {
    var observation = runtime.submitAndAwaitDone(command, Duration.ofSeconds(10));
    EffectRuntimeSnapshotAssertions.assertSafe(
            observation.stats(), observation.status(),
            Set.of("kafka.publish"), secretCanaries);
}
```

This boots a one-member app chain through the public `AppChainSubsystem`,
finalizes the command, emits one effect, selects the supplied factory through
its normal plugin scheme, and returns the real Effect Runtime status/metrics.
The caller owns the storage directory and external provider; the harness never
deletes files or starts vendor services. Use it alongside the black-box suite,
not as a replacement for Kafka/MinIO/Kubo integration tests.

Shape-equivalent `runtimeStats()` and `runtimeStatus()` fixture maps validate
only the stable key/value shape and redaction rules; they do not prove that a
provider outcome caused the corresponding runtime transition. Every
first-party connector phase must therefore use `EffectRuntimeHarness` with the
real connector factory to observe a successful `DONE` transition and at least
one applicable non-happy transition (`RETRY`, `SUBMITTED`, `PARKED`, or
provider-unavailable) before the phase is accepted.

`forbiddenSentinels()` and `secretCanaries` must contain synthetic test-only
values. Never copy a real credential, token, private endpoint, or production
secret into source code, test reports, fixtures, or assertion inputs.

## Scope

The testkit is for application and state-machine tests. It is not a public
network simulator and does not model adversarial timing, Byzantine peers, L1
rollback, or long-running production load.
