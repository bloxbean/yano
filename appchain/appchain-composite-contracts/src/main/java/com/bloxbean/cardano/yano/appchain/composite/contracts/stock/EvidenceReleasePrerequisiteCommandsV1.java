package com.bloxbean.cardano.yano.appchain.composite.contracts.stock;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * No-SPI client encoders for the stdlib prerequisites consumed by the stock
 * evidence-release workflow. Compatibility is locked against the stdlib
 * state machines by the composite implementation test suite.
 */
public final class EvidenceReleasePrerequisiteCommandsV1 {
    public static final String REGISTRY_TOPIC = "registry.command.v1";
    public static final String APPROVALS_TOPIC = "approvals.command.v1";

    private static final int REGISTRY_PUT = 0;
    private static final int APPROVAL_PROPOSE = 0;
    private static final int APPROVAL_APPROVE = 1;
    private static final int MAX_REGISTRY_KEY_BYTES = 192;
    private static final int MAX_REGISTRY_VALUE_BYTES = 4_096;
    private static final int MAX_APPROVAL_PAYLOAD_BYTES = 8_192;
    private static final Pattern ITEM_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,126}");

    private EvidenceReleasePrerequisiteCommandsV1() {
    }

    public static byte[] registryPut(byte[] key, byte[] value) {
        byte[] safeKey = bounded(key, 1, MAX_REGISTRY_KEY_BYTES, "registry key");
        byte[] safeValue = bounded(value, 1, MAX_REGISTRY_VALUE_BYTES, "registry value");
        Array command = new Array();
        command.add(new UnsignedInteger(REGISTRY_PUT));
        command.add(new ByteString(safeKey));
        command.add(new ByteString(safeValue));
        return encode(command);
    }

    public static byte[] approvalPropose(
            String itemId,
            byte[] payload,
            int required,
            long deadlineMillis
    ) {
        String safeItemId = itemId(itemId);
        byte[] safePayload = bounded(payload, 1, MAX_APPROVAL_PAYLOAD_BYTES, "approval payload");
        if (required < 1 || deadlineMillis < 0) {
            throw new IllegalArgumentException("approval threshold and deadline are outside their bounds");
        }
        Array command = new Array();
        command.add(new UnsignedInteger(APPROVAL_PROPOSE));
        command.add(new UnicodeString(safeItemId));
        command.add(new ByteString(safePayload));
        command.add(new UnsignedInteger(required));
        command.add(new UnsignedInteger(deadlineMillis));
        return encode(command);
    }

    public static byte[] approvalApprove(String itemId) {
        Array command = new Array();
        command.add(new UnsignedInteger(APPROVAL_APPROVE));
        command.add(new UnicodeString(itemId(itemId)));
        return encode(command);
    }

    private static String itemId(String value) {
        Objects.requireNonNull(value, "itemId");
        if (!ITEM_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("approval item id is not a bounded identifier");
        }
        return value;
    }

    private static byte[] bounded(byte[] value, int minimum, int maximum, String field) {
        Objects.requireNonNull(value, field);
        if (value.length < minimum || value.length > maximum) {
            throw new IllegalArgumentException(field + " is outside its byte bound");
        }
        return value.clone();
    }

    private static byte[] encode(DataItem value) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new CborEncoder(output).encode(value);
            return output.toByteArray();
        } catch (Exception impossible) {
            throw new IllegalStateException("in-memory prerequisite command encoding failed", impossible);
        }
    }
}
