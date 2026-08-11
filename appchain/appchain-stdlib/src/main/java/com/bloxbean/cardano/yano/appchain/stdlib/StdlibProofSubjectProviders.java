package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider.ClaimRequest;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider.ClaimResult;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.ApprovalsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.BalancesContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.KvRegistryContract;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Released data-only proof subjects for the standard application contracts. */
final class StdlibProofSubjectProviders {
    static final String BALANCE = "account-balance-v1";
    static final String REGISTRY = "registry-entry-v1";
    static final String DOCUMENT = "document-head-v1";
    static final String APPROVAL = "basic-approval-outcome-v1";
    static final String AUTH_MAP = "authenticated-map-entry-v1";
    static final String AUTH_MAP_RECEIPT = "authenticated-map-receipt-v1";

    private StdlibProofSubjectProviders() {
    }

    static ProofSubjectProvider balances() {
        var descriptor = descriptor(BALANCE, "Account balance",
                "The authenticated non-negative balance for one application account.",
                List.of(textCoordinate("account", "Account")),
                List.of(claim("exact", "expected", ProofSubjectDescriptorV1.ValueType.INTEGER),
                        claim("minimum", "expected", ProofSubjectDescriptorV1.ValueType.INTEGER),
                        claim("maximum", "expected", ProofSubjectDescriptorV1.ValueType.INTEGER)),
                List.of(field("balance", ProofSubjectDescriptorV1.ValueType.INTEGER,
                        "Balance", "units")));
        return new Base(descriptor) {
            @Override byte[] key(Map<String, String> coordinates) {
                return BalancesContract.accountKey(one(coordinates, "account"));
            }
            @Override Map<String, Object> fields(byte[] value) {
                return Map.of("balance", BalancesContract.decodeBalance(value).toString());
            }
            @Override ClaimResult claim(TypedFact fact, ClaimRequest request) {
                BigInteger actual = new BigInteger((String) fact.fields().get("balance"));
                BigInteger expected = integerOperand(request);
                return result(switch (request.claimId()) {
                    case "exact" -> actual.compareTo(expected) == 0;
                    case "minimum" -> actual.compareTo(expected) >= 0;
                    case "maximum" -> actual.compareTo(expected) <= 0;
                    default -> throw invalid();
                });
            }
        };
    }

    static ProofSubjectProvider registry() {
        var descriptor = descriptor(REGISTRY, "Registry entry",
                "The authenticated owner and value digest for one registry key.",
                List.of(hexCoordinate("key", "Registry key")),
                List.of(claim("owner-equals", "expected", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX),
                        claim("value-digest-equals", "expected",
                                ProofSubjectDescriptorV1.ValueType.DIGEST_HEX)),
                List.of(field("owner", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX, "Owner", ""),
                        field("value-digest", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX,
                                "Value digest", "")));
        return new Base(descriptor) {
            @Override byte[] key(Map<String, String> coordinates) {
                return hex(one(coordinates, "key"), 1, 256);
            }
            @Override Map<String, Object> fields(byte[] value) {
                var entry = KvRegistryContract.decodeEntry(value);
                return Map.of("owner", hex(entry.owner()),
                        "value-digest", hex(Blake2bUtil.blake2bHash256(entry.value())));
            }
            @Override ClaimResult claim(TypedFact fact, ClaimRequest request) {
                String field = switch (request.claimId()) {
                    case "owner-equals" -> "owner";
                    case "value-digest-equals" -> "value-digest";
                    default -> throw invalid();
                };
                return result(digestOperand(request).equals(fact.fields().get(field)));
            }
        };
    }

    static ProofSubjectProvider documentTrail() {
        var descriptor = descriptor(DOCUMENT, "Document trail head",
                "The authenticated revision count and chained digest for one document or entity.",
                List.of(textCoordinate("entity-id", "Document or entity ID")),
                List.of(claim("revision-exact", "expected", ProofSubjectDescriptorV1.ValueType.UINT64),
                        claim("revision-minimum", "expected", ProofSubjectDescriptorV1.ValueType.UINT64),
                        claim("digest-equals", "expected", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX)),
                List.of(field("revision", ProofSubjectDescriptorV1.ValueType.UINT64, "Revision", "entries"),
                        field("head-digest", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX,
                                "Head digest", "")));
        return new Base(descriptor) {
            @Override byte[] key(Map<String, String> coordinates) {
                return DocTrailContract.entityKey(one(coordinates, "entity-id"));
            }
            @Override Map<String, Object> fields(byte[] value) {
                var head = DocTrailContract.decodeHead(value);
                return Map.of("revision", head.count(), "head-digest", hex(head.headHash()));
            }
            @Override ClaimResult claim(TypedFact fact, ClaimRequest request) {
                if ("digest-equals".equals(request.claimId())) {
                    return result(digestOperand(request).equals(fact.fields().get("head-digest")));
                }
                long actual = ((Number) fact.fields().get("revision")).longValue();
                long expected = unsignedOperand(request);
                return result(switch (request.claimId()) {
                    case "revision-exact" -> actual == expected;
                    case "revision-minimum" -> actual >= expected;
                    default -> throw invalid();
                });
            }
        };
    }

