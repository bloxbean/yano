package com.bloxbean.cardano.yano.appchain.examples.evidence.command;

import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;

/** Stable v1 command opcodes. */
public enum EvidenceCommandOperation {
    /** Create version one and emit its two storage effects. */
    SUBMIT(0),
    /** Emit the notification effect after both storage receipts are confirmed. */
    NOTIFY(1),
    /** Create the next immutable version after a prior version is terminal. */
    REPUBLISH(2);

    private final int code;

    EvidenceCommandOperation(int code) {
        this.code = code;
    }

    /** Returns the unsigned v1 wire opcode. */
    public int code() {
        return code;
    }

    /** Resolves one exact v1 wire opcode. */
    public static EvidenceCommandOperation fromCode(long code) {
        for (EvidenceCommandOperation operation : values()) {
            if (operation.code == code) {
                return operation;
            }
        }
        throw EvidenceValidation.invalid();
    }
}
