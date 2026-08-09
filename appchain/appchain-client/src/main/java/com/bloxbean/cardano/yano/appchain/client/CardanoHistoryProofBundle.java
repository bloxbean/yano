package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.api.appchain.anchor.AnchorDatumV1;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofSubject;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsCanonicalCodec;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/**
 * Portable ADR-028 semantic proof bundles. Every history claim binds its exact
 * physical composite key and, where required, a completeness leaf at the same
 * independently authenticated state root.
 */
public final class CardanoHistoryProofBundle {
    private CardanoHistoryProofBundle() { }

    public enum AmountMode { MINIMUM, EXACT, ABSENT }
    public enum StakeMode { MINIMUM, POOL, MINIMUM_AND_POOL, EXACT_AND_POOL, ABSENT }

    public record AnchorIdentity(String chainId, byte[] genesisId, String applicationId) {
        public AnchorIdentity {
            if (chainId == null || chainId.isBlank() || genesisId == null || genesisId.length != 32
                    || applicationId == null || applicationId.isBlank())
                throw new IllegalArgumentException("invalid Cardano History anchor identity");
            genesisId = genesisId.clone();
        }
        @Override public byte[] genesisId() { return genesisId.clone(); }
        public ProofVerifier.TrustedStateRoot trustedRoot(AnchorDatumV1 anchor) {
            return ProofVerifier.trustedRootFromCardanoAnchor(
                    anchor, chainId, genesisId, applicationId);
        }
    }

    public record ProtocolParameters(long epoch, String componentId,
                                     AppChainClient.TypedProof<byte[]> fact) {
        public boolean verify(AnchorDatumV1 anchor, AnchorIdentity identity) {
            var subject = ProofSubjects.epochProtocolParameters(componentId, epoch);
            return verifyPresent(fact, subject, identity.trustedRoot(anchor))
                    && Arrays.equals(fact.decodedValue(),
                    ProtocolParamsCanonicalCodec.validate(epoch, fact.decodedValue()));
        }
    }

    public record Stake(long epoch, String componentId, int credentialType,
                        byte[] credentialHash, StakeMode mode, BigInteger coin,
                        byte[] poolHash,
                        AppChainClient.TypedProof<EpochStakeContract.Value> fact,
                        AppChainClient.TypedProof<EpochStakeContract.Meta> completeness) {
        public Stake {
            credentialHash = cloneExact(credentialHash, 28, "credentialHash");
            poolHash = poolHash == null ? new byte[0] : poolHash.clone();
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(coin, "coin");
            if (coin.signum() < 0 || mode != StakeMode.ABSENT && mode != StakeMode.MINIMUM
                    && poolHash.length != 28) throw new IllegalArgumentException("invalid stake predicate");
        }
        @Override public byte[] credentialHash() { return credentialHash.clone(); }
        @Override public byte[] poolHash() { return poolHash.clone(); }
        public boolean verify(AnchorDatumV1 anchor, AnchorIdentity identity) {
            ProofVerifier.TrustedStateRoot root = identity.trustedRoot(anchor);
            var factSubject = ProofSubjects.epochStake(
                    componentId, epoch, credentialType, credentialHash);
            var completeSubject = ProofSubjects.epochStakeCompleteness(componentId, epoch);
            if (!verifyProof(fact, factSubject, root) || !verifyPresent(completeness, completeSubject, root)
                    || completeness.decodedValue().manifest().epoch() != epoch
                    || !completeness.decodedValue().complete()) return false;
            if (mode == StakeMode.ABSENT)
                return fact.proof().presence() == AppChainClient.ProofPresence.ABSENT;
            if (fact.proof().presence() != AppChainClient.ProofPresence.PRESENT
                    || fact.decodedValue() == null) return false;
            EpochStakeContract.Value value = fact.decodedValue();
            boolean amount = value.coin().compareTo(coin) >= 0;
            boolean exact = value.coin().equals(coin);
            boolean pool = Arrays.equals(value.poolHash(), poolHash);
            return switch (mode) {
                case MINIMUM -> amount;
                case POOL -> pool;
                case MINIMUM_AND_POOL -> amount && pool;
                case EXACT_AND_POOL -> exact && pool;
                case ABSENT -> false;
            };
        }
    }

