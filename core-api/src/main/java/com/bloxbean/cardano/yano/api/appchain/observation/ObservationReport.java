package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;

import java.util.List;

/** One authorized reporter's immutable signed claim for one source and round. */
public record ObservationReport(
        int version,
        byte[] chainGenesisId,
        String chainId,
        byte[] consensusProfileDigest,
        byte[] observationProfileDigest,
        byte[] definitionDigest,
        byte[] subscriptionId,
        long roundNumber,
        byte[] membershipDigest,
        byte[] reporterSetDigest,
        byte[] reporterPublicKey,
        byte[] sourceId,
        byte[] value,
        byte[] evidence,
        byte[] sourceVersion,
        int freshnessAnchorType,
        long freshnessAnchor,
        byte[] signature
) {
    public static final int MAX_ENCODED_BYTES = 512 * 1024;
    private static final int FIELDS = 18;

    public ObservationReport {
        if (version != ObservationCbor.VERSION || roundNumber < 0
                || freshnessAnchorType < 0 || freshnessAnchor < 0) {
            throw new IllegalArgumentException("invalid observation report fields");
        }
        chainGenesisId = ObservationCbor.fixed(chainGenesisId, 32, "chain genesis id");
        chainId = ObservationCbor.boundedText(chainId, 128, "chain id");
        consensusProfileDigest = ObservationCbor.fixed(consensusProfileDigest, 32, "consensus profile digest");
        observationProfileDigest = ObservationCbor.fixed(observationProfileDigest, 32, "observation profile digest");
        definitionDigest = ObservationCbor.fixed(definitionDigest, 32, "definition digest");
        subscriptionId = ObservationCbor.fixed(subscriptionId, 32, "subscription id");
        membershipDigest = ObservationCbor.fixed(membershipDigest, 32, "membership digest");
        reporterSetDigest = ObservationCbor.fixed(reporterSetDigest, 32, "reporter set digest");
        reporterPublicKey = ObservationCbor.fixed(reporterPublicKey, 32, "reporter public key");
        sourceId = ObservationCbor.bounded(sourceId, 256, "source id");
        if (sourceId.length == 0) {
            throw new IllegalArgumentException("source id must not be empty");
        }
        value = ObservationCbor.bounded(value, 64 * 1024, "observation value");
        evidence = ObservationCbor.bounded(evidence, 384 * 1024, "observation evidence");
        sourceVersion = ObservationCbor.bounded(sourceVersion, 256, "source version");
        signature = ObservationCbor.fixed(signature, 64, "report signature");
    }

    @Override public byte[] chainGenesisId() { return chainGenesisId.clone(); }
    @Override public byte[] consensusProfileDigest() { return consensusProfileDigest.clone(); }
    @Override public byte[] observationProfileDigest() { return observationProfileDigest.clone(); }
    @Override public byte[] definitionDigest() { return definitionDigest.clone(); }
    @Override public byte[] subscriptionId() { return subscriptionId.clone(); }
    @Override public byte[] membershipDigest() { return membershipDigest.clone(); }
    @Override public byte[] reporterSetDigest() { return reporterSetDigest.clone(); }
    @Override public byte[] reporterPublicKey() { return reporterPublicKey.clone(); }
    @Override public byte[] sourceId() { return sourceId.clone(); }
    @Override public byte[] value() { return value.clone(); }
    @Override public byte[] evidence() { return evidence.clone(); }
    @Override public byte[] sourceVersion() { return sourceVersion.clone(); }
    @Override public byte[] signature() { return signature.clone(); }

    public byte[] signingDigest() {
        return ObservationHashes.reportSigningDigest(this);
    }

    public byte[] encodeWithoutSignature() {
        return encodeFields(false);
    }

    public byte[] encode() {
        return encodeFields(true);
    }

    private byte[] encodeFields(boolean includeSignature) {
        Array result = ObservationCbor.array();
        ObservationCbor.uint(result, version);
        ObservationCbor.bytes(result, chainGenesisId);
        ObservationCbor.text(result, chainId);
        ObservationCbor.bytes(result, consensusProfileDigest);
        ObservationCbor.bytes(result, observationProfileDigest);
        ObservationCbor.bytes(result, definitionDigest);
        ObservationCbor.bytes(result, subscriptionId);
        ObservationCbor.uint(result, roundNumber);
        ObservationCbor.bytes(result, membershipDigest);
        ObservationCbor.bytes(result, reporterSetDigest);
        ObservationCbor.bytes(result, reporterPublicKey);
        ObservationCbor.bytes(result, sourceId);
        ObservationCbor.bytes(result, value);
        ObservationCbor.bytes(result, evidence);
        ObservationCbor.bytes(result, sourceVersion);
        ObservationCbor.uint(result, freshnessAnchorType);
        ObservationCbor.uint(result, freshnessAnchor);
        if (includeSignature) {
            ObservationCbor.bytes(result, signature);
        }
        return ObservationCbor.encode(result);
    }

    public static ObservationReport decode(byte[] bytes) {
        try {
            List<DataItem> f = ObservationCbor.decode(bytes, MAX_ENCODED_BYTES, 48, 24,
                    384 * 1024, FIELDS, "report");
            ObservationReport value = new ObservationReport(
                    ObservationCbor.intValue(f.get(0)), ObservationCbor.bytesValue(f.get(1)),
                    ObservationCbor.textValue(f.get(2)), ObservationCbor.bytesValue(f.get(3)),
                    ObservationCbor.bytesValue(f.get(4)), ObservationCbor.bytesValue(f.get(5)),
                    ObservationCbor.bytesValue(f.get(6)), ObservationCbor.longValue(f.get(7)),
                    ObservationCbor.bytesValue(f.get(8)), ObservationCbor.bytesValue(f.get(9)),
                    ObservationCbor.bytesValue(f.get(10)), ObservationCbor.bytesValue(f.get(11)),
                    ObservationCbor.bytesValue(f.get(12)), ObservationCbor.bytesValue(f.get(13)),
                    ObservationCbor.bytesValue(f.get(14)), ObservationCbor.intValue(f.get(15)),
                    ObservationCbor.longValue(f.get(16)), ObservationCbor.bytesValue(f.get(17)));
            ObservationCbor.canonical(bytes, value.encode(), "report");
            return value;
        } catch (RuntimeException malformed) {
            throw ObservationCbor.invalid("report");
        }
    }
}
