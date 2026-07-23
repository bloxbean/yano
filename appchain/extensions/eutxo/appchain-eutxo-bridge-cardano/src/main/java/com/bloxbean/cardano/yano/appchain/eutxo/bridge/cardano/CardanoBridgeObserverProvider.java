package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;

import java.util.Map;

/** Service-loaded exact observer for stable, accepted EUTxO vault deposits. */
public final class CardanoBridgeObserverProvider implements L1ObserverProvider {
    public static final String TYPE = "eutxo-vault-deposit-v1";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public L1Observer create(String observerId, Map<String, String> settings) {
        return new AcceptedVaultDepositObserver(observerId, settings);
    }
}
