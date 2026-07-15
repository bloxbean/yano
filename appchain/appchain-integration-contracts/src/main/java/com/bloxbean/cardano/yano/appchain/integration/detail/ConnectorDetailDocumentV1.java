package com.bloxbean.cardano.yano.appchain.integration.detail;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorLimits;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.util.Arrays;
import java.util.List;

/**
 * Stable off-chain detail envelope. Connector data must itself be one strict
 * canonical CBOR array containing only connector-allowlisted, non-secret,
 * non-volatile receipt fields.
 *
 * @param effectIdHash the 32-byte hash that binds this document to its effect
 * @param action the connector action represented by {@code connectorData}
 * @param connectorData the canonical connector-specific detail array; defensively copied
 */
public record ConnectorDetailDocumentV1(byte[] effectIdHash,
                                        ConnectorAction action,
                                        byte[] connectorData) {
    private static final int ENVELOPE_OVERHEAD_BUDGET = 128;

    /** Validates the action/data coupling and defensively snapshots byte arrays. */
    public ConnectorDetailDocumentV1 {
        effectIdHash = ContractValidation.hash32(effectIdHash);
        if (action == null) {
            throw CanonicalCbor.malformed();
        }
        if (connectorData == null || connectorData.length > ConnectorLimits.MAX_DETAIL_DOCUMENT_BYTES
                - ENVELOPE_OVERHEAD_BUDGET) {
            throw CanonicalCbor.malformed();
        }
        connectorData = connectorData.clone();
        validateData(action, connectorData);
    }

    /**
     * Returns a defensive copy of the bound effect-id hash.
     *
     * @return the 32-byte effect-id hash
     */
    @Override public byte[] effectIdHash() { return effectIdHash.clone(); }

    /**
     * Returns a defensive copy of the canonical connector detail array.
     *
     * @return the connector-specific canonical CBOR
     */
    @Override public byte[] connectorData() { return connectorData.clone(); }

    /**
     * Creates an envelope from typed connector detail data.
     *
     * @param effectIdHash the effect-id hash to bind
     * @param data the typed connector detail
     * @return the validated detail document
     */
    public static ConnectorDetailDocumentV1 of(byte[] effectIdHash, ConnectorDetailData data) {
        if (data == null) {
            throw CanonicalCbor.malformed();
        }
        return new ConnectorDetailDocumentV1(effectIdHash, data.action(), data.encode());
    }

    /**
     * Decodes the connector-specific data into its typed representation.
     *
     * @return the typed connector detail
     */
    public ConnectorDetailData data() {
        return decodeData(action, connectorData);
    }

    /**
     * Encodes this document as strict canonical CBOR.
     *
     * @return a new canonical encoding
     */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(1));
        root.add(new ByteString(effectIdHash));
        root.add(new UnsignedInteger(action.code()));
        root.add(CanonicalCbor.decodeArray(connectorData,
                ConnectorLimits.MAX_DETAIL_DOCUMENT_BYTES - ENVELOPE_OVERHEAD_BUDGET));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, ConnectorLimits.MAX_DETAIL_DOCUMENT_BYTES);
        return encoded;
    }

    /**
     * Decodes and validates a canonical detail document.
     *
     * @param bytes the bounded canonical encoding
     * @return the validated document
     */
    public static ConnectorDetailDocumentV1 decode(byte[] bytes) {
        byte[] input = CanonicalCbor.boundedSnapshot(
                bytes, ConnectorLimits.MAX_DETAIL_DOCUMENT_BYTES);
        Array root = CanonicalCbor.decodeArray(
                input, ConnectorLimits.MAX_DETAIL_DOCUMENT_BYTES, 4);
        List<DataItem> items = CanonicalCbor.items(root);
        CanonicalCbor.requireVersion(items.get(0));
        ConnectorDetailDocumentV1 document = new ConnectorDetailDocumentV1(
                CanonicalCbor.bytes(items.get(1)),
                ConnectorAction.fromCode(CanonicalCbor.uint(items.get(2))),
                CanonicalCbor.encode(CanonicalCbor.array(items.get(3))));
        if (!Arrays.equals(input, document.encode())) {
            throw CanonicalCbor.malformed();
        }
        return document;
    }

    private static void validateData(ConnectorAction action, byte[] bytes) {
        if (bytes == null || bytes.length > ConnectorLimits.MAX_DETAIL_DOCUMENT_BYTES
                - ENVELOPE_OVERHEAD_BUDGET) {
            throw CanonicalCbor.malformed();
        }
        ConnectorDetailData decoded = decodeData(action, bytes);
        if (!Arrays.equals(bytes, decoded.encode())) {
            throw CanonicalCbor.malformed();
        }
    }

    private static ConnectorDetailData decodeData(ConnectorAction action, byte[] bytes) {
        return switch (action) {
            case KAFKA_PUBLISH -> KafkaPublishDetailV1.decode(bytes);
            case OBJECT_PUT -> ObjectPutDetailV1.decode(bytes);
            case IPFS_PIN -> IpfsPinDetailV1.decode(bytes);
        };
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ConnectorDetailDocumentV1 document
                && action == document.action
                && Arrays.equals(effectIdHash, document.effectIdHash)
                && Arrays.equals(connectorData, document.connectorData);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(effectIdHash);
        result = 31 * result + action.hashCode();
        return 31 * result + Arrays.hashCode(connectorData);
    }
}