    /** Nested primary-descriptor + secondary MPF/JMT proof for an epoch-stake entry. */
    public record SnapshotStake(long epoch, String seriesId, int credentialType,
                                byte[] credentialHash, StakeMode mode, BigInteger coin,
                                byte[] poolHash,
                                AppChainClient.AuthenticatedSnapshotProof proof) {
        public SnapshotStake {
            credentialHash = cloneExact(credentialHash, 28, "credentialHash");
            poolHash = poolHash == null ? new byte[0] : poolHash.clone();
            Objects.requireNonNull(mode, "mode"); Objects.requireNonNull(coin, "coin");
            Objects.requireNonNull(proof, "proof");
            if (coin.signum() < 0 || mode != StakeMode.ABSENT && mode != StakeMode.MINIMUM
                    && poolHash.length != 28) throw new IllegalArgumentException("invalid stake predicate");
        }
        @Override public byte[] credentialHash() { return credentialHash.clone(); }
        @Override public byte[] poolHash() { return poolHash.clone(); }
        public boolean verify(AnchorDatumV1 anchor, AnchorIdentity identity) {
            var descriptor = proof.descriptor();
            if (!seriesId.equals(descriptor.seriesId()) || !descriptor.complete()
                    || !"epoch-stake-v1".equals(descriptor.schemaId())
                    || !"blake2b256".equals(descriptor.sourceCommitmentAlgorithm())
                    || !"epoch-stake-source-v1".equals(descriptor.sourceCommitmentWireVersion())
                    || !(descriptor.sourceBoundary()
                    instanceof com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSourceBoundary.L1Epoch boundary)
                    || boundary.datasetEpoch() != epoch || boundary.previousEpoch() != epoch
                    || epoch == Long.MAX_VALUE || boundary.newEpoch() != epoch + 1
                    || !Arrays.equals(Hex.decode(proof.secondaryProof().keyHex()),
                    EpochStakeContract.credentialOrderKey(credentialType, credentialHash))
                    || !ProofVerifier.verifyAuthenticatedSnapshot(proof, identity.trustedRoot(anchor))) {
                return false;
            }
            if (mode == StakeMode.ABSENT) return "ABSENT".equals(proof.secondaryProof().presence());
            if (!"PRESENT".equals(proof.secondaryProof().presence())
                    || proof.secondaryProof().valueHex() == null) return false;
            EpochStakeContract.Value value = EpochStakeContract.decodeValue(
                    Hex.decode(proof.secondaryProof().valueHex()));
            boolean minimum = value.coin().compareTo(coin) >= 0;
            boolean exact = value.coin().equals(coin);
            boolean pool = Arrays.equals(value.poolHash(), poolHash);
            return switch (mode) {
                case MINIMUM -> minimum;
                case POOL -> pool;
                case MINIMUM_AND_POOL -> minimum && pool;
                case EXACT_AND_POOL -> exact && pool;
                case ABSENT -> false;
            };
        }
    }

    public record Proposal(long epoch, String componentId, byte[] transactionId,
                           int governanceActionIndex,
                           EpochGovernanceContract.ActionType actionType,
                           EpochGovernanceContract.ProposalStatus status,
                           EpochGovernanceContract.ProposalReason reason,
                           AppChainClient.TypedProof<EpochGovernanceContract.ProposalValue> fact,
                           AppChainClient.TypedProof<EpochGovernanceContract.ProposalMeta> completeness) {
        public Proposal { transactionId = cloneExact(transactionId, 32, "transactionId"); }
        @Override public byte[] transactionId() { return transactionId.clone(); }
        public boolean verify(AnchorDatumV1 anchor, AnchorIdentity identity) {
            ProofVerifier.TrustedStateRoot root = identity.trustedRoot(anchor);
            var factSubject = ProofSubjects.governanceProposal(
                    componentId, epoch, transactionId, governanceActionIndex);
            var completeSubject = ProofSubjects.governanceProposalCompleteness(componentId, epoch);
            if (!verifyPresent(fact, factSubject, root)
                    || !verifyPresent(completeness, completeSubject, root)
                    || completeness.decodedValue().epoch() != epoch
                    || !completeness.decodedValue().complete()) return false;
            var value = fact.decodedValue();
            return value.actionType() == actionType && value.status() == status && value.reason() == reason;
        }
    }

