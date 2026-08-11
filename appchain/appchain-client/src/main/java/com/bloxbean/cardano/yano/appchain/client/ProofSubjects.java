package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.api.appchain.state.StateProofSubject;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedMessageIndex;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedBlockMessageRootIndex;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsCanonicalCodec;

import java.math.BigInteger;
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

    public static StateProofSubject<FinalizedBlockMessageRootIndex.BlockRecord>
    finalizedBlockMessages(long height) {
        return subject(FinalizedBlockMessageRootIndex.SUBJECT_ID,
                FinalizedBlockMessageRootIndex.blockKey(height),
                FinalizedBlockMessageRootIndex::decode);
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
        return componentSubject(EpochParamsContract.PROOF_SUBJECT, componentId,
                HistoricalL1StateKeys.protocolParameters(epoch),
                value -> ProtocolParamsCanonicalCodec.validate(epoch, value));
    }

    public static StateProofSubject<ProtocolParamsCanonicalCodec.Field> epochProtocolParameterField(
            String componentId,
            long epoch,
            String fieldId
    ) {
        Objects.requireNonNull(fieldId, "fieldId");
        return componentSubject(EpochParamsContract.FIELD_PROOF_SUBJECT, componentId,
                EpochParamsContract.fieldKey(epoch, fieldId), value -> {
                    var field = ProtocolParamsCanonicalCodec.decodeLeaf(value);
                    if (!field.id().equals(fieldId)) {
                        throw new IllegalArgumentException("protocol-parameter field id mismatch");
                    }
                    return field;
                });
    }

    public static StateProofSubject<EpochStakeContract.Value> epochStake(
            String componentId,
            long epoch,
            int credentialType,
            byte[] credentialHash
    ) {
        return componentSubject(EpochStakeContract.PROOF_SUBJECT, componentId,
                HistoricalL1StateKeys.epochStake(epoch, credentialType, credentialHash),
                EpochStakeContract::decodeValue);
    }

    public static StateProofSubject<EpochStakeContract.Meta> epochStakeCompleteness(
            String componentId, long epoch) {
        return componentSubject(EpochStakeContract.PROOF_SUBJECT + "/completeness",
                componentId, EpochStakeContract.metaKey(epoch), EpochStakeContract::decodeMeta);
    }

    public static StateProofSubject<EpochGovernanceContract.ProposalValue> governanceProposal(
            String componentId, long epoch, byte[] transactionId, int governanceActionIndex) {
        return componentSubject(EpochGovernanceContract.PROPOSAL_PROOF_SUBJECT, componentId,
                EpochGovernanceContract.proposalKey(epoch, transactionId, governanceActionIndex),
                EpochGovernanceContract::decodeProposalValue);
    }

    public static StateProofSubject<EpochGovernanceContract.ProposalMeta>
    governanceProposalCompleteness(String componentId, long epoch) {
        return componentSubject(EpochGovernanceContract.PROPOSAL_PROOF_SUBJECT + "/completeness",
                componentId, EpochGovernanceContract.proposalMetaKey(epoch),
                EpochGovernanceContract::decodeProposalMeta);
    }

    public static StateProofSubject<BigInteger> drepDistribution(
            String componentId, long epoch, int drepType, byte[] drepHash) {
        return componentSubject(EpochGovernanceContract.DREP_PROOF_SUBJECT, componentId,
                EpochGovernanceContract.drepKey(epoch, drepType, drepHash),
                EpochGovernanceContract::decodeCoin);
    }

    public static StateProofSubject<EpochGovernanceContract.DRepMeta>
    drepDistributionCompleteness(String componentId, long epoch) {
        return componentSubject(EpochGovernanceContract.DREP_PROOF_SUBJECT + "/completeness",
                componentId, EpochGovernanceContract.drepMetaKey(epoch),
                EpochGovernanceContract::decodeDRepMeta);
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
        private HistoricalL1StateKeys() {
        }

        public static byte[] protocolParameters(long epoch) {
            return EpochParamsContract.stateKey(epoch);
        }

        public static byte[] epochStake(long epoch, int credentialType, byte[] credentialHash) {
            return EpochStakeContract.entryKey(epoch, credentialType, credentialHash);
        }
    }
}
