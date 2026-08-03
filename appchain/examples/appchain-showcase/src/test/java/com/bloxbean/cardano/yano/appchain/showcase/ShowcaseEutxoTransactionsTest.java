package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShowcaseEutxoTransactionsTest {

    @Test
    void deterministicVirtualWalletBuildsAChainedPayment() throws Exception {
        String address = ShowcaseEutxoTransactions.address();
        byte[] transaction = ShowcaseEutxoTransactions.payment(
                ShowcaseEutxoTransactions.genesisOutpoint());

        assertThat(address).isEqualTo(
                "addr_test1vrld3msldls64ax7c06vu85nvhk70260q970cssxzjh0hlchc79qg");
        assertThat(ShowcaseEutxoTransactions.genesisOutpoint().transactionId())
                .hasSize(64);
        assertThat(transaction).isNotEmpty();
        assertThat(TransactionUtil.getTxHash(transaction)).hasSize(64);
        assertThat(ShowcaseEutxoTransactions.payment(
                ShowcaseEutxoTransactions.genesisOutpoint())).containsExactly(transaction);
    }

    @Test
    void decodesTheCommittedTransactionOutcome() {
        var receipt = new EutxoReceipt(
                EutxoReceipt.Status.ACCEPTED,
                "01".repeat(32),
                new byte[32],
                7,
                0,
                0,
                "",
                "");

        assertThat(ShowcaseEutxoTransactions.receiptStatus(
                EutxoQueryCodec.optionalReceipt(receipt))).isEqualTo("ACCEPTED");
        assertThat(ShowcaseEutxoTransactions.receiptStatus(
                EutxoQueryCodec.optionalReceipt(null))).isEqualTo("NOT_FOUND");
    }
}
