package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.evidence.MessageInclusionProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofSubject;

import java.util.Arrays;
import java.util.Objects;

/**
 * Portable verification input for a finalized-message claim and an optional typed state fact.
 * The bundle deliberately makes no data-availability claim: a body supplied to this verifier
 * proves only its content/signature binding to the finalized message id.
 */
public record PortableProofBundle<T>(
        int schemaVersion,
        MessageInclusionProof messageProof,
        AppMessage suppliedMessage,
        StateProofSubject<T> stateSubject,
        AppChainClient.TypedProof<T> stateProof
) {
    public static final int SCHEMA_VERSION = 1;

    public PortableProofBundle {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("portable proof bundle schemaVersion must be 1");
        }
        messageProof = Objects.requireNonNull(messageProof, "messageProof");
        if ((stateSubject == null) != (stateProof == null)) {
            throw new IllegalArgumentException("state subject and proof must be supplied together");
        }
        if (stateSubject != null
                && (!stateSubject.subjectType().equals(stateProof.subjectType())
                || !Arrays.equals(stateSubject.canonicalKey(),
                Hex.decode(stateProof.proof().keyHex())))) {
            throw new IllegalArgumentException("typed state proof does not match its subject");
        }
    }

    public PortableProofBundle(MessageInclusionProof messageProof) {
        this(SCHEMA_VERSION, messageProof, null, null, null);
    }

    /** Verify against roots/block identity obtained independently of the proof-serving node. */
    public VerificationResult verify(
            TrustedBlock trustedBlock,
            ProofVerifier.TrustedStateRoot trustedStateRoot
    ) {
        boolean finalized = trustedBlock != null && messageProof.verifies(
                trustedBlock.chainId(), trustedBlock.height(), trustedBlock.blockHash(),
                trustedBlock.messagesRoot(), messageProof.messageId());
        ContentStatus content = suppliedMessage == null
                ? ContentStatus.ID_ONLY
                : validSuppliedMessage(suppliedMessage, messageProof)
                ? ContentStatus.SUPPLIED_CONTENT_VERIFIED
                : ContentStatus.SUPPLIED_CONTENT_INVALID;
        StateFactStatus stateFact;
        if (stateProof == null) {
            stateFact = StateFactStatus.NOT_INCLUDED;
        } else if (trustedStateRoot == null) {
            stateFact = StateFactStatus.TRUSTED_ROOT_REQUIRED;
        } else {
            stateFact = ProofVerifier.verify(stateProof.proof(), trustedStateRoot)
                    ? StateFactStatus.VERIFIED : StateFactStatus.INVALID;
        }
        return new VerificationResult(
                finalized ? FinalizationStatus.VERIFIED : FinalizationStatus.INVALID,
                content, stateFact, AvailabilityStatus.NOT_PROVEN);
    }

    private static boolean validSuppliedMessage(
            AppMessage message,
            MessageInclusionProof proof
    ) {
        try {
            return Arrays.equals(message.getMessageId(), proof.messageId())
                    && proof.chainId().equals(message.getChainId())
                    && message.hasValidMessageId()
                    && message.getAuthProof() != null
                    && message.getAuthProof().length == 64
                    && CryptoConfiguration.INSTANCE.getSigningProvider().verify(
                    message.getAuthProof(), message.signedBodyBytes(), message.getSender());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    public record TrustedBlock(
            String chainId,
            long height,
            byte[] blockHash,
            byte[] messagesRoot,
            Source source
    ) {
        public TrustedBlock {
            chainId = Objects.requireNonNull(chainId, "chainId");
            if (height <= 0 || blockHash == null || blockHash.length != 32
                    || messagesRoot == null || messagesRoot.length != 32) {
                throw new IllegalArgumentException("invalid trusted block identity");
            }
            blockHash = blockHash.clone();
            messagesRoot = messagesRoot.clone();
            source = Objects.requireNonNull(source, "source");
        }

        @Override public byte[] blockHash() { return blockHash.clone(); }
        @Override public byte[] messagesRoot() { return messagesRoot.clone(); }

        public enum Source {
            FINALITY_CERTIFICATE,
            CARDANO_ANCHOR,
            CALLER_PINNED
        }
    }

    public record VerificationResult(
            FinalizationStatus finalization,
            ContentStatus content,
            StateFactStatus stateFact,
            AvailabilityStatus availability
    ) {
        public boolean valid() {
            return finalization == FinalizationStatus.VERIFIED
                    && content != ContentStatus.SUPPLIED_CONTENT_INVALID
                    && stateFact != StateFactStatus.INVALID
                    && stateFact != StateFactStatus.TRUSTED_ROOT_REQUIRED;
        }
    }

    public enum FinalizationStatus { VERIFIED, INVALID }
    public enum ContentStatus { ID_ONLY, SUPPLIED_CONTENT_VERIFIED, SUPPLIED_CONTENT_INVALID }
    public enum StateFactStatus { NOT_INCLUDED, TRUSTED_ROOT_REQUIRED, VERIFIED, INVALID }
    public enum AvailabilityStatus { NOT_PROVEN }
}
