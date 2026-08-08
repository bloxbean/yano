package com.bloxbean.cardano.yano.appchain.client;

/** Explicit profile-tagged proof fixtures for client tests. */
final class TestProofs {
    private TestProofs() {
    }

    static AppChainClient.Proof mpf(
            String keyHex,
            String chainId,
            String stateRootHex,
            String proofWireHex,
            String valueHex,
            Long finalizedAtHeight,
            Long committedHeight
    ) {
        ProofVerifier.ProfileMetadata metadata = ProofVerifier.profileMetadata(
                ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
        return new AppChainClient.Proof(
                keyHex, chainId, stateRootHex, proofWireHex, valueHex,
                finalizedAtHeight, committedHeight, 1,
                ProofVerifier.MPF_BLAKE2B256_V1, metadata.backend(),
                metadata.commitmentFormatId(), metadata.formatFingerprintHex(),
                "11".repeat(32), metadata.proofEncodingId(),
                metadata.nativeVersioning(), metadata.physicalDelete(),
                committedHeight,
                valueHex == null
                        ? AppChainClient.ProofPresence.ABSENT
                        : AppChainClient.ProofPresence.PRESENT,
                null, null);
    }
}
