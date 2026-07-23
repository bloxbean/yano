package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Immutable first profile for the genesis-funded ledger.
 *
 * <p>M1 deliberately graduates only key-controlled, zero-fee Conway
 * transactions. Later bounded script families can be added by a new profile
 * version without weakening this profile's consensus meaning.</p>
 */
public record EutxoProfile(
        String id,
        int version,
        int maxTransactionBytes,
        int maxInputs,
        int maxReferenceInputs,
        int maxOutputs,
        int maxAddressUtxos,
        int maxOutputCborBytes,
        boolean scriptsEnabled
) {
    public static final EutxoProfile V1 = new EutxoProfile(
            "yano-eutxo-v1",
            1,
            64 * 1024,
            64,
            64,
            64,
            1_024,
            16 * 1024,
            false);

    public EutxoProfile {
        if (!"yano-eutxo-v1".equals(id) || version != 1) {
            throw new IllegalArgumentException("unsupported EUTxO profile");
        }
        if (maxTransactionBytes < 1 || maxInputs < 1 || maxOutputs < 1
                || maxAddressUtxos < 1 || maxOutputCborBytes < 1) {
            throw new IllegalArgumentException("EUTxO profile bounds must be positive");
        }
    }

    /** Digest of every consensus-relevant profile field. */
    public String digestHex() {
        String canonical = id + '\n' + version + '\n' + maxTransactionBytes + '\n'
                + maxInputs + '\n' + maxReferenceInputs + '\n' + maxOutputs + '\n'
                + maxAddressUtxos + '\n' + maxOutputCborBytes + '\n' + scriptsEnabled;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
