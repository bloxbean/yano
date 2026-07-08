package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

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
            public void apply(AppBlock block, AppStateWriter writer) {
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
