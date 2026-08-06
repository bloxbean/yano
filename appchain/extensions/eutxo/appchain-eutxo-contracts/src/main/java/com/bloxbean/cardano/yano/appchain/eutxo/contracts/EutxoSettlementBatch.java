package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Payload of an {@code l1.settlement} effect (ADR-UTXO-009 §7.2/SP-M3): the
 * half-open settlement-sequence range {@code [fromSequence, toSequence)} the
 * owning executor must batch-settle for one bridge epoch. A range (rather
 * than an embedded claim-id list) keeps the payload O(1) and lets the
 * executor resolve the claims through the query API deterministically.
 */
public record EutxoSettlementBatch(
        int version,
        String chainId,
        long bridgeEpoch,
        long batchSeq,
        long fromSequence,
        long toSequence
) {
    public static final int VERSION = 1;

    public EutxoSettlementBatch {
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported settlement batch version");
        }
        chainId = chainId == null ? "" : chainId.trim();
        if (chainId.isEmpty() || chainId.length() > 128) {
            throw new IllegalArgumentException("chain id must contain 1-128 characters");
        }
        if (bridgeEpoch < 0 || batchSeq < 0 || fromSequence < 0
                || toSequence <= fromSequence) {
            throw new IllegalArgumentException("settlement batch range is invalid");
        }
    }

    public int claimCount() {
        return Math.toIntExact(toSequence - fromSequence);
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new CborEncoder(out).encode(new CborBuilder()
                    .addArray()
                    .add(new UnsignedInteger(version))
                    .add(chainId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .add(new UnsignedInteger(bridgeEpoch))
                    .add(new UnsignedInteger(batchSeq))
                    .add(new UnsignedInteger(fromSequence))
                    .add(new UnsignedInteger(toSequence))
                    .end()
                    .build());
            return out.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot encode settlement batch", failure);
        }
    }

    public static EutxoSettlementBatch decode(byte[] bytes) {
        try {
            List<DataItem> items = new CborDecoder(
                    new ByteArrayInputStream(bytes)).decode();
            if (items.size() != 1 || !(items.getFirst() instanceof Array array)
                    || array.getDataItems().size() != 6) {
                throw new IllegalArgumentException(
                        "settlement batch must be a 6-field CBOR array");
            }
            List<DataItem> f = array.getDataItems();
            return new EutxoSettlementBatch(
                    (int) unsigned(f.get(0)),
                    new String(((co.nstant.in.cbor.model.ByteString) f.get(1))
                            .getBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    unsigned(f.get(2)),
                    unsigned(f.get(3)),
                    unsigned(f.get(4)),
                    unsigned(f.get(5)));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("malformed settlement batch", failure);
        }
    }

    private static long unsigned(DataItem item) {
        if (!(item instanceof UnsignedInteger value)) {
            throw new IllegalArgumentException("expected an unsigned integer");
        }
        return value.getValue().longValueExact();
    }
}
