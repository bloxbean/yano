package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider.ClaimRequest;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider.ClaimResult;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Released proof semantics for the role and approval reference application. */
final class RoleProofSubjectProviders {
    static final String ACTOR = "actor-role-assignment-v1";
    static final String APPROVAL = "role-approval-outcome-v1";

    private RoleProofSubjectProviders() {
    }

    static ProofSubjectProvider actor() {
        ProofSubjectDescriptorV1 descriptor = descriptor(ACTOR, "Actor role assignment",
                "An authenticated actor revision and its bounded role membership.",
                List.of(text("actor-id", "Actor ID"), number("revision", "Actor revision")),
                List.of(claim("status", "expected", ProofSubjectDescriptorV1.ValueType.ENUM),
                        claim("has-role", "expected", ProofSubjectDescriptorV1.ValueType.STRING)),
                List.of(field("status", ProofSubjectDescriptorV1.ValueType.ENUM, "Status"),
                        field("revision", ProofSubjectDescriptorV1.ValueType.UINT64, "Revision"),
                        field("roles", ProofSubjectDescriptorV1.ValueType.STRING, "Roles")));
        return new Base(descriptor) {
            @Override byte[] key(Map<String, String> coordinates) {
                exact(coordinates, "actor-id", "revision");
                return RoleWorkflowKeys.actorRevision(coordinates.get("actor-id"),
                        positive(coordinates.get("revision")));
            }
            @Override Map<String, Object> fields(byte[] bytes) {
                ActorRecordV1 actor = ActorRecordV1.decode(bytes);
                return Map.of("status", actor.status().name(), "revision", actor.revision(),
                        "roles", String.join(",", actor.roles()));
            }
            @Override ClaimResult claim(TypedFact fact, ClaimRequest claim) {
                String expected = operand(claim);
                boolean satisfied = switch (claim.claimId()) {
                    case "status" -> expected.equals(fact.fields().get("status"));
                    case "has-role" -> List.of(((String) fact.fields().get("roles")).split(","))
                            .contains(expected);
                    default -> throw invalid();
                };
                return result(satisfied);
            }
        };
    }

    static ProofSubjectProvider approval() {
        ProofSubjectDescriptorV1 descriptor = descriptor(APPROVAL, "Role approval outcome",
                "An authenticated role-policy proposal outcome and payload digest.",
                List.of(text("proposal-id", "Proposal ID")),
                List.of(claim("status", "expected", ProofSubjectDescriptorV1.ValueType.ENUM),
                        claim("payload-digest", "expected", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX)),
                List.of(field("status", ProofSubjectDescriptorV1.ValueType.ENUM, "Status"),
                        field("decision-count", ProofSubjectDescriptorV1.ValueType.UINT64, "Decisions"),
                        field("payload-digest", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX,
                                "Payload digest")));
        return new Base(descriptor) {
            @Override byte[] key(Map<String, String> coordinates) {
                exact(coordinates, "proposal-id");
                return RoleWorkflowKeys.proposal(coordinates.get("proposal-id"));
            }
            @Override Map<String, Object> fields(byte[] bytes) {
                ApprovalProposalV1 proposal = ApprovalProposalV1.decode(bytes);
                return Map.of("status", proposal.status().name(),
                        "decision-count", proposal.decisions().size(),
                        "payload-digest", HexFormat.of().formatHex(proposal.payloadHash()));
            }
            @Override ClaimResult claim(TypedFact fact, ClaimRequest claim) {
                String expected = operand(claim);
                if ("payload-digest".equals(claim.claimId())
                        && !expected.matches("[0-9a-f]{64}")) throw invalid();
                String field = switch (claim.claimId()) {
                    case "status" -> "status";
                    case "payload-digest" -> "payload-digest";
                    default -> throw invalid();
                };
                return result(expected.equals(fact.fields().get(field)));
            }
        };
    }

    static AppCapabilityManifest.ProofSubject manifest(ProofSubjectProvider provider,
                                                       String namespace) {
        var descriptor = provider.descriptors(null).getFirst();
        return new AppCapabilityManifest.ProofSubject(descriptor.subjectId(), 1, "", namespace,
                "typed-state-proof", descriptor.descriptorDigest());
    }

