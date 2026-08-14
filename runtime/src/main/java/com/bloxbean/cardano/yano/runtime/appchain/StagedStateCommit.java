package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.state.PreparedStateCommit;
import org.rocksdb.WriteBatch;

/** Runtime extension that stages a frozen backend update into the shared ledger batch. */
interface StagedStateCommit extends PreparedStateCommit {
    void stage(WriteBatch batch);
}