    public record DRepAmount(long epoch, String componentId, int drepType,
                             byte[] drepHash, AmountMode mode, BigInteger coin,
                             AppChainClient.TypedProof<BigInteger> fact,
                             AppChainClient.TypedProof<EpochGovernanceContract.DRepMeta> completeness) {
        public DRepAmount {
            drepHash = cloneExact(drepHash, 28, "drepHash");
            Objects.requireNonNull(mode, "mode"); Objects.requireNonNull(coin, "coin");
            if (coin.signum() < 0) throw new IllegalArgumentException("coin cannot be negative");
        }
        @Override public byte[] drepHash() { return drepHash.clone(); }
        public boolean verify(AnchorDatumV1 anchor, AnchorIdentity identity) {
            ProofVerifier.TrustedStateRoot root = identity.trustedRoot(anchor);
            var factSubject = ProofSubjects.drepDistribution(componentId, epoch, drepType, drepHash);
            var completeSubject = ProofSubjects.drepDistributionCompleteness(componentId, epoch);
            if (!verifyProof(fact, factSubject, root) || !verifyPresent(completeness, completeSubject, root)
                    || completeness.decodedValue().epoch() != epoch
                    || !completeness.decodedValue().complete()) return false;
            if (mode == AmountMode.ABSENT)
                return fact.proof().presence() == AppChainClient.ProofPresence.ABSENT;
            if (fact.proof().presence() != AppChainClient.ProofPresence.PRESENT
                    || fact.decodedValue() == null) return false;
            return mode == AmountMode.EXACT ? fact.decodedValue().equals(coin)
                    : fact.decodedValue().compareTo(coin) >= 0;
        }
    }

    /** Nested primary-descriptor + secondary MPF/JMT proof for a DRep amount. */
    public record SnapshotDRepAmount(long epoch, String seriesId, int drepType,
                                     byte[] drepHash, AmountMode mode, BigInteger coin,
                                     AppChainClient.AuthenticatedSnapshotProof proof) {
        public SnapshotDRepAmount {
            drepHash = cloneExact(drepHash, 28, "drepHash");
            Objects.requireNonNull(mode, "mode"); Objects.requireNonNull(coin, "coin");
            Objects.requireNonNull(proof, "proof");
            if (coin.signum() < 0) throw new IllegalArgumentException("coin cannot be negative");
        }
        @Override public byte[] drepHash() { return drepHash.clone(); }
        public boolean verify(AnchorDatumV1 anchor, AnchorIdentity identity) {
            var descriptor = proof.descriptor();
            if (!seriesId.equals(descriptor.seriesId()) || !descriptor.complete()
                    || !"epoch-drep-distribution-v1".equals(descriptor.schemaId())
                    || !"blake2b256".equals(descriptor.sourceCommitmentAlgorithm())
                    || !"epoch-drep-source-v1".equals(descriptor.sourceCommitmentWireVersion())
                    || !(descriptor.sourceBoundary()
                    instanceof com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSourceBoundary.L1Epoch boundary)
                    || boundary.datasetEpoch() != epoch || boundary.newEpoch() != epoch
                    || boundary.previousEpoch() != (epoch == 0 ? 0 : epoch - 1)
                    || !Arrays.equals(Hex.decode(proof.secondaryProof().keyHex()),
                    EpochGovernanceContract.drepOrderKey(drepType, drepHash))
                    || !ProofVerifier.verifyAuthenticatedSnapshot(proof, identity.trustedRoot(anchor))) {
                return false;
            }
            if (mode == AmountMode.ABSENT) return "ABSENT".equals(proof.secondaryProof().presence());
            if (!"PRESENT".equals(proof.secondaryProof().presence())
                    || proof.secondaryProof().valueHex() == null) return false;
            BigInteger actual = EpochGovernanceContract.decodeCoin(
                    Hex.decode(proof.secondaryProof().valueHex()));
            return mode == AmountMode.EXACT ? actual.equals(coin) : actual.compareTo(coin) >= 0;
        }
    }

    private static <T> boolean verifyPresent(AppChainClient.TypedProof<T> proof,
                                             StateProofSubject<T> subject,
                                             ProofVerifier.TrustedStateRoot root) {
        return proof != null && proof.proof().presence() == AppChainClient.ProofPresence.PRESENT
                && proof.decodedValue() != null && verifyProof(proof, subject, root);
    }
    private static <T> boolean verifyProof(AppChainClient.TypedProof<T> proof,
                                           StateProofSubject<T> subject,
                                           ProofVerifier.TrustedStateRoot root) {
        return proof != null && proof.subjectType().equals(subject.subjectType())
                && Arrays.equals(Hex.decode(proof.proof().keyHex()), subject.canonicalKey())
                && ProofVerifier.verify(proof.proof(), root);
    }
    private static byte[] cloneExact(byte[] value, int length, String field) {
        if (value == null || value.length != length)
            throw new IllegalArgumentException(field + " must contain " + length + " bytes");
        return value.clone();
    }
}
