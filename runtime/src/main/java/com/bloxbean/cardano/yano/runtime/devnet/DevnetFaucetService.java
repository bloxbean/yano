package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.runtime.utxo.UtxoStoreWriter;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Devnet faucet operations over the runtime UTXO writer.
 */
public final class DevnetFaucetService {
    private final BooleanSupplier devMode;
    private final BooleanSupplier blockProductionAvailable;
    private final Supplier<UtxoStoreWriter> utxoStoreSupplier;

    public DevnetFaucetService(BooleanSupplier devMode,
                               BooleanSupplier blockProductionAvailable,
                               Supplier<UtxoStoreWriter> utxoStoreSupplier) {
        this.devMode = Objects.requireNonNull(devMode, "devMode");
        this.blockProductionAvailable = Objects.requireNonNull(blockProductionAvailable, "blockProductionAvailable");
        this.utxoStoreSupplier = Objects.requireNonNull(utxoStoreSupplier, "utxoStoreSupplier");
    }

    public FundResult fundAddress(String address, long lovelace) {
        requireDevMode();

        UtxoStoreWriter utxoStore = utxoStoreSupplier.get();
        if (utxoStore == null || !utxoStore.isEnabled()) {
            throw new IllegalStateException("Faucet requires UTXO store to be enabled");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Address must not be empty");
        }
        if (lovelace <= 0) {
            throw new IllegalArgumentException("Lovelace amount must be positive");
        }

        String txHash = utxoStore.injectFaucetUtxo(address, lovelace);
        return new FundResult(txHash, 0, lovelace);
    }

    private void requireDevMode() {
        if (!devMode.getAsBoolean()) {
            throw new IllegalStateException("Faucet requires dev mode (yano.dev-mode=true)");
        }
        if (!blockProductionAvailable.getAsBoolean()) {
            throw new IllegalStateException("Faucet requires block producer to be running");
        }
    }
}
