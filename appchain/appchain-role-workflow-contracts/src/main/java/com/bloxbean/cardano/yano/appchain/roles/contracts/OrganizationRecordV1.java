package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.List;

/** Immutable organization revision stored under an authenticated revision key. */
public record OrganizationRecordV1(String organizationId, long revision,
                                   RecordStatus status, byte[] metadataCommitment) {
    public OrganizationRecordV1 {
        organizationId = RoleWorkflowIdentifiers.id(organizationId, "organizationId");
        if (revision < 1 || status == null) throw invalid();
        metadataCommitment = optionalHash(metadataCommitment);
    }

    @Override public byte[] metadataCommitment() { return metadataCommitment.clone(); }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(organizationId));
        value.add(new UnsignedInteger(revision));
        value.add(new UnsignedInteger(status.code()));
        value.add(new ByteString(metadataCommitment));
        return RoleWorkflowCbor.encode(value);
    }

    public static OrganizationRecordV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 5).getDataItems();
        requireVersion(values.get(0));
        return new OrganizationRecordV1(RoleWorkflowCbor.text(values.get(1)),
                RoleWorkflowCbor.uint(values.get(2)),
                RecordStatus.fromCode(RoleWorkflowCbor.uintInt(values.get(3))),
                RoleWorkflowCbor.bytes(values.get(4)));
    }

    static byte[] optionalHash(byte[] value) {
        byte[] safe = value != null ? value.clone() : new byte[0];
        if (safe.length != 0 && safe.length != 32) throw invalid();
        return safe;
    }

    static void requireVersion(co.nstant.in.cbor.model.DataItem value) {
        if (RoleWorkflowCbor.uint(value) != 1) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.UNSUPPORTED_VERSION);
        }
    }

    static RoleWorkflowException invalid() {
        return new RoleWorkflowException(RoleWorkflowResultCode.INVALID_PAYLOAD);
    }
}
