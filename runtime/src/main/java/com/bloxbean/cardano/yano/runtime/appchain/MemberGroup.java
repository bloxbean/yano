package com.bloxbean.cardano.yano.runtime.appchain;

import java.util.Locale;
import java.util.Set;

/**
 * The chain's effective member set + finality threshold, shared (mutable,
 * volatile) between the subsystem and the engine so staged key rotation
 * (ADR app-layer/006 E4.5) can take effect at runtime without a restart.
 * Updates are operator-driven admin actions applied on every node
 * (out-of-band coordination — see the rotation runbook); rotated state is
 * persisted in the app ledger meta and survives restarts, taking precedence
 * over the static config. Interim measure until chain-governed membership
 * (ADR 005 D6) ships.
 */
final class MemberGroup {

    private volatile Set<String> members;
    private volatile int threshold;

    MemberGroup(Set<String> members, int threshold) {
        this.members = Set.copyOf(members);
        this.threshold = Math.max(1, threshold);
    }

    Set<String> members() {
        return members;
    }

    int threshold() {
        return threshold;
    }

    int size() {
        return members.size();
    }

    boolean contains(String publicKeyHex) {
        return members.contains(publicKeyHex.toLowerCase(Locale.ROOT));
    }

    /** Atomic swap; callers validate invariants (threshold <= size etc.) first. */
    void update(Set<String> newMembers, int newThreshold) {
        this.members = Set.copyOf(newMembers);
        this.threshold = Math.max(1, newThreshold);
    }
}
