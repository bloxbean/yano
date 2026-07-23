package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentEngine;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentProvider;

import java.util.Map;

/** Service-loaded provider for the optional ZeroJ Poseidon commitment. */
public final class ZerojPoseidonValidityProvider
        implements EutxoValidityCommitmentProvider {
    public static final String ID = "zeroj-poseidon-v1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EutxoValidityCommitmentEngine create(
            String chainId,
            EutxoProfile profile,
            Map<String, String> settings
    ) {
        if (!EutxoProfile.V1.equals(profile)) {
            throw new IllegalArgumentException(
                    "the Z0 validity profile supports key-controlled EUTxO v1 only");
        }
        String bridgeObserver =
                settings.get("machines.eutxo.bridge.observer-id");
        if (bridgeObserver != null && !bridgeObserver.isBlank()) {
            throw new IllegalArgumentException(
                    "the Z0 validity profile does not cover bridge transitions");
        }
        return new ZerojPoseidonValidityEngine(chainId, profile);
    }
}
