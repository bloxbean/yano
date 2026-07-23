package com.bloxbean.cardano.yano.appchain.roles.internal;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

record GovernanceRecordV1(String mutationId, byte[] mutation, byte[] mutationHash,
                          long expiryHeight, byte[] proposer, List<byte[]> approvals,
                          Status status) {
    GovernanceRecordV1 {
        mutationId = RoleWorkflowIdentifiers.id(mutationId, "mutationId");
        if (mutation == null || mutation.length == 0
                || mutation.length > RoleWorkflowLimits.MAX_MUTATION_BYTES
                || mutationHash == null || mutationHash.length != 32
                || expiryHeight < 1 || proposer == null || proposer.length != 32
                || approvals == null
                || approvals.size() > RoleWorkflowLimits.MAX_ADMINISTRATORS
                || status == null) {
            throw new IllegalArgumentException("invalid governance record");
        }
        mutation = mutation.clone();
        mutationHash = mutationHash.clone();
        proposer = proposer.clone();
        approvals = approvals.stream().map(byte[]::clone)
                .sorted(Arrays::compareUnsigned).toList();
        if (!MessageDigest.isEqual(mutationHash, hash(mutation))
                || approvals.stream().anyMatch(key -> key.length != 32)
                || adjacentDuplicate(approvals)) {
            throw new IllegalArgumentException("invalid governance record");
        }
    }

    @Override public byte[] mutation() { return mutation.clone(); }
    @Override public byte[] mutationHash() { return mutationHash.clone(); }
    @Override public byte[] proposer() { return proposer.clone(); }
    @Override public List<byte[]> approvals() { return approvals.stream().map(byte[]::clone).toList(); }

    GovernanceRecordV1 withApproval(byte[] sender) {
        if (approvals.stream().anyMatch(key -> Arrays.equals(key, sender))) return this;
        java.util.ArrayList<byte[]> updated = new java.util.ArrayList<>(approvals);
        updated.add(sender.clone());
        return new GovernanceRecordV1(mutationId, mutation, mutationHash, expiryHeight,
                proposer, updated, status);
    }

    GovernanceRecordV1 withStatus(Status next) {
        return new GovernanceRecordV1(mutationId, mutation, mutationHash, expiryHeight,
                proposer, approvals, next);
    }

    private static boolean adjacentDuplicate(List<byte[]> values) {
        for (int index = 1; index < values.size(); index++) {
            if (Arrays.equals(values.get(index - 1), values.get(index))) return true;
        }
        return false;
    }

    private static byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    byte[] encode() {
        Array voters = new Array();
        approvals.forEach(key -> voters.add(new ByteString(key)));
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(mutationId));
        value.add(new ByteString(mutation));
        value.add(new ByteString(mutationHash));
        value.add(new UnsignedInteger(expiryHeight));
        value.add(new ByteString(proposer));
        value.add(voters);
        value.add(new UnsignedInteger(status.code));
        return RoleWorkflowCbor.encode(value);
    }

    static GovernanceRecordV1 decode(byte[] bytes) {
        try {
            List<co.nstant.in.cbor.model.DataItem> values =
                    RoleWorkflowCbor.decodeArray(bytes, 8).getDataItems();
            if (RoleWorkflowCbor.uint(values.get(0)) != 1) throw RoleWorkflowCbor.malformed();
            Array voters = RoleWorkflowCbor.array(
                    values.get(6), RoleWorkflowLimits.MAX_ADMINISTRATORS);
            GovernanceRecordV1 decoded = new GovernanceRecordV1(
                    RoleWorkflowCbor.text(values.get(1)),
                    RoleWorkflowCbor.bytes(values.get(2)),
                    RoleWorkflowCbor.bytes(values.get(3), 32),
                    RoleWorkflowCbor.uint(values.get(4)),
                    RoleWorkflowCbor.bytes(values.get(5), 32),
                    voters.getDataItems().stream()
                            .map(item -> RoleWorkflowCbor.bytes(item, 32)).toList(),
                    Status.fromCode(RoleWorkflowCbor.uintInt(values.get(7))));
            RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
            return decoded;
        } catch (RuntimeException corrupt) {
            throw new IllegalStateException("corrupt role-workflow governance record", corrupt);
        }
    }

    enum Status {
        PENDING(0), ACTIVATED(1), CANCELLED(2), FAILED(3), EXPIRED(4);
        private final int code;
        Status(int code) { this.code = code; }
        static Status fromCode(int code) {
            for (Status value : values()) if (value.code == code) return value;
            throw RoleWorkflowCbor.malformed();
        }
    }
}
