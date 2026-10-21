package org.yanoproject.runtime.validation.shadowsync;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What shadow sync needs from the node before it starts (ADR-056 Phase 7a): the account/governance store and the
 * UTxO store (the pre-block view reads both), with UTxO applied synchronously inside the canonical write section (an
 * asynchronous apply makes every capture unavailable). When one is missing, shadow sync is not started and the node
 * logs why, instead of reporting every block as not validated. It never stops the node.
 */
public final class ShadowSyncPreconditions {

    private ShadowSyncPreconditions() {
    }

    /**
     * @param accountStateEnabled the account/governance store is enabled ({@code yano.account-state.enabled})
     * @param utxoEnabled         the UTxO store is enabled ({@code yano.utxo.enabled})
     * @param utxoApplyAsync      UTxO apply runs off the write section ({@code yano.utxo.applyAsync=true})
     * @return why shadow sync cannot run, or empty when it can
     */
    public static Optional<String> unmetReason(boolean accountStateEnabled, boolean utxoEnabled,
                                               boolean utxoApplyAsync) {
        List<String> reasons = new ArrayList<>();
        if (!accountStateEnabled) {
            reasons.add("account state is disabled (yano.account-state.enabled=false)");
        }
        if (!utxoEnabled) {
            reasons.add("the UTxO store is disabled (yano.utxo.enabled=false)");
        } else if (utxoApplyAsync) {
            reasons.add("UTxO apply is asynchronous (yano.utxo.applyAsync=true), so no pre-block state can be captured");
        }
        return reasons.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", reasons));
    }
}
