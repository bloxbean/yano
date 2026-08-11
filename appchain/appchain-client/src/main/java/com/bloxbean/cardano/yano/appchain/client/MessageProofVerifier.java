package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceVerifier;
import com.bloxbean.cardano.yano.api.appchain.proof.MessageProofPackageV1;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedBlockMessageRootIndex;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedMessageIndex;

import java.util.Arrays;
import java.util.Objects;

/** Release-matched, side-effect-free verifier for appchain-message-proof-v1. */
public final class MessageProofVerifier {
    private MessageProofVerifier() {
    }

    public static Result verify(MessageProofPackageV1 bundle, TrustContext trust) {
        if (bundle == null) return Result.invalid("missing package");
        try {
            boolean inclusion = bundle.chainId().equals(bundle.messageInclusionProof().chainId())
                    && Arrays.equals(bundle.messageId(), bundle.messageInclusionProof().messageId())
                    && bundle.messageInclusionProof().verifiesRoot();
            ContentStatus content = content(bundle.suppliedMessage(), bundle);

            boolean identityBound = trust != null
                    && bundle.chainId().equals(trust.chainId())
                    && bundle.applicationIdentity().equals(trust.identity());
            boolean blockBound = identityBound && verifyStateProof(
                    bundle.blockMessageRootProof(), trust,
                    FinalizedBlockMessageRootIndex.blockKey(
                            bundle.messageInclusionProof().blockHeight()));
            if (blockBound) {
                var record = FinalizedBlockMessageRootIndex.decode(
                        bundle.blockMessageRootProof().proof().value());
                blockBound = record.height() == bundle.messageInclusionProof().blockHeight()
                        && record.messageCount() == bundle.messageInclusionProof().leafCount()
                        && Arrays.equals(record.messagesRoot(),
                        bundle.messageInclusionProof().messagesRoot());
            }

            boolean directRecorded = identityBound && verifyStateProof(
                    bundle.stateRecordProof(), trust,
                    FinalizedMessageIndex.messageKey(bundle.messageId()));
            if (directRecorded) {
                var record = FinalizedMessageIndex.decode(bundle.stateRecordProof().proof().value());
                directRecorded = record.height() == bundle.messageInclusionProof().blockHeight()
                        && record.originalMessageIndex()
                        == bundle.messageInclusionProof().messageIndex();
                if (directRecorded && bundle.suppliedMessage() != null) {
                    directRecorded = record.topic().equals(bundle.suppliedMessage().getTopic())
                            && Arrays.equals(record.sender(), bundle.suppliedMessage().getSender());
                }
            }

            EvidenceVerifier.Result evidence = trust == null || trust.evidenceTrust() == null
                    || bundle.evidence() == null ? null
                    : EvidenceVerifier.verify(bundle.evidence(), trust.evidenceTrust());
            boolean certificateFinality = evidence != null && evidence.valid();
            boolean anchorBound = identityBound && bundle.anchorReference() != null
                    && bundle.anchorReference().chainId().equals(trust.chainId())
                    && bundle.anchorReference().anchoredHeight() == trust.height()
                    && Arrays.equals(bundle.anchorReference().stateRoot(), trust.stateRoot())
                    && (trust.blockHash() == null || Arrays.equals(
                    bundle.anchorReference().blockHash(), trust.blockHash()));
            ProofLabVocabulary.FinalityEvidence finality = blockBound && certificateFinality
                    ? ProofLabVocabulary.FinalityEvidence.BOTH
                    : blockBound ? ProofLabVocabulary.FinalityEvidence.AUTHENTICATED_BLOCK_RECORD
                    : certificateFinality ? ProofLabVocabulary.FinalityEvidence.CERTIFICATE
                    : ProofLabVocabulary.FinalityEvidence.UNVERIFIED;
            boolean accepted = inclusion && content != ContentStatus.INVALID && identityBound
                    && (blockBound || certificateFinality)
                    && trust.trustLevel()
                    != ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY;
            return new Result(inclusion, content, identityBound, finality, blockBound,
                    directRecorded, anchorBound,
                    ProofLabVocabulary.Availability.NOT_PROVEN, trust == null
                    ? ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY
                    : trust.trustLevel(), accepted, accepted ? null : "required checks did not pass");
        } catch (RuntimeException malformed) {
            return Result.invalid("malformed package");
        }
    }