    static ProofSubjectProvider approvals() {
        var descriptor = descriptor(APPROVAL, "Approval outcome",
                "The authenticated decision, quorum and payload digest for one approval proposal.",
                List.of(textCoordinate("proposal-id", "Proposal ID")),
                List.of(claim("status", "expected", ProofSubjectDescriptorV1.ValueType.ENUM),
                        new ProofSubjectDescriptorV1.Claim("quorum-reached", List.of(),
                                List.of(ProofSubjectDescriptorV1.ValueType.BOOLEAN)),
                        claim("payload-digest", "expected", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX)),
                List.of(field("status", ProofSubjectDescriptorV1.ValueType.ENUM, "Status", ""),
                        field("approval-count", ProofSubjectDescriptorV1.ValueType.UINT64,
                                "Approvals", "members"),
                        field("required", ProofSubjectDescriptorV1.ValueType.UINT64,
                                "Required", "members"),
                        field("payload-digest", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX,
                                "Payload digest", "")));
        return new Base(descriptor) {
            @Override byte[] key(Map<String, String> coordinates) {
                return ApprovalsContract.itemKey(one(coordinates, "proposal-id"));
            }
            @Override Map<String, Object> fields(byte[] value) {
                var item = ApprovalsContract.decodeItem(value);
                return Map.of("status", approvalStatus(item.status()),
                        "approval-count", item.approvers().size(), "required", item.required(),
                        "payload-digest", hex(item.payloadHash()));
            }
            @Override ClaimResult claim(TypedFact fact, ClaimRequest request) {
                return result(switch (request.claimId()) {
                    case "status" -> enumOperand(request).equals(fact.fields().get("status"));
                    case "quorum-reached" -> ((Number) fact.fields().get("approval-count")).intValue()
                            >= ((Number) fact.fields().get("required")).intValue();
                    case "payload-digest" -> digestOperand(request)
                            .equals(fact.fields().get("payload-digest"));
                    default -> throw invalid();
                });
            }
        };
    }

    static ProofSubjectProvider authenticatedMap() {
        var descriptor = descriptor(AUTH_MAP, "Authenticated map entry",
                "The authenticated status, revision and value digest for one collection entry.",
                List.of(textCoordinate("collection", "Collection"),
                        hexCoordinate("key", "Application key")),
                List.of(claim("status", "expected", ProofSubjectDescriptorV1.ValueType.ENUM),
                        claim("revision-exact", "expected", ProofSubjectDescriptorV1.ValueType.UINT64),
                        claim("value-digest", "expected", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX)),
                List.of(field("status", ProofSubjectDescriptorV1.ValueType.ENUM, "Status", ""),
                        field("revision", ProofSubjectDescriptorV1.ValueType.UINT64, "Revision", ""),
                        field("value-digest", ProofSubjectDescriptorV1.ValueType.DIGEST_HEX,
                                "Logical value digest", "")));
        return new Base(descriptor) {
            @Override byte[] key(Map<String, String> coordinates) {
                exactCoordinates(coordinates, "collection", "key");
                return AuthenticatedMapContract.canonicalKey(coordinates.get("collection"),
                        hex(coordinates.get("key"), 1, AuthenticatedMapContract.MAX_APPLICATION_KEY_BYTES));
            }
            @Override Map<String, Object> fields(byte[] value) {
                var entry = AuthenticatedMapContract.decodeEntry(value);
                return Map.of("status", entry.status() == AuthenticatedMapContract.STATUS_ACTIVE
                                ? "ACTIVE" : "REVOKED",
                        "revision", entry.revision(), "value-digest", hex(entry.logicalValueHash()));
            }
            @Override ClaimResult claim(TypedFact fact, ClaimRequest request) {
                return result(switch (request.claimId()) {
                    case "status" -> enumOperand(request).equals(fact.fields().get("status"));
                    case "revision-exact" -> ((Number) fact.fields().get("revision")).longValue()
                            == unsignedOperand(request);
                    case "value-digest" -> digestOperand(request)
                            .equals(fact.fields().get("value-digest"));
                    default -> throw invalid();
                });
            }
        };
    }

    static AppCapabilityManifest.ProofSubject manifest(ProofSubjectProvider provider,
                                                       String keyNamespace) {
        ProofSubjectDescriptorV1 value = provider.descriptors(null).getFirst();
        return new AppCapabilityManifest.ProofSubject(value.subjectId(), value.subjectVersion(),
                value.componentId(), keyNamespace, "typed-state-proof", value.descriptorDigest());
    }

