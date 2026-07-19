package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.Comparator;
import java.util.List;

/** Full-revision registry mutations governed by {@link GovernedMutationCommandV1}. */
public sealed interface RegistryMutationV1 permits RegistryMutationV1.PutOrganization,
        RegistryMutationV1.PutActor {
    int OP_ORGANIZATION = 0;
    int OP_ACTOR = 1;

    byte[] encode();

    record PutOrganization(OrganizationRecordV1 organization) implements RegistryMutationV1 {
        public PutOrganization {
            if (organization == null) throw OrganizationRecordV1.invalid();
        }
        @Override public byte[] encode() {
            return encodeMutation(OP_ORGANIZATION, organization.encode(), List.of());
        }
    }

    record PutActor(ActorRecordV1 actor, List<ActorKeyProofV1> keyProofs)
            implements RegistryMutationV1 {
        public PutActor {
            if (actor == null || keyProofs == null
                    || keyProofs.size() > RoleWorkflowLimits.MAX_KEYS_PER_ACTOR) {
                throw OrganizationRecordV1.invalid();
            }
            keyProofs = keyProofs.stream().sorted(Comparator.comparing(
                    proof -> proof.key().keyId())).toList();
            if (keyProofs.stream().map(proof -> proof.key().keyId()).distinct().count()
                    != keyProofs.size()) throw OrganizationRecordV1.invalid();
        }
        @Override public List<ActorKeyProofV1> keyProofs() { return List.copyOf(keyProofs); }
        @Override public byte[] encode() {
            return encodeMutation(OP_ACTOR, actor.encode(), keyProofs);
        }
    }

    static RegistryMutationV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 4).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        int operation = RoleWorkflowCbor.uintInt(values.get(1));
        byte[] record = RoleWorkflowCbor.bytes(values.get(2));
        Array proofs = RoleWorkflowCbor.array(values.get(3), RoleWorkflowLimits.MAX_KEYS_PER_ACTOR);
        RegistryMutationV1 decoded = switch (operation) {
            case OP_ORGANIZATION -> {
                if (!proofs.getDataItems().isEmpty()) throw OrganizationRecordV1.invalid();
                yield new PutOrganization(OrganizationRecordV1.decode(record));
            }
            case OP_ACTOR -> new PutActor(ActorRecordV1.decode(record),
                    proofs.getDataItems().stream().map(RoleWorkflowCbor::bytes)
                            .map(ActorKeyProofV1::decode).toList());
            default -> throw OrganizationRecordV1.invalid();
        };
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    private static byte[] encodeMutation(int operation, byte[] record,
                                         List<ActorKeyProofV1> proofs) {
        Array proofValues = new Array();
        proofs.forEach(proof -> proofValues.add(new ByteString(proof.encode())));
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnsignedInteger(operation));
        value.add(new ByteString(record));
        value.add(proofValues);
        byte[] encoded = RoleWorkflowCbor.encode(value);
        if (encoded.length > RoleWorkflowLimits.MAX_MUTATION_BYTES) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.LIMIT_EXCEEDED);
        }
        return encoded;
    }
}
