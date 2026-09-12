# App-chain testkit

Test your state machine with an embedded cluster of Yano members.

Canonical URL: https://getyano.dev/develop/app-chain-testkit/

`org.yanoproject:yano-appchain-testkit` provides a JUnit 5 extension with generated member keys, temporary ledgers, and real socket connections between embedded nodes.

```java
import org.yanoproject.appchain.testkit.AppChainCluster;
import org.yanoproject.appchain.testkit.AppChainClusterHandle;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;

@AppChainCluster(nodes = 3, stateMachine = "ordered-log")
class SharedLogTest {
    @Test
    void finalizesOnEveryMember(AppChainClusterHandle cluster) throws Exception {
        String id = cluster.node(1).submit(
                "orders", "order-1".getBytes(StandardCharsets.UTF_8));
        cluster.awaitFinalized(id);
    }
}
```

`awaitFinalized` waits for the message on all nodes and fails on timeout. Node 0 is the sequencer. The default `threshold = 0` selects all members; `blockIntervalMs` defaults to `300`. You can set the chain ID, state-machine ID, member count, threshold, and proposer tick interval on the annotation.

This is an integration fixture, not a simulation of Byzantine peers, adversarial timing, L1 rollback, or production load. Add workload-specific assertions and separate tests for those concerns.

For effect executor plugins, the module also supplies provider-neutral conformance suites and an `EffectRuntimeHarness`. See the module source and contracts in the [repository](https://github.com/bloxbean/yano/tree/fd7fe406e9364689a3e829b79f82707488cebf1e/appchain/appchain-testkit).
