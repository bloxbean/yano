package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowEd25519;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Domain-separated proof that the proposed actor key is controlled. */
public record ActorKeyProofV1(String chainId, String actorId, long actorRevision,
                              ActorKeyEpochV1 key, byte[] signature) {
    private static final byte[] DOMAIN =
            "yano:actor-key-proof:v1\0".getBytes(StandardCharsets.US_ASCII);

    public ActorKeyProofV1 {
        chainId = RoleWorkflowIdentifiers.id(chainId, "chainId");
        actorId = RoleWorkflowIdentifiers.id(actorId, "actorId");
        if (actorRevision < 1 || key == null || signature == null || signature.length != 64) {
            throw OrganizationRecordV1.invalid();
        }
        signature = signature.clone();
    }

    @Override public byte[] signature() { return signature.clone(); }

    public static ActorKeyProofV1 sign(String chainId, String actorId, long actorRevision,
                                       ActorKeyEpochV1 key, byte[] privateSeed) {
        byte[] unsigned = unsignedBytes(chainId, actorId, actorRevision, key);
        byte[] signature = RoleWorkflowEd25519.sign(preimage(unsigned), privateSeed);
        return new ActorKeyProofV1(chainId, actorId, actorRevision, key, signature);
    }

    public boolean verify() {
        return RoleWorkflowEd25519.verify(signature,
                preimage(unsignedBytes(chainId, actorId, actorRevision, key)), key.publicKey());
    }

    public byte[] encode() {
        Array value = unsignedCbor(chainId, actorId, actorRevision, key);
        value.add(new ByteString(signature));
        return RoleWorkflowCbor.encode(value);
    }

    public static ActorKeyProofV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 6).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        ActorKeyEpochV1 key = ActorKeyEpochV1.fromCbor(values.get(4));
        return new ActorKeyProofV1(RoleWorkflowCbor.text(values.get(1)),
                RoleWorkflowCbor.text(values.get(2)), RoleWorkflowCbor.uint(values.get(3)),
                key, RoleWorkflowCbor.bytes(values.get(5), 64));
    }

    private static byte[] unsignedBytes(String chainId, String actorId, long revision,
                                        ActorKeyEpochV1 key) {
        return RoleWorkflowCbor.encode(unsignedCbor(chainId, actorId, revision, key));
    }

    private static Array unsignedCbor(String chainId, String actorId, long revision,
                                      ActorKeyEpochV1 key) {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(RoleWorkflowIdentifiers.id(chainId, "chainId")));
        value.add(new UnicodeString(RoleWorkflowIdentifiers.id(actorId, "actorId")));
        value.add(new UnsignedInteger(revision));
        value.add(key.toCbor());
        return value;
    }

    private static byte[] preimage(byte[] unsigned) {
        return ByteBuffer.allocate(DOMAIN.length + Integer.BYTES + unsigned.length)
                .put(DOMAIN).putInt(unsigned.length).put(unsigned).array();
    }
}
