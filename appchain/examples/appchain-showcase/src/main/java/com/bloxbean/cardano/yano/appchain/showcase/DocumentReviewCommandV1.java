package com.bloxbean.cardano.yano.appchain.showcase;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalReferenceV1;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Canonical demo command binding one approved proposal to one document append. */
public record DocumentReviewCommandV1(
        String proposalId,
        String policyId,
        long policyRevision,
        String documentEntityId,
        byte[] documentHash,
        String documentRef
) implements ApprovalReferenceV1 {
    public static final String TOPIC = "document-review.release.v1";
    public static final String PAYLOAD_DOMAIN = "document.review.release.v1";

    public DocumentReviewCommandV1 {
        proposalId = identifier(proposalId, "proposalId");
        policyId = identifier(policyId, "policyId");
        documentEntityId = identifier(documentEntityId, "documentEntityId");
        documentRef = Objects.requireNonNull(documentRef, "documentRef");
        documentHash = Objects.requireNonNull(documentHash, "documentHash").clone();
        if (policyRevision < 1 || documentHash.length != 32
                || documentRef.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException("invalid document-review command");
        }
    }

    @Override public byte[] documentHash() { return documentHash.clone(); }

    @Override public byte[] actionCommitment() { return Blake2bUtil.blake2bHash256(encode()); }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(proposalId));
        value.add(new UnicodeString(policyId));
        value.add(new UnsignedInteger(policyRevision));
        value.add(new UnicodeString(documentEntityId));
        value.add(new ByteString(documentHash));
        value.add(new UnicodeString(documentRef));
        return CborSerializationUtil.serialize(value);
    }

    public static DocumentReviewCommandV1 decode(byte[] encoded) {
        DataItem item = CborSerializationUtil.deserializeOne(encoded);
        if (!(item instanceof Array array) || array.getDataItems().size() != 7) {
            throw new IllegalArgumentException("invalid document-review command");
        }
        List<DataItem> values = array.getDataItems();
        if (!(values.get(0) instanceof UnsignedInteger version)
                || version.getValue().intValueExact() != 1) {
            throw new IllegalArgumentException("unsupported document-review command version");
        }
        DocumentReviewCommandV1 command = new DocumentReviewCommandV1(
                text(values.get(1)), text(values.get(2)), uint(values.get(3)),
                text(values.get(4)), bytes(values.get(5)), text(values.get(6)));
        if (!Arrays.equals(encoded, command.encode())) {
            throw new IllegalArgumentException("document-review command is not canonical");
        }
        return command;
    }

    private static String identifier(String value, String field) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static String text(DataItem value) {
        if (!(value instanceof UnicodeString text)) throw new IllegalArgumentException("expected text");
        return text.getString();
    }

    private static long uint(DataItem value) {
        if (!(value instanceof UnsignedInteger number)) throw new IllegalArgumentException("expected uint");
        return number.getValue().longValueExact();
    }

    private static byte[] bytes(DataItem value) {
        if (!(value instanceof ByteString bytes)) throw new IllegalArgumentException("expected bytes");
        return bytes.getBytes();
    }
}
