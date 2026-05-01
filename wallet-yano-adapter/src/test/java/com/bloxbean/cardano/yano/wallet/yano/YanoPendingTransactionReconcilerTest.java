package com.bloxbean.cardano.yano.wallet.yano;

import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransaction;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStatus;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickAdaTxDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class YanoPendingTransactionReconcilerTest {
    @TempDir
    Path tempDir;

    @Test
    void confirmsPersistedPendingTransactionFromUtxoIndex() {
        FilePendingTransactionStore store = new FilePendingTransactionStore(tempDir.resolve("pending-transactions.json"));
        PendingTransaction pending = PendingTransaction.fromDraft(draft("a".repeat(64)), "wallet-1", "preprod")
                .markPending(1_000L);
        store.save(pending);

        int confirmed = new YanoPendingTransactionReconciler(store, "preprod")
                .reconcile(new FakeUtxoState(pending.txHash()));

        PendingTransaction reconciled = store.find(pending.txHash()).orElseThrow();
        assertThat(confirmed).isEqualTo(1);
        assertThat(reconciled.status()).isEqualTo(PendingTransactionStatus.CONFIRMED);
        assertThat(reconciled.confirmedSlot()).isEqualTo(12_345L);
        assertThat(reconciled.confirmedBlock()).isEqualTo(77L);
    }

    @Test
    void leavesMissingTransactionsPending() {
        FilePendingTransactionStore store = new FilePendingTransactionStore(tempDir.resolve("pending-transactions.json"));
        PendingTransaction pending = PendingTransaction.fromDraft(draft("b".repeat(64)), "wallet-1", "preprod")
                .markPending(1_000L);
        store.save(pending);

        int confirmed = new YanoPendingTransactionReconciler(store, "preprod")
                .reconcile(new FakeUtxoState("c".repeat(64)));

        assertThat(confirmed).isZero();
        assertThat(store.find(pending.txHash()).orElseThrow().status()).isEqualTo(PendingTransactionStatus.PENDING);
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

    private static class FakeUtxoState implements UtxoState {
        private final String confirmedTxHash;

        private FakeUtxoState(String confirmedTxHash) {
            this.confirmedTxHash = confirmedTxHash;
        }

        @Override
        public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
            return List.of();
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            return Optional.empty();
        }

        @Override
        public List<Utxo> getOutputsByTxHash(String txHash) {
            if (!confirmedTxHash.equals(txHash)) {
                return List.of();
            }
            return List.of(new Utxo(
                    new Outpoint(txHash, 0),
                    "addr_receiver",
                    BigInteger.valueOf(1_000_000L),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    12_345L,
                    77L,
                    "d".repeat(64)));
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
