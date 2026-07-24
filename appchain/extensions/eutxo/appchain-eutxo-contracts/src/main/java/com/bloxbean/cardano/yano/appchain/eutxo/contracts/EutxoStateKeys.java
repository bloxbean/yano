package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned deterministic keys committed by the Yano app-chain MPF. */
public final class EutxoStateKeys {
    private static final String PREFIX = "eutxo/v1/";
    private static final byte[] WITHDRAWAL_COMMITMENT_PREFIX =
            bytes(PREFIX + "wc/");

    private EutxoStateKeys() {
    }

    public static byte[] profile() {
        return bytes(PREFIX + "profile");
    }

    public static byte[] genesis() {
        return bytes(PREFIX + "genesis");
    }

    /** Selected optional validity-commitment engine identity. */
    public static byte[] validityEngine() {
        return bytes(PREFIX + "validity/engine");
    }

    /** Latest ZK-friendly validity commitment, distinct from the runtime MPF root. */
    public static byte[] validityRoot() {
        return bytes(PREFIX + "validity/root");
    }

    /** Descriptor of the transition witness that produced the latest validity root. */
    public static byte[] validityWitness() {
        return bytes(PREFIX + "validity/witness");
    }

    /** Exact finalized transition witness keyed by app height and message ordinal. */
    public static byte[] validityTransition(long appHeight, int ordinal) {
        if (appHeight < 1 || ordinal < 0) {
            throw new IllegalArgumentException(
                    "invalid validity transition position");
        }
        return bytes(PREFIX + "validity/transition/"
                + appHeight + "/" + ordinal);
    }

    /** Active L2 authorization key for one Cardano payment credential. */
    public static byte[] l2Key(String paymentCredential) {
        String credential = EutxoL2Authorization.credential(paymentCredential);
        return bytes(PREFIX + "validity/l2-key/" + credential);
    }

    public static byte[] utxo(EutxoOutpoint outpoint) {
        return bytes(PREFIX + "u/" + Objects.requireNonNull(outpoint, "outpoint"));
    }

    public static byte[] transaction(String transactionId) {
        return bytes(PREFIX + "t/" + transactionId(transactionId));
    }

    public static byte[] deposit(EutxoOutpoint acceptedOutpoint) {
        return bytes(PREFIX + "d/" + Objects.requireNonNull(
                acceptedOutpoint, "acceptedOutpoint"));
    }

    public static byte[] reserve(String assetId) {
        Objects.requireNonNull(assetId, "assetId");
        if (assetId.isBlank() || assetId.length() > 120) {
            throw new IllegalArgumentException("asset id must contain 1-120 characters");
        }
        return bytes(PREFIX + "r/" + assetId);
    }

    public static byte[] bridgeHalt() {
        return bytes(PREFIX + "bridge/halt");
    }

    public static byte[] withdrawal(String claimId) {
        return bytes(PREFIX + "w/" + transactionId(claimId));
    }

    public static byte[] withdrawalCommitment(String claimId) {
        byte[] claim = HexFormat.of().parseHex(transactionId(claimId));
        byte[] key = java.util.Arrays.copyOf(
                WITHDRAWAL_COMMITMENT_PREFIX,
                WITHDRAWAL_COMMITMENT_PREFIX.length + claim.length);
        System.arraycopy(
                claim,
                0,
                key,
                WITHDRAWAL_COMMITMENT_PREFIX.length,
                claim.length);
        return key;
    }

    public static byte[] withdrawalCommitmentPrefix() {
        return WITHDRAWAL_COMMITMENT_PREFIX.clone();
    }

    public static byte[] pendingWithdrawalCount() {
        return bytes(PREFIX + "bridge/pending-withdrawal-count");
    }

    public static byte[] totalWithdrawalCount(long bridgeEpoch) {
        if (bridgeEpoch < 0) {
            throw new IllegalArgumentException(
                    "bridge epoch cannot be negative");
        }
        return bytes(PREFIX + "bridge/" + bridgeEpoch
                + "/total-withdrawal-count");
    }

    public static byte[] attempt(byte[] appMessageId) {
        Objects.requireNonNull(appMessageId, "appMessageId");
        if (appMessageId.length != 32) {
            throw new IllegalArgumentException("app message id must contain 32 bytes");
        }
        return bytes(PREFIX + "a/" + HexFormat.of().formatHex(appMessageId));
    }

    public static byte[] addressIndex(String address) {
        Objects.requireNonNull(address, "address");
        if (address.isBlank() || address.length() > 256) {
            throw new IllegalArgumentException("address must contain 1-256 characters");
        }
        byte[] digest = Blake2bUtil.blake2bHash256(address.getBytes(StandardCharsets.UTF_8));
        return bytes(PREFIX + "x/address/" + HexFormat.of().formatHex(digest));
    }

    private static String transactionId(String value) {
        return new EutxoOutpoint(value, 0).transactionId();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
