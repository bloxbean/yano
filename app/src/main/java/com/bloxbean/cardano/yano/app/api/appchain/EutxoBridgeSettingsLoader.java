package com.bloxbean.cardano.yano.app.api.appchain;

import org.eclipse.microprofile.config.Config;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Resolves a chain's {@code machines.eutxo.bridge.*} / {@code observers.*}
 * facts from the runtime configuration (ADR-UTXO-008). Mirrors the indexed
 * chain layout the runtime itself parses; a chain without
 * {@code bridge.observer-id} has no bridge surface.
 */
final class EutxoBridgeSettingsLoader {
    private static final int MAX_CHAINS = 50;
    private static final BigInteger DEFAULT_MAX_DEPOSIT =
            new BigInteger("45000000000000000");

    private EutxoBridgeSettingsLoader() {
    }

    static Optional<EutxoBridgeResource.BridgeSettings> load(
            Config config, String chainId) {
        for (int index = 0; index < MAX_CHAINS; index++) {
            String prefix = "yano.app-chain.chains[" + index + "].";
            Optional<String> configured = config.getOptionalValue(
                    prefix + "chain-id", String.class);
            if (configured.isEmpty() || !configured.orElseThrow().equals(chainId)) {
                continue;
            }
            Optional<String> observerId = config.getOptionalValue(
                    prefix + "machines.eutxo.bridge.observer-id", String.class);
            if (observerId.isEmpty() || observerId.orElseThrow().isBlank()) {
                return Optional.empty();
            }
            String vaultAddress = config.getOptionalValue(
                    prefix + "machines.eutxo.bridge.vault-address", String.class)
                    .orElse("");
            String vaultScriptHash = config.getOptionalValue(
                    prefix + "machines.eutxo.bridge.vault-script-hash", String.class)
                    .orElse("");
            if (vaultAddress.isBlank() || vaultScriptHash.isBlank()) {
                return Optional.empty();
            }
            BigInteger maxDeposit = config.getOptionalValue(
                    prefix + "observers." + observerId.orElseThrow()
                            + ".max-lovelace", String.class)
                    .map(BigInteger::new)
                    .orElse(DEFAULT_MAX_DEPOSIT);
            return Optional.of(new EutxoBridgeResource.BridgeSettings(
                    vaultAddress,
                    vaultScriptHash,
                    config.getOptionalValue(
                            prefix + "machines.eutxo.bridge.withdrawal-address",
                            String.class).orElse(""),
                    config.getOptionalValue(
                            prefix + "machines.eutxo.bridge.epoch", Long.class)
                            .orElse(0L),
                    maxDeposit,
                    config.getOptionalValue(
                            prefix + "machines.eutxo.bridge.withdrawals-paused",
                            Boolean.class).orElse(false),
                    config.getOptionalValue(
                            prefix + "l1.stability-depth", Long.class)
                            .orElse(0L)));
        }
        return Optional.empty();
    }
}
