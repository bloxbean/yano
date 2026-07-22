package com.bloxbean.cardano.yano.appchain.examples.evidence.command;

import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;

/** Idempotent request to publish the notification for one storage-ready version. */
public record NotifyEvidenceCommandV1(String evidenceId,
                                      long businessVersion) implements EvidenceCommandV1 {
    /** Validates the identifier and positive business version. */
    public NotifyEvidenceCommandV1 {
        evidenceId = EvidenceValidation.evidenceId(evidenceId);
        businessVersion = EvidenceValidation.positiveVersion(businessVersion);
    }

    @Override
    public EvidenceCommandOperation operation() {
        return EvidenceCommandOperation.NOTIFY;
    }

    @Override
    public byte[] encode() {
        return EvidenceCommandCodec.encodeNotify(this);
    }
}
