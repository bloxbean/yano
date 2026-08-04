package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Immutable direct-role authorization policy revision used by authenticated-map. */
public record DirectRolePolicyV1(
        String policyId,
        long revision,
        RecordStatus status,
        String requiredRole,
        long maximumAuthorizationLifetimeBlocks
) {
    public DirectRolePolicyV1 {
        policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
        requiredRole = RoleWorkflowIdentifiers.role(requiredRole);
        if (revision < 1 || status == null
                || maximumAuthorizationLifetimeBlocks < 1
                || maximumAuthorizationLifetimeBlocks
                > RoleWorkflowLimits.MAX_AUTHORIZATION_LIFETIME_BLOCKS) {
            throw OrganizationRecordV1.invalid();
        }
    }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(policyId));
        value.add(new UnsignedInteger(revision));
        value.add(new UnsignedInteger(status.code()));
        value.add(new UnicodeString(requiredRole));
        value.add(new UnsignedInteger(maximumAuthorizationLifetimeBlocks));
        return RoleWorkflowCbor.encode(value);
    }

    public byte[] digest() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(encode());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static DirectRolePolicyV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 6).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        DirectRolePolicyV1 decoded = new DirectRolePolicyV1(
                RoleWorkflowCbor.text(values.get(1)),
                RoleWorkflowCbor.uint(values.get(2)),
                RecordStatus.fromCode(RoleWorkflowCbor.uintInt(values.get(3))),
                RoleWorkflowCbor.text(values.get(4)),
                RoleWorkflowCbor.uint(values.get(5)));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }
}
