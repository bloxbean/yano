package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.List;

/** Governed policy change or threshold-admin emergency cancellation. */
public sealed interface PolicyMutationV1 permits PolicyMutationV1.PutPolicy,
        PolicyMutationV1.CancelProposal, PolicyMutationV1.PutDirectPolicy {
    int OP_POLICY = 0;
    int OP_CANCEL_PROPOSAL = 1;
    int OP_DIRECT_POLICY = 2;

    byte[] encode();

    record PutPolicy(ApprovalPolicyV1 policy) implements PolicyMutationV1 {
        public PutPolicy {
            if (policy == null) throw OrganizationRecordV1.invalid();
        }
        @Override public byte[] encode() {
            return encodeValue(OP_POLICY, policy.encode());
        }
    }

    record CancelProposal(String proposalId) implements PolicyMutationV1 {
        public CancelProposal {
            proposalId = RoleWorkflowIdentifiers.id(proposalId, "proposalId");
        }
        @Override public byte[] encode() {
            return encodeValue(OP_CANCEL_PROPOSAL,
                    proposalId.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }

    record PutDirectPolicy(DirectRolePolicyV1 policy) implements PolicyMutationV1 {
        public PutDirectPolicy {
            if (policy == null) throw OrganizationRecordV1.invalid();
        }
        @Override public byte[] encode() {
            return encodeValue(OP_DIRECT_POLICY, policy.encode());
        }
    }

    static PolicyMutationV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 3).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        int operation = RoleWorkflowCbor.uintInt(values.get(1));
        byte[] payload = RoleWorkflowCbor.bytes(values.get(2));
        PolicyMutationV1 decoded = switch (operation) {
            case OP_POLICY -> new PutPolicy(ApprovalPolicyV1.decode(payload));
            case OP_CANCEL_PROPOSAL -> new CancelProposal(
                    new String(payload, java.nio.charset.StandardCharsets.US_ASCII));
            case OP_DIRECT_POLICY -> new PutDirectPolicy(
                    DirectRolePolicyV1.decode(payload));
            default -> throw OrganizationRecordV1.invalid();
        };
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    private static byte[] encodeValue(int operation, byte[] payload) {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnsignedInteger(operation));
        value.add(new ByteString(payload));
        return RoleWorkflowCbor.encode(value);
    }
}
