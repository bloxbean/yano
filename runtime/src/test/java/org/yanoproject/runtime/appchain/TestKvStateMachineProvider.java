package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateWriter;

import java.nio.charset.StandardCharsets;

/**
 * Example custom state machine registered via ServiceLoader (the same
 * mechanism plugin jars use): interprets each opaque body as "key=value"
 * text and writes it to the state trie.
 */
public class TestKvStateMachineProvider implements AppStateMachineProvider {

    public static final String ID = "test-kv";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AppStateMachine create() {
        return new AppStateMachine() {
            @Override
            public String id() {
                return ID;
            }

            @Override
            public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
            AppBlock block = context.block();
                for (AppMessage message : block.messages()) {
                    String body = new String(message.getBody(), StandardCharsets.UTF_8);
                    int eq = body.indexOf('=');
                    if (eq > 0) {
                        writer.put(body.substring(0, eq).getBytes(StandardCharsets.UTF_8),
                                body.substring(eq + 1).getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        };
    }
}
