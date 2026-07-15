package com.bloxbean.cardano.yano.appchain.integration.objectstore;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Canonical v1 command for immutable promotion from a configured staging location.
 *
 * @param target the configured object-store target alias
 * @param sourceKey the target-relative immutable staging-object key
 * @param destinationKey the target-relative destination-object key
 * @param digestAlgorithm the algorithm used by {@code digest}
 * @param digest the expected content digest; defensively copied
 * @param size the expected object size in bytes
 * @param contentType the object media type
 * @param retentionClass an optional configured retention-class alias
 */
public record ObjectPutCommandV1(String target,
                                 String sourceKey,
                                 String destinationKey,
                                 DigestAlgorithm digestAlgorithm,
                                 byte[] digest,
                                 long size,
                                 String contentType,
                                 String retentionClass) {
    /** Maximum canonical command size in bytes. */
    public static final int MAX_ENCODED_BYTES = 2_048;
    /** Maximum object size supported by the v1 command. */
    public static final long MAX_OBJECT_BYTES = 16_777_216L;

    /** Validates aliases, keys, digest, size, and media type. */
    public ObjectPutCommandV1 {
        target = ContractValidation.alias(target, "target");
        sourceKey = ContractValidation.objectKey(sourceKey);
        destinationKey = ContractValidation.objectKey(destinationKey);
        if (digestAlgorithm == null) {
            throw CanonicalCbor.malformed();
        }
        digest = ContractValidation.bytes(digest, digestAlgorithm.digestBytes(),
                digestAlgorithm.digestBytes());
        size = ContractValidation.bounded(size, 0, MAX_OBJECT_BYTES);
        contentType = ContractValidation.contentType(contentType);
        retentionClass = ContractValidation.optionalAlias(retentionClass, "retentionClass");
    }

    /**
     * Returns a defensive copy of the expected content digest.
     *
     * @return the digest bytes
     */
    @Override
    public byte[] digest() {
        return digest.clone();
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
        root.add(new UnicodeString(sourceKey));
        root.add(new UnicodeString(destinationKey));
        root.add(new UnsignedInteger(digestAlgorithm.code()));
        root.add(new ByteString(digest));
        root.add(new UnsignedInteger(size));
        root.add(new UnicodeString(contentType));
        root.add(CanonicalCbor.nullable(retentionClass));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, MAX_ENCODED_BYTES);
        return encoded;
    }

    /**
     * Decodes and validates a canonical object promotion command.
     *
     * @param bytes the bounded canonical encoding
     * @return the validated command
     */
    public static ObjectPutCommandV1 decode(byte[] bytes) {
        byte[] input = CanonicalCbor.boundedSnapshot(bytes, MAX_ENCODED_BYTES);
        Array root = CanonicalCbor.decodeArray(input, MAX_ENCODED_BYTES, 9);
        List<DataItem> items = CanonicalCbor.items(root);
        CanonicalCbor.requireVersion(items.get(0));
        ObjectPutCommandV1 command = new ObjectPutCommandV1(
                CanonicalCbor.text(items.get(1)),
                CanonicalCbor.text(items.get(2)),
                CanonicalCbor.text(items.get(3)),
                DigestAlgorithm.fromCode(CanonicalCbor.uint(items.get(4))),
                CanonicalCbor.bytes(items.get(5)),
                CanonicalCbor.uint(items.get(6)),
                CanonicalCbor.text(items.get(7)),
                CanonicalCbor.nullableText(items.get(8)));
        if (!Arrays.equals(input, command.encode())) {
            throw CanonicalCbor.malformed();
        }
        return command;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ObjectPutCommandV1 command
                && size == command.size
                && target.equals(command.target)
                && sourceKey.equals(command.sourceKey)
                && destinationKey.equals(command.destinationKey)
                && digestAlgorithm == command.digestAlgorithm
                && Arrays.equals(digest, command.digest)
                && contentType.equals(command.contentType)
                && Objects.equals(retentionClass, command.retentionClass);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(target, sourceKey, destinationKey, digestAlgorithm,
                size, contentType, retentionClass);
        return 31 * result + Arrays.hashCode(digest);
    }
}
