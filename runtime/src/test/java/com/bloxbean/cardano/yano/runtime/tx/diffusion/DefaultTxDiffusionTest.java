package com.bloxbean.cardano.yano.runtime.tx.diffusion;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.runtime.chain.DefaultMemPool;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTxDiffusionTest {

    @Test
    void plansOnlyUnknownTransactionsWithinInFlightLimits() {
        DefaultMemPool memPool = new DefaultMemPool();
        byte[] knownTx = sampleTxCbor(200_000);
        byte[] unknownTx1 = sampleTxCbor(200_001);
        byte[] unknownTx2 = sampleTxCbor(200_002);
        memPool.addTransaction(knownTx);
        DefaultTxDiffusion diffusion = diffusion(memPool, 1, 1_000_000);
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
        assertThat(diffusion.stats().inFlightTxs()).isEqualTo(1);
        assertThat(diffusion.stats().inFlightBytes()).isEqualTo(unknownTx1.length);
    }

    @Test
    void duplicateAnnouncementsDoNotIncreaseInFlightState() {
        DefaultMemPool memPool = new DefaultMemPool();
        byte[] tx = sampleTxCbor(200_003);
        DefaultTxDiffusion diffusion = diffusion(memPool, 10, 1_000_000);
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
        DefaultMemPool memPool = new DefaultMemPool();
        byte[] tx = sampleTxCbor(200_004);
        DefaultTxDiffusion diffusion = diffusion(memPool, 10, 1_000_000);
        String peerId = "peer-a";
        diffusion.onPeerTxIds(peerId, PeerClass.DOWNSTREAM,
                List.of(new TxIdAndSize(hash(tx), tx.length)));

        diffusion.onPeerDisconnected(peerId);

        assertThat(diffusion.stats().peerCount()).isZero();
        assertThat(diffusion.stats().inFlightTxs()).isZero();
        assertThat(diffusion.stats().inFlightBytes()).isZero();
    }

    @Test
    void servesRequestedMempoolTransactionsWithinLimitsWithoutCopyingBodies() {
        DefaultMemPool memPool = new DefaultMemPool();
        byte[] firstTx = sampleTxCbor(200_005);
        byte[] secondTx = sampleTxCbor(200_006);
        memPool.addTransaction(firstTx);
        memPool.addTransaction(secondTx);
        DefaultTxDiffusion diffusion = diffusion(memPool, 1, 1_000_000);

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
        DefaultMemPool memPool = new DefaultMemPool();
        byte[] tx = sampleTxCbor(200_007);
        memPool.addTransaction(tx);
        DefaultTxDiffusion diffusion = new DefaultTxDiffusion(
                TxDiffusionMode.DISABLED,
                memPool,
                100,
                1_000_000,
                60_000,
                LoggerFactory.getLogger(DefaultTxDiffusionTest.class));

        TxBodyServeResult result = diffusion.onPeerRequestedTxs("peer-a", PeerClass.DOWNSTREAM, List.of(hash(tx)));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.missing()).isEqualTo(1);
        assertThat(diffusion.stats().servedTxs()).isZero();
    }

    private static DefaultTxDiffusion diffusion(DefaultMemPool memPool, int maxTxs, long maxBytes) {
        return new DefaultTxDiffusion(
                TxDiffusionMode.ALL_HOT,
                memPool,
                maxTxs,
                maxBytes,
                60_000,
                LoggerFactory.getLogger(DefaultTxDiffusionTest.class));
    }

    private static String hash(byte[] txCbor) {
        return TransactionUtil.getTxHash(txCbor);
    }

    private static byte[] sampleTxCbor(long fee) {
        Map txBody = new Map();
        Array inputs = new Array();
        Array input = new Array();
        input.add(new ByteString(new byte[32]));
        input.add(new UnsignedInteger(0));
        inputs.add(input);
        txBody.put(new UnsignedInteger(0), inputs);

        Array outputs = new Array();
        Map output = new Map();
        output.put(new UnsignedInteger(0), new ByteString(new byte[28]));
        output.put(new UnsignedInteger(1), new UnsignedInteger(1_000_000));
        outputs.add(output);
        txBody.put(new UnsignedInteger(1), outputs);
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(fee));

        Map witnesses = new Map();
        Array tx = new Array();
        tx.add(txBody);
        tx.add(witnesses);
        tx.add(SimpleValue.TRUE);
        tx.add(SimpleValue.NULL);
        return CborSerializationUtil.serialize(tx);
    }
}
