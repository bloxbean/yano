package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.zeroj.api.CircuitId;
import com.bloxbean.cardano.zeroj.api.CurveId;
import com.bloxbean.cardano.zeroj.api.ProofSystemId;
import com.bloxbean.cardano.zeroj.api.PublicInputs;
import com.bloxbean.cardano.zeroj.api.VerificationKeyRef;
import com.bloxbean.cardano.zeroj.api.VerificationResult;
import com.bloxbean.cardano.zeroj.api.ZkProofEnvelope;
import com.bloxbean.cardano.zeroj.verifier.core.VerifierOrchestrator;
import com.bloxbean.cardano.zeroj.verifier.core.VerifierRegistry;

/**
 * Verifies E7.1 proof bodies via ZeroJ's {@link VerifierOrchestrator} against a
 * config-pinned {@link ConfigVkRegistry} (ADR app-layer/006 §4.4). The node only
 * ever <em>verifies</em> (pure Java, deterministic, bounded) — proving happens
 * client-side. Backends (Groth16/PlonK) are discovered via ServiceLoader.
 */
final class ZkVerificationService {

    private final VerifierOrchestrator orchestrator;
    private final ConfigVkRegistry vkRegistry;

    ZkVerificationService(ConfigVkRegistry vkRegistry) {
        this.vkRegistry = vkRegistry;
        this.orchestrator = new VerifierOrchestrator(VerifierRegistry.withServiceLoader(), vkRegistry);
    }

    /** Package-visible ctor for tests that inject a stub verifier backend. */
    ZkVerificationService(ConfigVkRegistry vkRegistry, VerifierRegistry verifierRegistry) {
        this.vkRegistry = vkRegistry;
        this.orchestrator = new VerifierOrchestrator(verifierRegistry, vkRegistry);
    }

    java.util.List<String> knownCircuits() {
        return vkRegistry.circuitIds();
    }

    /**
     * @return a short rejection reason, or null when the proof is cryptographically
     *         valid and accepted.
     */
    String verify(ZkProofBody body) {
        // circuitId must be pinned in config (VK known) — reject unknown circuits.
        if (vkRegistry.lookup(new VerificationKeyRef.ById(body.circuitId())).isEmpty()) {
            return "unknown circuit '" + body.circuitId() + "' (not pinned in zk.circuits config)";
        }
        ZkProofEnvelope envelope;
        try {
            PublicInputs publicInputs = new PublicInputs(body.publicInputs());
            envelope = ZkProofEnvelope.builder()
                    .proofSystem(ProofSystemId.fromValue(body.proofSystem()))
                    .curve(CurveId.fromValue(body.curve()))
                    .circuitId(new CircuitId(body.circuitId()))
                    .proofBytes(body.proof())
                    .publicInputs(publicInputs)
                    .vkRef(new VerificationKeyRef.ById(body.circuitId()))
                    .build();
        } catch (Exception e) {
            return "malformed proof envelope: " + e.getMessage();
        }
        VerificationResult result = orchestrator.verify(envelope);
        if (result.proofValid() && result.accepted()) {
            return null;
        }
        return result.message().orElse(result.reasonCode().map(Enum::name).orElse("proof rejected"));
    }
}
