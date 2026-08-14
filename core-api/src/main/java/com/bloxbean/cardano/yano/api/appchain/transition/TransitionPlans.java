package com.bloxbean.cardano.yano.api.appchain.transition;

import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;

import java.util.List;
import java.util.Objects;

/** Mechanical commit adapter for an already-approved transition plan. */
public final class TransitionPlans {
    private TransitionPlans() {
    }

    public static void commit(
            TransitionPlan plan,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(effects, "effects");
        apply(plan.mutations(), writer);
        apply(plan.consumptions(), writer);
        apply(plan.receipts(), writer);
        plan.effects().forEach(effects::emit);
    }

    public static boolean commitIfApproved(
            TransitionDecision decision,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        if (decision instanceof TransitionDecision.Approved approved) {
            commit(approved.plan(), writer, effects);
            return true;
        }
        return false;
    }

    private static void apply(List<StateMutation> mutations, AppStateWriter writer) {
        for (StateMutation mutation : mutations) {
            if (mutation.kind() == StateMutation.Kind.PUT) {
                writer.put(mutation.key(), mutation.value());
            } else {
                writer.delete(mutation.key());
            }
        }
    }
}
