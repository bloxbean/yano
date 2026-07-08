package com.bloxbean.cardano.yano.appchain.testkit;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-test of the testkit (ADR 006 E1.2): the exact experience an
 * application developer gets — annotate, inject the handle, assert on the
 * replicated chain.
 */
@AppChainCluster(nodes = 3)
@Timeout(90)
class AppChainClusterExtensionTest {

    @Test
    void threeNodeCluster_replicatesAndProves(AppChainClusterHandle cluster) throws Exception {
        assertThat(cluster.size()).isEqualTo(3);

        // Submit on a non-proposer member
        String id = cluster.node(1).submit("orders", "order-1".getBytes(StandardCharsets.UTF_8));
        cluster.awaitFinalized(id);

        // Identical roots everywhere
        byte[] root = cluster.proposer().stateRoot();
        for (int i = 1; i < cluster.size(); i++) {
            assertThat(cluster.node(i).stateRoot()).isEqualTo(root);
        }

        // 3-of-3 finality cert on the block
        long height = cluster.proposer().messageHeight(HexUtil.decodeHexString(id)).orElseThrow();
        assertThat(cluster.node(2).block(height).orElseThrow().cert().signatures()).hasSize(3);

        // Proof available from any node
        assertThat(cluster.node(2).stateProof(HexUtil.decodeHexString(id))).isPresent();
    }

    @Test
    void multipleSubmissions_stayOrdered(AppChainClusterHandle cluster) throws Exception {
        String first = cluster.node(0).submit("t", "a".getBytes(StandardCharsets.UTF_8));
        cluster.awaitFinalized(first);
        String second = cluster.node(2).submit("t", "b".getBytes(StandardCharsets.UTF_8));
        cluster.awaitFinalized(second);

        long firstHeight = cluster.node(1).messageHeight(HexUtil.decodeHexString(first)).orElseThrow();
        long secondHeight = cluster.node(1).messageHeight(HexUtil.decodeHexString(second)).orElseThrow();
        assertThat(firstHeight).isLessThan(secondHeight);
    }
}
