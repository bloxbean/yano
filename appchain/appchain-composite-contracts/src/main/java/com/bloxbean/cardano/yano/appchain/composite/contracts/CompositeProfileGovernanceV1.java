package com.bloxbean.cardano.yano.appchain.composite.contracts;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Frozen v1 command/hash contract for governed composite-profile epochs. */
public final class CompositeProfileGovernanceV1 {
    public static final int VERSION = 1;
    public static final String TOPIC = "~governance/composite-profile";
    public static final int MAX_PROFILE_BYTES = 65_536;
    public static final int MAX_CHUNKS = 8;
    public static final int MAX_CHUNK_BYTES = 16_384;
    public static final int MAX_COMMAND_BYTES = 17_024;

    private static final byte[] PROPOSAL_DOMAIN =
            "yano-composite-profile-proposal-v1\0".getBytes(StandardCharsets.US_ASCII);

    private CompositeProfileGovernanceV1() {
    }

    public sealed interface Command permits Begin, Chunk, Seal, Approve, Ready, Cancel {
        byte[] encode();
    }

    public record Begin(byte[] proposalId,
                        byte[] baseProfileDigest,
                        byte[] membershipEpochDigest,
                        byte[] targetProfileDigest,
                        int totalBytes,
                        int chunkCount,
                        long activationHeight,
                        long expiryHeight) implements Command {
        public Begin {
            proposalId = digest(proposalId, "proposalId");
            baseProfileDigest = digest(baseProfileDigest, "baseProfileDigest");
            membershipEpochDigest = digest(membershipEpochDigest, "membershipEpochDigest");
            targetProfileDigest = digest(targetProfileDigest, "targetProfileDigest");
            if (totalBytes < 1 || totalBytes > MAX_PROFILE_BYTES
                    || chunkCount < 1 || chunkCount > MAX_CHUNKS
                    || activationHeight < 2 || expiryHeight < activationHeight) {
                throw malformed();
            }
        }

        @Override public byte[] proposalId() { return proposalId.clone(); }
        @Override public byte[] baseProfileDigest() { return baseProfileDigest.clone(); }
        @Override public byte[] membershipEpochDigest() { return membershipEpochDigest.clone(); }
        @Override public byte[] targetProfileDigest() { return targetProfileDigest.clone(); }

        @Override
        public byte[] encode() {
            return encodeArray(1, proposalId, baseProfileDigest, membershipEpochDigest,
                    targetProfileDigest, totalBytes, chunkCount, activationHeight, expiryHeight);
        }
    }

    public record Chunk(byte[] proposalId, int index, byte[] bytes) implements Command {
        public Chunk {
            proposalId = digest(proposalId, "proposalId");
            bytes = snapshot(bytes, 1, MAX_CHUNK_BYTES);
            if (index < 0 || index >= MAX_CHUNKS) {
                throw malformed();
            }
        }

        @Override public byte[] proposalId() { return proposalId.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public byte[] encode() { return encodeArray(2, proposalId, index, bytes); }
    }

    public record Seal(byte[] proposalId) implements Command {
        public Seal { proposalId = digest(proposalId, "proposalId"); }
        @Override public byte[] proposalId() { return proposalId.clone(); }
        @Override public byte[] encode() { return encodeArray(3, proposalId); }
    }

    public record Approve(byte[] proposalHash) implements Command {
        public Approve { proposalHash = digest(proposalHash, "proposalHash"); }
        @Override public byte[] proposalHash() { return proposalHash.clone(); }
        @Override public byte[] encode() { return encodeArray(4, proposalHash); }
    }

    public record Ready(byte[] proposalHash, byte[] targetProfileDigest) implements Command {
        public Ready {
            proposalHash = digest(proposalHash, "proposalHash");
            targetProfileDigest = digest(targetProfileDigest, "targetProfileDigest");
        }
        @Override public byte[] proposalHash() { return proposalHash.clone(); }
        @Override public byte[] targetProfileDigest() { return targetProfileDigest.clone(); }
        @Override public byte[] encode() { return encodeArray(5, proposalHash, targetProfileDigest); }
    }

    public record Cancel(byte[] proposalHash) implements Command {
        public Cancel { proposalHash = digest(proposalHash, "proposalHash"); }
        @Override public byte[] proposalHash() { return proposalHash.clone(); }
        @Override public byte[] encode() { return encodeArray(6, proposalHash); }
    }

