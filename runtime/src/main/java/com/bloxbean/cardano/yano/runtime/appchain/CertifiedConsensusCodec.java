package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Canonical bounded wire types for ADR-036 certified consensus. */
final class CertifiedConsensusCodec {
    static final int VERSION = 2;
    static final int MAX_VOTE_BYTES = 320;
    static final int MAX_QC_BYTES = AppChainConfig.MAX_FINALITY_CERT_HEADROOM_BYTES + 256;
    static final int MAX_TIMEOUT_BYTES = 2 * MAX_QC_BYTES + 512;
    static final int MAX_NEW_VIEW_BYTES = 512 * 1024;
    private static final CborStructurePreflight.Limits VOTE_LIMITS =
            new CborStructurePreflight.Limits(MAX_VOTE_BYTES, 4, 16, 8,
                    AppChainConfig.ED25519_SIGNATURE_BYTES);
    private static final CborStructurePreflight.Limits QC_LIMITS =
            new CborStructurePreflight.Limits(MAX_QC_BYTES, 5, 256,
                    AppChainConfig.MAX_MEMBERS, AppChainConfig.ED25519_SIGNATURE_BYTES);
    private static final CborStructurePreflight.Limits TIMEOUT_LIMITS =
            new CborStructurePreflight.Limits(MAX_TIMEOUT_BYTES, 6, 512,
                    AppChainConfig.MAX_MEMBERS, MAX_QC_BYTES);
    private static final CborStructurePreflight.Limits NEW_VIEW_LIMITS =
            new CborStructurePreflight.Limits(MAX_NEW_VIEW_BYTES, 8, 4096,
                    AppChainConfig.MAX_MEMBERS, MAX_TIMEOUT_BYTES);
    private static final byte[] TIMEOUT_DOMAIN =
            "yano-appchain-timeout-v2\0".getBytes(StandardCharsets.US_ASCII);

    enum Phase {
        PREPARE(0, "yano-appchain-prepare-v2\0"),
        COMMIT(1, "yano-appchain-commit-v2\0");

        private final int code;
        private final byte[] domain;

        Phase(int code, String domain) {
            this.code = code;
            this.domain = domain.getBytes(StandardCharsets.US_ASCII);
        }

        static Phase fromCode(int code) {
            return switch (code) {
                case 0 -> PREPARE;
                case 1 -> COMMIT;
                default -> throw new IllegalArgumentException("Unknown certified vote phase");
            };
        }
    }

