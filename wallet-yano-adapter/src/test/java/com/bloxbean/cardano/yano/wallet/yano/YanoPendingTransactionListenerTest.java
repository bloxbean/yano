package com.bloxbean.cardano.yano.wallet.yano;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransaction;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStatus;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickAdaTxDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YanoPendingTransactionListenerTest {
    @TempDir
    Path tempDir;

    @Test
    void confirmsPendingTransactionWhenAppliedBlockContainsHash() {
        FilePendingTransactionStore store = new FilePendingTransactionStore(tempDir.resolve("pending-transactions.json"));
        PendingTransaction pending = PendingTransaction.fromDraft(draft("a".repeat(64)), "wallet-1", "preprod")
                .markPending(1_000L);
        store.save(pending);

        new YanoPendingTransactionListener(store, "preprod")
                .onBlockApplied(blockApplied(10_000L, 42L, pending.txHash()));

        PendingTransaction confirmed = store.find(pending.txHash()).orElseThrow();
        assertThat(confirmed.status()).isEqualTo(PendingTransactionStatus.CONFIRMED);
        assertThat(confirmed.confirmedSlot()).isEqualTo(10_000L);
        assertThat(confirmed.confirmedBlock()).isEqualTo(42L);
    }

    @Test
    void ignoresInvalidTransactionIndexes() {
        FilePendingTransactionStore store = new FilePendingTransactionStore(tempDir.resolve("pending-transactions.json"));
        PendingTransaction pending = PendingTransaction.fromDraft(draft("b".repeat(64)), "wallet-1", "preprod")
                .markPending(1_000L);
        store.save(pending);
        Block block = Block.builder()
                .transactionBodies(List.of(TransactionBody.builder().txHash(pending.txHash()).build()))
                .invalidTransactions(List.of(0))
                .build();

        new YanoPendingTransactionListener(store, "preprod")
                .onBlockApplied(new BlockAppliedEvent(Era.Conway, 10_000L, 42L, "c".repeat(64), block));

        assertThat(store.find(pending.txHash()).orElseThrow().status()).isEqualTo(PendingTransactionStatus.PENDING);
    }

    @Test
    void movesConfirmedTransactionBackToRolledBackWhenRollbackRemovesIt() {
        FilePendingTransactionStore store = new FilePendingTransactionStore(tempDir.resolve("pending-transactions.json"));
        PendingTransaction confirmed = PendingTransaction.fromDraft(draft("d".repeat(64)), "wallet-1", "preprod")
                .markPending(1_000L)
                .markConfirmed(10_000L, 42L, "e".repeat(64));
        store.save(confirmed);

        new YanoPendingTransactionListener(store, "preprod")
                .onRollback(new RollbackEvent(new Point(9_999L, "f".repeat(64)), true));

        PendingTransaction rolledBack = store.find(confirmed.txHash()).orElseThrow();
        assertThat(rolledBack.status()).isEqualTo(PendingTransactionStatus.ROLLED_BACK);
        assertThat(rolledBack.confirmedSlot()).isNull();
    }

    private BlockAppliedEvent blockApplied(long slot, long blockNumber, String txHash) {
        Block block = Block.builder()
                .transactionBodies(List.of(TransactionBody.builder().txHash(txHash).build()))
                .build();
        return new BlockAppliedEvent(Era.Conway, slot, blockNumber, "1".repeat(64), block);
    }

    private QuickAdaTxDraft draft(String txHash) {
        return new QuickAdaTxDraft(
                txHash,
                "00",
                "addr_sender",
                "addr_receiver",
                BigInteger.valueOf(2_000_000L),
                BigInteger.valueOf(170_000L),
                12_000L,
                "",
                "",
                1,
                2);
    }
}
