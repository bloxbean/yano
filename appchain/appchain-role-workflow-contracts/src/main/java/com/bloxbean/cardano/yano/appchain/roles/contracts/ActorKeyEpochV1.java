package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.List;

/** One retained Ed25519 actor-key epoch. */
public record ActorKeyEpochV1(String keyId, byte[] publicKey,
                              long validFromHeight, long validUntilHeight,
                              RecordStatus status) {
    public ActorKeyEpochV1 {
        keyId = RoleWorkflowIdentifiers.id(keyId, "keyId");
        publicKey = publicKey != null ? publicKey.clone() : null;
        if (publicKey == null || publicKey.length != 32 || validFromHeight < 1
                || (validUntilHeight != 0 && validUntilHeight < validFromHeight)
                || status == null) throw OrganizationRecordV1.invalid();
    }

    @Override public byte[] publicKey() { return publicKey.clone(); }

    public boolean activeAt(long height) {
        return status == RecordStatus.ACTIVE && height >= validFromHeight
                && (validUntilHeight == 0 || height <= validUntilHeight);
    }

    Array toCbor() {
        Array value = new Array();
        value.add(new UnicodeString(keyId));
        value.add(new ByteString(publicKey));
        value.add(new UnsignedInteger(validFromHeight));
        value.add(new UnsignedInteger(validUntilHeight));
        value.add(new UnsignedInteger(status.code()));
        return value;
    }

    static ActorKeyEpochV1 fromCbor(co.nstant.in.cbor.model.DataItem value) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.array(value, 5).getDataItems();
        if (values.size() != 5) throw OrganizationRecordV1.invalid();
        return new ActorKeyEpochV1(RoleWorkflowCbor.text(values.get(0)),
                RoleWorkflowCbor.bytes(values.get(1), 32),
                RoleWorkflowCbor.uint(values.get(2)), RoleWorkflowCbor.uint(values.get(3)),
                RecordStatus.fromCode(RoleWorkflowCbor.uintInt(values.get(4))));
    }
}
