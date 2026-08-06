package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

/**
 * Governed tier-2 bridge-settlement parameters (ADR-UTXO-009 §8). Genesis
 * supplies the initial record; later values activate through
 * {@link EutxoBridgeParamsGovernanceV1} commands at recorded heights. The
 * tier-1 ceilings they must respect are digest-frozen in
 * {@link EutxoProfile} V3.
 */
public record EutxoBridgeParams(
        int version,
        long feeFlatLovelace,
        int feeBasisPoints,
        long minWithdrawalLovelace,
        int softBatchCap,
        long rootingBlocks,
        long rootingSeconds,
        long fallbackDelaySlots,
        long effectiveHeight
) {
    public static final int VERSION = 1;

    public EutxoBridgeParams {
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported bridge params version");
        }
        if (feeFlatLovelace < 0
                || feeFlatLovelace > EutxoProfile.V3_BOUNTY_CAP_FLAT_LOVELACE) {
            throw new IllegalArgumentException(
                    "flat fee is outside the profile's frozen bounty cap");
        }
        if (feeBasisPoints < 0
                || feeBasisPoints > EutxoProfile.V3_BOUNTY_CAP_BASIS_POINTS) {
            throw new IllegalArgumentException(
                    "basis-point fee is outside the profile's frozen bounty cap");
        }
        if (minWithdrawalLovelace < 1_000_000L) {
            throw new IllegalArgumentException(
                    "minimum withdrawal must be at least 1000000 lovelace");
        }
        if (softBatchCap < 1 || softBatchCap > 1_024) {
            throw new IllegalArgumentException("soft batch cap must be in 1-1024");
        }
        if (rootingBlocks < 1 || rootingSeconds < 1) {
            throw new IllegalArgumentException(
                    "rooting cadence values must be positive");
        }
        if (fallbackDelaySlots < EutxoProfile.V3_FALLBACK_DELAY_MIN_SLOTS
                || fallbackDelaySlots > EutxoProfile.V3_FALLBACK_DELAY_MAX_SLOTS) {
            throw new IllegalArgumentException(
                    "fallback delay is outside the profile's frozen bounds");
        }
        if (effectiveHeight < 0) {
            throw new IllegalArgumentException("effective height cannot be negative");
        }
    }

    /** ADR-UTXO-009 §9 ratified defaults (flat 2 ADA, bps 0). */
    public static EutxoBridgeParams defaults() {
        return new EutxoBridgeParams(
                VERSION, 2_000_000L, 0, 2_000_000L, 8, 100L, 3_600L, 86_400L, 0L);
    }

    /**
     * The executor bounty a withdrawal of {@code total} lovelace pays:
     * {@code flat + total * bps / 10_000}, always below {@code total} by
     * claim-creation validation (payout minimum), and bounded by the frozen
     * caps through record validation.
     */
    public BigInteger resolveBounty(BigInteger total) {
        return BigInteger.valueOf(feeFlatLovelace).add(
                total.multiply(BigInteger.valueOf(feeBasisPoints))
                        .divide(BigInteger.valueOf(10_000)));
    }

    public EutxoBridgeParams withEffectiveHeight(long height) {
        return new EutxoBridgeParams(version, feeFlatLovelace, feeBasisPoints,
                minWithdrawalLovelace, softBatchCap, rootingBlocks,
                rootingSeconds, fallbackDelaySlots, height);
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new CborEncoder(out).encode(new CborBuilder()
                    .addArray()
                    .add(new UnsignedInteger(version))
                    .add(new UnsignedInteger(feeFlatLovelace))
                    .add(new UnsignedInteger(feeBasisPoints))
                    .add(new UnsignedInteger(minWithdrawalLovelace))
                    .add(new UnsignedInteger(softBatchCap))
                    .add(new UnsignedInteger(rootingBlocks))
                    .add(new UnsignedInteger(rootingSeconds))
                    .add(new UnsignedInteger(fallbackDelaySlots))
                    .add(new UnsignedInteger(effectiveHeight))
                    .end()
                    .build());
            return out.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot encode bridge params", failure);
        }
    }

    public static EutxoBridgeParams decode(byte[] bytes) {
        try {
            List<DataItem> items = new CborDecoder(
                    new ByteArrayInputStream(bytes)).decode();
            if (items.size() != 1 || !(items.getFirst() instanceof Array array)
                    || array.getDataItems().size() != 9) {
                throw new IllegalArgumentException(
                        "bridge params must be a 9-field CBOR array");
            }
            List<DataItem> fields = array.getDataItems();
            return new EutxoBridgeParams(
                    intAt(fields, 0),
                    longAt(fields, 1),
                    intAt(fields, 2),
                    longAt(fields, 3),
                    intAt(fields, 4),
                    longAt(fields, 5),
                    longAt(fields, 6),
                    longAt(fields, 7),
                    longAt(fields, 8));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("malformed bridge params", failure);
        }
    }

    private static long longAt(List<DataItem> fields, int index) {
        if (!(fields.get(index) instanceof UnsignedInteger value)) {
            throw new IllegalArgumentException(
                    "bridge params field " + index + " must be an unsigned integer");
        }
        return value.getValue().longValueExact();
    }

    private static int intAt(List<DataItem> fields, int index) {
        return Math.toIntExact(longAt(fields, index));
    }
}
