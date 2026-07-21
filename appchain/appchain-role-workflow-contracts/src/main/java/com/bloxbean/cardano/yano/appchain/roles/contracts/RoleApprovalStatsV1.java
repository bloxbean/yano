package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.List;

/** Authenticated aggregate proposal counts for bounded operational queries. */
public record RoleApprovalStatsV1(long created, long pending, long approved,
                                  long rejected, long cancelled, long expired) {
    public RoleApprovalStatsV1 {
        if (created < 0 || pending < 0 || approved < 0 || rejected < 0
                || cancelled < 0 || expired < 0
                || created != add(pending, approved, rejected, cancelled, expired)) {
            throw OrganizationRecordV1.invalid();
        }
    }

    public static RoleApprovalStatsV1 empty() {
        return new RoleApprovalStatsV1(0, 0, 0, 0, 0, 0);
    }

    public RoleApprovalStatsV1 proposalCreated() {
        return new RoleApprovalStatsV1(increment(created), increment(pending),
                approved, rejected, cancelled, expired);
    }

    public RoleApprovalStatsV1 terminal(ApprovalProposalV1.ProposalStatus status) {
        if (pending == 0 || status == null || status == ApprovalProposalV1.ProposalStatus.PENDING) {
            throw OrganizationRecordV1.invalid();
        }
        return switch (status) {
            case APPROVED -> new RoleApprovalStatsV1(created, pending - 1,
                    increment(approved), rejected, cancelled, expired);
            case REJECTED -> new RoleApprovalStatsV1(created, pending - 1,
                    approved, increment(rejected), cancelled, expired);
            case CANCELLED -> new RoleApprovalStatsV1(created, pending - 1,
                    approved, rejected, increment(cancelled), expired);
            case EXPIRED -> new RoleApprovalStatsV1(created, pending - 1,
                    approved, rejected, cancelled, increment(expired));
            case PENDING -> throw OrganizationRecordV1.invalid();
        };
    }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnsignedInteger(created));
        value.add(new UnsignedInteger(pending));
        value.add(new UnsignedInteger(approved));
        value.add(new UnsignedInteger(rejected));
        value.add(new UnsignedInteger(cancelled));
        value.add(new UnsignedInteger(expired));
        return RoleWorkflowCbor.encode(value);
    }

    public static RoleApprovalStatsV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 7).getDataItems();
        OrganizationRecordV1.requireVersion(values.getFirst());
        return new RoleApprovalStatsV1(RoleWorkflowCbor.uint(values.get(1)),
                RoleWorkflowCbor.uint(values.get(2)), RoleWorkflowCbor.uint(values.get(3)),
                RoleWorkflowCbor.uint(values.get(4)), RoleWorkflowCbor.uint(values.get(5)),
                RoleWorkflowCbor.uint(values.get(6)));
    }

    private static long increment(long value) {
        try {
            return Math.addExact(value, 1);
        } catch (ArithmeticException exhausted) {
            throw OrganizationRecordV1.invalid();
        }
    }

    private static long add(long... values) {
        long result = 0;
        try {
            for (long value : values) result = Math.addExact(result, value);
            return result;
        } catch (ArithmeticException exhausted) {
            throw OrganizationRecordV1.invalid();
        }
    }
}
