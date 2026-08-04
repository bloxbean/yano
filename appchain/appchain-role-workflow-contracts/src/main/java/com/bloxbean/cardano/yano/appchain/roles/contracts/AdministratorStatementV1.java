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

/** Exact actor-authenticated vote for one governed registry or policy mutation. */
public record AdministratorStatementV1(
        Decision decision,
        String chainId,
        byte[] genesisId,
        String authorityId,
        long authorityRevision,
        String mutationId,
        byte[] mutationHash,
        long notBeforeHeight,
        long expiryHeight,
        String actorId,
        long actorRevision,
        String keyId,
        byte[] publicKey,
        long issuedHeight,
        long deadlineHeight,
        int signatureAlgorithm
) {
    public static final int ED25519 = 0;
    private static final byte[] DOMAIN =
            "yano:authenticated-map:administrator:v1\0"
                    .getBytes(StandardCharsets.US_ASCII);

    public AdministratorStatementV1 {
        if (decision == null) throw OrganizationRecordV1.invalid();
        chainId = RoleWorkflowIdentifiers.chainId(chainId);
        genesisId = exact(genesisId, 32);
        authorityId = RoleWorkflowIdentifiers.id(authorityId, "authorityId");
        mutationId = RoleWorkflowIdentifiers.id(mutationId, "mutationId");
        mutationHash = exact(mutationHash, 32);
        actorId = RoleWorkflowIdentifiers.id(actorId, "actorId");
        keyId = RoleWorkflowIdentifiers.id(keyId, "keyId");
        publicKey = exact(publicKey, 32);
        if (authorityRevision < 1 || actorRevision < 1
                || notBeforeHeight < 1 || expiryHeight <= notBeforeHeight
                || issuedHeight < 1 || deadlineHeight <= issuedHeight
                || deadlineHeight > expiryHeight || signatureAlgorithm != ED25519) {
            throw OrganizationRecordV1.invalid();
        }
    }

    @Override public byte[] genesisId() { return genesisId.clone(); }
    @Override public byte[] mutationHash() { return mutationHash.clone(); }
    @Override public byte[] publicKey() { return publicKey.clone(); }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnsignedInteger(decision.code));
        value.add(new UnicodeString(chainId));
        value.add(new ByteString(genesisId));
        value.add(new UnicodeString(authorityId));
        value.add(new UnsignedInteger(authorityRevision));
        value.add(new UnicodeString(mutationId));
        value.add(new ByteString(mutationHash));
        value.add(new UnsignedInteger(notBeforeHeight));
        value.add(new UnsignedInteger(expiryHeight));
        value.add(new UnicodeString(actorId));
        value.add(new UnsignedInteger(actorRevision));
        value.add(new UnicodeString(keyId));
        value.add(new ByteString(publicKey));
        value.add(new UnsignedInteger(issuedHeight));
        value.add(new UnsignedInteger(deadlineHeight));
        value.add(new UnsignedInteger(signatureAlgorithm));
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

    public static AdministratorStatementV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 17).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        AdministratorStatementV1 decoded = new AdministratorStatementV1(
                Decision.fromCode(RoleWorkflowCbor.uintInt(values.get(1))),
                RoleWorkflowCbor.text(values.get(2)),
                RoleWorkflowCbor.bytes(values.get(3), 32),
                RoleWorkflowCbor.text(values.get(4)),
                RoleWorkflowCbor.uint(values.get(5)),
                RoleWorkflowCbor.text(values.get(6)),
                RoleWorkflowCbor.bytes(values.get(7), 32),
                RoleWorkflowCbor.uint(values.get(8)),
                RoleWorkflowCbor.uint(values.get(9)),
                RoleWorkflowCbor.text(values.get(10)),
                RoleWorkflowCbor.uint(values.get(11)),
                RoleWorkflowCbor.text(values.get(12)),
                RoleWorkflowCbor.bytes(values.get(13), 32),
                RoleWorkflowCbor.uint(values.get(14)),
                RoleWorkflowCbor.uint(values.get(15)),
                RoleWorkflowCbor.uintInt(values.get(16)));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    private static byte[] exact(byte[] value, int length) {
        if (value == null || value.length != length) {
            throw OrganizationRecordV1.invalid();
        }
        return value.clone();
    }

    public enum Decision {
        PROPOSE(0), APPROVE(1), CANCEL(2);

        private final int code;

        Decision(int code) { this.code = code; }
        public int code() { return code; }

        static Decision fromCode(int code) {
            for (Decision value : values()) if (value.code == code) return value;
            throw OrganizationRecordV1.invalid();
        }
    }
}
