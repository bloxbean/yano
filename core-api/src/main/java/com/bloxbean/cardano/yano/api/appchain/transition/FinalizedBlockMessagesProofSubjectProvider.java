package com.bloxbean.cardano.yano.api.appchain.transition;

import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Released typed contract for finalized-block-messages-v1. */
public final class FinalizedBlockMessagesProofSubjectProvider implements ProofSubjectProvider {
    public static final ProofSubjectDescriptorV1 DESCRIPTOR = new ProofSubjectDescriptorV1(
            1, FinalizedBlockMessageRootIndex.SUBJECT_ID, 1, "",
            "Finalized block messages",
            "The ordered message root and count committed for one finalized block.", "",
            ProofLabVocabulary.StorageScope.PRIMARY_STATE,
            List.of(new ProofSubjectDescriptorV1.Coordinate("height",
                    ProofSubjectDescriptorV1.ValueType.UINT64, "Block height",
                    Map.of("minimum", "1"), "decimal")),
            List.of(
                    new ProofSubjectDescriptorV1.Claim("count-equals", List.of("expected"),
                            List.of(ProofSubjectDescriptorV1.ValueType.UINT64)),
                    new ProofSubjectDescriptorV1.Claim("messages-root-equals", List.of("expected"),
                            List.of(ProofSubjectDescriptorV1.ValueType.DIGEST_HEX))),
            List.of(
                    new ProofSubjectDescriptorV1.FactField("height",
                            ProofSubjectDescriptorV1.ValueType.UINT64, "Block height", "block"),
                    new ProofSubjectDescriptorV1.FactField("message-count",
                            ProofSubjectDescriptorV1.ValueType.UINT64, "Message count", "messages"),
                    new ProofSubjectDescriptorV1.FactField("messages-root",
                            ProofSubjectDescriptorV1.ValueType.DIGEST_HEX, "Messages root", "")),
            ProofLabVocabulary.Completeness.NONE,
            List.of(ProofLabVocabulary.VerificationTarget.OFFCHAIN_MPF,
                    ProofLabVocabulary.VerificationTarget.OFFCHAIN_JMT,
                    ProofLabVocabulary.VerificationTarget.ONCHAIN_MPF),
            new ProofSubjectDescriptorV1.RetentionHints(true, false,
                    "Query an archival node when the selected proof height is pruned."),
            ProofSubjectDescriptorV1.Limits.defaults());

    @Override public List<ProofSubjectDescriptorV1> descriptors(AppCapabilityManifest profile) {
        return List.of(DESCRIPTOR);
    }

    @Override
    public ResolvedProofSubject resolve(String subjectId, Map<String, String> coordinates,
                                        ProofView view) {
        requireSubject(subjectId);
        if (coordinates == null || coordinates.size() != 1) throw invalid();
        String value = coordinates.get("height");
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        long height;
        try { height = Long.parseLong(value); } catch (NumberFormatException malformed) { throw invalid(); }
        byte[] key = FinalizedBlockMessageRootIndex.blockKey(height);
        return new ResolvedProofSubject(subjectId, Map.of("height", Long.toString(height)), key, key);
    }

    @Override
    public TypedFact decode(String subjectId, byte[] canonicalValue) {
        requireSubject(subjectId);
        var record = FinalizedBlockMessageRootIndex.decode(canonicalValue);
        return new TypedFact(subjectId, Map.of(
                "height", record.height(),
                "message-count", record.messageCount(),
                "messages-root", HexFormat.of().formatHex(record.messagesRoot())));
    }

    @Override
    public ClaimResult evaluate(String subjectId, TypedFact fact, ClaimRequest claim) {
        requireSubject(subjectId);
        if (claim == null) return ClaimResult.unsupported("No claim requested");
        String expected = claim.operands().get("expected");
        if (expected == null || claim.operands().size() != 1) throw invalid();
        return switch (claim.claimId()) {
            case "count-equals" -> result(Long.toString(
                    ((Number) fact.fields().get("message-count")).longValue()).equals(expected));
            case "messages-root-equals" -> {
                if (!expected.matches("[0-9a-f]{64}")) throw invalid();
                yield result(expected.equals(fact.fields().get("messages-root")));
            }
            default -> ClaimResult.unsupported("Unsupported claim");
        };
    }

    private static ClaimResult result(boolean satisfied) {
        return new ClaimResult(true, satisfied,
                satisfied ? "Authenticated value satisfies the claim"
                        : "Authenticated value does not satisfy the claim");
    }
    private static void requireSubject(String value) {
        if (!FinalizedBlockMessageRootIndex.SUBJECT_ID.equals(value)) throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid finalized block-message subject request");
    }
}
