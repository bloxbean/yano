package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityIds;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedMessageIndex;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionPlans;

/**
 * Built-in default app: an append-only ordered log of opaque messages.
 * For every finalized message it writes
 * {@code key = sha256(namespace || message-id)} →
 * {@code cbor([height, index, topic, sender])}
 * into the state trie — so any consumer can obtain an MPF inclusion proof
 * that a given message was finalized at a given position, verifiable against
 * an anchored state root without trusting the nodes (ADR app-layer/005 D10).
 */
public final class OrderedLogStateMachine implements AppStateMachine {

    public static final String ID = "ordered-log";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AppCapabilityManifest capabilityManifest() {
        FinalizedMessageIndex.Config config = FinalizedMessageIndex.Config.allMessages();
        return AppCapabilityManifest.builder(ID, "1.0.0")
                .crossCutting(new AppCapabilityManifest.CrossCutting(
                        AppCapabilityIds.FINALIZED_MESSAGE, "1.0.0", true,
                        java.util.HexFormat.of().formatHex(config.digest()),
                        java.util.Map.of("policy", config.policy().name(),
                                "maxMessagesPerBlock",
                                Integer.toString(config.maxMessagesPerBlock()),
                                "keyNamespace", FinalizedMessageIndex.LOGICAL_NAMESPACE,
                                "keyDerivation", "sha256(namespace || logical-key)"),
                        AppCapabilityManifest.Origin.INTRINSIC))
                .proofSubject(new AppCapabilityManifest.ProofSubject(
                        com.bloxbean.cardano.yano.api.appchain.transition
                                .FinalizedMessageProofSubjectProvider.SUBJECT_ID, 1, "",
                        FinalizedMessageIndex.LOGICAL_NAMESPACE, "state-proof",
                        com.bloxbean.cardano.yano.api.appchain.transition
                                .FinalizedMessageProofSubjectProvider.DESCRIPTOR.descriptorDigest()))
                .build();
    }

    @Override
    public java.util.List<com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider>
    proofSubjectProviders() {
        return java.util.List.of(new com.bloxbean.cardano.yano.api.appchain.transition
                .FinalizedMessageProofSubjectProvider());
    }

    @Override
    public void apply(
            AppBlockExecutionContext context,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        TransitionPlans.commit(
                FinalizedMessageIndex.plan(context, FinalizedMessageIndex.Config.allMessages()),
                writer, effects);
    }
}