    record Vote(Phase phase,
                long height,
                long view,
                byte[] contextDigest,
                byte[] blockHash,
                byte[] valueHash,
                byte[] signature) {
        Vote {
            phase = Objects.requireNonNull(phase, "phase");
            if (height < 1 || view < 0) {
                throw new IllegalArgumentException("Invalid certified vote height/view");
            }
            contextDigest = bytes(contextDigest, 32, "context digest");
            blockHash = bytes(blockHash, 32, "block hash");
            valueHash = bytes(valueHash, 32, "value hash");
            signature = bytes(signature, AppChainConfig.ED25519_SIGNATURE_BYTES, "signature");
        }

        @Override public byte[] contextDigest() { return contextDigest.clone(); }
        @Override public byte[] blockHash() { return blockHash.clone(); }
        @Override public byte[] valueHash() { return valueHash.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
    }

    record QuorumCertificate(Phase phase,
                             long height,
                             long view,
                             byte[] contextDigest,
                             byte[] blockHash,
                             byte[] valueHash,
                             List<FinalityCert.Signature> signatures) {
        QuorumCertificate {
            phase = Objects.requireNonNull(phase, "phase");
            if (height < 1 || view < 0) {
                throw new IllegalArgumentException("Invalid QC height/view");
            }
            contextDigest = bytes(contextDigest, 32, "context digest");
            blockHash = bytes(blockHash, 32, "block hash");
            valueHash = bytes(valueHash, 32, "value hash");
            signatures = Objects.requireNonNull(signatures, "signatures").stream()
                    .map(signature -> new FinalityCert.Signature(
                            bytes(signature.signer(), 32, "QC signer"),
                            bytes(signature.signature(), AppChainConfig.ED25519_SIGNATURE_BYTES,
                                    "QC signature")))
                    .sorted(Comparator.comparing(FinalityCert.Signature::signer,
                            Arrays::compareUnsigned))
                    .toList();
            if (signatures.isEmpty() || signatures.size() > AppChainConfig.MAX_MEMBERS) {
                throw new IllegalArgumentException("Invalid QC signature count");
            }
            for (int index = 1; index < signatures.size(); index++) {
                if (Arrays.equals(signatures.get(index - 1).signer(),
                        signatures.get(index).signer())) {
                    throw new IllegalArgumentException("Duplicate QC signer");
                }
            }
        }

        @Override public byte[] contextDigest() { return contextDigest.clone(); }
        @Override public byte[] blockHash() { return blockHash.clone(); }
        @Override public byte[] valueHash() { return valueHash.clone(); }
        @Override public List<FinalityCert.Signature> signatures() {
            return signatures.stream().map(signature -> new FinalityCert.Signature(
                    signature.signer().clone(), signature.signature().clone())).toList();
        }
    }

    record Timeout(long height,
                   long targetView,
                   byte[] contextDigest,
                   byte[] preparedQc,
                   byte[] finalityCertificate,
                   byte[] signature) {
        Timeout {
            if (height < 1 || targetView < 1) {
                throw new IllegalArgumentException("Invalid timeout height/view");
            }
            contextDigest = bytes(contextDigest, 32, "context digest");
            preparedQc = bounded(preparedQc, MAX_QC_BYTES, "prepared QC");
            finalityCertificate = bounded(finalityCertificate,
                    MAX_QC_BYTES, "finality certificate");
            signature = bytes(signature, AppChainConfig.ED25519_SIGNATURE_BYTES, "signature");
        }

        @Override public byte[] contextDigest() { return contextDigest.clone(); }
        @Override public byte[] preparedQc() { return preparedQc.clone(); }
        @Override public byte[] finalityCertificate() { return finalityCertificate.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
    }

    record SignedTimeout(byte[] signer, Timeout timeout) {
        SignedTimeout {
            signer = bytes(signer, 32, "timeout signer");
            timeout = Objects.requireNonNull(timeout, "timeout");
        }

        @Override public byte[] signer() { return signer.clone(); }
    }

    record NewViewCertificate(long height,
                              long targetView,
                              byte[] contextDigest,
                              List<SignedTimeout> timeouts) {
        NewViewCertificate {
            if (height < 1 || targetView < 1) {
                throw new IllegalArgumentException("Invalid new-view height/view");
            }
            contextDigest = bytes(contextDigest, 32, "context digest");
            timeouts = Objects.requireNonNull(timeouts, "timeouts").stream()
                    .sorted(Comparator.comparing(SignedTimeout::signer, Arrays::compareUnsigned))
                    .toList();
            if (timeouts.isEmpty() || timeouts.size() > AppChainConfig.MAX_MEMBERS) {
                throw new IllegalArgumentException("Invalid new-view timeout count");
            }
            for (int index = 1; index < timeouts.size(); index++) {
                if (Arrays.equals(timeouts.get(index - 1).signer(),
                        timeouts.get(index).signer())) {
                    throw new IllegalArgumentException("Duplicate new-view signer");
                }
            }
        }

        @Override public byte[] contextDigest() { return contextDigest.clone(); }
        @Override public List<SignedTimeout> timeouts() { return List.copyOf(timeouts); }
    }

    private CertifiedConsensusCodec() {
    }

    static byte[] signingDigest(Phase phase, long height, long view,
                                byte[] contextDigest, byte[] blockHash, byte[] valueHash) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(phase.domain);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeLong(height);
                out.writeLong(view);
                out.write(bytes(contextDigest, 32, "context digest"));
                out.write(bytes(blockHash, 32, "block hash"));
                out.write(bytes(valueHash, 32, "value hash"));
            }
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    static byte[] encodeVote(Vote vote) {
        Array array = prefix(vote.phase(), vote.height(), vote.view(),
                vote.contextDigest(), vote.blockHash());
        array.add(new ByteString(vote.valueHash()));
        array.add(new ByteString(vote.signature()));
        return CborSerializationUtil.serialize(array);
    }

    static Vote decodeVote(byte[] encoded) {
        if (!CborStructurePreflight.accepts(encoded, VOTE_LIMITS)) {
            throw invalid("Invalid bounded certified vote");
        }
        try {
            List<DataItem> fields = ((Array) CborSerializationUtil.deserializeOne(encoded))
                    .getDataItems();
            if (fields.size() != 8 || number(fields.get(0)) != VERSION) {
                throw invalid("Invalid certified vote shape");
            }
            Vote vote = new Vote(Phase.fromCode(Math.toIntExact(number(fields.get(1)))),
                    number(fields.get(2)), number(fields.get(3)),
                    bytes(fields.get(4)), bytes(fields.get(5)), bytes(fields.get(6)),
                    bytes(fields.get(7)));
            if (!Arrays.equals(encoded, encodeVote(vote))) {
                throw invalid("Non-canonical certified vote");
            }
            return vote;
        } catch (RuntimeException failure) {
            throw invalid("Invalid canonical certified vote");
        }
    }

    static byte[] encodeQc(QuorumCertificate certificate) {
        Array array = prefix(certificate.phase(), certificate.height(), certificate.view(),
                certificate.contextDigest(), certificate.blockHash());
        array.add(new ByteString(certificate.valueHash()));
        Array signatures = new Array();
        for (FinalityCert.Signature signature : certificate.signatures()) {
            Array item = new Array();
            item.add(new ByteString(signature.signer()));
            item.add(new ByteString(signature.signature()));
            signatures.add(item);
        }
        array.add(signatures);
        return CborSerializationUtil.serialize(array);
    }

    static byte[] timeoutSigningDigest(long height, long targetView,
                                       byte[] contextDigest, byte[] preparedQc,
                                       byte[] finalityCertificate) {
        try {
            ByteArrayOutputStream value = new ByteArrayOutputStream();
            value.write(TIMEOUT_DOMAIN);
            try (DataOutputStream out = new DataOutputStream(value)) {
                out.writeLong(height);
                out.writeLong(targetView);
                out.write(bytes(contextDigest, 32, "context digest"));
                out.write(Blake2bUtil.blake2bHash256(
                        bounded(preparedQc, MAX_QC_BYTES, "prepared QC")));
                out.write(Blake2bUtil.blake2bHash256(bounded(finalityCertificate,
                        MAX_QC_BYTES,
                        "finality certificate")));
            }
            return Blake2bUtil.blake2bHash256(value.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    static byte[] encodeTimeout(Timeout timeout) {
        Array array = new Array();
        array.add(new UnsignedInteger(VERSION));
        array.add(new UnsignedInteger(timeout.height()));
        array.add(new UnsignedInteger(timeout.targetView()));
        array.add(new ByteString(timeout.contextDigest()));
        array.add(new ByteString(timeout.preparedQc()));
        array.add(new ByteString(timeout.finalityCertificate()));
        array.add(new ByteString(timeout.signature()));
        return CborSerializationUtil.serialize(array);
    }

    static Timeout decodeTimeout(byte[] encoded) {
        if (!CborStructurePreflight.accepts(encoded, TIMEOUT_LIMITS)) {
            throw invalid("Invalid bounded timeout");
        }
        try {
            List<DataItem> fields = ((Array) CborSerializationUtil.deserializeOne(encoded))
                    .getDataItems();
            if (fields.size() != 7 || number(fields.get(0)) != VERSION) {
                throw invalid("Invalid timeout shape");
            }
            Timeout timeout = new Timeout(number(fields.get(1)), number(fields.get(2)),
                    bytes(fields.get(3)), bytes(fields.get(4)), bytes(fields.get(5)),
                    bytes(fields.get(6)));
            if (!Arrays.equals(encoded, encodeTimeout(timeout))) {
                throw invalid("Non-canonical timeout");
            }
            return timeout;
        } catch (RuntimeException failure) {
            throw invalid("Invalid canonical timeout");
        }
    }

    static byte[] encodeNewView(NewViewCertificate certificate) {
        Array array = new Array();
        array.add(new UnsignedInteger(VERSION));
        array.add(new UnsignedInteger(certificate.height()));
        array.add(new UnsignedInteger(certificate.targetView()));
        array.add(new ByteString(certificate.contextDigest()));
        Array timeouts = new Array();
        for (SignedTimeout signed : certificate.timeouts()) {
            Array item = new Array();
            item.add(new ByteString(signed.signer()));
            item.add(new ByteString(encodeTimeout(signed.timeout())));
            timeouts.add(item);
        }
        array.add(timeouts);
        byte[] encoded = CborSerializationUtil.serialize(array);
        if (encoded.length > MAX_NEW_VIEW_BYTES) {
            throw invalid("New-view certificate exceeds released bound");
        }
        return encoded;
    }

    static NewViewCertificate decodeNewView(byte[] encoded) {
        if (!CborStructurePreflight.accepts(encoded, NEW_VIEW_LIMITS)) {
            throw invalid("Invalid bounded new-view certificate");
        }
        try {
            List<DataItem> fields = ((Array) CborSerializationUtil.deserializeOne(encoded))
                    .getDataItems();
            if (fields.size() != 5 || number(fields.get(0)) != VERSION) {
                throw invalid("Invalid new-view shape");
            }
            List<SignedTimeout> timeouts = new ArrayList<>();
            for (DataItem item : ((Array) fields.get(4)).getDataItems()) {
                List<DataItem> parts = ((Array) item).getDataItems();
                if (parts.size() != 2) throw invalid("Invalid signed timeout shape");
                timeouts.add(new SignedTimeout(bytes(parts.get(0)),
                        decodeTimeout(bytes(parts.get(1)))));
            }
            NewViewCertificate certificate = new NewViewCertificate(
                    number(fields.get(1)), number(fields.get(2)), bytes(fields.get(3)), timeouts);
            if (!Arrays.equals(encoded, encodeNewView(certificate))) {
                throw invalid("Non-canonical new-view certificate");
            }
            return certificate;
        } catch (RuntimeException failure) {
            throw invalid("Invalid canonical new-view certificate");
        }
    }

    static QuorumCertificate decodeQc(byte[] encoded) {
        if (!CborStructurePreflight.accepts(encoded, QC_LIMITS)) {
            throw invalid("Invalid bounded quorum certificate");
        }
        try {
            List<DataItem> fields = ((Array) CborSerializationUtil.deserializeOne(encoded))
                    .getDataItems();
            if (fields.size() != 8 || number(fields.get(0)) != VERSION) {
                throw invalid("Invalid quorum certificate shape");
            }
            List<FinalityCert.Signature> signatures = ((Array) fields.get(7)).getDataItems()
                    .stream()
                    .map(item -> ((Array) item).getDataItems())
                    .map(parts -> new FinalityCert.Signature(
                            bytes(parts.get(0)), bytes(parts.get(1))))
                    .toList();
            QuorumCertificate certificate = new QuorumCertificate(
                    Phase.fromCode(Math.toIntExact(number(fields.get(1)))),
                    number(fields.get(2)), number(fields.get(3)),
                    bytes(fields.get(4)), bytes(fields.get(5)), bytes(fields.get(6)), signatures);
            if (!Arrays.equals(encoded, encodeQc(certificate))) {
                throw invalid("Non-canonical quorum certificate");
            }
            return certificate;
        } catch (RuntimeException failure) {
            throw invalid("Invalid canonical quorum certificate");
        }
    }

    private static Array prefix(Phase phase, long height, long view,
                                byte[] contextDigest, byte[] blockHash) {
        Array array = new Array();
        array.add(new UnsignedInteger(VERSION));
        array.add(new UnsignedInteger(phase.code));
        array.add(new UnsignedInteger(height));
        array.add(new UnsignedInteger(view));
        array.add(new ByteString(contextDigest));
        array.add(new ByteString(blockHash));
        return array;
    }

    private static long number(DataItem item) {
        return ((UnsignedInteger) item).getValue().longValueExact();
    }

    private static byte[] bytes(DataItem item) {
        return ((ByteString) item).getBytes();
    }

    private static byte[] bytes(byte[] value, int length, String name) {
        Objects.requireNonNull(value, name);
        if (value.length != length) {
            throw new IllegalArgumentException(name + " must be " + length + " bytes");
        }
        return value.clone();
    }

    private static byte[] bounded(byte[] value, int maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.length > maximum) {
            throw new IllegalArgumentException(name + " exceeds released bound");
        }
        return value.clone();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
