package org.yanoproject.ledger.amaru.runtime;

/**
 * One instance of the module: single-threaded, used by one worker thread at a time. Every call either
 * returns the response payload or throws; after a throw the instance must be discarded
 * (ADR-057 invariant 4).
 */
public interface AmaruInstance extends AutoCloseable {

    /** @return the module's {@code abi_version} */
    int abiVersion();

    /** @return the module's {@code amaru_version} text (tag, commit, toolchain) */
    String amaruVersion();

    /** Calls {@code required_keys(tx, env)} and returns the response document. */
    byte[] requiredKeys(byte[] transaction, byte[] env);

    /** Calls {@code validate(request)} and returns the response document. */
    byte[] validate(byte[] request);

    @Override
    void close();
}
