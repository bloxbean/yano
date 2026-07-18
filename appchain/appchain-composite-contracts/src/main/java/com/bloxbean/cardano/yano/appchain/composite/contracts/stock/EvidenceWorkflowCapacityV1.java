package com.bloxbean.cardano.yano.appchain.composite.contracts.stock;

/**
 * Frozen arithmetic for the stock evidence composite's per-block workflow capacity.
 *
 * <p>The derived reservations are consensus-relevant because they are committed in
 * component and workflow descriptors in the canonical composite profile.</p>
 */
public record EvidenceWorkflowCapacityV1(int workflowsPerBlock) {
    public static final int DEFAULT_CAPACITY = 8;
    public static final int MAX_CAPACITY = 65_536;

    public EvidenceWorkflowCapacityV1 {
        if (workflowsPerBlock < 1 || workflowsPerBlock > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                    "evidence capacity must be between 1 and " + MAX_CAPACITY);
        }
    }

    public int releaseWorkflowEffects() {
        return multiply(2);
    }

    public int notificationWorkflowEffects() {
        return workflowsPerBlock;
    }

    public int gatedEvidenceComponentEffects() {
        return workflowsPerBlock;
    }

    public int directEvidenceComponentEffects() {
        return multiply(2);
    }

    public int totalReservedEffects() {
        return multiply(4);
    }

    public void validateAgainst(int maxBlockMessages, int maxEffectsPerBlock) {
        if (workflowsPerBlock > maxBlockMessages) {
            throw new IllegalArgumentException(
                    "evidence capacity must be <= block.max-messages (" + maxBlockMessages + ")");
        }
        int required = totalReservedEffects();
        if (required > maxEffectsPerBlock) {
            throw new IllegalArgumentException(
                    "evidence capacity requires effects.max-per-block >= " + required);
        }
    }

    private int multiply(int factor) {
        return Math.multiplyExact(workflowsPerBlock, factor);
    }
}
