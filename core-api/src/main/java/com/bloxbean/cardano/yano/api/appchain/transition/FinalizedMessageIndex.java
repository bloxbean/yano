package com.bloxbean.cardano.yano.api.appchain.transition;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reusable authenticated index proving that finalized messages occupied block positions. */
public final class FinalizedMessageIndex {
    public static final int SCHEMA_VERSION = 1;
    public static final byte[] TIP_KEY = "~tip".getBytes(StandardCharsets.US_ASCII);

    public enum InclusionPolicy {
        ALL,
        APPLICATION_ONLY;

        boolean includes(AppMessage message) {
            return this == ALL || message.getTopic() == null || !message.getTopic().startsWith("~");
        }
    }

    /** Consensus configuration that applications commit in their machine/composite profile. */
    public record Config(InclusionPolicy policy, int maxMessagesPerBlock) {
        public static final int SCHEMA_VERSION = 1;

        public Config {
            policy = Objects.requireNonNull(policy, "policy");
            if (maxMessagesPerBlock < 1
                    || maxMessagesPerBlock > AppChainConfig.MAX_BLOCK_MESSAGES) {
                throw new IllegalArgumentException("invalid finalized-message index block limit");
            }
        }

        public static Config allMessages() {
            return new Config(InclusionPolicy.ALL, AppChainConfig.MAX_BLOCK_MESSAGES);
        }

        /** Stable bytes suitable for a component or application genesis profile. */
        public byte[] canonicalBytes() {
            return ByteBuffer.allocate(12).putInt(SCHEMA_VERSION)
                    .putInt(policy.ordinal()).putInt(maxMessagesPerBlock).array();
        }

        public byte[] digest() {
            return Blake2bUtil.blake2bHash256(canonicalBytes());
        }

        /** Conservative number of authenticated writes (records plus the tip marker). */
        public int maximumWritesPerBlock() {
            return maxMessagesPerBlock + 1;
        }
    }

    private FinalizedMessageIndex() {
    }

    public static TransitionPlan plan(
            AppBlockExecutionContext context,
            Config config
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(config, "config");
        AppBlock block = context.block();
        List<StateMutation> writes = new ArrayList<>();
        List<AppMessage> messages = context.messages();
        if (messages.size() > config.maxMessagesPerBlock()) {
            throw new IllegalArgumentException(
                    "finalized-message index block exceeds committed cost limit");
        }
        for (int visibleIndex = 0; visibleIndex < messages.size(); visibleIndex++) {
            AppMessage message = messages.get(visibleIndex);
            if (!config.policy().includes(message)) continue;
            writes.add(messageMutation(TransitionContext.of(
                    block, context.originalMessageIndex(visibleIndex), message)));
        }
        writes.add(tipMutation(block.height()));
        return TransitionPlan.mutations(writes);
    }

    public static TransitionPlan planMessage(TransitionContext context) {
        return TransitionPlan.mutations(List.of(messageMutation(context)));
    }

    public static TransitionPlan planTip(long height) {
        return TransitionPlan.mutations(List.of(tipMutation(height)));
    }

    public static MessageRecord decode(byte[] value) {
        Array array = (Array) CborSerializationUtil.deserializeOne(value);
        List<DataItem> fields = array.getDataItems();
        if (fields.size() != 5
                || ((UnsignedInteger) fields.get(0)).getValue().intValueExact() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("invalid finalized-message index record");
        }
        return new MessageRecord(
                ((UnsignedInteger) fields.get(1)).getValue().longValueExact(),
                ((UnsignedInteger) fields.get(2)).getValue().intValueExact(),
                ((UnicodeString) fields.get(3)).getString(),
                ((ByteString) fields.get(4)).getBytes());
    }

    private static StateMutation messageMutation(TransitionContext context) {
        MessageRecord record = new MessageRecord(context.height(), context.originalMessageIndex(),
                context.topic(), context.sender());
        Array value = new Array();
        value.add(new UnsignedInteger(SCHEMA_VERSION));
        value.add(new UnsignedInteger(record.height()));
        value.add(new UnsignedInteger(record.originalMessageIndex()));
        value.add(new UnicodeString(record.topic()));
        value.add(new ByteString(record.sender()));
        return StateMutation.put(context.messageId(), CborSerializationUtil.serialize(value));
    }

    private static StateMutation tipMutation(long height) {
        return StateMutation.put(TIP_KEY,
                CborSerializationUtil.serialize(new UnsignedInteger(height)));
    }

    public record MessageRecord(
            long height,
            int originalMessageIndex,
            String topic,
            byte[] sender
    ) {
        public MessageRecord {
            if (height < 0 || originalMessageIndex < 0) {
                throw new IllegalArgumentException("message position must be non-negative");
            }
            topic = Objects.requireNonNull(topic, "topic");
            sender = Objects.requireNonNull(sender, "sender").clone();
        }

        @Override public byte[] sender() { return sender.clone(); }
    }
}
