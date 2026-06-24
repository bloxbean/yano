package com.bloxbean.cardano.yano.runtime.handlers;

import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxs;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.Tx;
import com.bloxbean.cardano.yaci.events.api.VetoableEvent;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationException;
import com.bloxbean.cardano.yano.runtime.tx.TransactionAdmission;
import org.junit.jupiter.api.Test;

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

    private static ReplyTxs replyWith(byte[] txBytes) {
        ReplyTxs replyTxs = new ReplyTxs();
        replyTxs.addTx(new Tx(null, txBytes));
        return replyTxs;
    }
}
