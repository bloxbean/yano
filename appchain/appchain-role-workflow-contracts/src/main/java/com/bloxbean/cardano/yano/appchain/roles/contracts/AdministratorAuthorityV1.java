package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Immutable administrator-actor threshold revision for governed application state. */
public record AdministratorAuthorityV1(
        String authorityId,
        long revision,
        List<String> administratorActorIds,
        int distinctActorThreshold,
        long maximumMutationLifetimeBlocks
) {
    public AdministratorAuthorityV1 {
        authorityId = RoleWorkflowIdentifiers.id(authorityId, "authorityId");
        if (revision < 1 || administratorActorIds == null
                || administratorActorIds.isEmpty()
                || administratorActorIds.size() > RoleWorkflowLimits.MAX_ADMINISTRATORS
                || maximumMutationLifetimeBlocks < 1
                || maximumMutationLifetimeBlocks
                > RoleWorkflowLimits.MAX_AUTHORIZATION_LIFETIME_BLOCKS) {
            throw OrganizationRecordV1.invalid();
        }
        administratorActorIds = administratorActorIds.stream()
                .map(actor -> RoleWorkflowIdentifiers.id(actor, "administratorActorId"))
                .sorted()
                .toList();
        if (administratorActorIds.stream().distinct().count()
                != administratorActorIds.size()
                || distinctActorThreshold < 1
                || distinctActorThreshold > administratorActorIds.size()) {
            throw OrganizationRecordV1.invalid();
        }
    }

    @Override
    public List<String> administratorActorIds() {
        return List.copyOf(administratorActorIds);
    }

    public byte[] encode() {
        Array actors = new Array();
        administratorActorIds.forEach(actor -> actors.add(new UnicodeString(actor)));
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(authorityId));
        value.add(new UnsignedInteger(revision));
        value.add(actors);
        value.add(new UnsignedInteger(distinctActorThreshold));
        value.add(new UnsignedInteger(maximumMutationLifetimeBlocks));
        return RoleWorkflowCbor.encode(value);
    }

    public byte[] digest() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(encode());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static AdministratorAuthorityV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 6).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        Array actors = RoleWorkflowCbor.array(
                values.get(3), RoleWorkflowLimits.MAX_ADMINISTRATORS);
        AdministratorAuthorityV1 decoded = new AdministratorAuthorityV1(
                RoleWorkflowCbor.text(values.get(1)),
                RoleWorkflowCbor.uint(values.get(2)),
                actors.getDataItems().stream().map(RoleWorkflowCbor::text).toList(),
                RoleWorkflowCbor.uintInt(values.get(4)),
                RoleWorkflowCbor.uint(values.get(5)));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }
}
