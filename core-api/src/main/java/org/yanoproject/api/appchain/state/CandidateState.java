package org.yanoproject.api.appchain.state;

import org.yanoproject.api.appchain.AppStateWriter;

/** Side-effect-free candidate state with read-your-writes semantics. */
public interface CandidateState extends AppStateWriter, AutoCloseable {
    long baseHeight();

    byte[] baseRoot();

    long targetHeight();

    PreparedStateCommit prepare();

    void discard();

    boolean closed();

    @Override
    default long committedHeight() {
        return baseHeight();
    }

    @Override
    default void close() {
        discard();
    }
}
