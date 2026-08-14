package com.bloxbean.cardano.yano.api.appchain.transition;

import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Released typed contract for the optional finalized-message-v1 fast path. */
public final class FinalizedMessageProofSubjectProvider implements ProofSubjectProvider {
    public static final String SUBJECT_ID = "finalized-message-v1";
    public static final ProofSubjectDescriptorV1 DESCRIPTOR = new ProofSubjectDescriptorV1(
            1, SUBJECT_ID, 1, "", "Finalized message record",
            "The authenticated block position, topic, and sender recorded for a message.", "",
            ProofLabVocabulary.StorageScope.PRIMARY_STATE,
            List.of(new ProofSubjectDescriptorV1.Coordinate("message-id",
                    ProofSubjectDescriptorV1.ValueType.DIGEST_HEX, "Message ID",
                    Map.of("bytes", "32"), "lowercase-hex")),
            List.of(new ProofSubjectDescriptorV1.Claim("recorded", List.of(),
                    List.of(ProofSubjectDescriptorV1.ValueType.BOOLEAN))),
            List.of(
                    new ProofSubjectDescriptorV1.FactField("height",
                            ProofSubjectDescriptorV1.ValueType.UINT64, "Block height", "block"),
                    new ProofSubjectDescriptorV1.FactField("index",
                            ProofSubjectDescriptorV1.ValueType.UINT64, "Message index", "position"),
                    new ProofSubjectDescriptorV1.FactField("topic",
                            ProofSubjectDescriptorV1.ValueType.STRING, "Topic", ""),
                    new ProofSubjectDescriptorV1.FactField("sender",
                            ProofSubjectDescriptorV1.ValueType.BYTES_HEX, "Sender", "")),
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
    @Override public ResolvedProofSubject resolve(String subjectId, Map<String, String> coordinates,
                                                   ProofView view) {
        if (!SUBJECT_ID.equals(subjectId) || coordinates == null || coordinates.size() != 1)
            throw invalid();
        String id = coordinates.get("message-id");
        if (id == null || !id.matches("[0-9a-f]{64}")) throw invalid();
        byte[] logical = HexFormat.of().parseHex(id);
        return new ResolvedProofSubject(subjectId, Map.of("message-id", id), logical,
                FinalizedMessageIndex.messageKey(logical));
    }
    @Override public TypedFact decode(String subjectId, byte[] canonicalValue) {
        if (!SUBJECT_ID.equals(subjectId)) throw invalid();
        var record = FinalizedMessageIndex.decode(canonicalValue);
        return new TypedFact(subjectId, Map.of("height", record.height(),
                "index", record.originalMessageIndex(), "topic", record.topic(),
                "sender", HexFormat.of().formatHex(record.sender())));
    }
    @Override public ClaimResult evaluate(String subjectId, TypedFact fact, ClaimRequest claim) {
        if (!SUBJECT_ID.equals(subjectId) || claim == null
                || !"recorded".equals(claim.claimId()) || !claim.operands().isEmpty()) throw invalid();
        return new ClaimResult(true, true, "The message record is authenticated as present");
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid finalized-message subject request");
    }
}
