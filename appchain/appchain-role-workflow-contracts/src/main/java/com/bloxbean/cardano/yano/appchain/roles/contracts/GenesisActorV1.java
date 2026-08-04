package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** One canonical genesis actor revision and proof of possession for every key. */
public record GenesisActorV1(ActorRecordV1 actor, List<ActorKeyProofV1> keyProofs) {
    public GenesisActorV1 {
        if (actor == null || keyProofs == null
                || keyProofs.size() != actor.keys().size()) {
            throw OrganizationRecordV1.invalid();
        }
        keyProofs = keyProofs.stream()
                .sorted(Comparator.comparing(proof -> proof.key().keyId()))
                .toList();
        if (keyProofs.stream().map(proof -> proof.key().keyId()).distinct().count()
                != keyProofs.size()) {
            throw OrganizationRecordV1.invalid();
        }
        for (int index = 0; index < actor.keys().size(); index++) {
            ActorKeyEpochV1 key = actor.keys().get(index);
            ActorKeyProofV1 proof = keyProofs.get(index);
            if (!proof.actorId().equals(actor.actorId())
                    || proof.actorRevision() != actor.revision()
                    || !sameKey(key, proof.key())
                    || !proof.verify()) {
                throw new RoleWorkflowException(RoleWorkflowResultCode.GOVERNANCE_PROOF_INVALID);
            }
        }
    }

    @Override
    public List<ActorKeyProofV1> keyProofs() {
        return List.copyOf(keyProofs);
    }

    public byte[] encode() {
        Array proofs = new Array();
        keyProofs.forEach(proof -> proofs.add(new ByteString(proof.encode())));
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new ByteString(actor.encode()));
        value.add(proofs);
        return RoleWorkflowCbor.encode(value);
    }

    public static GenesisActorV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 3).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        Array proofs = RoleWorkflowCbor.array(
                values.get(2), RoleWorkflowLimits.MAX_KEYS_PER_ACTOR);
        GenesisActorV1 decoded = new GenesisActorV1(
                ActorRecordV1.decode(RoleWorkflowCbor.bytes(values.get(1))),
                proofs.getDataItems().stream()
                        .map(RoleWorkflowCbor::bytes)
                        .map(ActorKeyProofV1::decode)
                        .toList());
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    private static boolean sameKey(ActorKeyEpochV1 left, ActorKeyEpochV1 right) {
        return left.keyId().equals(right.keyId())
                && Arrays.equals(left.publicKey(), right.publicKey())
                && left.validFromHeight() == right.validFromHeight()
                && left.validUntilHeight() == right.validUntilHeight()
                && left.status() == right.status();
    }
}
