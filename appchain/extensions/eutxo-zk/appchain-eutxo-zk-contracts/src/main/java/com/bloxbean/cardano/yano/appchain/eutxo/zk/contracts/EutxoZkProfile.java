package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable identity and bounds for one EUTxO validity circuit family. */
public record EutxoZkProfile(
        String id,
        int version,
        String circuitId,
        String proofSystem,
        String curve,
        int maximumBatchSize,
        int maximumInputs,
        int maximumOutputs
) {
    public static final EutxoZkProfile Z0_SINGLE_KEY_PAYMENT =
            new EutxoZkProfile(
                    "yano-eutxo-zk-z0",
                    1,
                    "eutxo-key-payment-v1",
                    "groth16",
                    "bls12-381",
                    1,
                    1,
                    2);
    public static final EutxoZkProfile Z1_BOUNDED_KEY_PAYMENTS =
            new EutxoZkProfile(
                    "yano-eutxo-zk-z1",
                    1,
                    "eutxo-key-payment-batch-v1",
                    "groth16",
                    "bls12-381",
                    4,
                    4,
                    8);
    public static final EutxoZkProfile Z3_VALIDITY_SETTLEMENT =
            new EutxoZkProfile(
                    "yano-eutxo-zk-z3",
                    1,
                    "eutxo-key-payment-settlement-v1",
                    "groth16",
                    "bls12-381",
                    4,
                    4,
                    8);

    public EutxoZkProfile {
        if (id == null || id.isBlank() || circuitId == null || circuitId.isBlank()
                || !"groth16".equals(proofSystem) || !"bls12-381".equals(curve)
                || version < 1 || maximumBatchSize < 1
                || maximumInputs < 1 || maximumOutputs < 1) {
            throw new IllegalArgumentException("invalid EUTxO ZK profile");
        }
    }

    public String digestHex() {
        String canonical = id + '\n' + version + '\n' + circuitId + '\n'
                + proofSystem + '\n' + curve + '\n' + maximumBatchSize + '\n'
                + maximumInputs + '\n' + maximumOutputs;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
