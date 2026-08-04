package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.List;

/** Retained administrator authorization plus eligibility facts accepted at one height. */
public record AcceptedAdministratorVoteV1(
        SignedAdministratorStatementV1 authorization,
        String organizationId,
        long organizationRevision,
        long acceptedHeight
) {
    public AcceptedAdministratorVoteV1 {
        if (authorization == null || organizationRevision < 1 || acceptedHeight < 1) {
            throw OrganizationRecordV1.invalid();
        }
        organizationId = RoleWorkflowIdentifiers.id(organizationId, "organizationId");
    }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new ByteString(authorization.encode()));
        value.add(new UnicodeString(organizationId));
        value.add(new UnsignedInteger(organizationRevision));
        value.add(new UnsignedInteger(acceptedHeight));
        return RoleWorkflowCbor.encode(value);
    }

    public static AcceptedAdministratorVoteV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 5).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        AcceptedAdministratorVoteV1 decoded = new AcceptedAdministratorVoteV1(
                SignedAdministratorStatementV1.decode(
                        RoleWorkflowCbor.bytes(values.get(1))),
                RoleWorkflowCbor.text(values.get(2)),
                RoleWorkflowCbor.uint(values.get(3)),
                RoleWorkflowCbor.uint(values.get(4)));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }
}