    private abstract static class Base implements ProofSubjectProvider {
        private final ProofSubjectDescriptorV1 descriptor;
        private Base(ProofSubjectDescriptorV1 descriptor) { this.descriptor = descriptor; }
        @Override public List<ProofSubjectDescriptorV1> descriptors(AppCapabilityManifest profile) {
            return List.of(descriptor);
        }
        @Override public ResolvedProofSubject resolve(String subjectId,
                                                      Map<String, String> coordinates,
                                                      ProofView view) {
            requireSubject(subjectId);
            byte[] key = key(coordinates);
            return new ResolvedProofSubject(subjectId, Map.copyOf(coordinates), key, key);
        }
        @Override public TypedFact decode(String subjectId, byte[] canonicalValue) {
            requireSubject(subjectId);
            return new TypedFact(subjectId, fields(canonicalValue));
        }
        @Override public ClaimResult evaluate(String subjectId, TypedFact fact,
                                              ClaimRequest claim) {
            requireSubject(subjectId);
            if (claim == null) return ClaimResult.unsupported("No claim requested");
            return claim(fact, claim);
        }
        private void requireSubject(String subjectId) {
            if (!descriptor.subjectId().equals(subjectId)) throw invalid();
        }
        abstract byte[] key(Map<String, String> coordinates);
        abstract Map<String, Object> fields(byte[] value);
        abstract ClaimResult claim(TypedFact fact, ClaimRequest request);
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

    private static ProofSubjectDescriptorV1.Coordinate textCoordinate(String id, String label) {
        return new ProofSubjectDescriptorV1.Coordinate(id,
                ProofSubjectDescriptorV1.ValueType.STRING, label,
                Map.of("max-bytes", "256"), "utf-8");
    }
    private static ProofSubjectDescriptorV1.Coordinate hexCoordinate(String id, String label) {
        return new ProofSubjectDescriptorV1.Coordinate(id,
                ProofSubjectDescriptorV1.ValueType.BYTES_HEX, label,
                Map.of("max-bytes", "256"), "lowercase-hex");
    }
    private static ProofSubjectDescriptorV1.Claim claim(String id, String operand,
                                                        ProofSubjectDescriptorV1.ValueType type) {
        return new ProofSubjectDescriptorV1.Claim(id, List.of(operand), List.of(type));
    }
    private static ProofSubjectDescriptorV1.FactField field(String id,
                                                            ProofSubjectDescriptorV1.ValueType type,
                                                            String label, String unit) {
        return new ProofSubjectDescriptorV1.FactField(id, type, label, unit);
    }
    private static String one(Map<String, String> coordinates, String name) {
        exactCoordinates(coordinates, name);
        String value = coordinates.get(name);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }
    private static void exactCoordinates(Map<String, String> coordinates, String... names) {
        if (coordinates == null || coordinates.size() != names.length) throw invalid();
        for (String name : names) if (!coordinates.containsKey(name)) throw invalid();
    }
    private static String operand(ClaimRequest request) {
        if (request.operands().size() != 1 || !request.operands().containsKey("expected")) throw invalid();
        return request.operands().get("expected");
    }
    private static BigInteger integerOperand(ClaimRequest request) {
        try { return new BigInteger(operand(request)); } catch (RuntimeException malformed) { throw invalid(); }
    }
    private static long unsignedOperand(ClaimRequest request) {
        String value = operand(request);
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (RuntimeException malformed) { throw invalid(); }
    }
    private static String digestOperand(ClaimRequest request) {
        String value = operand(request);
        if (!value.matches("[0-9a-f]{64}")) throw invalid();
        return value;
    }
    private static String enumOperand(ClaimRequest request) {
        String value = operand(request);
        if (!value.matches("[A-Z][A-Z0-9_]{0,31}")) throw invalid();
        return value;
    }
    private static byte[] hex(String value, int minimum, int maximum) {
        if (value == null || (value.length() & 1) != 0 || !value.matches("[0-9a-f]+")) throw invalid();
        try {
            byte[] decoded = HexFormat.of().parseHex(value);
            if (decoded.length < minimum || decoded.length > maximum) throw invalid();
            return decoded;
        } catch (RuntimeException malformed) { throw invalid(); }
    }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    private static String approvalStatus(int value) {
        return switch (value) {
            case ApprovalsStateMachine.STATUS_PENDING -> "PENDING";
            case ApprovalsStateMachine.STATUS_APPROVED -> "APPROVED";
            case ApprovalsStateMachine.STATUS_REJECTED -> "REJECTED";
            case ApprovalsStateMachine.STATUS_EXPIRED -> "EXPIRED";
            default -> throw invalid();
        };
    }
    private static ClaimResult result(boolean satisfied) {
        return new ClaimResult(true, satisfied, satisfied
                ? "Authenticated value satisfies the claim"
                : "Authenticated value does not satisfy the claim");
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid standard proof subject request");
    }
}
