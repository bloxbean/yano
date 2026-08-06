package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import java.util.Objects;
import java.util.Optional;

/**
 * Crash-safe WAL for A2 batch settlement, keyed by the effect idempotency
 * hash ({@code effect.idHash()}). The executor records progress so a retry
 * or restart resumes at the recorded stage instead of rebuilding and
 * double-submitting.
 */
public interface BatchSettlementJournal {
    Optional<Entry> find(String effectKey);

    void save(Entry entry);

    enum Stage {
        BUILT,
        SUBMITTED,
        CONFIRMED,
        FAILED
    }

    record Entry(String effectKey, Stage stage, String transactionId) {
        public Entry {
            Objects.requireNonNull(effectKey, "effectKey");
            if (effectKey.isBlank()) {
                throw new IllegalArgumentException("effectKey is required");
            }
            Objects.requireNonNull(stage, "stage");
            transactionId = transactionId == null ? "" : transactionId;
        }
    }
}
