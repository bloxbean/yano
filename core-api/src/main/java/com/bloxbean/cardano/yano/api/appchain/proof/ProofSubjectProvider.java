package com.bloxbean.cardano.yano.api.appchain.proof;

import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;

import java.util.List;
import java.util.Map;

/** Data-only, deterministic typed proof contract supplied by an application plugin. */
public interface ProofSubjectProvider {
    List<ProofSubjectDescriptorV1> descriptors(AppCapabilityManifest applicationProfile);

    ResolvedProofSubject resolve(String subjectId, Map<String, String> coordinates, ProofView view);

    TypedFact decode(String subjectId, byte[] canonicalValue);

    ClaimResult evaluate(String subjectId, TypedFact fact, ClaimRequest claim);

    record ResolvedProofSubject(String subjectId, Map<String, String> normalizedCoordinates,
                                byte[] canonicalLogicalKey, byte[] physicalKey) {
        public ResolvedProofSubject {
            normalizedCoordinates = Map.copyOf(normalizedCoordinates);
            canonicalLogicalKey = canonicalLogicalKey.clone();
            physicalKey = physicalKey.clone();
            if (canonicalLogicalKey.length == 0 || physicalKey.length == 0
                    || physicalKey.length > 256) {
                throw new IllegalArgumentException("invalid resolved proof subject key");
            }
        }
        @Override public byte[] canonicalLogicalKey() { return canonicalLogicalKey.clone(); }
        @Override public byte[] physicalKey() { return physicalKey.clone(); }
    }

    record TypedFact(String subjectId, Map<String, Object> fields) {
        public TypedFact { fields = Map.copyOf(fields); }
    }

    record ClaimRequest(String claimId, Map<String, String> operands) {
        public ClaimRequest { operands = operands == null ? Map.of() : Map.copyOf(operands); }
    }

    record ClaimResult(boolean evaluated, boolean satisfied, String explanation) {
        public static ClaimResult unsupported(String explanation) {
            return new ClaimResult(false, false, explanation);
        }
    }

    record ProofView(Kind kind, Long height, String snapshotSeries, Long snapshotSequence) {
        public enum Kind { LATEST, HEIGHT, LATEST_CONFIRMED_ANCHOR, SNAPSHOT }
        public static ProofView latest() { return new ProofView(Kind.LATEST, null, null, null); }
    }
}
