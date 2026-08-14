package com.bloxbean.cardano.yano.api.appchain;

import java.util.Objects;

/**
 * Result of one bounded state-machine query against a committed app-chain root.
 *
 * @param chainId         chain whose committed state was queried
 * @param stateMachineId  state-machine implementation that produced the payload
 * @param committedHeight finalized height whose post-state was queried
 * @param stateRoot       committed post-state root at {@code committedHeight}
 * @param payload         opaque state-machine response bytes
 */
public record AppQueryResult(
        String chainId,
        String stateMachineId,
        long committedHeight,
        byte[] stateRoot,
        byte[] payload
) {

    public AppQueryResult {
        chainId = requireIdentifier(chainId, "chainId");
        stateMachineId = requireIdentifier(stateMachineId, "stateMachineId");
        if (committedHeight < 0) {
            throw new IllegalArgumentException("committedHeight must not be negative");
        }
        Objects.requireNonNull(stateRoot, "stateRoot");
        if (stateRoot.length != 32) {
            throw new IllegalArgumentException("stateRoot must contain exactly 32 bytes");
        }
        Objects.requireNonNull(payload, "payload");
        stateRoot = stateRoot.clone();
        payload = payload.clone();
    }

    @Override
    public byte[] stateRoot() {
        return stateRoot.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    private static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
