package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Exact actor authorization intent covered by an Ed25519 signature. */
public record ActorStatementV1(Action action, String chainId, String proposalId,
                               String policyId, long policyRevision,
                               String payloadDomain, byte[] payloadHash,
                               long deadlineHeight, String actorId,
                               long actorRevision, String keyId, String clauseId) {
    private static final byte[] DOMAIN =
            "yano:role-approval:v1\0".getBytes(StandardCharsets.US_ASCII);

    public ActorStatementV1 {
        if (action == null) throw OrganizationRecordV1.invalid();
        chainId = RoleWorkflowIdentifiers.id(chainId, "chainId");
        proposalId = RoleWorkflowIdentifiers.id(proposalId, "proposalId");
        policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
        payloadDomain = RoleWorkflowIdentifiers.payloadDomain(payloadDomain);
        actorId = RoleWorkflowIdentifiers.id(actorId, "actorId");
        keyId = RoleWorkflowIdentifiers.id(keyId, "keyId");
        payloadHash = payloadHash != null ? payloadHash.clone() : null;
        clauseId = clauseId != null ? clauseId : "";
        if (policyRevision < 1 || actorRevision < 1 || deadlineHeight < 1
                || payloadHash == null || payloadHash.length != 32
                || (action.requiresClause() && clauseId.isEmpty())
                || (!action.requiresClause() && !clauseId.isEmpty())) {
            throw OrganizationRecordV1.invalid();
        }
        if (!clauseId.isEmpty()) RoleWorkflowIdentifiers.id(clauseId, "clauseId");
    }

    @Override public byte[] payloadHash() { return payloadHash.clone(); }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnsignedInteger(action.code));
        value.add(new UnicodeString(chainId));
        value.add(new UnicodeString(proposalId));
        value.add(new UnicodeString(policyId));
        value.add(new UnsignedInteger(policyRevision));
        value.add(new UnicodeString(payloadDomain));
        value.add(new ByteString(payloadHash));
        value.add(new UnsignedInteger(deadlineHeight));
        value.add(new UnicodeString(actorId));
        value.add(new UnsignedInteger(actorRevision));
        value.add(new UnicodeString(keyId));
        value.add(new UnicodeString(clauseId));
        return RoleWorkflowCbor.encode(value);
    }

    public byte[] signingPreimage() {
        byte[] statement = encode();
        return ByteBuffer.allocate(DOMAIN.length + Integer.BYTES + statement.length)
                .put(DOMAIN).putInt(statement.length).put(statement).array();
    }

    public byte[] digest() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(encode());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static ActorStatementV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 13).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        return new ActorStatementV1(Action.fromCode(RoleWorkflowCbor.uintInt(values.get(1))),
                RoleWorkflowCbor.text(values.get(2)), RoleWorkflowCbor.text(values.get(3)),
                RoleWorkflowCbor.text(values.get(4)), RoleWorkflowCbor.uint(values.get(5)),
                RoleWorkflowCbor.text(values.get(6)), RoleWorkflowCbor.bytes(values.get(7), 32),
                RoleWorkflowCbor.uint(values.get(8)), RoleWorkflowCbor.text(values.get(9)),
                RoleWorkflowCbor.uint(values.get(10)), RoleWorkflowCbor.text(values.get(11)),
                RoleWorkflowCbor.text(values.get(12)));
    }

    public enum Action {
        PROPOSE(0, false), APPROVE(1, true), REJECT(2, true), CANCEL(3, false);
        private final int code;
        private final boolean requiresClause;
        Action(int code, boolean requiresClause) {
            this.code = code;
            this.requiresClause = requiresClause;
        }
        public int code() { return code; }
        public boolean requiresClause() { return requiresClause; }
        static Action fromCode(int code) {
            for (Action value : values()) if (value.code == code) return value;
            throw OrganizationRecordV1.invalid();
        }
    }
}
