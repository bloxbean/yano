package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchSettlementMarker;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * ADR-UTXO-009 SP-M6: the settlement stack's window onto the chain's
 * COMMITTED withdrawal state, used on BOTH sides of the co-sign round —
 * the owner resolves a batch's claims from it, and every member verifies a
 * proposed settlement body against it before signing (the custody gate).
 *
 * <p>Reads go through the injected committed-state query (path, request →
 * response payload), so owner and members always judge from their own
 * node's committed view.
 */
final class SettlementClaimsView {
    /** The machine's lifecycle-page ceiling. */
    private static final int PAGE_LIMIT = 50;

    private final BiFunction<String, byte[], byte[]> query;

    SettlementClaimsView(BiFunction<String, byte[], byte[]> query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    /**
     * The PENDING claims of a settlement batch, in settlement-sequence
     * order. Claims that already settled (confirmed) drop out — an empty
     * result means the whole range is done.
     */
    List<EutxoWithdrawalClaim> pendingClaimsInRange(EutxoSettlementBatch batch) {
        List<EutxoWithdrawalClaim> claims = new ArrayList<>();
        for (EutxoWithdrawalRecord record : lifecyclePage()) {
            EutxoWithdrawalClaim claim = record.claim();
            if (claim.settlementSequence() >= batch.fromSequence()
                    && claim.settlementSequence() < batch.toSequence()
                    && record.status() == EutxoWithdrawalRecord.Status.PENDING) {
                claims.add(claim);
            }
        }
        claims.sort(java.util.Comparator.comparingLong(
                EutxoWithdrawalClaim::settlementSequence));
        return List.copyOf(claims);
    }

    /**
     * Every SETTLED (confirmed) claim id belonging to {@code shard} — the
     * reconstruction input for the shard's nullifier mirror (SP-M4: the MPF
     * root is a pure function of this set).
     */
    List<byte[]> settledClaimIdsForShard(int shard) {
        List<byte[]> ids = new ArrayList<>();
        for (EutxoWithdrawalRecord record : lifecyclePage()) {
            if (record.status() != EutxoWithdrawalRecord.Status.CONFIRMED) {
                continue;
            }
            byte[] id = HexFormat.of().parseHex(record.claim().claimId());
            if ((id[31] & 0x0F) == shard) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * The member-side custody gate: a proposed settlement body is signable
     * only when its batch marker's claims are OUR OWN pending claims and
     * every positional payout pays exactly the committed destination and
     * amount. Anything undecodable or mismatched refuses.
     */
    boolean verifyProposedBody(byte[] unsignedBodyCbor, String vaultAddress) {
        try {
            TransactionBody body = TransactionBody.deserialize(
                    (co.nstant.in.cbor.model.Map) CborSerializationUtil
                            .deserialize(unsignedBodyCbor));
            List<TransactionOutput> outputs = body.getOutputs();
            if (outputs == null || outputs.isEmpty()) {
                return false;
            }
            EutxoBatchSettlementMarker marker = null;
            int markerIndex = -1;
            for (int index = 0; index < outputs.size(); index++) {
                TransactionOutput output = outputs.get(index);
                if (!vaultAddress.equals(output.getAddress())
                        || output.getInlineDatum() == null) {
                    continue;
                }
                try {
                    marker = EutxoBatchSettlementMarker.decode(
                            output.getInlineDatum().serializeToBytes());
                    markerIndex = index;
                } catch (RuntimeException notMarker) {
                    // a shard-continuation datum also sits at the vault-side
                    // of the tx; ignore non-marker datums
                }
            }
            if (marker == null || markerIndex != marker.count()) {
                return false;
            }
            for (int index = 0; index < marker.count(); index++) {
                EutxoWithdrawalRecord record = withdrawal(
                        marker.claimIds().get(index));
                if (record == null
                        || record.status() != EutxoWithdrawalRecord.Status.PENDING) {
                    return false;
                }
                TransactionOutput payout = outputs.get(index);
                if (!record.claim().destinationAddress()
                        .equals(payout.getAddress())
                        || !record.claim().lovelace()
                        .equals(payout.getValue().getCoin())
                        || (payout.getValue().getMultiAssets() != null
                        && !payout.getValue().getMultiAssets().isEmpty())) {
                    return false;
                }
            }
            return true;
        } catch (Exception undecodable) {
            return false;
        }
    }

    private EutxoWithdrawalRecord withdrawal(String claimId) {
        byte[] response = query.apply(
                EutxoQueryCodec.WITHDRAWAL_PATH,
                EutxoQueryCodec.withdrawalRequest(claimId));
        return response == null ? null
                : EutxoQueryCodec.decodeOptionalWithdrawalRecord(response);
    }

    /**
     * Every withdrawal record, aggregated across lifecycle pages: the
     * machine serves up to 50 records per page walking DOWN from index
     * {@code before − 1}, so iterate from the total count.
     */
    private List<EutxoWithdrawalRecord> lifecyclePage() {
        byte[] countResponse = query.apply(
                EutxoQueryCodec.WITHDRAWAL_COUNT_PATH, new byte[0]);
        long count = countResponse == null ? 0
                : EutxoQueryCodec.decodeCount(countResponse);
        List<EutxoWithdrawalRecord> records = new ArrayList<>();
        for (long high = count; high > 0; high -= PAGE_LIMIT) {
            byte[] response = query.apply(
                    EutxoQueryCodec.WITHDRAWALS_PATH,
                    EutxoQueryCodec.lifecyclePageRequest(high + 1, PAGE_LIMIT));
            if (response == null) {
                break;
            }
            records.addAll(EutxoQueryCodec.decodeWithdrawalRecords(response));
        }
        return records;
    }
}
