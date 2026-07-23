package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;

import java.util.List;
import java.util.Objects;

/**
 * Pure deterministic boundary between Cardano transaction validation and
 * app-chain state mutation.
 */
public interface UtxoTransitionEngine {

    PreflightResult preflight(byte[] transactionCbor);

    TransitionResult transition(byte[] transactionCbor, long l1Slot, AppStateReader state);

    record PreflightResult(boolean accepted, String transactionId, String code, String detail) {
        public PreflightResult {
            transactionId = Objects.requireNonNullElse(transactionId, "");
            code = Objects.requireNonNullElse(code, "");
            detail = Objects.requireNonNullElse(detail, "");
        }

        public static PreflightResult accept(String transactionId) {
            return new PreflightResult(true, transactionId, "", "");
        }

        public static PreflightResult reject(String transactionId, String code, String detail) {
            return new PreflightResult(false, transactionId, code, detail);
        }
    }

    record TransitionResult(
            boolean accepted,
            String transactionId,
            List<EutxoOutpoint> consumed,
            List<EutxoRecord> created,
            String code,
            String detail
    ) {
        public TransitionResult {
            transactionId = Objects.requireNonNullElse(transactionId, "");
            consumed = List.copyOf(Objects.requireNonNull(consumed, "consumed"));
            created = List.copyOf(Objects.requireNonNull(created, "created"));
            code = Objects.requireNonNullElse(code, "");
            detail = Objects.requireNonNullElse(detail, "");
            if (!accepted && (!consumed.isEmpty() || !created.isEmpty())) {
                throw new IllegalArgumentException("rejected transitions must not contain a mutation");
            }
        }

        public static TransitionResult accept(
                String transactionId,
                List<EutxoOutpoint> consumed,
                List<EutxoRecord> created
        ) {
            return new TransitionResult(true, transactionId, consumed, created, "", "");
        }

        public static TransitionResult reject(String transactionId, String code, String detail) {
            return new TransitionResult(false, transactionId, List.of(), List.of(), code, detail);
        }
    }
}
