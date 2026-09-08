package com.bloxbean.cardano.yano.api.archive;

import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;

/**
 * Produces projection sections during canonical block application (ADR-039 §8).
 *
 * <p>Implementations are called <em>inside</em> a contributing subsystem's existing
 * write batch and must only stage records through the supplied
 * {@link ProjectionStagingWriter}. They must not open archive sessions, wait on an
 * archive writer, or scan a large artifact: projection construction is allowed on the
 * hot path, sink I/O is not.
 *
 * <p>{@link #NOOP} is what a history-disabled node uses, so the cost of the hook is one
 * predictable {@code enabled()} check that returns a constant.
 */
public interface CanonicalProjectionContributor {

    /** False for a history-disabled node; callers skip all projection work. */
    boolean enabled();

    /**
     * Contribute the Shelley-and-later sections for an already-decoded block.
     * Called with the caller's batch still open, before it is committed.
     */
    void contributeBlock(BlockAppliedEvent event, ProjectionStagingWriter writer);

    /**
     * Whether this contributor needs the addresses of the outputs the block consumed.
     *
     * <p>Only the address-transaction section does. Answering false lets the caller skip
     * collecting them entirely, so a node projecting the other sections pays nothing for a
     * dataset it does not produce.
     */
    default boolean needsConsumedOutputAddresses() {
        return false;
    }

    /**
     * Contribute with consumed-output addresses available.
     *
     * <p>Defaults to the plain form, so a contributor that does not need them — and a caller
     * that has not collected them — both keep working unchanged.
     */
    default void contributeBlock(BlockAppliedEvent event, ConsumedOutputAddresses consumed,
                                 ProjectionStagingWriter writer) {
        contributeBlock(event, writer);
    }

    /**
     * Contribute an already-decoded Byron epoch-boundary block. It may contribute an
     * empty envelope when the coordinate is not already occupied by a main block.
     */
    void contributeByronBlock(ByronBlockProjectionEvent event, ProjectionStagingWriter writer);

    /**
     * Contribute a Byron main block inside the UTXO store's canonical write batch.
     *
     * <p>The default preserves source compatibility for custom contributors that do not
     * support Byron history.
     */
    default void contributeByronMainBlock(ByronMainBlockAppliedEvent event,
                                          ConsumedOutputAddresses consumed,
                                          ProjectionStagingWriter writer) {
    }

    /**
     * Refresh any cached native storage handles after the chain-state database is replaced.
     *
     * <p>Devnet snapshot restore closes and reopens RocksDB. Contributors that retain outbox
     * handles must rebind them before canonical application or background draining resumes.
     */
    default void reinitializeAfterSnapshotRestore() {
    }

    /**
     * Report a contribution failure after the caller has discarded this block's staged
     * projection writes and chosen to continue canonical L1 application. The supplied writer
     * addresses the caller's restored savepoint, allowing a durable failure marker to commit
     * with L1 state.
     *
     * <p>The default is deliberately inert so projection failure reporting can never become a
     * second reason for block application to fail.
     */
    default void contributionFailed(long blockNumber, ProjectionStagingWriter writer,
                                    RuntimeException failure) {
    }

    /** Drop pending envelopes at or above {@code fromBlockNumber} and rewind cursors. */
    void rollbackFrom(long fromBlockNumber);

    CanonicalProjectionContributor NOOP = new CanonicalProjectionContributor() {
        @Override public boolean enabled() { return false; }
        @Override public void contributeBlock(BlockAppliedEvent event, ProjectionStagingWriter writer) { }
        @Override public boolean needsConsumedOutputAddresses() { return false; }
        @Override public void contributeByronBlock(ByronBlockProjectionEvent event, ProjectionStagingWriter writer) { }
        @Override public void rollbackFrom(long fromBlockNumber) { }
    };
}