    private static boolean verifyStateProof(
            StateProofEnvelope envelope,
            TrustContext trust,
            byte[] expectedKey
    ) {
        if (envelope == null || trust == null || !trust.chainId().equals(envelope.chainId())) {
            return false;
        }
        StateProof proof = envelope.proof();
        return proof.presence() == StateProof.Presence.PRESENT
                && proof.snapshot().identity().equals(trust.identity())
                && proof.snapshot().height() == trust.height()
                && Arrays.equals(proof.snapshot().stateRoot(), trust.stateRoot())
                && Arrays.equals(proof.canonicalKey(), expectedKey)
                && ProofVerifier.verifyNative(proof.snapshot().identity().profile().id(),
                AppChainClient.ProofPresence.PRESENT, trust.stateRoot(), expectedKey,
                proof.value(), proof.nativeProof());
    }

    private static ContentStatus content(AppMessage message, MessageProofPackageV1 bundle) {
        if (message == null || message.getAuthProof() == null
                || message.getAuthProof().length == 0) return ContentStatus.NOT_SUPPLIED;
        try {
            return Arrays.equals(message.getMessageId(), bundle.messageId())
                    && bundle.chainId().equals(message.getChainId())
                    && message.hasValidMessageId()
                    && message.getAuthProof().length == 64
                    && CryptoConfiguration.INSTANCE.getSigningProvider().verify(
                    message.getAuthProof(), message.signedBodyBytes(), message.getSender())
                    ? ContentStatus.VERIFIED : ContentStatus.INVALID;
        } catch (RuntimeException malformed) {
            return ContentStatus.INVALID;
        }
    }

    public record TrustContext(
            String chainId,
            StateCommitmentIdentity identity,
            long height,
            byte[] stateRoot,
            byte[] blockHash,
            ProofLabVocabulary.TrustLevel trustLevel,
            EvidenceVerifier.TrustContext evidenceTrust
    ) {
        public TrustContext {
            chainId = Objects.requireNonNull(chainId, "chainId");
            identity = Objects.requireNonNull(identity, "identity");
            if (height < 1 || stateRoot == null || stateRoot.length != 32
                    || blockHash != null && blockHash.length != 32) {
                throw new IllegalArgumentException("invalid trusted message root");
            }
            stateRoot = stateRoot.clone();
            blockHash = blockHash == null ? null : blockHash.clone();
            trustLevel = Objects.requireNonNull(trustLevel, "trustLevel");
        }

        @Override public byte[] stateRoot() { return stateRoot.clone(); }
        @Override public byte[] blockHash() { return blockHash == null ? null : blockHash.clone(); }
    }

    public record Result(
            boolean messageIncluded,
            ContentStatus content,
            boolean identityBound,
            ProofLabVocabulary.FinalityEvidence finalityEvidence,
            boolean messageRootStateBound,
            boolean stateRecorded,
            boolean anchorBound,
            ProofLabVocabulary.Availability availability,
            ProofLabVocabulary.TrustLevel trust,
            boolean accepted,
            String reason
    ) {
        static Result invalid(String reason) {
            return new Result(false, ContentStatus.INVALID, false,
                    ProofLabVocabulary.FinalityEvidence.UNVERIFIED, false, false, false,
                    ProofLabVocabulary.Availability.NOT_PROVEN,
                    ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY, false, reason);
        }
    }

    public enum ContentStatus { NOT_SUPPLIED, VERIFIED, INVALID }
}
