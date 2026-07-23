package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.util.Objects;

/**
 * Result returned by an optional validity commitment engine.
 *
 * @param root proof-friendly root after the transition
 * @param witnessDescriptor canonical non-secret descriptor for asynchronous proving
 */
public record EutxoValidityCommitment(byte[] root, byte[] witnessDescriptor) {
    public EutxoValidityCommitment {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(witnessDescriptor, "witnessDescriptor");
        if (root.length != 32) {
            throw new IllegalArgumentException("validity root must contain 32 bytes");
        }
        if (witnessDescriptor.length == 0 || witnessDescriptor.length > 16 * 1024) {
            throw new IllegalArgumentException(
                    "validity witness descriptor must contain 1-16384 bytes");
        }
        root = root.clone();
        witnessDescriptor = witnessDescriptor.clone();
    }

    @Override
    public byte[] root() {
        return root.clone();
    }

    @Override
    public byte[] witnessDescriptor() {
        return witnessDescriptor.clone();
    }
}
