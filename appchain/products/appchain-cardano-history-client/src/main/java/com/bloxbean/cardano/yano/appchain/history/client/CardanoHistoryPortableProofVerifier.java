package com.bloxbean.cardano.yano.appchain.history.client;

import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotProofBundleCodec;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotProofBundleV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotCanonicalCodec;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSourceBoundary;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.util.Arrays;
import java.util.HexFormat;

/** Standalone canonical-bundle verifier. It performs no network calls. */
public final class CardanoHistoryPortableProofVerifier {
    private CardanoHistoryPortableProofVerifier() { }

    /** Verifies against the embedded root, which is never an independent anchor trust source. */
    public static Verification verify(CardanoHistoryPortableStakeProof portable) {
        if (portable == null) return Verification.INVALID;
        try {
            var embedded = portable.trustedRoot().toVerifierRoot();
            var pinned = new ProofVerifier.TrustedStateRoot(embedded.chainId(), embedded.profile(),
                    embedded.genesisIdHex(), embedded.height(), embedded.stateRootHex(),
                    ProofVerifier.TrustedRootSource.CALLER_PINNED, embedded.blockHashHex());
            return verify(portable, pinned);
        } catch (RuntimeException malformed) {
            return Verification.INVALID;
        }
    }

    /** Verifies against a root established independently by the caller. */
    public static Verification verify(CardanoHistoryPortableStakeProof portable,
                                      ProofVerifier.TrustedStateRoot independentlyTrustedRoot) {
        if (portable == null) return Verification.INVALID;
        try {
            var trusted = independentlyTrustedRoot;
            if (trusted == null) return Verification.INVALID;
            var bundle = AuthenticatedSnapshotProofBundleCodec.decode(portable.canonicalBundle());
            var descriptor = SnapshotCanonicalCodec.decodeDescriptor(bundle.descriptorBytes());
            var secondary = bundle.snapshotProof();
            if (!ProofVerifier.verifyAuthenticatedSnapshot(transport(bundle), trusted)
                    || !descriptor.seriesId().equals(CardanoHistoryClient.STAKE_COMPONENT + ".distribution")
                    || !descriptor.complete()
                    || !"epoch-stake-v1".equals(descriptor.schemaId())
                    || !"epoch-stake-source-v1".equals(descriptor.sourceCommitmentWireVersion())
                    || !(descriptor.sourceBoundary() instanceof SnapshotSourceBoundary.L1Epoch boundary)
                    || boundary.datasetEpoch() != portable.epoch()
                    || boundary.previousEpoch() != portable.epoch()
                    || portable.epoch() == Long.MAX_VALUE
                    || boundary.newEpoch() != portable.epoch() + 1
                    || !Arrays.equals(secondary.canonicalKey(), EpochStakeContract.credentialOrderKey(
                    portable.credentialType(), portable.credentialHash()))) return Verification.INVALID;

            if (!predicate(portable, secondary)) return Verification.INVALID;
            return trusted.source() == ProofVerifier.TrustedRootSource.CARDANO_ANCHOR
                    ? Verification.L1_ANCHORED_VALID
                    : Verification.ROOT_VERIFIED_ANCHOR_UNCHECKED;
        } catch (RuntimeException | StackOverflowError malformed) {
            return Verification.INVALID;
        }
    }

    private static AppChainClient.AuthenticatedSnapshotProof transport(
            AuthenticatedSnapshotProofBundleV1 bundle) {
        var descriptor = SnapshotCanonicalCodec.decodeDescriptor(bundle.descriptorBytes());
        var anchor = bundle.anchor();
        return new AppChainClient.AuthenticatedSnapshotProof(descriptor, bundle.descriptorBytes(),
                nativeProof(bundle.descriptorProof().proof()), nativeProof(bundle.snapshotProof()),
                new AppChainClient.SnapshotAnchor(anchor.chainId(), anchor.mode(),
                        anchor.anchoredHeight(), hex(anchor.stateRoot()), hex(anchor.blockHash()),
                        anchor.transactionHash(), anchor.l1Slot()),
                hex(bundle.statementCommitment()), hex(bundle.bundleCommitment()),
                bundle.canonicalBytes());
    }

    private static AppChainClient.SnapshotNativeProof nativeProof(StateProof proof) {
        var identity = proof.snapshot().identity();
        var profile = ProofVerifier.profileMetadata(identity.profile().id()).orElseThrow();
        return new AppChainClient.SnapshotNativeProof(profile.id(), profile.backend(),
                profile.commitmentFormatId(), profile.proofEncodingId(),
                profile.formatFingerprintHex(), hex(identity.genesisId()), proof.snapshot().height(),
                hex(proof.snapshot().stateRoot()), hex(proof.canonicalKey()),
                proof.value() == null ? null : hex(proof.value()),
                proof.presence().name(), hex(proof.nativeProof()));
    }

    private static boolean predicate(CardanoHistoryPortableStakeProof portable, StateProof fact) {
        if (portable.mode() == CardanoHistoryProofBundle.StakeMode.ABSENT) {
            return fact.presence() == StateProof.Presence.ABSENT;
        }
        if (fact.presence() != StateProof.Presence.PRESENT) return false;
        EpochStakeContract.Value value = EpochStakeContract.decodeValue(fact.value());
        boolean minimum = value.coin().compareTo(portable.parsedCoin()) >= 0;
        boolean exact = value.coin().equals(portable.parsedCoin());
        boolean pool = Arrays.equals(value.poolHash(), portable.poolHash());
        return switch (portable.mode()) {
            case MINIMUM -> minimum;
            case POOL -> pool;
            case MINIMUM_AND_POOL -> minimum && pool;
            case EXACT_AND_POOL -> exact && pool;
            case ABSENT -> false;
        };
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    public enum Verification {
        INVALID,
        ROOT_VERIFIED_ANCHOR_UNCHECKED,
        L1_ANCHORED_VALID
    }
}
