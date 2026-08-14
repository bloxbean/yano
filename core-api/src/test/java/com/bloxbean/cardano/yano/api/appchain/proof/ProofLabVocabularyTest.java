package com.bloxbean.cardano.yano.api.appchain.proof;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProofLabVocabularyTest {
    @Test
    void freezesTheV1WireVocabulary() {
        assertThat(ProofLabVocabulary.FinalityEvidence.values()).extracting(Enum::name)
                .containsExactly("CERTIFICATE", "AUTHENTICATED_BLOCK_RECORD", "BOTH", "UNVERIFIED");
        assertThat(ProofLabVocabulary.Availability.values()).extracting(Enum::name)
                .containsExactly("NOT_PROVEN", "LOCALLY_RETAINED");
        assertThat(ProofLabVocabulary.TrustLevel.values()).extracting(Enum::name)
                .containsExactly("INTERNAL_CONSISTENCY_ONLY", "CALLER_PINNED_ROOT",
                        "NODE_CONFIRMED_L1_REFERENCE", "CALLER_PINNED_ANCHOR",
                        "INDEPENDENTLY_VERIFIED_L1_ANCHOR");
    }
}
