package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ADR-UTXO-009 SP-M3: the L1 evidence for one A2 batch settlement — a single
 * observation claim covering every claim the settlement transaction paid,
 * rather than one observation per claim.
 *
 * <p>The framework keys observations by {@code observerId/txHash/slot}
 * ({@code L1Observation.key()}); N observations from the same settlement
 * transaction would collide on that key and only the last would survive
 * follower verification. So a batch settlement emits exactly one observation
 * whose claim is this record: the shared L1 identity once, plus an ordered
 * list of per-claim entries mirroring the positional payouts. Every member
 * recomputes the identical claim from its own L1 view, so verification stays
 * deterministic; the state machine expands it back into per-claim
 * {@link EutxoWithdrawalConfirmation}s and clears each withdrawal.
 */
public record EutxoBatchWithdrawalConfirmation(
        int abiVersion,
        String chainId,
        long bridgeEpoch,
        String settlementTransactionId,
        EutxoOutpoint continuingVaultOutpoint,
        BigInteger continuingVaultLovelace,
        long l1Slot,
        byte[] l1BlockHash,
        List<Entry> entries
) {
    public static final int ABI_VERSION = 1;
    private static final int MAX_ENTRIES = 1_024;

    public EutxoBatchWithdrawalConfirmation {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported EUTxO batch withdrawal confirmation ABI");
        }
        chainId = text(chainId, "chainId", 128);
        if (bridgeEpoch < 0 || l1Slot < 0) {
            throw new IllegalArgumentException(
                    "bridge epoch and L1 slot cannot be negative");
        }
        settlementTransactionId = canonicalHash(
                settlementTransactionId, "settlementTransactionId");
        Objects.requireNonNull(continuingVaultOutpoint, "continuingVaultOutpoint");
        continuingVaultLovelace = nonNegative(
                continuingVaultLovelace, "continuingVaultLovelace");
        l1BlockHash = Objects.requireNonNull(l1BlockHash, "l1BlockHash").clone();
        if (l1BlockHash.length != 32) {
            throw new IllegalArgumentException("L1 block hash must contain 32 bytes");
        }
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "batch confirmation must carry 1-" + MAX_ENTRIES + " entries");
        }
        List<Entry> copied = List.copyOf(entries);
        // Positional payouts occupy output indices [0, count); reject gaps or
        // reordering so the claim is a faithful, deterministic mirror.
        for (int index = 0; index < copied.size(); index++) {
            if (copied.get(index).payoutIndex() != index) {
                throw new IllegalArgumentException(
                        "batch confirmation entries must be positional and dense");
            }
        }
        entries = copied;
    }

    @Override
    public byte[] l1BlockHash() {
        return l1BlockHash.clone();
    }

    public int count() {
        return entries.size();
    }

    /** Expand this batch into the per-claim confirmations the ledger applies. */
    public List<EutxoWithdrawalConfirmation> confirmations() {
        List<EutxoWithdrawalConfirmation> expanded = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            expanded.add(new EutxoWithdrawalConfirmation(
                    EutxoWithdrawalConfirmation.ABI_VERSION,
                    chainId,
                    bridgeEpoch,
                    entry.claimId(),
                    settlementTransactionId,
                    entry.payoutIndex(),
                    entry.destinationAddress(),
                    entry.lovelace(),
                    continuingVaultOutpoint,
                    continuingVaultLovelace,
                    l1Slot,
                    l1BlockHash));
        }
        return List.copyOf(expanded);
    }

    public byte[] encode() {
        return EutxoCbor.encodeBatchWithdrawalConfirmation(this);
    }

    public static EutxoBatchWithdrawalConfirmation decode(byte[] bytes) {
        return EutxoCbor.decodeBatchWithdrawalConfirmation(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoBatchWithdrawalConfirmation confirmation
                && abiVersion == confirmation.abiVersion
                && chainId.equals(confirmation.chainId)
                && bridgeEpoch == confirmation.bridgeEpoch
                && settlementTransactionId.equals(confirmation.settlementTransactionId)
                && continuingVaultOutpoint.equals(confirmation.continuingVaultOutpoint)
                && continuingVaultLovelace.equals(confirmation.continuingVaultLovelace)
                && l1Slot == confirmation.l1Slot
                && java.util.Arrays.equals(l1BlockHash, confirmation.l1BlockHash)
                && entries.equals(confirmation.entries);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                abiVersion, chainId, bridgeEpoch, settlementTransactionId,
                continuingVaultOutpoint, continuingVaultLovelace, l1Slot, entries);
        return 31 * result + java.util.Arrays.hashCode(l1BlockHash);
    }

    /** One settled claim: its id and the positional payout output it received. */
    public record Entry(
            String claimId,
            int payoutIndex,
            String destinationAddress,
            BigInteger lovelace
    ) {
        public Entry {
            claimId = canonicalHash(claimId, "claimId");
            if (payoutIndex < 0) {
                throw new IllegalArgumentException("payout index cannot be negative");
            }
            destinationAddress = text(destinationAddress, "destinationAddress", 256);
            lovelace = positive(lovelace, "lovelace");
        }
    }

    private static String canonicalHash(String value, String field) {
        String hash = text(value, field, 64);
        if (hash.length() != 64
                || !hash.equals(hash.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be 32-byte lowercase hex");
        }
        try {
            java.util.HexFormat.of().parseHex(hash);
            return hash;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    field + " must be 32-byte lowercase hex", failure);
        }
    }

    private static String text(String value, String field, int maximum) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximum + " characters");
        }
        return normalized;
    }

    private static BigInteger positive(BigInteger value, String field) {
        BigInteger checked = nonNegative(value, field);
        if (checked.signum() == 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return checked;
    }

    private static BigInteger nonNegative(BigInteger value, String field) {
        BigInteger checked = Objects.requireNonNull(value, field);
        if (checked.signum() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return checked;
    }
}
