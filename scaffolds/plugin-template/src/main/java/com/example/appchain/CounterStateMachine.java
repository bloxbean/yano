package com.example.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
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
            byte[] key = ("c/" + new String(message.getBody(), StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
            long current = writer.get(key)
                    .map(b -> Long.parseLong(new String(b, StandardCharsets.UTF_8)))
                    .orElse(0L);
            writer.put(key, Long.toString(current + 1).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Off-consensus, read-only query over one root-fixed committed snapshot. */
    @Override
    public byte[] query(String path, byte[] params, AppQueryContext context) {
        if (!"counter/read".equals(path)) {
            throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                    "unknown counter query");
        }
        if (!isUnreservedAscii(params)) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "counter key must be 1-64 unreserved ASCII bytes");
        }
        byte[] key = new byte[2 + params.length];
        key[0] = 'c';
        key[1] = '/';
        System.arraycopy(params, 0, key, 2, params.length);
        return context.get(key).orElseGet(() -> "0".getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isUnreservedAscii(byte[] value) {
        if (value == null || value.length == 0 || value.length > 64) {
            return false;
        }
        for (byte next : value) {
            int character = Byte.toUnsignedInt(next);
            if (!((character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '.'
                    || character == '_' || character == '~')) {
                return false;
            }
        }
        return true;
    }
}