    private abstract static class Base implements ProofSubjectProvider {
        private final ProofSubjectDescriptorV1 descriptor;
        private Base(ProofSubjectDescriptorV1 descriptor) { this.descriptor = descriptor; }
        @Override public List<ProofSubjectDescriptorV1> descriptors(AppCapabilityManifest profile) {
            return List.of(descriptor);
        }
        @Override public ResolvedProofSubject resolve(String subjectId, Map<String, String> coordinates,
                                                      ProofView view) {
            require(subjectId);
            byte[] key = key(coordinates);
            return new ResolvedProofSubject(subjectId, Map.copyOf(coordinates), key, key);
        }
        @Override public TypedFact decode(String subjectId, byte[] canonicalValue) {
            require(subjectId);
            return new TypedFact(subjectId, fields(canonicalValue));
        }
        @Override public ClaimResult evaluate(String subjectId, TypedFact fact, ClaimRequest claim) {
            require(subjectId);
            return claim(fact, claim);
        }
        private void require(String subjectId) {
            if (!descriptor.subjectId().equals(subjectId)) throw invalid();
        }
        abstract byte[] key(Map<String, String> coordinates);
        abstract Map<String, Object> fields(byte[] bytes);
        abstract ClaimResult claim(TypedFact fact, ClaimRequest claim);
    }

    private static ProofSubjectDescriptorV1 descriptor(String id, String label, String description,
                                                        List<ProofSubjectDescriptorV1.Coordinate> coordinates,
                                                        List<ProofSubjectDescriptorV1.Claim> claims,
                                                        List<ProofSubjectDescriptorV1.FactField> fields) {
        return new ProofSubjectDescriptorV1(1, id, 1, "", label, description, "",
                ProofLabVocabulary.StorageScope.PRIMARY_STATE, coordinates, claims, fields,
                ProofLabVocabulary.Completeness.NONE,
                List.of(ProofLabVocabulary.VerificationTarget.OFFCHAIN_MPF,
                        ProofLabVocabulary.VerificationTarget.OFFCHAIN_JMT),
                new ProofSubjectDescriptorV1.RetentionHints(true, false,
                        "Query an archival node when the selected proof height is pruned."),
                ProofSubjectDescriptorV1.Limits.defaults());
    }
    private static ProofSubjectDescriptorV1.Coordinate text(String id, String label) {
        return new ProofSubjectDescriptorV1.Coordinate(id,
                ProofSubjectDescriptorV1.ValueType.STRING, label,
                Map.of("max-bytes", "128"), "utf-8");
    }
    private static ProofSubjectDescriptorV1.Coordinate number(String id, String label) {
        return new ProofSubjectDescriptorV1.Coordinate(id,
                ProofSubjectDescriptorV1.ValueType.UINT64, label,
                Map.of("minimum", "1"), "decimal");
    }
    private static ProofSubjectDescriptorV1.Claim claim(String id, String operand,
                                                        ProofSubjectDescriptorV1.ValueType type) {
        return new ProofSubjectDescriptorV1.Claim(id, List.of(operand), List.of(type));
    }
    private static ProofSubjectDescriptorV1.FactField field(String id,
                                                            ProofSubjectDescriptorV1.ValueType type,
                                                            String label) {
        return new ProofSubjectDescriptorV1.FactField(id, type, label, "");
    }
    private static void exact(Map<String, String> coordinates, String... names) {
        if (coordinates == null || coordinates.size() != names.length) throw invalid();
        for (String name : names) if (!coordinates.containsKey(name)
                || coordinates.get(name).isBlank()) throw invalid();
    }
    private static long positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (RuntimeException malformed) { throw invalid(); }
    }
    private static String operand(ClaimRequest claim) {
        if (claim == null || claim.operands().size() != 1
                || !claim.operands().containsKey("expected")) throw invalid();
        return claim.operands().get("expected");
    }
    private static ClaimResult result(boolean satisfied) {
        return new ClaimResult(true, satisfied, satisfied
                ? "Authenticated value satisfies the claim"
                : "Authenticated value does not satisfy the claim");
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid role proof subject request");
    }
}
