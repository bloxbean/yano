package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.Comparator;
import java.util.List;

/** Immutable actor revision, including the bounded key-epoch history visible at that revision. */
public record ActorRecordV1(String actorId, String organizationId, long revision,
                            RecordStatus status, List<String> roles,
                            List<ActorKeyEpochV1> keys, byte[] metadataCommitment) {
    public ActorRecordV1 {
        actorId = RoleWorkflowIdentifiers.id(actorId, "actorId");
        organizationId = RoleWorkflowIdentifiers.id(organizationId, "organizationId");
        if (revision < 1 || status == null || roles == null || roles.isEmpty()
                || roles.size() > RoleWorkflowLimits.MAX_ROLES_PER_ACTOR
                || keys == null || keys.isEmpty()
                || keys.size() > RoleWorkflowLimits.MAX_KEYS_PER_ACTOR) {
            throw OrganizationRecordV1.invalid();
        }
        roles = roles.stream().map(RoleWorkflowIdentifiers::role).sorted().toList();
        if (roles.stream().distinct().count() != roles.size()) throw OrganizationRecordV1.invalid();
        keys = keys.stream().sorted(Comparator.comparing(ActorKeyEpochV1::keyId)).toList();
        if (keys.stream().map(ActorKeyEpochV1::keyId).distinct().count() != keys.size()) {
            throw OrganizationRecordV1.invalid();
        }
        metadataCommitment = OrganizationRecordV1.optionalHash(metadataCommitment);
    }

    @Override public List<String> roles() { return List.copyOf(roles); }
    @Override public List<ActorKeyEpochV1> keys() { return List.copyOf(keys); }
    @Override public byte[] metadataCommitment() { return metadataCommitment.clone(); }

    public ActorKeyEpochV1 key(String keyId) {
        return keys.stream().filter(key -> key.keyId().equals(keyId)).findFirst().orElse(null);
    }

    public byte[] encode() {
        Array roleValues = new Array();
        roles.forEach(role -> roleValues.add(new UnicodeString(role)));
        Array keyValues = new Array();
        keys.forEach(key -> keyValues.add(key.toCbor()));
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(actorId));
        value.add(new UnicodeString(organizationId));
        value.add(new UnsignedInteger(revision));
        value.add(new UnsignedInteger(status.code()));
        value.add(roleValues);
        value.add(keyValues);
        value.add(new ByteString(metadataCommitment));
        return RoleWorkflowCbor.encode(value);
    }

    public static ActorRecordV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 8).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        Array roleValues = RoleWorkflowCbor.array(values.get(5),
                RoleWorkflowLimits.MAX_ROLES_PER_ACTOR);
        Array keyValues = RoleWorkflowCbor.array(values.get(6),
                RoleWorkflowLimits.MAX_KEYS_PER_ACTOR);
        ActorRecordV1 decoded = new ActorRecordV1(RoleWorkflowCbor.text(values.get(1)),
                RoleWorkflowCbor.text(values.get(2)), RoleWorkflowCbor.uint(values.get(3)),
                RecordStatus.fromCode(RoleWorkflowCbor.uintInt(values.get(4))),
                roleValues.getDataItems().stream().map(RoleWorkflowCbor::text).toList(),
                keyValues.getDataItems().stream().map(ActorKeyEpochV1::fromCbor).toList(),
                RoleWorkflowCbor.bytes(values.get(7)));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }
}
