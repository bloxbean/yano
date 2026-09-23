package org.yanoproject.api.appchain.transition;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.codec.MessageCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure single-message ordered-log transition for composition. */
public final class OrderedLogKernel implements TransitionKernel<byte[], Boolean> {
    public static final String EVENT_ID = "ordered-log.message-finalized.v1";
    private static final MessageCodec<byte[]> CODEC = new MessageCodec<>() {
        @Override public byte[] encode(byte[] value) { return value.clone(); }
        @Override public byte[] decode(byte[] body) { return body.clone(); }
        @Override public Class<byte[]> type() { return byte[].class; }
    };

    @Override public MessageCodec<byte[]> codec() { return CODEC; }
    @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
        return true;
    }
    @Override public List<CommandDescriptor> commands() {
        return List.of(new CommandDescriptor("append", CommandDescriptor.Layout.RAW_BYTES, 0, List.of()));
    }
    @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
    @Override public List<EventDescriptor> events() {
        return List.of(new EventDescriptor(EVENT_ID, List.of(
                field("topic", TransitionScalars.Type.TEXT), field("sender", TransitionScalars.Type.BYTES),
                field("messageId", TransitionScalars.Type.BYTES), field("height", TransitionScalars.Type.INTEGER),
                field("index", TransitionScalars.Type.INTEGER), field("bodyHash", TransitionScalars.Type.BYTES))));
    }
    @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
        List<StateMutation> writes = new ArrayList<>(FinalizedMessageIndex.planMessage(context).mutations());
        writes.addAll(FinalizedMessageIndex.planTip(context.height()).mutations());
        byte[] payload = TransitionScalars.encode(Map.of(
                "topic", context.topic(), "sender", context.sender(), "messageId", context.messageId(),
                "height", context.height(), "index", (long) context.originalMessageIndex(),
                "bodyHash", Blake2bUtil.blake2bHash256(command)));
        return TransitionDecision.approve(new TransitionPlan(writes, List.of(), List.of(), List.of(),
                List.of(new TransitionEvent(EVENT_ID, payload))));
    }
    private static CommandDescriptor.Field field(String name, TransitionScalars.Type type) {
        return new CommandDescriptor.Field(name, type, true, CommandDescriptor.Role.DATA);
    }
}
