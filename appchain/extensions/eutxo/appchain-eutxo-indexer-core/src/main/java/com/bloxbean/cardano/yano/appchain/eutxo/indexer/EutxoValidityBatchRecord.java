package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral, bounded public metadata for one validity batch.
 *
 * <p>Proof bytes, private witnesses, proving keys, and provider implementation
 * classes deliberately stay outside the lifecycle index.</p>
 */
public record EutxoValidityBatchRecord(
        String batchId,
        String provider,
        String proofSystem,
        String profileId,
        String profileDigest,
        List<String> transactionIds,
        String previousRoot,
        String nextRoot,
        String dataCommitment,
        String dataStatus,
        String proofDigest,
        String verificationKeyDigest,
        String proofStatus,
        String settlementStatus,
        String settlementTransactionId,
        long settlementSlot,
        String settlementBlockHash
) {
    public EutxoValidityBatchRecord {
        batchId = digest(batchId, "batchId");
        provider = text(provider, "provider");
        proofSystem = text(proofSystem, "proofSystem");
        profileId = text(profileId, "profileId");
        profileDigest = digest(profileDigest, "profileDigest");
        transactionIds = List.copyOf(Objects.requireNonNull(
                transactionIds, "transactionIds"));
        if (transactionIds.isEmpty() || transactionIds.size() > 64
                || transactionIds.stream().anyMatch(id ->
                id == null || !id.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "validity batch requires 1-64 transaction ids");
        }
        previousRoot = digest(previousRoot, "previousRoot");
        nextRoot = digest(nextRoot, "nextRoot");
        dataCommitment = digest(dataCommitment, "dataCommitment");
        dataStatus = text(dataStatus, "dataStatus");
        proofDigest = digest(proofDigest, "proofDigest");
        verificationKeyDigest = digest(
                verificationKeyDigest, "verificationKeyDigest");
        proofStatus = text(proofStatus, "proofStatus");
        settlementStatus = text(settlementStatus, "settlementStatus");
        settlementTransactionId = optionalDigest(
                settlementTransactionId, "settlementTransactionId");
        settlementBlockHash = optionalDigest(
                settlementBlockHash, "settlementBlockHash");
        if (settlementSlot < 0) {
            throw new IllegalArgumentException(
                    "settlementSlot cannot be negative");
        }
    }

    private static String text(String value, String label) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > 128
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static String digest(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static String optionalDigest(String value, String label) {
        value = Objects.requireNonNullElse(value, "");
        return value.isEmpty() ? "" : digest(value, label);
    }
}
