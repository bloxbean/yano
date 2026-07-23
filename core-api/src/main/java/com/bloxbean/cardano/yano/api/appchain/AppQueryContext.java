package com.bloxbean.cardano.yano.api.appchain;

/**
 * Root-fixed committed-state view supplied for one state-machine query.
 *
 * <p>The context is valid only while the query callback is executing. A
 * runtime rejects every read after the callback returns, including reads from
 * plugin-created threads that retained the instance. Query implementations
 * must not start child work that outlives the callback.</p>
 */
public interface AppQueryContext extends AppStateReader {

    /** Finalized app-chain height whose post-state is exposed by this context. */
    long committedHeight();
}
