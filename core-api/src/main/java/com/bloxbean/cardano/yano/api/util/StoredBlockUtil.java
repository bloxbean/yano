package com.bloxbean.cardano.yano.api.util;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.math.BigInteger;

/**
 * Helpers for interpreting block bytes already persisted in {@code ChainState}.
 */
public final class StoredBlockUtil {
    /** Byron's outer CBOR discriminator. It is not a Shelley-era ordinal. */
    public enum ByronEnvelopeKind {
        MAIN,
        EBB
    }

    private StoredBlockUtil() {
    }

    /**
     * Return true only when a stored block body is explicitly tagged as Byron.
     * <p>
     * Ledger-state replay intentionally skips Byron bodies because live sync does
     * not feed Byron block-derived UTXO/account events into those stores. Unknown
     * or malformed CBOR must not be treated as Byron; replay should fail closed
     * for non-Byron corruption instead of silently skipping data.
     */
    public static boolean isStoredByronBlock(Era storedEra, byte[] blockBytes) {
        if (storedEra != null && storedEra != Era.Byron) {
            return false;
        }
        if (blockBytes == null) {
            return false;
        }

        try {
            requireByronEnvelopeKind(blockBytes);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Classify a stored Byron envelope before selecting a body decoder.
     *
     * <p>The Byron wire format uses {@code 0} for an epoch-boundary block and
     * {@code 1} for a regular/main block. Decoders must not be used for
     * classification because the current Byron decoders are permissive about
     * these discriminators.</p>
     *
     * @throws IllegalArgumentException when the envelope is malformed or has
     *                                  an unsupported discriminator
     */
    public static ByronEnvelopeKind requireByronEnvelopeKind(byte[] blockBytes) {
        if (blockBytes == null) {
            throw new IllegalArgumentException("Stored Byron block bytes are required");
        }
        try {
            DataItem item = CborSerializationUtil.deserializeOne(blockBytes);
            if (!(item instanceof Array array) || array.getDataItems().isEmpty()) {
                throw new IllegalArgumentException("Stored Byron block must be a non-empty CBOR array");
            }
            DataItem first = array.getDataItems().get(0);
            if (!(first instanceof UnsignedInteger envelopeTag)) {
                throw new IllegalArgumentException("Stored Byron envelope tag must be an unsigned integer");
            }
            BigInteger tag = envelopeTag.getValue();
            if (BigInteger.ZERO.equals(tag)) {
                return ByronEnvelopeKind.EBB;
            }
            if (BigInteger.ONE.equals(tag)) {
                return ByronEnvelopeKind.MAIN;
            }
            throw new IllegalArgumentException("Unsupported stored Byron envelope tag: " + tag);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalArgumentException("Failed to decode stored Byron envelope", t);
        }
    }
}
