package com.bloxbean.cardano.yano.api.appchain.snapshot;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.AppAnchorCommitment;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;
import com.bloxbean.cardano.yano.api.appchain.state.StateSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Strict canonical CBOR transport and commitments for the complete nested proof trust chain. */
public final class AuthenticatedSnapshotProofBundleCodec {
    public static final String ANCHOR_EVIDENCE_WIRE_ID = "app-anchor-commitment-cbor-v1";
    private static final int MAX_BUNDLE_BYTES = 4 * 1024 * 1024;
    private static final byte[] STATEMENT_DOMAIN =
            "yano-authenticated-snapshot-statement-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BUNDLE_DOMAIN =
            "yano-authenticated-snapshot-proof-bundle-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() { };

    private AuthenticatedSnapshotProofBundleCodec() { }

    public static byte[] encode(AuthenticatedSnapshotProofBundleV1 bundle) {
        List<Object> payload = payload(bundle);
        List<Object> complete = new ArrayList<>(payload);
        complete.add(bundleCommitment(bundle));
        return write(complete);
    }

    public static AuthenticatedSnapshotProofBundleV1 decode(byte[] canonical) {
        List<Object> top = read(canonical, 6);
        if (number(top.get(0)) != 1) throw new IllegalArgumentException("unsupported proof bundle version");
        List<?> anchorEvidence = list(top.get(1), 2);
        if (!ANCHOR_EVIDENCE_WIRE_ID.equals(text(anchorEvidence.get(0)))) {
            throw new IllegalArgumentException("unsupported anchor evidence wire");
        }
        AppAnchorCommitment anchor = decodeAnchor(bytes(anchorEvidence.get(1)));
        StateProofEnvelope primary = decodeEnvelope(list(top.get(2), 2));
        StateProof secondary = decodeProof(list(top.get(3), 10));
        List<?> metadata = list(top.get(4), 6);
        byte[] descriptorBytes = boundedBytes(metadata.get(3), 1, 64 * 1024, "descriptor bytes");
        SnapshotDescriptorV1 descriptor = SnapshotCanonicalCodec.decodeDescriptor(descriptorBytes);
        if (!descriptor.seriesId().equals(text(metadata.get(0)))
                || descriptor.sequence() != number(metadata.get(1))
                || !descriptor.schemaId().equals(text(metadata.get(2)))
                || !Arrays.equals(descriptor.commitment(), bytes(metadata.get(4)))) {
            throw new IllegalArgumentException("proof bundle descriptor metadata mismatch");
        }
        AuthenticatedSnapshotProofBundleV1 bundle = new AuthenticatedSnapshotProofBundleV1(
                1, descriptorBytes, primary, secondary, anchor);
        if (!Arrays.equals(statementCommitment(bundle), bytes(metadata.get(5)))
                || !Arrays.equals(bundleCommitment(bundle), bytes(top.get(5)))
                || !Arrays.equals(canonical, encode(bundle))) {
            throw new IllegalArgumentException("non-canonical or mismatched proof bundle");
        }
        return bundle;
    }

    public static byte[] statementCommitment(AuthenticatedSnapshotProofBundleV1 bundle) {
        SnapshotDescriptorV1 descriptor = SnapshotCanonicalCodec.decodeDescriptor(bundle.descriptorBytes());
        StateProof proof = bundle.snapshotProof();
        byte[] value = proof.value() != null ? proof.value() : new byte[0];
        byte[] statement = write(List.of(descriptor.chainGenerationId(),
                descriptor.applicationProfileDigest(), descriptor.seriesId(), descriptor.sequence(),
                descriptor.commitment(), descriptor.snapshotRoot(), descriptor.schemaId(),
                proof.canonicalKey(), proof.presence().ordinal(), Blake2bUtil.blake2bHash256(value)));
        return Blake2bUtil.blake2bHash256(ByteBuffer.allocate(STATEMENT_DOMAIN.length + statement.length)
                .put(STATEMENT_DOMAIN).put(statement).array());
    }

