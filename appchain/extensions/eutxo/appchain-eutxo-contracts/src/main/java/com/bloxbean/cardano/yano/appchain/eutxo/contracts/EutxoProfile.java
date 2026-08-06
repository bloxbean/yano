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

    // ADR-UTXO-009 tier-1 (consensus-frozen) bridge-settlement bounds. They
    // are digest-bound for version >= 3; changing any of them is a new
    // profile. Batch-size caps join the digest in SP-M2 once ex-unit
    // budgets are measured.
    public static final long V3_BOUNTY_CAP_FLAT_LOVELACE = 5_000_000L;
    public static final int V3_BOUNTY_CAP_BASIS_POINTS = 100;
    public static final int V3_NULLIFIER_SHARDS = 16;
    public static final long V3_FALLBACK_DELAY_MIN_SLOTS = 21_600L;
    public static final long V3_FALLBACK_DELAY_MAX_SLOTS = 2_592_000L;
    /**
     * Tier-1 max claims per settlement transaction, measured in SP-M2 on the
     * julc VM: the A2 signer path amortizes an O(1) threshold check so it
     * batches wide (16 claims well under the 10B cpu / 14M mem tx limits);
     * the A3 proof path pays ~80M cpu per MPF inclusion so it batches narrow.
     */
    public static final int V3_MAX_SETTLE_BATCH = 16;
    public static final int V3_MAX_EXIT_BATCH = 6;

    /**
     * ADR-UTXO-009 bridge-settlement profile: V2's script surface plus the
     * governed-settlement machine semantics (claim ABI v2 with committed
     * executor bounty, governed bridge parameters, nullifier shards).
     */
    public static final EutxoProfile V3 = new EutxoProfile(
            "yano-eutxo-v3-bridge-settlement",
            3,
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
        boolean v3 = "yano-eutxo-v3-bridge-settlement".equals(id) && version == 3;
        if (!v1 && !v2 && !v3) {
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
        if (version >= 3) {
            canonical += "\nbridge-settlement\n" + V3_BOUNTY_CAP_FLAT_LOVELACE
                    + '\n' + V3_BOUNTY_CAP_BASIS_POINTS
                    + '\n' + V3_NULLIFIER_SHARDS
                    + '\n' + V3_FALLBACK_DELAY_MIN_SLOTS
                    + '\n' + V3_FALLBACK_DELAY_MAX_SLOTS
                    + '\n' + V3_MAX_SETTLE_BATCH
                    + '\n' + V3_MAX_EXIT_BATCH;
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
