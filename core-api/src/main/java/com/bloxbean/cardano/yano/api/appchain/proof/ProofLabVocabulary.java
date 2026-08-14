package com.bloxbean.cardano.yano.api.appchain.proof;

/**
 * Frozen ADR-037 v1 verdict and trust vocabulary.
 *
 * <p>These values are wire language. Callers must not collapse independent
 * results into one generic "verified" flag.</p>
 */
public final class ProofLabVocabulary {
    public enum FinalityEvidence {
        CERTIFICATE,
        AUTHENTICATED_BLOCK_RECORD,
        BOTH,
        UNVERIFIED
    }

    public enum Availability {
        NOT_PROVEN,
        LOCALLY_RETAINED
    }

    public enum TrustLevel {
        INTERNAL_CONSISTENCY_ONLY,
        CALLER_PINNED_ROOT,
        NODE_CONFIRMED_L1_REFERENCE,
        CALLER_PINNED_ANCHOR,
        INDEPENDENTLY_VERIFIED_L1_ANCHOR
    }

    public enum VerificationTarget {
        OFFCHAIN_MPF,
        OFFCHAIN_JMT,
        ONCHAIN_MPF
    }

    public enum StorageScope {
        PRIMARY_STATE,
        AUTHENTICATED_SNAPSHOT
    }

    public enum Completeness {
        NONE,
        MARKER,
        SNAPSHOT_DESCRIPTOR,
        PAIRED_SUBJECT
    }

    private ProofLabVocabulary() {
    }
}
