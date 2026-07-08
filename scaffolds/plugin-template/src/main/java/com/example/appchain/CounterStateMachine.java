package com.example.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.nio.charset.StandardCharsets;

/**
 * Example custom app-chain state machine: per-key counters incremented by
 * plain-text {@code "<key>"} messages. Replace this logic with your own —
 * the contract is:
 * <ul>
 *   <li>{@link #validate} is a fast, side-effect-free admission check.</li>
 *   <li>{@link #apply} is a DETERMINISTIC transition over a finalized block:
 *       same block + same prior state → byte-identical new state on every
 *       member (followers re-execute and compare state roots). No wall-clock,
 *       no randomness, no external I/O — use {@code block.timestamp()} for
 *       time.</li>
 *   <li>State keys written here become individually provable (MPF).</li>
 * </ul>
 */
public class CounterStateMachine implements AppStateMachine {

    public static final String ID = "counter";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        if (message.getBody() == null || message.getBody().length == 0) {
            return AdmissionResult.reject("empty counter key");
        }
        return AdmissionResult.accept();
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        for (AppMessage message : block.messages()) {
            byte[] key = ("c/" + new String(message.getBody(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            long current = writer.get(key)
                    .map(b -> Long.parseLong(new String(b, StandardCharsets.UTF_8)))
                    .orElse(0L);
            writer.put(key, Long.toString(current + 1).getBytes(StandardCharsets.UTF_8));
        }
    }
}
