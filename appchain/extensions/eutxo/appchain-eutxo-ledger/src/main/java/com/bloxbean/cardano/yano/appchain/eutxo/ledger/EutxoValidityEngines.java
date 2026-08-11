package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentEngine;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentProvider;

import java.util.Map;
import java.util.Objects;

/** Fail-closed selection for the family-private optional validity capability. */
final class EutxoValidityEngines {
    static final String ENABLED = "machines.eutxo.validity.enabled";
    static final String PROVIDER = "machines.eutxo.validity.provider";

    private EutxoValidityEngines() {
    }

    static EutxoValidityCommitmentEngine discover(
            String chainId,
            EutxoProfile profile,
            Map<String, String> settings,
            EutxoValidityCommitmentProvider packagedProvider
    ) {
        Objects.requireNonNull(settings, "settings");
        String enabledValue = settings.getOrDefault(ENABLED, "false").trim();
        if (!"true".equalsIgnoreCase(enabledValue)
                && !"false".equalsIgnoreCase(enabledValue)) {
            throw new IllegalArgumentException(
                    "EUTxO validity enabled must be true or false");
        }
        if (!Boolean.parseBoolean(enabledValue)) {
            return null;
        }
        String selected = settings.getOrDefault(PROVIDER, "").trim();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "validity commitments are enabled but no provider is selected");
        }
        if (packagedProvider == null || !selected.equals(packagedProvider.id())) {
            throw new IllegalArgumentException(
                    "selected EUTxO validity provider is unavailable: " + selected);
        }
        EutxoValidityCommitmentEngine engine =
                packagedProvider.create(chainId, profile, Map.copyOf(settings));
        if (engine == null || !selected.equals(engine.id())) {
            throw new IllegalArgumentException(
                    "selected EUTxO validity provider returned the wrong engine identity");
        }
        return engine;
    }
}
