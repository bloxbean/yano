# Yano App-Chain Testkit

JUnit 5 extension for testing applications against an embedded, multi-node Yano
app-chain cluster.

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

## Scope

The testkit is for application and state-machine tests. It is not a public
network simulator and does not model adversarial timing, Byzantine peers, L1
rollback, or long-running production load.
