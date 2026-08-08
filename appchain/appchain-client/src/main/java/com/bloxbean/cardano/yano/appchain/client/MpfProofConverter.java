package com.bloxbean.cardano.yano.appchain.client;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Tag;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.proofs.MpfNormalizedProof;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Converts a verified MPF wire proof to the bounded L1 fold representation. */
public final class MpfProofConverter {
    private static final long TAG_BRANCH = 121;
    private static final long TAG_FORK = 122;
    private static final long TAG_LEAF = 123;

    private MpfProofConverter() {
    }

    public static MpfNormalizedProof convert(AppChainClient.Proof proof) {
        Objects.requireNonNull(proof, "proof");
        if (proof.valueHex() == null) {
            throw new IllegalArgumentException(
                    "proof-gated withdrawal requires an inclusion proof");
        }
        if (proof.committedHeight() == null || proof.committedHeight() < 0) {
            throw new IllegalArgumentException(
                    "proof-gated withdrawal requires a root-fixed committed height");
        }
        if (!ProofVerifier.verifyInternalConsistency(proof)) {
            throw new IllegalArgumentException(
                    "node supplied an invalid MPF inclusion proof");
        }
        byte[] root = hex(proof.stateRootHex(), "state root");
        byte[] key = hex(proof.keyHex(), "key");
        byte[] value = hex(proof.valueHex(), "value");
        byte[] wire = hex(proof.proofWireHex(), "proof wire");
        byte[] path = MpfNormalizedProof.nibbles(
                Blake2bUtil.blake2bHash256(key));

        List<DataItem> decoded;
        try {
            decoded = new CborDecoder(
                    new ByteArrayInputStream(wire)).decode();
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "invalid MPF proof CBOR", failure);
        }
        if (decoded.size() != 1 || !(decoded.getFirst() instanceof Array steps)) {
            throw new IllegalArgumentException(
                    "MPF proof must contain one step array");
        }
        if (steps.getDataItems().size() > MpfNormalizedProof.MAX_FOLDS) {
            throw new IllegalArgumentException("MPF proof contains too many steps");
        }
        int cursor = 0;
        List<MpfNormalizedProof.FoldStep> forward = new ArrayList<>();
        for (DataItem item : steps.getDataItems()) {
            if (!(item instanceof Array step)) {
                throw new IllegalArgumentException(
                        "MPF proof step must be an array");
            }
            long tag = tag(step);
            if (tag == TAG_LEAF) {
                throw new IllegalArgumentException(
                        "inclusion proof cannot contain a conflicting-leaf step");
            }
            int skip = uint(step, 0, "skip");
            int nextCursor = Math.addExact(cursor, Math.addExact(skip, 1));
            if (nextCursor > MpfNormalizedProof.PATH_NIBBLES) {
                throw new IllegalArgumentException(
                        "MPF proof consumes beyond the hashed key path");
            }
            byte[] prefix = java.util.Arrays.copyOfRange(
                    path, cursor, nextCursor - 1);
            int nibble = path[nextCursor - 1] & 0xFF;
            List<byte[]> neighbors;
            byte[] branchValueHash = new byte[0];
            if (tag == TAG_BRANCH) {
                if (step.getDataItems().size() != 2
                        && step.getDataItems().size() != 3) {
                    throw new IllegalArgumentException(
                            "MPF branch step has the wrong shape");
                }
                byte[] joined = bytes(step.getDataItems().get(1), "neighbors");
                if (joined.length != MpfNormalizedProof.HASH_BYTES * 4) {
                    throw new IllegalArgumentException(
                            "MPF branch step requires four 32-byte neighbors");
                }
                neighbors = new ArrayList<>(4);
                for (int i = 0; i < 4; i++) {
                    neighbors.add(java.util.Arrays.copyOfRange(
                            joined,
                            i * MpfNormalizedProof.HASH_BYTES,
                            (i + 1) * MpfNormalizedProof.HASH_BYTES));
                }
                if (step.getDataItems().size() == 3) {
                    branchValueHash = exact(
                            bytes(step.getDataItems().get(2), "branch value hash"),
                            MpfNormalizedProof.HASH_BYTES,
                            "branch value hash");
                }
            } else if (tag == TAG_FORK) {
                if (step.getDataItems().size() != 2
                        || !(step.getDataItems().get(1) instanceof Array neighbor)
                        || tag(neighbor) != TAG_BRANCH
                        || neighbor.getDataItems().size() != 3) {
                    throw new IllegalArgumentException(
                            "MPF fork step has the wrong shape");
                }
                int neighborNibble = uint(neighbor, 0, "fork nibble");
                if (neighborNibble > 15 || neighborNibble == nibble) {
                    throw new IllegalArgumentException(
                            "MPF fork neighbor nibble is invalid");
                }
                byte[] neighborPrefix = bytes(
                        neighbor.getDataItems().get(1), "fork prefix");
                requireNibbles(neighborPrefix, "fork prefix");
                byte[] neighborRoot = exact(
                        bytes(neighbor.getDataItems().get(2), "fork root"),
                        MpfNormalizedProof.HASH_BYTES,
                        "fork root");
                neighbors = MpfNormalizedProof.sparseNeighbors(
                        nibble,
                        neighborNibble,
                        Blake2bUtil.blake2bHash256(
                                concatenate(neighborPrefix, neighborRoot)));
            } else {
                throw new IllegalArgumentException(
                        "unsupported MPF proof step tag " + tag);
            }
            forward.add(new MpfNormalizedProof.FoldStep(
                    cursor, prefix, nibble, neighbors, branchValueHash));
            cursor = nextCursor;
        }
        List<MpfNormalizedProof.FoldStep> folds = new ArrayList<>(forward);
        Collections.reverse(folds);
        MpfNormalizedProof converted = new MpfNormalizedProof(
                root,
                key,
                value,
                MpfNormalizedProof.encodeLeafSuffix(
                        java.util.Arrays.copyOfRange(
                                path, cursor, MpfNormalizedProof.PATH_NIBBLES)),
                folds,
                proof.committedHeight());
        if (!converted.verify()) {
            throw new IllegalArgumentException(
                    "normalized MPF proof does not reconstruct its state root");
        }
        return converted;
    }

    private static long tag(Array value) {
        Tag tag = value.getTag();
        return tag == null ? TAG_BRANCH : tag.getValue();
    }

    private static int uint(Array value, int index, String field) {
        if (index >= value.getDataItems().size()
                || !(value.getDataItems().get(index)
                instanceof UnsignedInteger integer)) {
            throw new IllegalArgumentException(
                    "MPF " + field + " must be an unsigned integer");
        }
        try {
            return integer.getValue().intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "MPF " + field + " exceeds integer range", failure);
        }
    }

    private static byte[] bytes(DataItem value, String field) {
        if (!(value instanceof ByteString bytes)) {
            throw new IllegalArgumentException(
                    "MPF " + field + " must be bytes");
        }
        return bytes.getBytes();
    }

    private static byte[] hex(String value, String field) {
        try {
            return HexFormat.of().parseHex(
                    Objects.requireNonNull(value, field));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "MPF " + field + " is not canonical hexadecimal", failure);
        }
    }

    private static byte[] exact(
            byte[] value,
            int length,
            String field
    ) {
        if (value.length != length) {
            throw new IllegalArgumentException(
                    "MPF " + field + " must contain " + length + " bytes");
        }
        return value;
    }

    private static void requireNibbles(byte[] value, String field) {
        for (byte nibble : value) {
            if ((nibble & 0xFF) > 15) {
                throw new IllegalArgumentException(
                        "MPF " + field + " contains a value outside 0-15");
            }
        }
    }

    private static byte[] concatenate(byte[] left, byte[] right) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(left.length + right.length);
        output.writeBytes(left);
        output.writeBytes(right);
        return output.toByteArray();
    }
}
