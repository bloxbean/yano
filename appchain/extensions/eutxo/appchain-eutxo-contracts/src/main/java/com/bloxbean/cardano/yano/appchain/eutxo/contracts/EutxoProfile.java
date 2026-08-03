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
        boolean scriptsEnabled,
        String scriptFamily,
        String scalusVersion,
        String protocolParametersDigest,
        int maxScripts,
        int maxDatums,
        int maxRedeemers,
        long maxExecutionMemory,
        long maxExecutionSteps
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
            false,
            "none",
            "none",
            "none",
            0,
            0,
            0,
            0,
            0);

    /**
     * First script-enabled profile. Only witnessed Plutus V3 spending is in
     * scope; minting, reference scripts, datum hashes, and failed-script
     * collateral transitions remain explicitly unsupported.
     */
    public static final EutxoProfile V2 = new EutxoProfile(
            "yano-eutxo-v2-plutus-v3",
            2,
            64 * 1024,
            64,
            0,
            64,
            1_024,
            16 * 1024,
            true,
            "plutus-v3-spend",
            "0.18.2",
            "96a3f80d3bff533febc37d367e293f7a4004a63655d99294536d1b39918441fe",
            16,
            32,
            16,
            14_000_000,
            10_000_000_000L);

    public EutxoProfile {
        boolean v1 = "yano-eutxo-v1".equals(id) && version == 1;
        boolean v2 = "yano-eutxo-v2-plutus-v3".equals(id) && version == 2;
        if (!v1 && !v2) {
            throw new IllegalArgumentException("unsupported EUTxO profile");
        }
        if (maxTransactionBytes < 1 || maxInputs < 1 || maxOutputs < 1
                || maxAddressUtxos < 1 || maxOutputCborBytes < 1) {
            throw new IllegalArgumentException("EUTxO profile bounds must be positive");
        }
        if (scriptFamily == null || scalusVersion == null
                || protocolParametersDigest == null) {
            throw new IllegalArgumentException("EUTxO script profile fields are required");
        }
        if (!scriptsEnabled && (maxScripts != 0 || maxDatums != 0
                || maxRedeemers != 0 || maxExecutionMemory != 0
                || maxExecutionSteps != 0)) {
            throw new IllegalArgumentException("script-disabled profile must have zero script bounds");
        }
        if (scriptsEnabled && (maxScripts < 1 || maxDatums < 1
                || maxRedeemers < 1 || maxExecutionMemory < 1
                || maxExecutionSteps < 1)) {
            throw new IllegalArgumentException("script-enabled profile bounds must be positive");
        }
    }

    /** Digest of every consensus-relevant profile field. */
    public String digestHex() {
        String canonical = id + '\n' + version + '\n' + maxTransactionBytes + '\n'
                + maxInputs + '\n' + maxReferenceInputs + '\n' + maxOutputs + '\n'
                + maxAddressUtxos + '\n' + maxOutputCborBytes + '\n' + scriptsEnabled;
        if (version >= 2) {
            canonical += '\n' + scriptFamily + '\n' + scalusVersion + '\n'
                    + protocolParametersDigest + '\n' + maxScripts + '\n' + maxDatums + '\n'
                    + maxRedeemers + '\n' + maxExecutionMemory + '\n' + maxExecutionSteps;
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
