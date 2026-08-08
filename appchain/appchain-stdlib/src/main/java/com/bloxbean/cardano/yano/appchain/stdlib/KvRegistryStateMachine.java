package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionContext;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionDecision;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionPlans;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.KvRegistryContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Standard-library state machine {@code kv-registry} (ADR app-layer/006 E2.1):
 * a replicated registry with per-key ownership — the first member to write a
 * key becomes its owner; only the owner may update or delete it. Every entry
 * is individually provable (key → MPF inclusion proof of [owner, value]).
 * <p>
 * Command body (CBOR): {@code [op(uint), key(bstr), value(bstr)]}
 * — op 0 = PUT, op 1 = DELETE (value empty).
 * <p>
 * State entry (CBOR): {@code key → [owner(bstr .size 32), value(bstr)]}.
 * <p>
 * Optional value-format check (ADR app-layer/008.1 I1.4, config
 * {@code machines.kv-registry.value-format = raw | cbor | utf8}): a PUT whose
 * value does not conform is rejected at admission and is a deterministic
 * no-op in {@link #apply} (consensus-enforced, same posture as ownership).
 * <p>
 * Use cases: token/metadata registries, DID documents, allow/deny lists,
 * shared configuration.
 */
public final class KvRegistryStateMachine implements AppStateMachine {

    public static final String ID = "kv-registry";
    public static final int OP_PUT = KvRegistryTransitions.OP_PUT;
    public static final int OP_DELETE = KvRegistryTransitions.OP_DELETE;

    private static final Logger log = LoggerFactory.getLogger(KvRegistryStateMachine.class);

    private final KvRegistryTransitions transitions;

    public KvRegistryStateMachine() {
        this(KvRegistryTransitions.ValueFormat.RAW);
    }

    public KvRegistryStateMachine(KvRegistryTransitions.ValueFormat valueFormat) {
        this.transitions = new KvRegistryTransitions(valueFormat);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        try {
            transitions.decodeCommand(message.getBody());
            return AdmissionResult.accept();
        } catch (Exception e) {
            return AdmissionResult.reject("Malformed kv-registry command: expected cbor [op, key, value]");
        }
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                      AppEffectEmitter effects) {
        AppBlock block = context.block();
        int visibleIndex = 0;
        for (AppMessage message : context.messages()) {
            int originalIndex = context.originalMessageIndex(visibleIndex++);
            KvRegistryTransitions.Command command;
            try {
                command = transitions.decodeCommand(message.getBody());
            } catch (Exception e) {
                // Should have been filtered at admission; skip deterministically
                continue;
            }
            TransitionDecision decision = transitions.decide(command,
                    TransitionContext.of(block, originalIndex, message),
                    new KvRegistryTransitions.Facts(writer.get(command.key())));
            if (!TransitionPlans.commitIfApproved(decision, writer, effects)) {
                log.debug("kv-registry: transition rejected at block {} ({})",
                        block.height(), ((TransitionDecision.Rejected) decision).rejection().code());
            }
        }
    }

    /** Client/helper: encode a PUT command body. */
    public static byte[] put(byte[] key, byte[] value) {
        return KvRegistryContract.put(key, value);
    }

    /** Client/helper: encode a DELETE command body. */
    public static byte[] delete(byte[] key) {
        return KvRegistryContract.delete(key);
    }

    /** Decode a state entry into [owner, value]. */
    public static byte[] decodeOwner(byte[] entry) {
        return KvRegistryTransitions.decodeOwner(entry);
    }

    public static byte[] decodeValue(byte[] entry) {
        return KvRegistryTransitions.decodeValue(entry);
    }
}
