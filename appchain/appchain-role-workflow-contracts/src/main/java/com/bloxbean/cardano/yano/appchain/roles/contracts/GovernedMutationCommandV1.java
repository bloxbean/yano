package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Member-threshold propose/approve/activate command shared by registry and policy governance. */
public sealed interface GovernedMutationCommandV1 permits GovernedMutationCommandV1.Propose,
        GovernedMutationCommandV1.Approve, GovernedMutationCommandV1.Activate,
        GovernedMutationCommandV1.Cancel {
    int OP_PROPOSE = 0;
    int OP_APPROVE = 1;
    int OP_ACTIVATE = 2;
    int OP_CANCEL = 3;

    String mutationId();
    byte[] encode();

    record Propose(String mutationId, byte[] mutation, long expiryHeight)
            implements GovernedMutationCommandV1 {
        public Propose {
            mutationId = RoleWorkflowIdentifiers.id(mutationId, "mutationId");
            mutation = mutation != null ? mutation.clone() : null;
            if (mutation == null || mutation.length == 0
                    || mutation.length > RoleWorkflowLimits.MAX_MUTATION_BYTES
                    || expiryHeight < 1) throw OrganizationRecordV1.invalid();
        }
        @Override public byte[] mutation() { return mutation.clone(); }
        public byte[] mutationHash() { return hash(mutation); }
        @Override public byte[] encode() {
            return encoded(OP_PROPOSE, mutationId, mutation, expiryHeight);
        }
    }

    record Approve(String mutationId, byte[] mutationHash) implements GovernedMutationCommandV1 {
        public Approve {
            mutationId = RoleWorkflowIdentifiers.id(mutationId, "mutationId");
            mutationHash = digest(mutationHash);
        }
        @Override public byte[] mutationHash() { return mutationHash.clone(); }
        @Override public byte[] encode() { return encoded(OP_APPROVE, mutationId, mutationHash, 0); }
    }

    record Activate(String mutationId, byte[] mutationHash) implements GovernedMutationCommandV1 {
        public Activate {
            mutationId = RoleWorkflowIdentifiers.id(mutationId, "mutationId");
            mutationHash = digest(mutationHash);
        }
        @Override public byte[] mutationHash() { return mutationHash.clone(); }
        @Override public byte[] encode() { return encoded(OP_ACTIVATE, mutationId, mutationHash, 0); }
    }

    record Cancel(String mutationId, byte[] mutationHash) implements GovernedMutationCommandV1 {
        public Cancel {
            mutationId = RoleWorkflowIdentifiers.id(mutationId, "mutationId");
            mutationHash = digest(mutationHash);
        }
        @Override public byte[] mutationHash() { return mutationHash.clone(); }
        @Override public byte[] encode() { return encoded(OP_CANCEL, mutationId, mutationHash, 0); }
    }

    static GovernedMutationCommandV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 5).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        int operation = RoleWorkflowCbor.uintInt(values.get(1));
        String id = RoleWorkflowCbor.text(values.get(2));
        byte[] payload = RoleWorkflowCbor.bytes(values.get(3));
        long height = RoleWorkflowCbor.uint(values.get(4));
        return switch (operation) {
            case OP_PROPOSE -> new Propose(id, payload, height);
            case OP_APPROVE -> {
                if (height != 0) throw OrganizationRecordV1.invalid();
                yield new Approve(id, payload);
            }
            case OP_ACTIVATE -> {
                if (height != 0) throw OrganizationRecordV1.invalid();
                yield new Activate(id, payload);
            }
            case OP_CANCEL -> {
                if (height != 0) throw OrganizationRecordV1.invalid();
                yield new Cancel(id, payload);
            }
            default -> throw OrganizationRecordV1.invalid();
        };
    }

    static byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] encoded(int operation, String id, byte[] payload, long height) {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnsignedInteger(operation));
        value.add(new UnicodeString(id));
        value.add(new ByteString(payload));
        value.add(new UnsignedInteger(height));
        byte[] encoded = RoleWorkflowCbor.encode(value);
        if (encoded.length > RoleWorkflowLimits.MAX_COMMAND_BYTES) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.LIMIT_EXCEEDED);
        }
        return encoded;
    }

    private static byte[] digest(byte[] value) {
        if (value == null || value.length != 32) throw OrganizationRecordV1.invalid();
        return value.clone();
    }
}
