package com.bloxbean.cardano.yano.p2p.tx.diffusion;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTxDiffusionTest {

    @Test
    void plansOnlyUnknownTransactionsWithinInFlightLimits() {
        FakeTxCatalog catalog = new FakeTxCatalog();
        byte[] knownTx = sampleTx(1);
        byte[] unknownTx1 = sampleTx(2);
        byte[] unknownTx2 = sampleTx(3);
        catalog.add(knownTx);
        DefaultTxDiffusion diffusion = diffusion(catalog, 1, 1_000_000);
        String peerId = "peer-a";
        diffusion.onPeerConnected(peerId, PeerClass.DOWNSTREAM);

        TxRequestPlan plan = diffusion.onPeerTxIds(peerId, PeerClass.DOWNSTREAM, List.of(
                new TxIdAndSize(hash(knownTx), knownTx.length),
                new TxIdAndSize(hash(unknownTx1), unknownTx1.length),
                new TxIdAndSize(hash(unknownTx2), unknownTx2.length)));

        assertThat(plan.requested()).extracting(TxIdAndSize::txHash)
                .containsExactly(hash(unknownTx1));
        assertThat(plan.ignored()).isEqualTo(1);
        assertThat(plan.rejected()).isEqualTo(1);
        TxDiffusionStats stats = diffusion.stats();
        assertThat(stats.inboundTxIdsRequested()).isEqualTo(1);
        assertThat(stats.inboundTxIdsIgnored()).isEqualTo(1);
        assertThat(stats.inboundTxIdsRejected()).isEqualTo(1);
        assertThat(stats.inboundTxBodiesAccepted()).isZero();
        assertThat(stats.inboundTxBodiesRejected()).isZero();
        assertThat(stats.inFlightTxs()).isEqualTo(1);
        assertThat(stats.inFlightBytes()).isEqualTo(unknownTx1.length);
        assertThat(diffusion.shouldRequestTransaction(peerId, PeerClass.DOWNSTREAM, hash(knownTx))).isFalse();
        assertThat(diffusion.shouldRequestTransaction(peerId, PeerClass.DOWNSTREAM, hash(unknownTx1))).isTrue();
        assertThat(diffusion.shouldRequestTransaction(peerId, PeerClass.DOWNSTREAM, hash(unknownTx2))).isFalse();
    }

    @Test
    void duplicateAnnouncementsDoNotIncreaseInFlightState() {
        FakeTxCatalog catalog = new FakeTxCatalog();
        byte[] tx = sampleTx(4);
        DefaultTxDiffusion diffusion = diffusion(catalog, 10, 1_000_000);
        String peerId = "peer-a";

        diffusion.onPeerTxIds(peerId, PeerClass.DOWNSTREAM,
                List.of(new TxIdAndSize(hash(tx), tx.length)));
        TxRequestPlan duplicate = diffusion.onPeerTxIds(peerId, PeerClass.DOWNSTREAM,
                List.of(new TxIdAndSize(hash(tx), tx.length)));

        assertThat(duplicate.requested()).isEmpty();
        assertThat(duplicate.ignored()).isEqualTo(1);
        assertThat(diffusion.stats().inFlightTxs()).isEqualTo(1);
    }

    @Test
    void peerDisconnectDropsBoundedState() {
        FakeTxCatalog catalog = new FakeTxCatalog();
        byte[] tx = sampleTx(5);
        DefaultTxDiffusion diffusion = diffusion(catalog, 10, 1_000_000);
        String peerId = "peer-a";
        diffusion.onPeerTxIds(peerId, PeerClass.DOWNSTREAM,
                List.of(new TxIdAndSize(hash(tx), tx.length)));

        diffusion.onPeerDisconnected(peerId);

        assertThat(diffusion.stats().peerCount()).isZero();
        assertThat(diffusion.stats().inFlightTxs()).isZero();
        assertThat(diffusion.stats().inFlightBytes()).isZero();
    }

    @Test
    void admitsOnlyRequestedTransactionBodiesThroughAdmissionPort() {
        FakeTxCatalog catalog = new FakeTxCatalog();
        byte[] tx = sampleTx(6);
        DefaultTxDiffusion diffusion = diffusion(catalog, 10, 1_000_000);
        String peerId = "peer-a";
        AtomicReference<byte[]> admittedTx = new AtomicReference<>();
        AtomicReference<String> admittedOrigin = new AtomicReference<>();

        diffusion.onPeerTxIds(peerId, PeerClass.DOWNSTREAM,
                List.of(new TxIdAndSize(hash(tx), tx.length)));
        TxBodyIngressResult result = diffusion.onPeerTxBodies(peerId, PeerClass.DOWNSTREAM, List.of(tx),
                (txCbor, origin) -> {
                    admittedTx.set(txCbor);
                    admittedOrigin.set(origin);
                    return hash(txCbor);
                });

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.rejected()).isZero();
        assertThat(result.ignored()).isZero();
        assertThat(admittedTx.get()).isSameAs(tx);
        assertThat(admittedOrigin.get()).isEqualTo("tx-diffusion:peer-a");
        TxDiffusionStats stats = diffusion.stats();
        assertThat(stats.inboundTxIdsRequested()).isEqualTo(1);
        assertThat(stats.inboundTxBodiesAccepted()).isEqualTo(1);
        assertThat(stats.inboundTxBodiesRejected()).isZero();
        assertThat(stats.inFlightTxs()).isZero();
        assertThat(diffusion.shouldRequestTransaction(peerId, PeerClass.DOWNSTREAM, hash(tx))).isFalse();
    }

    @Test
    void admissionFailureIncrementsBodyRejectedWithoutChangingTxIdRejected() {
        FakeTxCatalog catalog = new FakeTxCatalog();
        byte[] tx = sampleTx(10);
        DefaultTxDiffusion diffusion = diffusion(catalog, 10, 1_000_000);
        String peerId = "peer-a";

        diffusion.onPeerTxIds(peerId, PeerClass.DOWNSTREAM,
                List.of(new TxIdAndSize(hash(tx), tx.length)));
        TxBodyIngressResult result = diffusion.onPeerTxBodies(peerId, PeerClass.DOWNSTREAM, List.of(tx),
                (txCbor, origin) -> {
                    throw new IllegalArgumentException("blocked");
                });

        assertThat(result.accepted()).isZero();
        assertThat(result.rejected()).isEqualTo(1);
        TxDiffusionStats stats = diffusion.stats();
        assertThat(stats.inboundTxIdsRequested()).isEqualTo(1);
        assertThat(stats.inboundTxIdsRejected()).isZero();
        assertThat(stats.inboundTxBodiesAccepted()).isZero();
        assertThat(stats.inboundTxBodiesRejected()).isEqualTo(1);
    }

    @Test
    void servesRequestedMempoolTransactionsWithinLimitsWithoutCopyingBodies() {
        FakeTxCatalog catalog = new FakeTxCatalog();
        byte[] firstTx = sampleTx(7);
        byte[] secondTx = sampleTx(8);
        catalog.add(firstTx);
        catalog.add(secondTx);
        DefaultTxDiffusion diffusion = diffusion(catalog, 1, 1_000_000);

        TxBodyServeResult result = diffusion.onPeerRequestedTxs("peer-a", PeerClass.DOWNSTREAM, List.of(
                hash(firstTx),
                hash(secondTx),
                "00"));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.transactions().getFirst().txBytes()).isSameAs(firstTx);
        assertThat(result.missing()).isEqualTo(2);
        assertThat(result.servedBytes()).isEqualTo(firstTx.length);
        assertThat(diffusion.stats().servedTxs()).isEqualTo(1L);
        assertThat(diffusion.stats().servedBytes()).isEqualTo(firstTx.length);
    }

    @Test
    void disabledModeDoesNotServeMempoolTransactions() {
        FakeTxCatalog catalog = new FakeTxCatalog();
        byte[] tx = sampleTx(9);
        catalog.add(tx);
        DefaultTxDiffusion diffusion = new DefaultTxDiffusion(
                TxDiffusionMode.DISABLED,
                catalog,
                DefaultTxDiffusionTest::hash,
                100,
                1_000_000,
                60_000,
                LoggerFactory.getLogger(DefaultTxDiffusionTest.class));

        TxBodyServeResult result = diffusion.onPeerRequestedTxs("peer-a", PeerClass.DOWNSTREAM, List.of(hash(tx)));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.missing()).isEqualTo(1);
        assertThat(diffusion.stats().servedTxs()).isZero();
    }

    private static DefaultTxDiffusion diffusion(FakeTxCatalog catalog, int maxTxs, long maxBytes) {
        return new DefaultTxDiffusion(
                TxDiffusionMode.ALL_HOT,
                catalog,
                DefaultTxDiffusionTest::hash,
                maxTxs,
                maxBytes,
                60_000,
                LoggerFactory.getLogger(DefaultTxDiffusionTest.class));
    }

    private static String hash(byte[] txCbor) {
        return HexUtil.encodeHexString(txCbor);
    }

    private static byte[] sampleTx(int id) {
        byte[] tx = new byte[128];
        Arrays.fill(tx, (byte) id);
        tx[0] = (byte) id;
        tx[1] = (byte) (id >>> 8);
        return tx;
    }

    private static final class FakeTxCatalog implements TxCatalog {
        private final Map<String, MemPoolTransaction> transactions = new LinkedHashMap<>();
        private long seq;

        void add(byte[] txCbor) {
            String hash = hash(txCbor);
            transactions.put(hash, new MemPoolTransaction(
                    ++seq,
                    HexUtil.decodeHexString(hash),
                    txCbor,
                    TxBodyType.CONWAY));
        }

        @Override
        public boolean contains(String txHash) {
            return transactions.containsKey(txHash);
        }

        @Override
        public MemPoolTransaction getTransaction(String txHash) {
            return transactions.get(txHash);
        }
    }
}
