package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowEd25519;

import java.util.List;

/** Client-facing actor-signed command transported inside a member-authenticated message. */
public record SignedActorCommandV1(ActorStatementV1 statement, byte[] signature) {
    public SignedActorCommandV1 {
        if (statement == null || signature == null || signature.length != 64) {
            throw OrganizationRecordV1.invalid();
        }
        signature = signature.clone();
    }

    @Override public byte[] signature() { return signature.clone(); }

    public static SignedActorCommandV1 sign(ActorStatementV1 statement, byte[] privateSeed) {
        return new SignedActorCommandV1(statement,
                RoleWorkflowEd25519.sign(statement.signingPreimage(), privateSeed));
    }

    public boolean verify(byte[] publicKey) {
        return RoleWorkflowEd25519.verify(
                signature, statement.signingPreimage(), publicKey);
    }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new ByteString(statement.encode()));
        value.add(new ByteString(signature));
        return RoleWorkflowCbor.encode(value);
    }

    public static SignedActorCommandV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 3).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        return new SignedActorCommandV1(
                ActorStatementV1.decode(RoleWorkflowCbor.bytes(values.get(1))),
                RoleWorkflowCbor.bytes(values.get(2), 64));
    }
}
