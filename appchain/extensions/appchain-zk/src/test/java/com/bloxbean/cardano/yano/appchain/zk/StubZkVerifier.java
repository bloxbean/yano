package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.zeroj.api.CurveId;
import com.bloxbean.cardano.zeroj.api.ProofSystemId;
import com.bloxbean.cardano.zeroj.api.VerificationMaterial;
import com.bloxbean.cardano.zeroj.api.VerificationResult;
import com.bloxbean.cardano.zeroj.api.ZkProofEnvelope;
import com.bloxbean.cardano.zeroj.backend.spi.BackendDescriptor;
import com.bloxbean.cardano.zeroj.backend.spi.ZkVerifier;

import java.nio.charset.StandardCharsets;

/**
 * Test-only ZkVerifier backend: accepts a proof whose bytes are exactly
 * {@code "VALID"}, rejects everything else. Lets us exercise the Yano ZK plugin
 * integration (body parsing, VK registry, admission/apply gating) without real
 * proofs or a trusted setup — the actual cryptography is ZeroJ's own concern.
 */
final class StubZkVerifier implements ZkVerifier {

    @Override
    public VerificationResult verify(ZkProofEnvelope envelope, VerificationMaterial material) {
        String proof = new String(envelope.proofBytes(), StandardCharsets.UTF_8);
        return "VALID".equals(proof)
                ? VerificationResult.ok()
                : VerificationResult.proofInvalid("stub rejects proof: " + proof);
    }

    @Override
    public BackendDescriptor descriptor() {
        return new BackendDescriptor(ProofSystemId.GROTH16, CurveId.BLS12_381, "stub");
    }
}
