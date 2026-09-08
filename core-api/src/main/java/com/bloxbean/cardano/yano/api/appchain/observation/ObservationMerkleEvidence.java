package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Bounded positive inclusion proof under a round-bound external Ed25519 root attestation. */
public record ObservationMerkleEvidence(int version, ObservationAttestation rootAttestation,
                                         byte[] value, long leafIndex, List<byte[]> siblings) {
    public static final String VERIFIER_ID = "ed25519-merkle-inclusion-v1";
    public static final int MAX_DEPTH = 20;
    public static final int MAX_ENCODED_BYTES = 68 * 1024;
    private static final byte[] LEAF_DOMAIN =
            "yano/observation/merkle/leaf/v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BRANCH_DOMAIN =
            "yano/observation/merkle/branch/v1\0".getBytes(StandardCharsets.US_ASCII);

    public ObservationMerkleEvidence {
        Objects.requireNonNull(rootAttestation, "rootAttestation");
        Objects.requireNonNull(siblings, "siblings");
        if (version != 1 || rootAttestation.claim().length != 32 || siblings.size() > MAX_DEPTH
                || leafIndex < 0 || leafIndex >= (1L << siblings.size())) {
            throw new IllegalArgumentException("Invalid observation inclusion proof bounds");
        }
        value = ObservationCbor.bounded(value, 64 * 1024, "Merkle observation value");
        siblings = siblings.stream().map(hash -> ObservationCbor.fixed(hash, 32, "Merkle sibling")).toList();
    }

    @Override public byte[] value() { return value.clone(); }
    @Override public List<byte[]> siblings() { return siblings.stream().map(byte[]::clone).toList(); }

    /** Parameter identity comes from the committed opened round, never from a proof-selected key. */
    public static byte[] leafHash(byte[] parametersDigest, byte[] sourceId, byte[] value) {
        byte[] parameters = ObservationCbor.fixed(parametersDigest, 32, "parameters digest");
        byte[] source = ObservationCbor.bounded(sourceId, 256, "source id");
        byte[] claim = ObservationCbor.bounded(value, 64 * 1024, "Merkle observation value");
        if (source.length == 0) throw new IllegalArgumentException("Source must not be empty");
        return ObservationHashes.digest(ByteBuffer.allocate(LEAF_DOMAIN.length + 40 + source.length + claim.length)
                .put(LEAF_DOMAIN).put(parameters).putInt(source.length).put(source).putInt(claim.length).put(claim)
                .array());
    }

    public static byte[] branchHash(byte[] left, byte[] right) {
        return ObservationHashes.digest(ByteBuffer.allocate(BRANCH_DOMAIN.length + 64).put(BRANCH_DOMAIN)
                .put(ObservationCbor.fixed(left, 32, "left Merkle hash"))
                .put(ObservationCbor.fixed(right, 32, "right Merkle hash")).array());
    }

    public byte[] computedRoot(byte[] parametersDigest) {
        byte[] hash = leafHash(parametersDigest, rootAttestation.sourceId(), value);
        for (int depth = 0; depth < siblings.size(); depth++) {
            hash = ((leafIndex >>> depth) & 1) == 0
                    ? branchHash(hash, siblings.get(depth)) : branchHash(siblings.get(depth), hash);
        }
        return hash;
    }

    public boolean verify(ObservationRound round, ObservationReport report, List<byte[]> authorizedAttestors,
                           ObservationSignatureVerifier signatures) {
        ObservationAttestation root = rootAttestation;
        return !authorizedAttestors.isEmpty() && authorizedAttestors.size() <= 32
                && authorizedAttestors.stream().anyMatch(key -> Arrays.equals(key, root.signerPublicKey()))
                && Arrays.equals(root.definitionDigest(), round.definitionDigest())
                && Arrays.equals(report.definitionDigest(), round.definitionDigest())
                && Arrays.equals(root.subscriptionId(), round.subscriptionId())
                && Arrays.equals(report.subscriptionId(), round.subscriptionId())
                && root.roundNumber() == round.roundNumber() && report.roundNumber() == round.roundNumber()
                && Arrays.equals(root.sourceId(), report.sourceId())
                && Arrays.equals(value, report.value())
                && Arrays.equals(root.sourceVersion(), report.sourceVersion())
                && root.freshnessAnchorType() == round.anchorType().code()
                && root.freshnessAnchorType() == report.freshnessAnchorType()
                && root.freshnessAnchor() == report.freshnessAnchor()
                && root.freshnessAnchor() >= round.dueAnchor()
                && root.freshnessAnchor() <= round.reportDeadlineAnchor()
                && Arrays.equals(computedRoot(round.parametersDigest()), root.claim())
                && signatures.verify(root.signerPublicKey(), root.signingDigest(), root.signature());
    }

    public byte[] encode() {
        Array out = ObservationCbor.array();
        ObservationCbor.uint(out, version);
        ObservationCbor.bytes(out, rootAttestation.encode());
        ObservationCbor.bytes(out, value);
        ObservationCbor.uint(out, leafIndex);
        Array path = ObservationCbor.array();
        for (byte[] sibling : siblings) ObservationCbor.bytes(path, sibling);
        out.add(path);
        return ObservationCbor.encode(out);
    }

    public static ObservationMerkleEvidence decode(byte[] bytes) {
        try {
            List<DataItem> fields = ObservationCbor.decode(bytes, MAX_ENCODED_BYTES, 64, 21, 64 * 1024,
                    5, "Merkle evidence");
            if (!(fields.get(4) instanceof Array path)) throw ObservationCbor.invalid("Merkle path");
            List<byte[]> siblings = new ArrayList<>();
            for (DataItem sibling : path.getDataItems()) siblings.add(ObservationCbor.bytesValue(sibling));
            ObservationMerkleEvidence value = new ObservationMerkleEvidence(ObservationCbor.intValue(fields.get(0)),
                    ObservationAttestation.decode(ObservationCbor.bytesValue(fields.get(1))),
                    ObservationCbor.bytesValue(fields.get(2)), ObservationCbor.longValue(fields.get(3)), siblings);
            ObservationCbor.canonical(bytes, value.encode(), "Merkle evidence");
            return value;
        } catch (RuntimeException malformed) {
            throw ObservationCbor.invalid("Merkle evidence");
        }
    }
}
