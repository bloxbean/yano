package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.api.appchain.state.StateProofSubject;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedMessageIndex;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;

/** Release-matched typed subjects for first-party authenticated application facts. */
public final class ProofSubjects {
    private ProofSubjects() {
    }

    public static byte[] compositeKey(String componentId, byte[] localKey) {
        return CompositeCommitmentV1.componentKey(componentId, localKey);
    }

    public static StateProofSubject<FinalizedMessageIndex.MessageRecord> finalizedMessage(
            byte[] messageId
    ) {
        return subject("finalized-message-v1",
                FinalizedMessageIndex.messageKey(exact32(messageId, "messageId")),
                FinalizedMessageIndex::decode);
    }

    public static StateProofSubject<FinalizedMessageIndex.MessageRecord>
    compositeFinalizedMessage(String componentId, byte[] messageId) {
        return componentSubject("finalized-message-v1", componentId,
                FinalizedMessageIndex.messageKey(exact32(messageId, "messageId")),
                FinalizedMessageIndex::decode);
    }

    public static StateProofSubject<DocTrailContract.Head> documentHead(
            String componentId,
            String entityId
    ) {
        return componentSubject("document-head-v1", componentId,
                DocTrailContract.entityKey(entityId), DocTrailContract::decodeHead);
    }

    public static StateProofSubject<AuthenticatedMapContract.Entry> authenticatedMapEntry(
            String componentId,
            String collectionId,
            byte[] applicationKey
    ) {
        return componentSubject("authenticated-map-entry-v1", componentId,
                AuthenticatedMapContract.canonicalKey(collectionId, applicationKey),
                AuthenticatedMapContract::decodeEntry);
    }

    public static StateProofSubject<ApprovalProposalV1> approvalOutcome(
            String componentId,
            String proposalId
    ) {
        return componentSubject("role-approval-outcome-v1", componentId,
                RoleWorkflowKeys.proposal(proposalId), ApprovalProposalV1::decode);
    }

    public static StateProofSubject<byte[]> epochProtocolParameters(
            String componentId,
            long epoch
    ) {
        return componentSubject("l1-epoch-protocol-parameters-v1", componentId,
                HistoricalL1StateKeys.protocolParameters(epoch), byte[]::clone);
    }

    public static StateProofSubject<byte[]> epochStake(
            String componentId,
            long epoch,
            int credentialType,
            byte[] credentialHash
    ) {
        return componentSubject("l1-epoch-stake-v1", componentId,
                HistoricalL1StateKeys.epochStake(epoch, credentialType, credentialHash),
                byte[]::clone);
    }

    public static StateProofSubject<byte[]> compositeProfile() {
        return subject("composite-profile-v1", CompositeCommitmentV1.profileMarkerKey(),
                ProofSubjects::boundedProfile);
    }

    private static <T> StateProofSubject<T> componentSubject(
            String type,
            String componentId,
            byte[] localKey,
            Function<byte[], T> decoder
    ) {
        return subject(type, compositeKey(componentId, localKey), decoder);
    }

    private static <T> StateProofSubject<T> subject(
            String type,
            byte[] key,
            Function<byte[], T> decoder
    ) {
        return new Subject<>(type, key, decoder);
    }

    private static byte[] boundedProfile(byte[] value) {
        if (value == null || value.length == 0
                || value.length > CompositeCommitmentV1.MAX_PROFILE_BYTES) {
            throw new IllegalArgumentException("invalid canonical composite profile");
        }
        return value.clone();
    }

    private static byte[] exact32(byte[] value, String field) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException(field + " must contain 32 bytes");
        }
        return value.clone();
    }

    private record Subject<T>(
            String subjectType,
            byte[] canonicalKey,
            Function<byte[], T> decoder
    ) implements StateProofSubject<T> {
        private Subject {
            subjectType = Objects.requireNonNull(subjectType, "subjectType");
            canonicalKey = Objects.requireNonNull(canonicalKey, "canonicalKey").clone();
            if (canonicalKey.length == 0) {
                throw new IllegalArgumentException("proof subject key must not be empty");
            }
            decoder = Objects.requireNonNull(decoder, "decoder");
        }

        @Override public int schemaVersion() { return SCHEMA_VERSION; }
        @Override public byte[] canonicalKey() { return canonicalKey.clone(); }
        @Override public T decodePresentValue(byte[] value) {
            return decoder.apply(Objects.requireNonNull(value, "canonicalValue").clone());
        }
    }

    /** Canonical ADR-028 local state keys. */
    public static final class HistoricalL1StateKeys {
        private static final byte[] PARAMS = "params/".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] STAKE = "stake/".getBytes(StandardCharsets.US_ASCII);

        private HistoricalL1StateKeys() {
        }

        public static byte[] protocolParameters(long epoch) {
            requireEpoch(epoch);
            return ByteBuffer.allocate(PARAMS.length + Long.BYTES)
                    .put(PARAMS).putLong(epoch).array();
        }

        public static byte[] epochStake(long epoch, int credentialType, byte[] credentialHash) {
            requireEpoch(epoch);
            if (credentialType < 0 || credentialType > 1
                    || credentialHash == null || credentialHash.length != 28) {
                throw new IllegalArgumentException("invalid Cardano stake credential");
            }
            return ByteBuffer.allocate(STAKE.length + Long.BYTES + 1 + 28)
                    .put(STAKE).putLong(epoch).put((byte) credentialType)
                    .put(credentialHash).array();
        }

        private static void requireEpoch(long epoch) {
            if (epoch < 0) throw new IllegalArgumentException("epoch must be non-negative");
        }
    }
}
