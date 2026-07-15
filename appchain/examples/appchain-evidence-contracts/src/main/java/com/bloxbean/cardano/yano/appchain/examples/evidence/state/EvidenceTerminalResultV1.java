package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorLimits;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Exact terminal tuple incorporated by the framework for one evidence effect. */
public record EvidenceTerminalResultV1(EvidenceTerminalOutcome outcome,
                                       byte[] externalRef,
                                       byte[] detailHash,
                                       long resultHeight) {
    private static final int MAX_ENCODED_BYTES = 192;

    /** Bounds the tuple without interpreting connector-specific receipt bytes. */
    public EvidenceTerminalResultV1 {
        outcome = Objects.requireNonNull(outcome, "outcome");
        externalRef = EvidenceValidation.boundedBytes(externalRef,
                ConnectorLimits.MAX_EXTERNAL_REF_BYTES);
        if (detailHash == null || detailHash.length == 0) {
            detailHash = null;
        } else {
            detailHash = EvidenceValidation.exactBytes(detailHash, EvidenceContract.HASH_BYTES);
        }
        if (resultHeight <= 0) {
            throw EvidenceValidation.invalid();
        }
    }

    @Override
    public byte[] externalRef() {
        return externalRef.clone();
    }

    @Override
    public byte[] detailHash() {
        return detailHash == null ? null : detailHash.clone();
    }

    /** Encodes the tuple as {@code [outcome, externalRef, detailHashOrEmpty, resultHeight]}. */
    public byte[] encode() {
        return CanonicalCbor.encode(toDataItem());
    }

    /** Decodes one strict canonical standalone tuple. */
    public static EvidenceTerminalResultV1 decode(byte[] encoded) {
        return fromDataItem(CanonicalCbor.decodeArray(encoded, MAX_ENCODED_BYTES));
    }

    Array toDataItem() {
        Array value = new Array();
        value.add(new UnsignedInteger(outcome.code()));
        value.add(new ByteString(externalRef));
        value.add(new ByteString(detailHash == null ? new byte[0] : detailHash));
        value.add(new UnsignedInteger(resultHeight));
        return value;
    }

    static EvidenceTerminalResultV1 fromDataItem(DataItem item) {
        Array value = CanonicalCbor.array(item, 4);
        List<DataItem> fields = CanonicalCbor.items(value);
        byte[] detailHash = CanonicalCbor.bytes(fields.get(2));
        try {
            return new EvidenceTerminalResultV1(
                    EvidenceTerminalOutcome.fromCode(
                            CanonicalCbor.uintInt(fields.get(0))),
                    CanonicalCbor.bytes(fields.get(1)),
                    detailHash.length == 0 ? null : detailHash,
                    CanonicalCbor.uint(fields.get(3)));
        } catch (RuntimeException exception) {
            throw EvidenceValidation.invalid();
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EvidenceTerminalResultV1 result
                && resultHeight == result.resultHeight
                && outcome == result.outcome
                && Arrays.equals(externalRef, result.externalRef)
                && Arrays.equals(detailHash, result.detailHash);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(outcome, resultHeight);
        result = 31 * result + Arrays.hashCode(externalRef);
        return 31 * result + Arrays.hashCode(detailHash);
    }
}
