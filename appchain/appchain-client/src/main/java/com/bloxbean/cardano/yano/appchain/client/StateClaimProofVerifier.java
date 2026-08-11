package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider;
import com.bloxbean.cardano.yano.api.appchain.proof.StateClaimProofPackageV1;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Independent bounded verifier for appchain-state-claim-proof-v1. */
public final class StateClaimProofVerifier {
    private StateClaimProofVerifier() {
    }

    public static Result verify(StateClaimProofPackageV1 bundle,
                                ProofSubjectProvider provider,
                                TrustContext trust) {
        if (bundle == null || provider == null || trust == null) return Result.invalid();
        try {
            var descriptor = provider.descriptors(null).stream()
                    .filter(value -> value.subjectId().equals(bundle.descriptor().subjectId()))
                    .findFirst().orElseThrow();
            boolean descriptorBound = descriptor.subjectVersion() == bundle.descriptor().subjectVersion()
                    && descriptor.componentId().equals(bundle.descriptor().componentId())
                    && descriptor.descriptorDigest().equals(bundle.descriptor().descriptorDigest());
            var resolved = provider.resolve(descriptor.subjectId(),
                    bundle.normalizedCoordinates(), ProofSubjectProvider.ProofView.latest());
            boolean keyBound = resolved.normalizedCoordinates().equals(bundle.normalizedCoordinates())
                    && Arrays.equals(resolved.physicalKey(),
                    bundle.primaryProof().proof().canonicalKey());
            StateProof proof = bundle.primaryProof().proof();
            boolean rootBound = bundle.primaryProof().chainId().equals(trust.chainId())
                    && proof.snapshot().identity().equals(trust.identity())
                    && proof.snapshot().height() == trust.height()
                    && Arrays.equals(proof.snapshot().stateRoot(), trust.stateRoot());
            AppChainClient.ProofPresence presence = AppChainClient.ProofPresence.valueOf(
                    proof.presence().name());
            boolean nativeProofValid = descriptorBound && keyBound && rootBound
                    && ProofVerifier.verifyNative(proof.snapshot().identity().profile().id(), presence,
                    trust.stateRoot(), resolved.physicalKey(), proof.value(), proof.nativeProof());
            boolean factBound = Arrays.equals(proof.value(), bundle.authenticatedFactBytes());
            ProofSubjectProvider.TypedFact fact = nativeProofValid
                    && factBound && proof.presence() == StateProof.Presence.PRESENT
                    ? provider.decode(descriptor.subjectId(), proof.value()) : null;
            ProofSubjectProvider.ClaimResult claimResult = bundle.claim() == null || fact == null
                    ? null : provider.evaluate(descriptor.subjectId(), fact, bundle.claim());
            boolean semantic = bundle.claim() == null
                    ? fact != null : claimResult != null && claimResult.evaluated()
                    && claimResult.satisfied();
            boolean accepted = nativeProofValid && factBound && semantic
                    && trust.trustLevel() != ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY;
            return new Result(descriptorBound, keyBound, rootBound, nativeProofValid, factBound,
                    fact, claimResult, trust.trustLevel(), accepted);
        } catch (RuntimeException | StackOverflowError malformed) {
            return Result.invalid();
        }
    }

    public record TrustContext(String chainId, StateCommitmentIdentity identity,
                               long height, byte[] stateRoot,
                               ProofLabVocabulary.TrustLevel trustLevel) {
        public TrustContext {
            chainId = Objects.requireNonNull(chainId, "chainId");
            identity = Objects.requireNonNull(identity, "identity");
            if (height < 1 || stateRoot == null || stateRoot.length != identity.profile().rootLength()) {
                throw new IllegalArgumentException("invalid trusted state claim root");
            }
            stateRoot = stateRoot.clone();
            trustLevel = Objects.requireNonNull(trustLevel, "trustLevel");
        }
        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }

    public record Result(boolean descriptorBound, boolean keyBound, boolean rootBound,
                         boolean nativeProofValid, boolean factBound,
                         ProofSubjectProvider.TypedFact fact,
                         ProofSubjectProvider.ClaimResult claimResult,
                         ProofLabVocabulary.TrustLevel trust, boolean accepted) {
        private static Result invalid() {
            return new Result(false, false, false, false, false, null, null,
                    ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY, false);
        }
    }
}
