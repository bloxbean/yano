package com.bloxbean.cardano.yano.wallet.core.tx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilePendingTransactionStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAndReloadsPendingTransactions() {
        Path file = tempDir.resolve("pending-transactions.json");
        FilePendingTransactionStore store = new FilePendingTransactionStore(file);

        PendingTransaction pending = PendingTransaction.fromDraft(draft("a".repeat(64)), "wallet-1", "preprod")
                .markPending(1_000L);
        store.save(pending);

        FilePendingTransactionStore reloaded = new FilePendingTransactionStore(file);

        PendingTransaction reloadedPending = reloaded.find(pending.txHash()).orElseThrow();
        assertThat(reloadedPending.status()).isEqualTo(PendingTransactionStatus.PENDING);
        assertThat(reloadedPending.submittedAtEpochMillis()).isEqualTo(1_000L);
    }

    @Test
    void upsertsByHashAndKeepsNewestStatus() {
        FilePendingTransactionStore store = new FilePendingTransactionStore(tempDir.resolve("pending-transactions.json"));
        PendingTransaction pending = PendingTransaction.fromDraft(draft("b".repeat(64)), "wallet-1", "preprod")
                .markPending(1_000L);

        store.save(pending);
        store.save(pending.markConfirmed(12_345L, 99L, "c".repeat(64)));

        assertThat(store.list()).hasSize(1);
        PendingTransaction confirmed = store.find(pending.txHash()).orElseThrow();
        assertThat(confirmed.status()).isEqualTo(PendingTransactionStatus.CONFIRMED);
        assertThat(confirmed.confirmedSlot()).isEqualTo(12_345L);
        assertThat(confirmed.confirmedBlock()).isEqualTo(99L);
    }

    @Test
    void filtersByWalletAndNetwork() {
        FilePendingTransactionStore store = new FilePendingTransactionStore(tempDir.resolve("pending-transactions.json"));
        store.save(PendingTransaction.fromDraft(draft("c".repeat(64)), "wallet-1", "preprod"));
        store.save(PendingTransaction.fromDraft(draft("d".repeat(64)), "wallet-2", "preprod"));
        store.save(PendingTransaction.fromDraft(draft("e".repeat(64)), "wallet-1", "preview"));

        assertThat(store.list("wallet-1", "preprod"))
                .extracting(PendingTransaction::txHash)
                .containsExactly("c".repeat(64));
        assertThat(store.listByNetwork("preprod"))
                .extracting(PendingTransaction::txHash)
                .containsExactlyInAnyOrder("c".repeat(64), "d".repeat(64));
    }

    @Test
    void detectsExpiryOnlyForAwaitingConfirmationTransactions() {
        PendingTransaction pending = PendingTransaction.fromDraft(draft("f".repeat(64)), "wallet-1", "preprod")
                .markPending(1_000L);

        assertThat(pending.isExpiredAt(12_000L)).isFalse();
        assertThat(pending.isExpiredAt(12_001L)).isTrue();
        assertThat(pending.markConfirmed(11_999L, 1L, "1".repeat(64)).isExpiredAt(12_001L)).isFalse();
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
