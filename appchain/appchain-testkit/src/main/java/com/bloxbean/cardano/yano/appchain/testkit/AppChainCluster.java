package com.bloxbean.cardano.yano.appchain.testkit;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Boots an embedded multi-node app-chain cluster for the annotated test class
 * (ADR app-layer/006 E1.2): N in-process nodes with temp ledgers, generated
 * member keys, full-mesh peers and real sockets. Node 0 is the sequencer.
 * Inject {@link AppChainClusterHandle} as a test method (or constructor)
 * parameter to interact with the cluster.
 *
 * <pre>
 * &#64;AppChainCluster(nodes = 3)
 * class MyAppTest {
 *     &#64;Test
 *     void ordersReplicate(AppChainClusterHandle cluster) throws Exception {
 *         String id = cluster.node(1).submit("orders", "o-1".getBytes());
 *         cluster.awaitFinalized(id);
 *         assertThat(cluster.node(2).stateProof(HexUtil.decodeHexString(id))).isPresent();
 *     }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(AppChainClusterExtension.class)
public @interface AppChainCluster {

    /** Number of member nodes (node 0 is the sequencer). */
    int nodes() default 2;

    String chainId() default "testkit-chain";

    /** Built-in or ServiceLoader-provided state machine id. */
    String stateMachine() default "ordered-log";

    /** Finality threshold; 0 = all nodes must co-sign. */
    int threshold() default 0;

    /** Proposer tick, kept fast for tests. */
    long blockIntervalMs() default 300;
}
