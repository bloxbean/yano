package com.bloxbean.cardano.yano.appchain.integration.ipfs;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.util.Arrays;
import java.util.List;

/**
 * Canonical pin-only v1 command. Document bytes and provider URLs are never accepted.
 *
 * @param target the configured target alias
 * @param cid the canonical CID to pin
 * @param recursive whether to pin the complete reachable DAG
 * @param replicationPolicy an optional configured replication-policy alias
 */
public record IpfsPinCommandV1(String target,
                               CanonicalCid cid,
                               boolean recursive,
                               String replicationPolicy) {
    /** Maximum canonical command size in bytes. */
    public static final int MAX_ENCODED_BYTES = 256;

    /** Validates aliases and applies the frozen v1 CID policy. */
    public IpfsPinCommandV1 {
        target = ContractValidation.alias(target, "target");
        cid = IpfsV1Policy.requireAllowed(cid);
        replicationPolicy = ContractValidation.optionalAlias(replicationPolicy, "replicationPolicy");
    }

    /**
     * Encodes this command as strict canonical CBOR.
     *
     * @return a new canonical encoding
     */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(1));
        root.add(new UnicodeString(target));
        root.add(new ByteString(cid.bytes()));
        root.add(CanonicalCbor.boolValue(recursive));
        root.add(CanonicalCbor.nullable(replicationPolicy));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, MAX_ENCODED_BYTES);
        return encoded;
    }

    /**
     * Decodes and validates a canonical pin command.
     *
     * @param bytes the bounded canonical encoding
     * @return the validated command
     */
    public static IpfsPinCommandV1 decode(byte[] bytes) {
        byte[] input = CanonicalCbor.boundedSnapshot(bytes, MAX_ENCODED_BYTES);
        Array root = CanonicalCbor.decodeArray(input, MAX_ENCODED_BYTES, 5);
        List<DataItem> items = CanonicalCbor.items(root);
        CanonicalCbor.requireVersion(items.get(0));
        IpfsPinCommandV1 command = new IpfsPinCommandV1(
                CanonicalCbor.text(items.get(1)),
                CanonicalCid.fromBytes(CanonicalCbor.bytes(items.get(2))),
                CanonicalCbor.bool(items.get(3)),
                CanonicalCbor.nullableText(items.get(4)));
        if (!Arrays.equals(input, command.encode())) {
            throw CanonicalCbor.malformed();
        }
        return command;
    }
}
