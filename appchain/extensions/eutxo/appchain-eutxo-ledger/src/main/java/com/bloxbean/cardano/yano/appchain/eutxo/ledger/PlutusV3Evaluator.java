package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;

import java.util.List;

/**
 * Family-private phase-2 boundary. The state machine owns deterministic
 * mutation; an implementation may use Scalus to validate the admitted script
 * family against the exact resolved inputs.
 */
interface PlutusV3Evaluator {
    Evaluation evaluate(byte[] transactionCbor, List<EutxoRecord> resolvedInputs);

    record Evaluation(boolean valid, String code, String detail) {
        static Evaluation accept() {
            return new Evaluation(true, "ACCEPTED", "");
        }

        static Evaluation reject(String code, String detail) {
            return new Evaluation(false, code, detail);
        }
    }
}
