package com.bloxbean.cardano.yano.appchain.history.client;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;

import java.math.BigInteger;
import java.util.HexFormat;

/** JSON-safe, canonical-bundle wrapper for an independently verifiable stake claim. */
public record CardanoHistoryPortableStakeProof(
        int schemaVersion,
        long epoch,
        int credentialType,
        String credentialHashHex,
        CardanoHistoryProofBundle.StakeMode mode,
        String coin,
        String poolHashHex,
        String canonicalBundleHex,
        CardanoHistoryTrustedRoot trustedRoot
) {
    public CardanoHistoryPortableStakeProof {
        if (schemaVersion != 1 || epoch < 0 || credentialType < 0 || credentialType > 1
                || !hex(credentialHashHex, 28) || mode == null
                || !unsigned(coin) || poolHashHex == null
                || (!poolHashHex.isEmpty() && !hex(poolHashHex, 28))
                || canonicalBundleHex == null || canonicalBundleHex.isEmpty()
                || canonicalBundleHex.length() % 2 != 0
                || !canonicalBundleHex.matches("[0-9a-f]+")
                || canonicalBundleHex.length() > 8 * 1024 * 1024
                || trustedRoot == null) {
            throw new IllegalArgumentException("invalid Cardano History portable stake proof");
        }
    }

    public static CardanoHistoryPortableStakeProof from(
            CardanoHistoryProofBundle.SnapshotStake proof,
            ProofVerifier.TrustedStateRoot trustedRoot) {
        AppChainClient.AuthenticatedSnapshotProof nested = proof.proof();
        return new CardanoHistoryPortableStakeProof(1, proof.epoch(), proof.credentialType(),
                HexFormat.of().formatHex(proof.credentialHash()), proof.mode(),
                proof.coin().toString(), HexFormat.of().formatHex(proof.poolHash()),
                HexFormat.of().formatHex(nested.canonicalBundleBytes()),
                CardanoHistoryTrustedRoot.from(trustedRoot));
    }

    BigInteger parsedCoin() {
        return new BigInteger(coin);
    }

    byte[] credentialHash() {
        return HexFormat.of().parseHex(credentialHashHex);
    }

    byte[] poolHash() {
        return poolHashHex.isEmpty() ? new byte[0] : HexFormat.of().parseHex(poolHashHex);
    }

    byte[] canonicalBundle() {
        return HexFormat.of().parseHex(canonicalBundleHex);
    }

    private static boolean unsigned(String value) {
        return value != null && value.matches("0|[1-9][0-9]{0,79}");
    }

    private static boolean hex(String value, int bytes) {
        return value != null && value.length() == bytes * 2 && value.matches("[0-9a-f]+");
    }
}
