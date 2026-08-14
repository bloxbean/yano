package com.bloxbean.cardano.yano.api.appchain.state;

/** Immutable single-use candidate result awaiting the shared durable commit. */
public interface PreparedStateCommit extends AutoCloseable {
    StateCommitmentIdentity identity();

    long baseHeight();

    byte[] baseRoot();

    long targetHeight();

    byte[] stateRoot();

    int mutationCount();

    boolean staged();

    @Override
    void close();
}
