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

    /** Active governed bridge parameters (ADR-UTXO-009, v3 profiles). */
    public static byte[] bridgeParamsCurrent() {
        return bytes(PREFIX + "bridge/params/current");
    }

    /** Threshold-scheduled parameters awaiting their activation height. */
    public static byte[] bridgeParamsPending() {
        return bytes(PREFIX + "bridge/params/pending");
    }

    /** Open parameter proposals (approval accumulation by command digest). */
    public static byte[] bridgeParamsProposals() {
        return bytes(PREFIX + "bridge/params/proposals");
    }

    /** Historical parameter record activated at {@code height} (0 = genesis). */
    public static byte[] bridgeParamsHistory(long height) {
        if (height < 0) {
            throw new IllegalArgumentException("history height cannot be negative");
        }
        return bytes(PREFIX + "bridge/params/history/"
                + String.format(java.util.Locale.ROOT, "%020d", height));
    }

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

    public static byte[] transactionSummary(String transactionId) {
        return bytes(PREFIX + "summary/tx/" + transactionId(transactionId));
    }

    public static byte[] messageSummary(byte[] appMessageId) {
        Objects.requireNonNull(appMessageId, "appMessageId");
        if (appMessageId.length != 32) {
            throw new IllegalArgumentException("app message id must contain 32 bytes");
        }
        return bytes(PREFIX + "summary/message/"
                + HexFormat.of().formatHex(appMessageId));
    }

    public static byte[] summaryIndex(long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("summary sequence must be positive");
        }
        return bytes(PREFIX + "summary/index/"
                + String.format(java.util.Locale.ROOT, "%020d", sequence));
    }

    public static byte[] summaryCount() {
        return bytes(PREFIX + "summary/count");
    }

    public static byte[] deposit(EutxoOutpoint acceptedOutpoint) {
        return bytes(PREFIX + "d/" + Objects.requireNonNull(
                acceptedOutpoint, "acceptedOutpoint"));
    }

    /**
     * A LIVE L1 vault UTxO (v3 settlement profile): present while the
     * outpoint is spendable vault custody. Settlement confirmations must
     * consume one of these — the authenticity anchor that a marker output
     * alone cannot fabricate (ADR-UTXO-009 SP-M6 review fix).
     */
    public static byte[] bridgeVaultOutpoint(EutxoOutpoint outpoint) {
        return bytes(PREFIX + "bridge/vault-utxo/" + Objects.requireNonNull(
                outpoint, "outpoint"));
    }

    /** Immutable deposit record in canonical acceptance order. */
    public static byte[] depositIndex(long sequence) {
        return indexedKey(PREFIX + "bridge/deposit/index/", sequence);
    }

    public static byte[] depositCount() {
        return bytes(PREFIX + "bridge/deposit/count");
    }

    /** Next unsettled withdrawal sequence in an epoch (A2 batch cursor). */
    public static byte[] settlementCursor(long bridgeEpoch) {
        if (bridgeEpoch < 0) {
            throw new IllegalArgumentException("bridge epoch cannot be negative");
        }
        return bytes(PREFIX + "bridge/" + bridgeEpoch + "/settlement/cursor");
    }

    /** Height at which the current settlement window opened (0 = closed). */
    public static byte[] settlementWindowOpen(long bridgeEpoch) {
        if (bridgeEpoch < 0) {
            throw new IllegalArgumentException("bridge epoch cannot be negative");
        }
        return bytes(PREFIX + "bridge/" + bridgeEpoch + "/settlement/window");
    }

    /** Monotonic settlement batch sequence within an epoch. */
    public static byte[] settlementBatchSeq(long bridgeEpoch) {
        if (bridgeEpoch < 0) {
            throw new IllegalArgumentException("bridge epoch cannot be negative");
        }
        return bytes(PREFIX + "bridge/" + bridgeEpoch + "/settlement/batch-seq");
    }

    /** Start sequence of a dispatched batch (for terminal-failure rewind). */
    public static byte[] settlementBatchStart(long bridgeEpoch, long batchSeq) {
        if (bridgeEpoch < 0 || batchSeq < 0) {
            throw new IllegalArgumentException("epoch and batch seq must be >= 0");
        }
        return bytes(PREFIX + "bridge/" + bridgeEpoch
                + "/settlement/start/" + batchSeq);
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

    /** Withdrawal claim id in canonical order within one bridge epoch. */
    public static byte[] withdrawalIndex(long bridgeEpoch, long sequence) {
        if (bridgeEpoch < 0) {
            throw new IllegalArgumentException(
                    "bridge epoch cannot be negative");
        }
        return indexedKey(
                PREFIX + "bridge/" + bridgeEpoch + "/withdrawal/index/",
                sequence);
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

    private static byte[] indexedKey(String prefix, long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("index sequence must be positive");
        }
        return bytes(prefix + String.format(
                java.util.Locale.ROOT, "%020d", sequence));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
