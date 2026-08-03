package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import java.util.Objects;

/**
 * Conservative next-action planner for the bounded proof-withdrawal flow.
 *
 * <p>After proof verification, either root or withdrawal transaction may be
 * relayed by any party. Before a proof exists, this profile cannot fabricate
 * the private witness and therefore reports a hard wait.</p>
 */
public final class EutxoExitRecoveryPlanner {
    public Plan plan(
            EutxoZkClient.Status artifacts,
            boolean validityRootAccepted,
            boolean withdrawalStable
    ) {
        Objects.requireNonNull(artifacts, "artifacts");
        return switch (artifacts.state()) {
            case NOT_FOUND, WAITING_FOR_DATA ->
                    new Plan(Action.WAIT_FOR_DATA, false);
            case WAITING_FOR_PROOF ->
                    new Plan(Action.WAIT_FOR_PROOF, false);
            case WAITING_FOR_KEY ->
                    new Plan(Action.WAIT_FOR_KEY, false);
            case INVALID ->
                    new Plan(Action.HALT_INVALID_ARTIFACT, false);
            case VERIFIED -> {
                if (!validityRootAccepted) {
                    yield new Plan(Action.RELAY_VALIDITY_ROOT, true);
                }
                if (!withdrawalStable) {
                    yield new Plan(Action.RELAY_WITHDRAWAL, true);
                }
                yield new Plan(Action.COMPLETE, true);
            }
        };
    }

    public enum Action {
        WAIT_FOR_DATA,
        WAIT_FOR_PROOF,
        WAIT_FOR_KEY,
        HALT_INVALID_ARTIFACT,
        RELAY_VALIDITY_ROOT,
        RELAY_WITHDRAWAL,
        COMPLETE
    }

    public record Plan(Action action, boolean permissionless) {
        public Plan {
            Objects.requireNonNull(action, "action");
        }
    }
}
