package com.bloxbean.cardano.yano.api.appchain.proof;

import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedBlockMessagesProofSubjectProvider;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedBlockMessageRootIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProofSubjectRegistryTest {
    @Test
    void bindsADataOnlyPluginSubjectToTheExactCommittedDeclaration() {
        var descriptor = FinalizedBlockMessagesProofSubjectProvider.DESCRIPTOR;
        var manifest = AppCapabilityManifest.builder("custom", "1")
                .proofSubject(new AppCapabilityManifest.ProofSubject(
                        descriptor.subjectId(), descriptor.subjectVersion(), descriptor.componentId(),
                        FinalizedBlockMessageRootIndex.LOGICAL_NAMESPACE, "state-proof",
                        descriptor.descriptorDigest()))
                .build();
        var registry = ProofSubjectRegistry.bind(manifest,
                List.of(new FinalizedBlockMessagesProofSubjectProvider()));
        assertThat(registry.descriptors()).containsExactly(descriptor);
        assertThat(registry.provider(descriptor.subjectId()).resolve(
                descriptor.subjectId(), Map.of("height", "7"),
                ProofSubjectProvider.ProofView.latest()).physicalKey())
                .containsExactly(FinalizedBlockMessageRootIndex.blockKey(7));
    }

    @Test
    void rejectsUndeclaredDigestMismatchAndProviderCollision() {
        var provider = new FinalizedBlockMessagesProofSubjectProvider();
        assertThatThrownBy(() -> ProofSubjectRegistry.bind(
                AppCapabilityManifest.application("custom"), List.of(provider)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("manifest-bound");
        var descriptor = FinalizedBlockMessagesProofSubjectProvider.DESCRIPTOR;
        var mismatch = AppCapabilityManifest.builder("custom", "1")
                .proofSubject(new AppCapabilityManifest.ProofSubject(descriptor.subjectId(), 1, "",
                        "namespace", "state-proof", "00".repeat(32))).build();
        assertThatThrownBy(() -> ProofSubjectRegistry.bind(mismatch, List.of(provider)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("manifest-bound");
        var valid = AppCapabilityManifest.builder("custom", "1")
                .proofSubject(new AppCapabilityManifest.ProofSubject(descriptor.subjectId(), 1, "",
                        "namespace", "state-proof", descriptor.descriptorDigest())).build();
        assertThatThrownBy(() -> ProofSubjectRegistry.bind(valid, List.of(provider, provider)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("colliding");
    }

    @Test
    void descriptorGrammarRejectsExecutableOrHostilePresentationText() {
        var original = FinalizedBlockMessagesProofSubjectProvider.DESCRIPTOR;
        assertThatThrownBy(() -> new ProofSubjectDescriptorV1(1, "hostile", 1, "",
                "<script>alert(1)</script>", "description", "", original.storageScope(),
                List.of(), List.of(), List.of(), ProofLabVocabulary.Completeness.NONE,
                List.of(ProofLabVocabulary.VerificationTarget.OFFCHAIN_MPF),
                original.retentionHints(), original.limits()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProofSubjectDescriptorV1(1, "hostile", 1, "",
                "label", "javascript:alert(1)", "", original.storageScope(),
                List.of(), List.of(), List.of(), ProofLabVocabulary.Completeness.NONE,
                List.of(ProofLabVocabulary.VerificationTarget.OFFCHAIN_MPF),
                original.retentionHints(), original.limits()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
