package com.bloxbean.cardano.yano.runtime.handlers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxs;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.Tx;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.events.api.VetoableEvent;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationException;
import com.bloxbean.cardano.yano.runtime.chain.DefaultMemPool;
import com.bloxbean.cardano.yano.runtime.tx.TransactionAdmission;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.DefaultTxDiffusion;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.TxDiffusion;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.TxDiffusionMode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class YaciTxSubmissionHandlerTest {

    @Test
    void handleReplyTxsDelegatesAdmissionWithTxSubmissionOrigin() {
        byte[] txBytes = new byte[] {1, 2, 3};
        AtomicReference<byte[]> admittedTx = new AtomicReference<>();
        AtomicReference<String> admittedOrigin = new AtomicReference<>();
        YaciTxSubmissionHandler handler = new YaciTxSubmissionHandler(new TransactionAdmission() {
            @Override
            public String admitTransaction(byte[] txCbor, String origin) {
                admittedTx.set(txCbor);
                admittedOrigin.set(origin);
                return "abc123";
            }

            @Override
            public int mempoolSize() {
                return 1;
            }
        });

        handler.handleReplyTxs(replyWith(txBytes));

        assertThat(admittedTx.get()).isSameAs(txBytes);
        assertThat(admittedOrigin.get()).isEqualTo("txsubmission");
        assertThat(handler.getTxsReceived()).isEqualTo(1L);
        assertThat(handler.getTxsAccepted()).isEqualTo(1L);
        assertThat(handler.getTxsRejected()).isZero();
        assertThat(handler.getMempoolSize()).isEqualTo(1);
    }

    @Test
    void disabledDiffusionPreservesLegacyTxSubmissionAdmission() {
        byte[] txBytes = new byte[] {1, 2, 3};
        AtomicReference<byte[]> admittedTx = new AtomicReference<>();
        AtomicReference<String> admittedOrigin = new AtomicReference<>();
        YaciTxSubmissionHandler handler = new YaciTxSubmissionHandler(new TransactionAdmission() {
            @Override
            public String admitTransaction(byte[] txCbor, String origin) {
                admittedTx.set(txCbor);
                admittedOrigin.set(origin);
                return "abc123";
            }

            @Override
            public int mempoolSize() {
                return 1;
            }
        }, false, TxDiffusion.disabled());

        handler.handleReplyTxs(replyWith(txBytes));

        assertThat(admittedTx.get()).isSameAs(txBytes);
        assertThat(admittedOrigin.get()).isEqualTo("txsubmission");
        assertThat(handler.shouldRequestTransaction(null)).isTrue();
        assertThat(handler.getTxsAccepted()).isEqualTo(1L);
        assertThat(handler.getTxsRejected()).isZero();
    }

    @Test
    void handleReplyTxsCountsValidationRejectionWithoutAdmissionSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        YaciTxSubmissionHandler handler = new YaciTxSubmissionHandler(new TransactionAdmission() {
            @Override
            public String admitTransaction(byte[] txCbor, String origin) {
                attempts.incrementAndGet();
                throw new TransactionValidationException(List.of(
                        new VetoableEvent.Rejection("test", "blocked")));
            }

            @Override
            public int mempoolSize() {
                return 0;
            }
        });

        handler.handleReplyTxs(replyWith(new byte[] {4, 5, 6}));

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(handler.getTxsReceived()).isEqualTo(1L);
        assertThat(handler.getTxsAccepted()).isZero();
        assertThat(handler.getTxsRejected()).isEqualTo(1L);
        assertThat(handler.getMempoolSize()).isZero();
    }

    @Test
    void diffusionIngressAdmitsPlannedTransactionsWithPeerOrigin() {
        byte[] txBytes = sampleTxCbor();
        AtomicReference<byte[]> admittedTx = new AtomicReference<>();
        AtomicReference<String> admittedOrigin = new AtomicReference<>();
        DefaultTxDiffusion diffusion = new DefaultTxDiffusion(
                TxDiffusionMode.ALL_HOT,
                new DefaultMemPool(),
                100,
                1_048_576,
                60_000,
                LoggerFactory.getLogger(YaciTxSubmissionHandlerTest.class));
        YaciTxSubmissionHandler handler = new YaciTxSubmissionHandler(new TransactionAdmission() {
            @Override
            public String admitTransaction(byte[] txCbor, String origin) {
                admittedTx.set(txCbor);
                admittedOrigin.set(origin);
                return TransactionUtil.getTxHash(txCbor);
            }

            @Override
            public int mempoolSize() {
                return 1;
            }
        }, false, diffusion);

        handler.onClientConnected("peer-1");
        ReplyTxIds txIds = new ReplyTxIds();
        txIds.addTxId(TxBodyType.CONWAY.getEra(), TransactionUtil.getTxHash(txBytes), txBytes.length);
        handler.handleReplyTxIds(txIds);
        handler.handleReplyTxs(replyWith(txBytes));

        assertThat(admittedTx.get()).isSameAs(txBytes);
        assertThat(admittedOrigin.get()).isEqualTo("tx-diffusion:peer-1");
        assertThat(handler.getTxsAccepted()).isEqualTo(1L);
        assertThat(handler.getTxsRejected()).isZero();
        assertThat(diffusion.stats().inboundAccepted()).isEqualTo(1L);
        assertThat(diffusion.stats().inFlightTxs()).isZero();
    }

    @Test
    void diffusionIngressIgnoresUnplannedTransactionBodies() {
        AtomicInteger attempts = new AtomicInteger();
        DefaultTxDiffusion diffusion = new DefaultTxDiffusion(
                TxDiffusionMode.ALL_HOT,
                new DefaultMemPool(),
                100,
                1_048_576,
                60_000,
                LoggerFactory.getLogger(YaciTxSubmissionHandlerTest.class));
        YaciTxSubmissionHandler handler = new YaciTxSubmissionHandler(new TransactionAdmission() {
            @Override
            public String admitTransaction(byte[] txCbor, String origin) {
                attempts.incrementAndGet();
                return "tx";
            }

            @Override
            public int mempoolSize() {
                return 0;
            }
        }, false, diffusion);

        handler.onClientConnected("peer-1");
        handler.handleReplyTxs(replyWith(sampleTxCbor()));

        assertThat(attempts.get()).isZero();
        assertThat(handler.getTxsAccepted()).isZero();
        assertThat(diffusion.stats().inboundIgnored()).isEqualTo(1L);
    }

    private static ReplyTxs replyWith(byte[] txBytes) {
        ReplyTxs replyTxs = new ReplyTxs();
        replyTxs.addTx(new Tx(null, txBytes));
        return replyTxs;
    }

    private static byte[] sampleTxCbor() {
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
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(200_000));

        Map witnesses = new Map();
        Array tx = new Array();
        tx.add(txBody);
        tx.add(witnesses);
        tx.add(SimpleValue.TRUE);
        tx.add(SimpleValue.NULL);
        return CborSerializationUtil.serialize(tx);
    }
}
