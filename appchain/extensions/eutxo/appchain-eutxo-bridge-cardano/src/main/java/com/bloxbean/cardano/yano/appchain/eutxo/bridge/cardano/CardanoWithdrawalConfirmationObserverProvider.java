package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;

import java.util.Map;

/** Service-loaded observer for stable, claim-bound Cardano payouts. */
public final class CardanoWithdrawalConfirmationObserverProvider
        implements L1ObserverProvider {
    public static final String TYPE = "eutxo-withdrawal-confirmation-v1";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public L1Observer create(String observerId, Map<String, String> settings) {
        return new WithdrawalConfirmationObserver(observerId, settings);
    }
}
