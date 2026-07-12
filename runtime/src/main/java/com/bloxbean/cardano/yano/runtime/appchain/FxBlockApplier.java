package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import org.rocksdb.WriteBatch;

/**
 * Apply-and-commit one block through the FxKernel pipeline against a ledger —
 * the single non-engine entry point used by the conformance harness and
 * tests, so they exercise byte-identical semantics. {@code AppChainEngine}
 * keeps its own structurally different variant because consensus DEFERS the
 * commit (apply at proposal, commit at cert) — any change to the kernel step
 * order must land in both, which the conformance harness itself verifies by
 * comparing roots.
 */
final class FxBlockApplier {

    /** The committed block (state root filled in), its hash, and the kernel result. */
    record Applied(AppBlock block, byte[] blockHash, FxKernel.Result fx) {
    }

    private FxBlockApplier() {
    }

    /** Mirrors AppChainEngine.applyBlock + stageFx + commitBlock in one immediate step. */
    static Applied applyAndCommit(AppLedgerStore store, FxKernel kernel,
                                  AppStateMachine machine, AppBlock block) {
        FxKernel.FxReader reader = store.fxReader();
        WriteBatch batch = new WriteBatch();
        byte[] committedRoot = store.stateRoot();
        try {
            FxKernel.Result[] fx = new FxKernel.Result[1];
            byte[] newRoot = store.mpfNodeStore().withBatch(batch, () -> {
                MpfTrie trie = committedRoot != null
                        ? new MpfTrie(store.mpfNodeStore(), committedRoot)
                        : new MpfTrie(store.mpfNodeStore());
                fx[0] = kernel.apply(machine, block, trie, reader);
                return trie.getRootHash();
            });
            byte[] effectiveRoot = newRoot != null ? newRoot : new byte[32];
            AppBlock applied = new AppBlock(block.version(), block.chainId(), block.height(),
                    block.prevHash(), block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                    block.messagesRoot(), effectiveRoot, block.messages(), block.proposer(),
                    block.cert());
            store.stageFx(batch, block.height(), fx[0]);
            byte[] blockHash = AppBlockCodec.blockHash(applied);
            store.commitBlock(applied, blockHash, effectiveRoot, batch);
            return new Applied(applied, blockHash, fx[0]);
        } finally {
            batch.close();
        }
    }
}