    public static byte[] bundleCommitment(AuthenticatedSnapshotProofBundleV1 bundle) {
        byte[] canonicalPayload = write(payload(bundle));
        return Blake2bUtil.blake2bHash256(ByteBuffer.allocate(BUNDLE_DOMAIN.length + canonicalPayload.length)
                .put(BUNDLE_DOMAIN).put(canonicalPayload).array());
    }

    private static List<Object> payload(AuthenticatedSnapshotProofBundleV1 bundle) {
        SnapshotDescriptorV1 descriptor = SnapshotCanonicalCodec.decodeDescriptor(bundle.descriptorBytes());
        return List.of(1,
                List.of(ANCHOR_EVIDENCE_WIRE_ID, encodeAnchor(bundle.anchor())),
                encodeEnvelope(bundle.descriptorProof()),
                encodeProof(bundle.snapshotProof()),
                List.of(descriptor.seriesId(), descriptor.sequence(), descriptor.schemaId(),
                        bundle.descriptorBytes(), descriptor.commitment(), statementCommitment(bundle)));
    }

    private static byte[] encodeAnchor(AppAnchorCommitment anchor) {
        return write(List.of(1, anchor.chainId(), anchor.mode(), anchor.anchoredHeight(),
                anchor.stateRoot(), anchor.blockHash(), anchor.transactionHash(), anchor.l1Slot()));
    }

    private static AppAnchorCommitment decodeAnchor(byte[] encoded) {
        List<Object> fields = read(encoded, 8);
        if (number(fields.get(0)) != 1) throw new IllegalArgumentException("anchor evidence version");
        return new AppAnchorCommitment(boundedText(fields.get(1), 128, "anchor chain id"),
                boundedText(fields.get(2), 32, "anchor mode"), number(fields.get(3)),
                exactBytes(fields.get(4), 32, "anchor state root"),
                exactBytes(fields.get(5), 32, "anchor block hash"),
                boundedText(fields.get(6), 256, "anchor transaction hash"), number(fields.get(7)));
    }

    private static List<Object> encodeEnvelope(StateProofEnvelope envelope) {
        List<Object> signatures = envelope.finalityCertificate().signatures().stream()
                .map(signature -> (Object) List.of(signature.signer(), signature.signature())).toList();
        return List.of(List.of(envelope.proofSchemaVersion(), envelope.chainId(), envelope.blockHash(),
                        envelope.finalityCertificate().scheme(), signatures), encodeProof(envelope.proof()));
    }

    private static StateProofEnvelope decodeEnvelope(List<?> encoded) {
        List<?> metadata = list(encoded.get(0), 5);
        List<?> signatures = list(metadata.get(4));
        if (signatures.size() > 64 || number(metadata.get(3)) != FinalityCert.SCHEME_ED25519) {
            throw new IllegalArgumentException("invalid finality certificate");
        }
        List<FinalityCert.Signature> decodedSignatures = new ArrayList<>(signatures.size());
        for (Object value : signatures) {
            List<?> signature = list(value, 2);
            decodedSignatures.add(new FinalityCert.Signature(
                    exactBytes(signature.get(0), 32, "finality signer"),
                    exactBytes(signature.get(1), 64, "finality signature")));
        }
        return new StateProofEnvelope(Math.toIntExact(number(metadata.get(0))),
                boundedText(metadata.get(1), 128, "proof chain id"),
                exactBytes(metadata.get(2), 32, "proof block hash"),
                decodeProof(list(encoded.get(1), 10)),
                new FinalityCert(Math.toIntExact(number(metadata.get(3))), decodedSignatures));
    }

    private static List<Object> encodeProof(StateProof proof) {
        StateCommitmentIdentity identity = proof.snapshot().identity();
        return List.of(identity.profile().id(), identity.profile().formatFingerprint(),
                proof.proofEncodingId(), identity.genesisId(), proof.snapshot().stateRoot(),
                proof.snapshot().height(), proof.canonicalKey(), proof.presence().ordinal(),
                proof.value() != null ? proof.value() : new byte[0], proof.nativeProof());
    }

