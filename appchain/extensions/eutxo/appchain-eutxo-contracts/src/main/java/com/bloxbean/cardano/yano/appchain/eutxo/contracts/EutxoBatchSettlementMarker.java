package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Inline datum on the continuing vault output of a batched A2 settlement
 * (ADR-UTXO-009 §7.2). Byte-exact twin of the on-chain
 * {@code SettlementVaultValidator} batch marker {@code Constr0[version=1,
 * count, concat(claimIds)]}: the ordered claim ids let the confirmation
 * observer reconcile each claim positionally (claim[i] -> payout output[i])
 * even when two claims share an (address, amount).
 */
public record EutxoBatchSettlementMarker(
        int version,
        List<String> claimIds
) {
    public static final int VERSION = 1;
    private static final int MAX_CLAIMS = 1_024;

    public EutxoBatchSettlementMarker {
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "unsupported batch settlement marker version");
        }
        Objects.requireNonNull(claimIds, "claimIds");
        if (claimIds.isEmpty() || claimIds.size() > MAX_CLAIMS) {
            throw new IllegalArgumentException(
                    "batch marker must carry 1-" + MAX_CLAIMS + " claim ids");
        }
        List<String> normalized = new ArrayList<>(claimIds.size());
        for (String claimId : claimIds) {
            normalized.add(canonicalHash(claimId));
        }
        claimIds = List.copyOf(normalized);
    }

    public int count() {
        return claimIds.size();
    }

    public byte[] encode() {
        try {
            byte[] joined = new byte[claimIds.size() * 32];
            for (int i = 0; i < claimIds.size(); i++) {
                byte[] raw = HexFormat.of().parseHex(claimIds.get(i));
                System.arraycopy(raw, 0, joined, i * 32, 32);
            }
            return ConstrPlutusData.builder()
                    .alternative(0)
                    .data(ListPlutusData.of(
                            BigIntPlutusData.of(version),
                            BigIntPlutusData.of(claimIds.size()),
                            BytesPlutusData.of(joined)))
                    .build()
                    .serializeToBytes();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot encode batch settlement marker", failure);
        }
    }

    public static EutxoBatchSettlementMarker decode(byte[] cbor) {
        Objects.requireNonNull(cbor, "cbor");
        try {
            PlutusData decoded = PlutusData.deserialize(cbor);
            if (!(decoded instanceof ConstrPlutusData constr)
                    || constr.getAlternative() != 0) {
                throw new IllegalArgumentException(
                        "batch marker must use constructor zero");
            }
            List<PlutusData> fields = constr.getData().getPlutusDataList();
            if (fields.size() != 3) {
                throw new IllegalArgumentException(
                        "batch marker must contain three fields");
            }
            int version = ((BigIntPlutusData) fields.get(0))
                    .getValue().intValueExact();
            int count = ((BigIntPlutusData) fields.get(1))
                    .getValue().intValueExact();
            byte[] joined = ((BytesPlutusData) fields.get(2)).getValue();
            if (count < 1 || joined.length != count * 32) {
                throw new IllegalArgumentException(
                        "batch marker claim-id length does not match count");
            }
            List<String> claimIds = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] raw = new byte[32];
                System.arraycopy(joined, i * 32, raw, 0, 32);
                claimIds.add(HexFormat.of().formatHex(raw));
            }
            return new EutxoBatchSettlementMarker(version, claimIds);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "invalid batch settlement marker", failure);
        }
    }

    private static String canonicalHash(String value) {
        String normalized = Objects.requireNonNull(value, "claimId").trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() != 64 || !normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "claimId must be 32-byte lowercase hex");
        }
        return normalized;
    }

    /** Ordering used both for the marker and positional payout outputs. */
    public static List<String> orderedFrom(
            List<EutxoWithdrawalClaim> claims) {
        List<String> ids = new ArrayList<>(claims.size());
        for (EutxoWithdrawalClaim claim : claims) {
            ids.add(claim.claimId());
        }
        return List.copyOf(ids);
    }
}
