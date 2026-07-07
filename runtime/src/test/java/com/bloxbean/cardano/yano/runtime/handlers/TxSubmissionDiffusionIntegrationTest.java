package com.bloxbean.cardano.yano.runtime.handlers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.Init;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxs;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxs;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.Tx;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.DefaultTxDiffusion;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.TxCatalog;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.TxDiffusionMode;
import com.bloxbean.cardano.yano.runtime.chain.DefaultMemPool;
import com.bloxbean.cardano.yano.runtime.tx.TransactionAdmission;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TxSubmissionDiffusionIntegrationTest {

    @Test
    void yaciAgentRequestsOnlyBodiesPlannedByYanoDiffusion() {
        DefaultMemPool memPool = new DefaultMemPool();
        byte[] knownTx = sampleTxCbor(1);
        byte[] requestedTx = sampleTxCbor(2);
        byte[] skippedTx = sampleTxCbor(3);
        memPool.addTransaction(knownTx);

        DefaultTxDiffusion diffusion = new DefaultTxDiffusion(
                TxDiffusionMode.ALL_HOT,
                txCatalog(memPool),
                TransactionUtil::getTxHash,
                1,
                1_048_576,
                60_000,
                LoggerFactory.getLogger(TxSubmissionDiffusionIntegrationTest.class));

        AtomicReference<byte[]> admittedTx = new AtomicReference<>();
        YaciTxSubmissionHandler handler = new YaciTxSubmissionHandler(new TransactionAdmission() {
            @Override
            public String admitTransaction(byte[] txCbor, String origin) {
                admittedTx.set(txCbor);
                memPool.addTransaction(txCbor);
                return TransactionUtil.getTxHash(txCbor);
            }

            @Override
            public int mempoolSize() {
                return memPool.size();
            }
        }, false, diffusion);

        TxSubmissionServerAgent agent = new TxSubmissionServerAgent(TxSubmissionConfig.createDefault());
        try {
            handler.onClientConnected("peer-1");
            agent.addListener(handler);

            agent.receiveResponse(new Init());
            Message initialRequest = agent.buildNextMessage();
            assertThat(initialRequest).isInstanceOf(RequestTxIds.class);

            ReplyTxIds replyTxIds = new ReplyTxIds(new LinkedHashMap<>());
            replyTxIds.addTxId(TxBodyType.CONWAY.getEra(), TransactionUtil.getTxHash(knownTx), knownTx.length);
            replyTxIds.addTxId(TxBodyType.CONWAY.getEra(), TransactionUtil.getTxHash(requestedTx), requestedTx.length);
            replyTxIds.addTxId(TxBodyType.CONWAY.getEra(), TransactionUtil.getTxHash(skippedTx), skippedTx.length);

            agent.receiveResponse(replyTxIds);

            Message bodyRequest = agent.buildNextMessage();
            assertThat(bodyRequest).isInstanceOf(RequestTxs.class);
            RequestTxs requestTxs = (RequestTxs) bodyRequest;
            assertThat(requestTxs.getTxIds())
                    .singleElement()
                    .satisfies(txId -> assertThat(txId.toString()).isEqualTo(TransactionUtil.getTxHash(requestedTx)));
            assertThat(agent.getReceivedTxIdCount()).isEqualTo(3);
            assertThat(agent.getOutstandingTxCount()).isEqualTo(1);
            assertThat(diffusion.stats().inboundTxIdsRequested()).isEqualTo(1);
            assertThat(diffusion.stats().inboundTxIdsIgnored()).isEqualTo(1);
            assertThat(diffusion.stats().inboundTxIdsRejected()).isEqualTo(1);

            ReplyTxs replyTxs = new ReplyTxs();
            replyTxs.addTx(new Tx(TxBodyType.CONWAY.getEra(), requestedTx));
            agent.receiveResponse(replyTxs);

            assertThat(admittedTx.get()).isSameAs(requestedTx);
            assertThat(agent.getOutstandingTxCount()).isZero();
            assertThat(diffusion.stats().inboundTxBodiesAccepted()).isEqualTo(1);
            assertThat(diffusion.stats().inboundTxBodiesIgnored()).isZero();
            assertThat(diffusion.stats().inFlightTxs()).isZero();

            Message nextRequest = agent.buildNextMessage();
            assertThat(nextRequest).isInstanceOf(RequestTxIds.class);
            assertThat(((RequestTxIds) nextRequest).getAckTxIds()).isEqualTo((short) 1);
        } finally {
            agent.shutdown();
            handler.onClientDisconnected("peer-1");
        }
    }

    private static TxCatalog txCatalog(DefaultMemPool memPool) {
        return new TxCatalog() {
            @Override
            public boolean contains(String txHash) {
                return memPool.contains(txHash);
            }

            @Override
            public MemPoolTransaction getTransaction(String txHash) {
                return memPool.getTransaction(txHash);
            }
        };
    }

    private static byte[] sampleTxCbor(int nonce) {
        Map txBody = new Map();
        Array inputs = new Array();
        Array input = new Array();
        input.add(new ByteString(new byte[32]));
        input.add(new UnsignedInteger(nonce));
        inputs.add(input);
        txBody.put(new UnsignedInteger(0), inputs);

        Array outputs = new Array();
        Map output = new Map();
        output.put(new UnsignedInteger(0), new ByteString(new byte[28]));
        output.put(new UnsignedInteger(1), new UnsignedInteger(1_000_000 + nonce));
        outputs.add(output);
        txBody.put(new UnsignedInteger(1), outputs);
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(200_000 + nonce));

        Map witnesses = new Map();
        Array tx = new Array();
        tx.add(txBody);
        tx.add(witnesses);
        tx.add(SimpleValue.TRUE);
        tx.add(SimpleValue.NULL);
        return CborSerializationUtil.serialize(tx);
    }
}
