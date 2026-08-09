package com.bloxbean.cardano.yano.appchain.history.client;

import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;

/** Caller-supplied root context; it is trusted only when passed separately to verification. */
public record CardanoHistoryTrustedRoot(
        String chainId,
        String profile,
        String genesisIdHex,
        long height,
        String stateRootHex,
        ProofVerifier.TrustedRootSource source,
        String blockHashHex
) {
    public ProofVerifier.TrustedStateRoot toVerifierRoot() {
        return new ProofVerifier.TrustedStateRoot(chainId, profile, genesisIdHex,
                height, stateRootHex, source, blockHashHex);
    }

    public static CardanoHistoryTrustedRoot from(ProofVerifier.TrustedStateRoot value) {
        return new CardanoHistoryTrustedRoot(value.chainId(), value.profile(), value.genesisIdHex(),
                value.height(), value.stateRootHex(), value.source(), value.blockHashHex());
    }
}
