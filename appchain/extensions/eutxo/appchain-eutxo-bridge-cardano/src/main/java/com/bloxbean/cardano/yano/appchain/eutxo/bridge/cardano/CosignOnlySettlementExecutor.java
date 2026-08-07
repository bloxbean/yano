package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;

import java.util.Set;

/**
 * ADR-UTXO-009 SP-M6: the non-owner member's settlement product. Under
 * single-owner pinning only {@code owner=true} drives {@code l1.settlement};
 * every other member still builds the co-sign service (registered by the
 * factory) but must own no effect type — otherwise the members would race to
 * submit the same batch.
 *
 * <p>It exists because a chain with {@code effects.executor.enabled=true} and
 * zero executor products fails activation. Declaring no {@link #effectTypes()}
 * routes it to the legacy-predicate lane, where {@link #supports(String)}
 * declines everything, so the Effect Runtime never dispatches to it.
 */
final class CosignOnlySettlementExecutor implements AppEffectExecutor {
    private final String id;

    CosignOnlySettlementExecutor(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    /** Owns nothing: this member co-signs, it does not settle. */
    @Override
    public Set<String> effectTypes() {
        return Set.of();
    }

    @Override
    public boolean supports(String effectType) {
        return false;
    }

    @Override
    public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) {
        throw new IllegalStateException("co-sign-only settlement executor '" + id
                + "' owns no effect type and must never be dispatched to (got '"
                + (effect == null ? "null" : effect.type()) + "')");
    }
}
