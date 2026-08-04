package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Authenticated result for one decodable finalized governance or approval command. */
public record RoleCommandResultV1(
        int commandKind,
        String subjectId,
        RoleWorkflowResultCode resultCode,
        long appliedHeight,
        byte[] messageId,
        byte[] commandDigest
) {
    public static final int KIND_REGISTRY_GOVERNANCE = 0;
    public static final int KIND_POLICY_GOVERNANCE = 1;
    public static final int KIND_APPROVAL = 2;

    public RoleCommandResultV1 {
        subjectId = RoleWorkflowIdentifiers.id(subjectId, "subjectId");
        messageId = exact(messageId);
        commandDigest = exact(commandDigest);
        if (commandKind < KIND_REGISTRY_GOVERNANCE
                || commandKind > KIND_APPROVAL
                || resultCode == null || appliedHeight < 1) {
            throw OrganizationRecordV1.invalid();
        }
    }

    @Override public byte[] messageId() { return messageId.clone(); }
    @Override public byte[] commandDigest() { return commandDigest.clone(); }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnsignedInteger(commandKind));
        value.add(new UnicodeString(subjectId));
        value.add(new UnsignedInteger(resultCode.code()));
        value.add(new UnsignedInteger(appliedHeight));
        value.add(new ByteString(messageId));
        value.add(new ByteString(commandDigest));
        return RoleWorkflowCbor.encode(value);
    }

    public static RoleCommandResultV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 7).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        RoleCommandResultV1 decoded = new RoleCommandResultV1(
                RoleWorkflowCbor.uintInt(values.get(1)),
                RoleWorkflowCbor.text(values.get(2)),
                RoleWorkflowResultCode.fromCode(RoleWorkflowCbor.uintInt(values.get(3))),
                RoleWorkflowCbor.uint(values.get(4)),
                RoleWorkflowCbor.bytes(values.get(5), 32),
                RoleWorkflowCbor.bytes(values.get(6), 32));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    public static byte[] commandDigest(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    body == null ? new byte[0] : body);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] exact(byte[] value) {
        if (value == null || value.length != 32) throw OrganizationRecordV1.invalid();
        return value.clone();
    }
}
