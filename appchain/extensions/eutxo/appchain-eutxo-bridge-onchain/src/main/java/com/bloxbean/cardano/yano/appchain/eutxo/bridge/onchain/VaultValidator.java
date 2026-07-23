package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

/**
 * M3's intentionally one-way vault. No spend path exists until M4's
 * withdrawal claims and reconciliation protocol are active.
 */
@SpendingValidator
public final class VaultValidator {
    private VaultValidator() {
    }

    @Entrypoint
    public static boolean validate(
            PlutusData datum,
            PlutusData redeemer,
            ScriptContext context
    ) {
        return false;
    }
}
