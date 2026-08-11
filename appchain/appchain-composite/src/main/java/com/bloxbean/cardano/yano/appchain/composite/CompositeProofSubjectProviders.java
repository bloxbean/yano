package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Component namespace adapters and the released composite profile subject. */
final class CompositeProofSubjectProviders {
    static final String PROFILE_SUBJECT = "composite-profile-v1";

    private CompositeProofSubjectProviders() {
    }

    static ProofSubjectProvider component(String componentId,
                                          ProofSubjectProvider delegate,
                                          ProofSubjectDescriptorV1 descriptor) {
        String exposedId = componentId + ":" + descriptor.subjectId();
        ProofSubjectDescriptorV1 exposed = descriptor.withIdentity(exposedId, componentId);
        return new ProofSubjectProvider() {
            @Override public List<ProofSubjectDescriptorV1> descriptors(AppCapabilityManifest profile) {
                return List.of(exposed);
            }
            @Override public ResolvedProofSubject resolve(String subjectId,
                                                          Map<String, String> coordinates,
                                                          ProofView view) {
                require(exposedId, subjectId);
                var local = delegate.resolve(descriptor.subjectId(), coordinates, view);
                return new ResolvedProofSubject(exposedId, local.normalizedCoordinates(),
                        local.canonicalLogicalKey(), CompositeCommitmentV1.componentKey(
                                componentId, local.physicalKey()));
            }
            @Override public TypedFact decode(String subjectId, byte[] canonicalValue) {
                require(exposedId, subjectId);
                var local = delegate.decode(descriptor.subjectId(), canonicalValue);
                return new TypedFact(exposedId, local.fields());
            }
            @Override public ClaimResult evaluate(String subjectId, TypedFact fact,
                                                  ClaimRequest claim) {
                require(exposedId, subjectId);
                return delegate.evaluate(descriptor.subjectId(),
                        new TypedFact(descriptor.subjectId(), fact.fields()), claim);
            }
        };
    }

    static ProofSubjectProvider profile() {
        ProofSubjectDescriptorV1 descriptor = new ProofSubjectDescriptorV1(
                1, PROFILE_SUBJECT, 1, "", "Composite application profile",
                "The authenticated canonical component and workflow profile active at genesis.", "",
                ProofLabVocabulary.StorageScope.PRIMARY_STATE, List.of(),
                List.of(new ProofSubjectDescriptorV1.Claim("digest-equals", List.of("expected"),
                        List.of(ProofSubjectDescriptorV1.ValueType.DIGEST_HEX))),
                List.of(new ProofSubjectDescriptorV1.FactField("profile-digest",
                        ProofSubjectDescriptorV1.ValueType.DIGEST_HEX, "Profile digest", "")),
                ProofLabVocabulary.Completeness.NONE,
                List.of(ProofLabVocabulary.VerificationTarget.OFFCHAIN_MPF,
                        ProofLabVocabulary.VerificationTarget.OFFCHAIN_JMT),
                new ProofSubjectDescriptorV1.RetentionHints(true, false,
                        "Query an archival node when the selected proof height is pruned."),
                ProofSubjectDescriptorV1.Limits.defaults());
        return new ProofSubjectProvider() {
            @Override public List<ProofSubjectDescriptorV1> descriptors(AppCapabilityManifest profile) {
                return List.of(descriptor);
            }
            @Override public ResolvedProofSubject resolve(String subjectId,
                                                          Map<String, String> coordinates,
                                                          ProofView view) {
                require(PROFILE_SUBJECT, subjectId);
                if (coordinates == null || !coordinates.isEmpty()) throw invalid();
                byte[] key = CompositeCommitmentV1.profileMarkerKey();
                return new ResolvedProofSubject(subjectId, Map.of(), key, key);
            }
            @Override public TypedFact decode(String subjectId, byte[] canonicalValue) {
                require(PROFILE_SUBJECT, subjectId);
                if (canonicalValue == null || canonicalValue.length == 0
                        || canonicalValue.length > CompositeCommitmentV1.MAX_PROFILE_BYTES) {
                    throw invalid();
                }
                return new TypedFact(subjectId, Map.of("profile-digest", HexFormat.of().formatHex(
                        CompositeCommitmentV1.profileDigest(canonicalValue))));
            }
            @Override public ClaimResult evaluate(String subjectId, TypedFact fact,
                                                  ClaimRequest claim) {
                require(PROFILE_SUBJECT, subjectId);
                String expected = claim != null && "digest-equals".equals(claim.claimId())
                        && claim.operands().size() == 1 ? claim.operands().get("expected") : null;
                if (expected == null || !expected.matches("[0-9a-f]{64}")) throw invalid();
                boolean satisfied = expected.equals(fact.fields().get("profile-digest"));
                return new ClaimResult(true, satisfied, satisfied
                        ? "Authenticated profile satisfies the claim"
                        : "Authenticated profile does not satisfy the claim");
            }
        };
    }

    private static void require(String expected, String actual) {
        if (!expected.equals(actual)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid composite proof subject request");
    }
}
