package com.bloxbean.cardano.yano.api.appchain.transition;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded result of one capability decision.
 *
 * <p>Mutations, one-use consumptions, and receipts are separate so callers can
 * audit application intent, but their keys must be globally unique within the
 * plan. This removes write-order ambiguity and lets commit remain mechanical.</p>
 */
public record TransitionPlan(
        List<StateMutation> mutations,
        List<EffectIntent> effects,
        List<StateMutation> consumptions,
        List<StateMutation> receipts
) {
    public static final int MAX_STATE_OPERATIONS = 32_768;
    public static final int MAX_EFFECTS = 1_048_576;
    public static final int MAX_KEY_BYTES = 4_096;
    public static final int MAX_VALUE_BYTES = 1_048_576;

    public TransitionPlan {
        mutations = immutableMutations(mutations, "mutations");
        consumptions = immutableMutations(consumptions, "consumptions");
        receipts = immutableMutations(receipts, "receipts");
        effects = effects == null ? List.of() : List.copyOf(effects);
        int stateOperations = mutations.size() + consumptions.size() + receipts.size();
        if (stateOperations > MAX_STATE_OPERATIONS) {
            throw new IllegalArgumentException("transition plan exceeds state-operation limit");
        }
        if (effects.size() > MAX_EFFECTS || effects.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("transition plan has invalid effects");
        }
        HashSet<BytesKey> keys = new HashSet<>();
        for (StateMutation mutation : all(mutations, consumptions, receipts)) {
            if (mutation.key().length > MAX_KEY_BYTES
                    || mutation.value() != null && mutation.value().length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("transition plan exceeds key/value byte limits");
            }
            if (!keys.add(new BytesKey(mutation.key()))) {
                throw new IllegalArgumentException("transition plan contains a duplicate state key");
            }
        }
    }

    public static TransitionPlan mutations(List<StateMutation> mutations) {
        return new TransitionPlan(mutations, List.of(), List.of(), List.of());
    }

    public static TransitionPlan empty() {
        return mutations(List.of());
    }

    private static List<StateMutation> immutableMutations(
            List<StateMutation> values,
            String field
    ) {
        if (values == null) return List.of();
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null");
        }
        return List.copyOf(values);
    }

    @SafeVarargs
    private static List<StateMutation> all(List<StateMutation>... groups) {
        List<StateMutation> all = new ArrayList<>();
        for (List<StateMutation> group : groups) all.addAll(group);
        return all;
    }

    private record BytesKey(byte[] value) {
        private BytesKey { value = value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof BytesKey that && java.util.Arrays.equals(value, that.value);
        }
        @Override public int hashCode() { return java.util.Arrays.hashCode(value); }
    }
}
