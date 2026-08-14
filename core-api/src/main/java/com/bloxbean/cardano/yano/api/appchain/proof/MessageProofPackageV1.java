package com.bloxbean.cardano.yano.api.appchain.proof;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppAnchorCommitment;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.MessageInclusionProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;

import java.util.Map;
import java.util.Objects;

/** Portable ADR-037 message statement. Embedded verification is explanatory only. */
public record MessageProofPackageV1(
        String schema,
        String chainId,
        StateCommitmentIdentity applicationIdentity,
        byte[] messageId,
        AppMessage suppliedMessage,
        MessageInclusionProof messageInclusionProof,
        EvidenceBundle evidence,
        StateProofEnvelope blockMessageRootProof,
        StateProofEnvelope stateRecordProof,
        AppAnchorCommitment anchorReference,
        VerificationPolicy verificationPolicy,
        ProofLabVocabulary.Availability localAvailability,
        Map<String, Object> verification
) {
    public static final String SCHEMA = "appchain-message-proof-v1";

    public MessageProofPackageV1 {
        if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("unsupported message package schema");
        chainId = Objects.requireNonNull(chainId, "chainId");
        if (chainId.isBlank() || chainId.length() > 128) {
            throw new IllegalArgumentException("invalid message package chain id");
        }
        applicationIdentity = Objects.requireNonNull(applicationIdentity, "applicationIdentity");
        messageId = Objects.requireNonNull(messageId, "messageId").clone();
        if (messageId.length != 32) throw new IllegalArgumentException("messageId must contain 32 bytes");
        messageInclusionProof = Objects.requireNonNull(messageInclusionProof, "messageInclusionProof");
        verificationPolicy = Objects.requireNonNull(verificationPolicy, "verificationPolicy");
        localAvailability = Objects.requireNonNull(localAvailability, "localAvailability");
        verification = verification == null ? Map.of() : Map.copyOf(verification);
    }

    @Override public byte[] messageId() { return messageId.clone(); }

    public enum VerificationPolicy {
        FINALITY_ONLY,
        NODE_CONFIRMED_ANCHOR_LINKAGE,
        CALLER_PINNED_ROOT_OR_ANCHOR,
        INDEPENDENT_CARDANO_ANCHOR
    }
}
