package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionDomain;

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
            byte[] canonicalTransaction,
            EutxoTransactionDomain validityDomain,
            List<EutxoRecord> resolvedInputs,
            List<EutxoOutpoint> consumed,
            List<EutxoRecord> created,
            String code,
            String detail
    ) {
        public TransitionResult {
            transactionId = Objects.requireNonNullElse(transactionId, "");
            canonicalTransaction = Objects.requireNonNull(
                    canonicalTransaction, "canonicalTransaction").clone();
            resolvedInputs = List.copyOf(Objects.requireNonNull(
                    resolvedInputs, "resolvedInputs"));
            consumed = List.copyOf(Objects.requireNonNull(consumed, "consumed"));
            created = List.copyOf(Objects.requireNonNull(created, "created"));
            code = Objects.requireNonNullElse(code, "");
            detail = Objects.requireNonNullElse(detail, "");
            if (accepted && canonicalTransaction.length == 0) {
                throw new IllegalArgumentException(
                        "accepted transitions require canonical transaction bytes");
            }
            if (accepted && resolvedInputs.size() != consumed.size()) {
                throw new IllegalArgumentException(
                        "resolved input records must match consumed outpoints");
            }
            if (!accepted && (canonicalTransaction.length != 0
                    || validityDomain != null
                    || !resolvedInputs.isEmpty()
                    || !consumed.isEmpty() || !created.isEmpty())) {
                throw new IllegalArgumentException("rejected transitions must not contain a mutation");
            }
        }

        @Override
        public byte[] canonicalTransaction() {
            return canonicalTransaction.clone();
        }

        public static TransitionResult accept(
                String transactionId,
                byte[] canonicalTransaction,
                EutxoTransactionDomain validityDomain,
                List<EutxoRecord> resolvedInputs,
                List<EutxoOutpoint> consumed,
                List<EutxoRecord> created
        ) {
            return new TransitionResult(
                    true, transactionId, canonicalTransaction, validityDomain,
                    resolvedInputs,
                    consumed, created, "", "");
        }

        public static TransitionResult reject(String transactionId, String code, String detail) {
            return new TransitionResult(
                    false, transactionId, new byte[0], null, List.of(),
                    List.of(), List.of(), code, detail);
        }
    }
}
