package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerService;
import com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystem;
import com.bloxbean.cardano.yano.runtime.utxo.UtxoStoreWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevnetFaucetServiceTest {
    @Test
    void fundAddressInjectsFaucetUtxoAndReturnsReference() {
        FakeUtxoStore store = new FakeUtxoStore(true, "abc123");
        DevnetFaucetService service = new DevnetFaucetService(() -> true, () -> true, () -> store);

        var result = service.fundAddress("addr_test1...", 42L);

        assertEquals("abc123", result.txHash());
        assertEquals(0, result.index());
        assertEquals(42L, result.lovelace());
        assertEquals("addr_test1...", store.address);
        assertEquals(42L, store.lovelace);
    }

    @Test
    void requiresDevMode() {
        DevnetFaucetService service = new DevnetFaucetService(() -> false, () -> true, FakeUtxoStore::enabled);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.fundAddress("addr", 1));

        assertEquals("Faucet requires dev mode (yano.dev-mode=true)", error.getMessage());
    }

    @Test
    void requiresDevnetProduction() {
        DevnetFaucetService service = new DevnetFaucetService(() -> true, () -> false, FakeUtxoStore::enabled);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.fundAddress("addr", 1));

        assertEquals("Faucet requires block producer to be running", error.getMessage());
    }

    @Test
    void allowsDevModeSlotLeaderProduction() {
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        producerSubsystem.installSlotLeader(new FakeBlockProducer());
        FakeUtxoStore store = new FakeUtxoStore(true, "slotleader-tx");
        DevnetFaucetService service = new DevnetFaucetService(
                () -> true,
                producerSubsystem::hasProduction,
                () -> store);

        var result = service.fundAddress("addr_test1...", 7L);

        assertEquals("slotleader-tx", result.txHash());
        assertEquals(7L, result.lovelace());
    }

    @Test
    void requiresEnabledUtxoStore() {
        DevnetFaucetService service = new DevnetFaucetService(() -> true, () -> true,
                () -> new FakeUtxoStore(false, "ignored"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.fundAddress("addr", 1));

        assertEquals("Faucet requires UTXO store to be enabled", error.getMessage());
    }

    @Test
    void rejectsInvalidInput() {
        DevnetFaucetService service = new DevnetFaucetService(() -> true, () -> true, FakeUtxoStore::enabled);

        assertEquals("Address must not be empty",
                assertThrows(IllegalArgumentException.class, () -> service.fundAddress(" ", 1)).getMessage());
        assertEquals("Lovelace amount must be positive",
                assertThrows(IllegalArgumentException.class, () -> service.fundAddress("addr", 0)).getMessage());
    }

    private static final class FakeUtxoStore implements UtxoStoreWriter {
        private final boolean enabled;
        private final String txHash;
        private String address;
        private long lovelace;

        private FakeUtxoStore(boolean enabled, String txHash) {
            this.enabled = enabled;
            this.txHash = txHash;
        }

        static FakeUtxoStore enabled() {
            return new FakeUtxoStore(true, "tx");
        }

        @Override
        public void applyBlock(BlockAppliedEvent e) {
        }

        @Override
        public void rollbackTo(RollbackEvent e) {
        }

        @Override
        public void reconcile(ChainState chainState) {
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public String injectFaucetUtxo(String address, long lovelace) {
            this.address = address;
            this.lovelace = lovelace;
            return txHash;
        }
    }

    private static final class FakeBlockProducer implements BlockProducerService {
        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public void resetToChainTip() {
        }
    }
}