    private static StateProof decodeProof(List<?> fields) {
        var profile = StateCommitmentProfiles.require(text(fields.get(0)));
        if (!Arrays.equals(profile.formatFingerprint(), exactBytes(fields.get(1), 32,
                "proof format fingerprint"))
                || !profile.proofEncodingId().equals(text(fields.get(2)))) {
            throw new IllegalArgumentException("proof profile metadata mismatch");
        }
        int presenceTag = Math.toIntExact(number(fields.get(7)));
        if (presenceTag >= StateProof.Presence.values().length) {
            throw new IllegalArgumentException("invalid proof presence");
        }
        StateProof.Presence presence = StateProof.Presence.values()[presenceTag];
        byte[] encodedValue = boundedBytes(fields.get(8), 0, 1024 * 1024, "proof value");
        byte[] value = presence == StateProof.Presence.ABSENT ? null : encodedValue;
        if (presence == StateProof.Presence.ABSENT && encodedValue.length != 0) {
            throw new IllegalArgumentException("absence proof contains a value");
        }
        StateCommitmentIdentity identity = StateCommitmentIdentity.explicit(profile,
                exactBytes(fields.get(3), 32, "proof genesis id"));
        return new StateProof(new StateSnapshot(identity, number(fields.get(5)),
                exactBytes(fields.get(4), profile.rootLength(), "proof root")),
                boundedBytes(fields.get(6), 1, 256, "proof key"), value, presence,
                profile.proofEncodingId(),
                boundedBytes(fields.get(9), 1, 1024 * 1024, "native proof"));
    }

    private static byte[] write(Object value) {
        try {
            byte[] result = CBOR.writeValueAsBytes(value);
            if (result.length > MAX_BUNDLE_BYTES) throw new IllegalArgumentException("proof bundle exceeds limit");
            return result;
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid proof bundle", failure);
        }
    }

    private static List<Object> read(byte[] encoded, int size) {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_BUNDLE_BYTES) {
            throw new IllegalArgumentException("invalid bounded proof bundle");
        }
        try {
            List<Object> result = CBOR.readValue(encoded, LIST);
            if (result.size() != size) throw new IllegalArgumentException("invalid proof bundle arity");
            return result;
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid proof bundle", failure);
        }
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?> result)) throw new IllegalArgumentException("expected array");
        return result;
    }

    private static List<?> list(Object value, int size) {
        List<?> result = list(value);
        if (result.size() != size) throw new IllegalArgumentException("invalid array arity");
        return result;
    }

    private static String text(Object value) {
        if (!(value instanceof String result)) throw new IllegalArgumentException("expected text");
        return result;
    }

    private static String boundedText(Object value, int maximumUtf8Bytes, String field) {
        String result = text(value);
        int length = result.getBytes(StandardCharsets.UTF_8).length;
        if (length == 0 || length > maximumUtf8Bytes) {
            throw new IllegalArgumentException(field + " exceeds its bound");
        }
        return result;
    }

    private static byte[] bytes(Object value) {
        if (!(value instanceof byte[] result)) throw new IllegalArgumentException("expected bytes");
        return result;
    }

    private static byte[] exactBytes(Object value, int length, String field) {
        byte[] result = bytes(value);
        if (result.length != length) throw new IllegalArgumentException(field + " has invalid length");
        return result;
    }

    private static byte[] boundedBytes(Object value, int minimum, int maximum, String field) {
        byte[] result = bytes(value);
        if (result.length < minimum || result.length > maximum) {
            throw new IllegalArgumentException(field + " exceeds its bound");
        }
        return result;
    }

    private static long number(Object value) {
        if (!(value instanceof Number result) || result.longValue() < 0) {
            throw new IllegalArgumentException("expected uint");
        }
        return result.longValue();
    }
}
