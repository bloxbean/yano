package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import org.rocksdb.WriteBatch;

import java.util.List;

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
    record Applied(AppBlock block, byte[] blockHash, FxKernel.Result fx,
                   ObservationKernel.Result observations) {
    }

    private FxBlockApplier() {
    }

    /** Mirrors AppChainEngine.applyBlock + stageFx + commitBlock in one immediate step. */
    static Applied applyAndCommit(AppLedgerStore store, FxKernel kernel,
                                  AppStateMachine machine, AppBlock block) {
        return applyAndCommit(store, (candidateMachine, context, state, fxReader,
                                      observationReader) -> new SystemInputKernel.Result(
                kernel.apply(candidateMachine, context, state, fxReader),
                ObservationKernel.Result.NONE), machine, block);
    }

    static Applied applyAndCommit(AppLedgerStore store, SystemInputKernel kernel,
                                  AppStateMachine machine, AppBlock block) {
        return applyAndCommit(store, kernel::apply, machine, block);
    }

    private static Applied applyAndCommit(AppLedgerStore store, Kernel kernel,
                                          AppStateMachine machine, AppBlock block) {
        FxKernel.FxReader reader = store.fxReader();
        WriteBatch batch = new WriteBatch();
        CandidateState candidate = null;
        StagedStateCommit stateCommit = null;
        try {
            long baseHeight = store.tipHeight();
            byte[] committedRoot = store.stateRoot();
            byte[] baseRoot = committedRoot != null ? committedRoot : new byte[32];
            candidate = store.stateBackend().beginCandidate(baseHeight, baseRoot, block.height());
            StateCommitmentGuard stateGuard = new StateCommitmentGuard(
                    store.stateCommitmentIdentity());
            stateGuard.apply(block.height(), candidate);
            AppBlockExecutionContext context = AppBlockExecutionContext.fromValidatedBlock(block);
            SystemInputKernel.Result result = kernel.apply(
                    machine, context, candidate, reader, store.observationReader());
            var prepared = candidate.prepare();
            if (!(prepared instanceof StagedStateCommit staged)) {
                prepared.close();
                throw new IllegalStateException(
                        "authenticated-state backend cannot join the ledger WriteBatch");
            }
            stateCommit = staged;
            byte[] effectiveRoot = stateCommit.stateRoot();
            AppBlock applied = new AppBlock(block.version(), block.chainId(), block.height(),
                    block.consensusContextDigest(), block.view(), block.prevHash(),
                    block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                    block.messagesRoot(), effectiveRoot, block.messages(), block.proposer(),
                    block.justification(), block.cert());
            store.stageFx(batch, block.height(), result.effects());
            store.stageObservations(batch, result.observations());
            byte[] blockHash = AppBlockCodec.blockHash(applied);
            store.commitBlock(applied, blockHash, stateCommit, batch, List.of());
            return new Applied(applied, blockHash, result.effects(), result.observations());
        } catch (Throwable failure) {
            if (failure instanceof Error error) {
                throw error;
            }
            throw new RuntimeException("Failed to apply app block " + block.height(), failure);
        } finally {
            if (stateCommit != null) {
                stateCommit.close();
            } else if (candidate != null) {
                candidate.discard();
            }
            batch.close();
        }
    }

    @FunctionalInterface
    private interface Kernel {
        SystemInputKernel.Result apply(AppStateMachine machine,
                                       AppBlockExecutionContext context,
                                       AppStateWriter state,
                                       FxKernel.FxReader reader,
                                       ObservationKernel.Reader observationReader);
    }
}