    public static Command decode(byte[] encoded) {
        byte[] input = snapshot(encoded, 1, MAX_COMMAND_BYTES);
        try {
            List<DataItem> roots = new CborDecoder(new ByteArrayInputStream(input)).decode();
            if (roots.size() != 1 || !(roots.getFirst() instanceof Array root)
                    || root.isChunked() || root.getDataItems().size() < 2) {
                throw malformed();
            }
            List<DataItem> items = root.getDataItems();
            requireUnsigned(items.get(0), VERSION);
            int operation = integer(items.get(1));
            Command command = switch (operation) {
                case 1 -> {
                    requireSize(items, 10);
                    yield new Begin(bytes(items.get(2)), bytes(items.get(3)), bytes(items.get(4)),
                            bytes(items.get(5)), integer(items.get(6)), integer(items.get(7)),
                            unsignedLong(items.get(8)), unsignedLong(items.get(9)));
                }
                case 2 -> {
                    requireSize(items, 5);
                    yield new Chunk(bytes(items.get(2)), integer(items.get(3)), bytes(items.get(4)));
                }
                case 3 -> {
                    requireSize(items, 3);
                    yield new Seal(bytes(items.get(2)));
                }
                case 4 -> {
                    requireSize(items, 3);
                    yield new Approve(bytes(items.get(2)));
                }
                case 5 -> {
                    requireSize(items, 4);
                    yield new Ready(bytes(items.get(2)), bytes(items.get(3)));
                }
                case 6 -> {
                    requireSize(items, 3);
                    yield new Cancel(bytes(items.get(2)));
                }
                default -> throw malformed();
            };
            if (!Arrays.equals(input, command.encode())) {
                throw malformed();
            }
            return command;
        } catch (CborException | RuntimeException malformed) {
            throw malformed();
        }
    }

    public static byte[] proposalHash(String chainId, Begin begin) {
        Objects.requireNonNull(begin, "begin");
        if (chainId == null || !chainId.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("invalid proposal chainId");
        }
        byte[] chain = chainId.getBytes(StandardCharsets.US_ASCII);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(PROPOSAL_DOMAIN);
            digest.update((byte) chain.length);
            digest.update(chain);
            digest.update(begin.proposalId());
            digest.update(begin.baseProfileDigest());
            digest.update(begin.membershipEpochDigest());
            digest.update(begin.targetProfileDigest());
            digest.update(ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES * 2)
                    .putLong(begin.activationHeight()).putLong(begin.expiryHeight())
                    .putInt(begin.totalBytes()).putInt(begin.chunkCount()).array());
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] encodeArray(int operation, Object... fields) {
        Array root = new Array();
        root.add(new UnsignedInteger(VERSION));
        root.add(new UnsignedInteger(operation));
        for (Object field : fields) {
            if (field instanceof byte[] bytes) {
                root.add(new ByteString(bytes));
            } else if (field instanceof Integer integer) {
                root.add(new UnsignedInteger(integer));
            } else if (field instanceof Long value) {
                root.add(new UnsignedInteger(value));
            } else {
                throw new IllegalStateException("unsupported governance field");
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new CborEncoder(out).encode(root);
            byte[] encoded = out.toByteArray();
            if (encoded.length > MAX_COMMAND_BYTES) {
                throw malformed();
            }
            return encoded;
        } catch (CborException impossible) {
            throw new IllegalStateException("in-memory governance encoding failed", impossible);
        }
    }

    private static void requireSize(List<DataItem> items, int size) {
        if (items.size() != size) throw malformed();
    }

    private static int integer(DataItem item) {
        long value = unsignedLong(item);
        if (value > Integer.MAX_VALUE) throw malformed();
        return (int) value;
    }

    private static long unsignedLong(DataItem item) {
        if (!(item instanceof UnsignedInteger integer)) throw malformed();
        try {
            return integer.getValue().longValueExact();
        } catch (ArithmeticException invalid) {
            throw malformed();
        }
    }

    private static void requireUnsigned(DataItem item, long expected) {
        if (unsignedLong(item) != expected) throw malformed();
    }

    private static byte[] bytes(DataItem item) {
        if (!(item instanceof ByteString bytes) || bytes.isChunked()) throw malformed();
        return bytes.getBytes();
    }

    private static byte[] digest(byte[] value, String field) {
        byte[] result = snapshot(value, 32, 32);
        if (field == null) throw malformed();
        return result;
    }

    private static byte[] snapshot(byte[] value, int minimum, int maximum) {
        if (value == null || value.length < minimum || value.length > maximum) throw malformed();
        return value.clone();
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid composite profile governance v1 command");
    }
}
