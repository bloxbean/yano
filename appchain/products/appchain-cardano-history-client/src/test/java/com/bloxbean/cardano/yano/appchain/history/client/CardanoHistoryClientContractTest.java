package com.bloxbean.cardano.yano.appchain.history.client;

import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardanoHistoryClientContractTest {
    @Test void requiresExplicitBaseUrlAndChain() {
        assertThatThrownBy(() -> CardanoHistoryClient.builder("", "history"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CardanoHistoryClient.builder("http://localhost:17070/api/v1", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CardanoHistoryClient.builder(
                "http://localhost:17070/api/v1", "history").build()).isNotNull();
    }

    @Test void malformedBundleAndWrongProfileFailClosed() {
        assertThatThrownBy(() -> new CardanoHistoryPortableStakeProof(1, 170, 0,
                "ab", CardanoHistoryProofBundle.StakeMode.MINIMUM, "1", "", "00",
                new CardanoHistoryTrustedRoot("history", "unknown", "00",
                        1, "00", ProofVerifier.TrustedRootSource.CALLER_PINNED, "")))
                .isInstanceOf(IllegalArgumentException.class);
        var malformed = new CardanoHistoryPortableStakeProof(1, 170, 0,
                "11".repeat(28), CardanoHistoryProofBundle.StakeMode.MINIMUM, "1", "", "00",
                new CardanoHistoryTrustedRoot("history",
                        ProofVerifier.MPF_BLAKE2B256_V1, "22".repeat(32), 1,
                        "33".repeat(32), ProofVerifier.TrustedRootSource.CALLER_PINNED, ""));
        assertThat(CardanoHistoryPortableProofVerifier.verify(malformed))
                .isEqualTo(CardanoHistoryPortableProofVerifier.Verification.INVALID);
    }

    @Test void embeddedSourceCanNeverSelfAssertCardanoAnchorTrust() {
        var selfAsserted = new CardanoHistoryPortableStakeProof(1, 170, 0,
                "11".repeat(28), CardanoHistoryProofBundle.StakeMode.MINIMUM, "1", "", "00",
                new CardanoHistoryTrustedRoot("history",
                        ProofVerifier.MPF_BLAKE2B256_V1, "22".repeat(32), 1,
                        "33".repeat(32), ProofVerifier.TrustedRootSource.CARDANO_ANCHOR,
                        "44".repeat(32)));
        assertThat(CardanoHistoryPortableProofVerifier.verify(selfAsserted))
                .isEqualTo(CardanoHistoryPortableProofVerifier.Verification.INVALID);
    }
}
