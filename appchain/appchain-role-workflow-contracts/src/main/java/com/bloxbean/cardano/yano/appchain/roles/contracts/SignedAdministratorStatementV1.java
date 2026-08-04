package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowEd25519;

import java.util.List;

/** An administrator statement and its claimed-key Ed25519 signature. */
public record SignedAdministratorStatementV1(
        AdministratorStatementV1 statement,
        byte[] signature
) {
    public SignedAdministratorStatementV1 {
        if (statement == null || signature == null || signature.length != 64) {
            throw OrganizationRecordV1.invalid();
        }
        signature = signature.clone();
    }

    @Override public byte[] signature() { return signature.clone(); }

    public static SignedAdministratorStatementV1 sign(
            AdministratorStatementV1 statement,
            byte[] privateSeed
    ) {
        return new SignedAdministratorStatementV1(statement,
                RoleWorkflowEd25519.sign(statement.signingPreimage(), privateSeed));
    }

    /** Stateless verification; apply must also bind the claimed key to current actor state. */
    public boolean verifyClaimedKey() {
        return RoleWorkflowEd25519.verify(
                signature, statement.signingPreimage(), statement.publicKey());
    }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new ByteString(statement.encode()));
        value.add(new ByteString(signature));
        return RoleWorkflowCbor.encode(value);
    }

    public static SignedAdministratorStatementV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 3).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        SignedAdministratorStatementV1 decoded = new SignedAdministratorStatementV1(
                AdministratorStatementV1.decode(RoleWorkflowCbor.bytes(values.get(1))),
                RoleWorkflowCbor.bytes(values.get(2), 64));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }
}
