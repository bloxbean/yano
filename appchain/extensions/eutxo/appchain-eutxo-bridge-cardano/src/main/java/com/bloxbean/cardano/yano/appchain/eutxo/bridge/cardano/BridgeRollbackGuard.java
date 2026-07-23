package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

/**
 * Fail-closed deep-rollback decision used by reconciliation and operator
 * tooling. The caller must persist/sequence the returned halt; this class does
 * not mutate app-chain state from a node-local callback.
 */
public final class BridgeRollbackGuard {
    private BridgeRollbackGuard() {
    }

    public static Decision assess(long rollbackToSlot, long highestCreditedSlot) {
        if (rollbackToSlot < 0 || highestCreditedSlot < 0) {
            throw new IllegalArgumentException("rollback slots cannot be negative");
        }
        return rollbackToSlot < highestCreditedSlot
                ? new Decision(true, "DEEP_ROLLBACK_BELOW_CREDITED_DEPOSIT")
                : new Decision(false, "NO_CREDITED_DEPOSIT_ROLLED_BACK");
    }

    public record Decision(boolean halt, String code) {
    }
}
