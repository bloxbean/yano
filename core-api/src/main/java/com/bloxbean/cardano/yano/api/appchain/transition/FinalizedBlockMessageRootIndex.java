package com.bloxbean.cardano.yano.api.appchain.transition;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * ADR-037 immutable authenticated bridge from a finalized block height to its
 * ordered message-tree root and leaf count.
 */
public final class FinalizedBlockMessageRootIndex {
    public static final int SCHEMA_VERSION = 1;
    public static final String SUBJECT_ID = "finalized-block-messages-v1";
    public static final String LOGICAL_NAMESPACE = "~yano/finalized-block-messages/v1/";
    public static final String PRIMARY_RETENTION = "primary";
    private static final byte[] NAMESPACE = LOGICAL_NAMESPACE.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BLOCK_PREFIX = "block/".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] CONFIG_KEY = key("config".getBytes(StandardCharsets.US_ASCII));

    /** Immutable application-profile selection, including explicit disablement. */
    public record Config(boolean enabled, int maxMessagesPerBlock, String retentionProfile) {
        public Config {
            if (maxMessagesPerBlock < 1
                    || maxMessagesPerBlock > AppChainConfig.MAX_BLOCK_MESSAGES) {
                throw new IllegalArgumentException("invalid finalized block-message limit");
            }
            retentionProfile = Objects.requireNonNull(retentionProfile, "retentionProfile");
            if (!PRIMARY_RETENTION.equals(retentionProfile)) {
                throw new IllegalArgumentException("unsupported finalized block-message retention profile");
            }
        }

        public static Config primary(boolean enabled, int maximum) {
            return new Config(enabled, maximum, PRIMARY_RETENTION);
        }

        /** Canonical profile bytes; namespace/schema are frozen into this v1 type. */
        public byte[] canonicalBytes() {
            Array value = new Array();
            value.add(new UnsignedInteger(SCHEMA_VERSION));
            value.add(new UnsignedInteger(enabled ? 1 : 0));
            value.add(new UnsignedInteger(maxMessagesPerBlock));
            value.add(new UnicodeString(retentionProfile));
            value.add(new UnicodeString(LOGICAL_NAMESPACE));
            return CborSerializationUtil.serialize(value);
        }

        public byte[] digest() {
            return Blake2bUtil.blake2bHash256(canonicalBytes());
        }
    }

    public record BlockRecord(long height, byte[] messagesRoot, int messageCount) {
        public BlockRecord {
            if (height < 1 || messagesRoot == null || messagesRoot.length != 32
                    || messageCount < 0 || messageCount > AppChainConfig.MAX_BLOCK_MESSAGES) {
                throw new IllegalArgumentException("invalid finalized block-message record");
            }
            messagesRoot = messagesRoot.clone();
        }

        @Override public byte[] messagesRoot() { return messagesRoot.clone(); }

        public byte[] canonicalBytes() {
            Array value = new Array();
            value.add(new UnsignedInteger(SCHEMA_VERSION));
            value.add(new UnsignedInteger(height));
            value.add(new ByteString(messagesRoot));
            value.add(new UnsignedInteger(messageCount));
            return CborSerializationUtil.serialize(value);
        }
    }

    private FinalizedBlockMessageRootIndex() {
    }

    public static TransitionPlan plan(AppBlockExecutionContext context, Config config) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(config, "config");
        if (!config.enabled()) return TransitionPlan.mutations(List.of());
        AppBlock block = context.block();
        if (block.messages().size() > config.maxMessagesPerBlock()) {
            throw new IllegalArgumentException(
                    "finalized block-message index exceeds committed message limit");
        }
        BlockRecord record = new BlockRecord(
                block.height(), block.messagesRoot(), block.messages().size());
        return TransitionPlan.mutations(List.of(
                StateMutation.put(blockKey(block.height()), record.canonicalBytes())));
    }

    /** Physical reserved key: SHA-256(namespace || "block/" || uint64-be height). */
    public static byte[] blockKey(long height) {
        if (height < 1) throw new IllegalArgumentException("block height must be positive");
        byte[] suffix = ByteBuffer.allocate(BLOCK_PREFIX.length + Long.BYTES)
                .put(BLOCK_PREFIX).putLong(height).array();
        return key(suffix);
    }

    public static BlockRecord decode(byte[] encoded) {
        try {
            DataItem item = CborSerializationUtil.deserializeOne(
                    Objects.requireNonNull(encoded, "encoded"));
            if (!(item instanceof Array array)) throw invalid();
            List<DataItem> fields = array.getDataItems();
            if (fields.size() != 4
                    || !(fields.get(0) instanceof UnsignedInteger version)
                    || version.getValue().intValueExact() != SCHEMA_VERSION
                    || !(fields.get(1) instanceof UnsignedInteger height)
                    || !(fields.get(2) instanceof ByteString root)
                    || !(fields.get(3) instanceof UnsignedInteger count)) throw invalid();
            return new BlockRecord(height.getValue().longValueExact(), root.getBytes(),
                    count.getValue().intValueExact());
        } catch (RuntimeException malformed) {
            if (malformed instanceof IllegalArgumentException
                    && "invalid finalized block-message record".equals(malformed.getMessage())) {
                throw malformed;
            }
            throw invalid(malformed);
        }
    }

    private static byte[] key(byte[] suffix) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(NAMESPACE);
            return digest.digest(suffix);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid finalized block-message record");
    }

    private static IllegalArgumentException invalid(Throwable cause) {
        return new IllegalArgumentException("invalid finalized block-message record", cause);
    }
}
