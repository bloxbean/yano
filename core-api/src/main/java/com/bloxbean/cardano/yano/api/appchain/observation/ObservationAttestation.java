package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;

import java.util.List;

/**
 * Bounded external Ed25519 attestation used by the Phase-1 reference provider.
 * The signed identity includes the definition, subscription round, unique
 * claim/source version, and freshness anchor so a valid object cannot be
 * transplanted across work or selectively refreshed.
 */
public record ObservationAttestation(
        int version,
        byte[] definitionDigest,
        byte[] subscriptionId,
        long roundNumber,
        byte[] signerPublicKey,
        byte[] sourceId,
        byte[] claim,
        byte[] sourceVersion,
        int freshnessAnchorType,
        long freshnessAnchor,
        byte[] signature
) {
    public static final int MAX_ENCODED_BYTES = 448 * 1024;
    private static final int FIELDS = 11;

    public ObservationAttestation {
        if (version != ObservationCbor.VERSION || roundNumber < 0 || freshnessAnchorType < 0
                || freshnessAnchor < 0) {
            throw new IllegalArgumentException("invalid observation attestation fields");
        }
        definitionDigest = ObservationCbor.fixed(
                definitionDigest, 32, "attestation definition digest");
        subscriptionId = ObservationCbor.fixed(
                subscriptionId, 32, "attestation subscription id");
        signerPublicKey = ObservationCbor.fixed(
                signerPublicKey, 32, "attestation signer public key");
        sourceId = ObservationCbor.bounded(sourceId, 256, "attestation source id");
        claim = ObservationCbor.bounded(claim, 64 * 1024, "attestation claim");
        sourceVersion = ObservationCbor.bounded(
                sourceVersion, 256, "attestation source version");
        if (sourceId.length == 0 || sourceVersion.length == 0) {
            throw new IllegalArgumentException(
                    "attestation source id and source version must not be empty");
        }
        signature = ObservationCbor.fixed(signature, 64, "attestation signature");
    }

    @Override public byte[] definitionDigest() { return definitionDigest.clone(); }
    @Override public byte[] subscriptionId() { return subscriptionId.clone(); }
    @Override public byte[] signerPublicKey() { return signerPublicKey.clone(); }
    @Override public byte[] sourceId() { return sourceId.clone(); }
    @Override public byte[] claim() { return claim.clone(); }
    @Override public byte[] sourceVersion() { return sourceVersion.clone(); }
    @Override public byte[] signature() { return signature.clone(); }

    public byte[] signingDigest() {
        return ObservationHashes.attestationSigningDigest(this);
    }

    public byte[] encodeWithoutSignature() {
        return encode(false);
    }

    public byte[] encode() {
        return encode(true);
    }

    private byte[] encode(boolean includeSignature) {
        Array result = ObservationCbor.array();
        ObservationCbor.uint(result, version);
        ObservationCbor.bytes(result, definitionDigest);
        ObservationCbor.bytes(result, subscriptionId);
        ObservationCbor.uint(result, roundNumber);
        ObservationCbor.bytes(result, signerPublicKey);
        ObservationCbor.bytes(result, sourceId);
        ObservationCbor.bytes(result, claim);
        ObservationCbor.bytes(result, sourceVersion);
        ObservationCbor.uint(result, freshnessAnchorType);
        ObservationCbor.uint(result, freshnessAnchor);
        if (includeSignature) {
            ObservationCbor.bytes(result, signature);
        }
        return ObservationCbor.encode(result);
    }

    public static ObservationAttestation decode(byte[] bytes) {
        try {
            List<DataItem> fields = ObservationCbor.decode(bytes, MAX_ENCODED_BYTES,
                    32, 16, 384 * 1024, FIELDS, "attestation");
            ObservationAttestation value = new ObservationAttestation(
                    ObservationCbor.intValue(fields.get(0)),
                    ObservationCbor.bytesValue(fields.get(1)),
                    ObservationCbor.bytesValue(fields.get(2)),
                    ObservationCbor.longValue(fields.get(3)),
                    ObservationCbor.bytesValue(fields.get(4)),
                    ObservationCbor.bytesValue(fields.get(5)),
                    ObservationCbor.bytesValue(fields.get(6)),
                    ObservationCbor.bytesValue(fields.get(7)),
                    ObservationCbor.intValue(fields.get(8)),
                    ObservationCbor.longValue(fields.get(9)),
                    ObservationCbor.bytesValue(fields.get(10)));
            ObservationCbor.canonical(bytes, value.encode(), "attestation");
            return value;
        } catch (RuntimeException malformed) {
            throw ObservationCbor.invalid("attestation");
        }
    }
}
