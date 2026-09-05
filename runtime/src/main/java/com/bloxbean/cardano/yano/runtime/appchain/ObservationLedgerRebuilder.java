package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;

import java.util.Arrays;
import java.util.Objects;

/**
 * Offline recovery into a separate empty ledger. The caller supplies a fresh,
 * initialized state machine and the original profile/membership-aware kernel.
 * No acquisition runtime is started and replay never modifies the source.
 * The destination is an index-repair artifact, not a runnable node ledger:
 * it does not contain the original node's consensus/signing safety journals.
 */
final class ObservationLedgerRebuilder {
    private ObservationLedgerRebuilder() { }

    static long replay(AppLedgerStore source, AppLedgerStore destination,
                       SystemInputKernel kernel, AppStateMachine machine) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        if (source == destination || destination.tipHeight() != 0) {
            throw new IllegalArgumentException("Observation rebuild requires a separate empty ledger");
        }
        if (!source.stateCommitmentIdentity().equals(destination.stateCommitmentIdentity())) {
            throw new IllegalArgumentException("Observation rebuild state identity mismatch");
        }
        destination.metaPutBytesSync(AppLedgerStore.OBS_REBUILD_CANDIDATE, new byte[]{1});
        long targetHeight = source.tipHeight();
        for (long height = 1; height <= targetHeight; height++) {
            long current = height;
            AppBlock original = source.block(height).orElseThrow(() ->
                    new IllegalStateException("Missing finalized block " + current + "; cannot rebuild"));
            FxBlockApplier.Applied replayed = FxBlockApplier.applyAndCommit(
                    destination, kernel, machine, original);
            if (!Arrays.equals(original.stateRoot(), replayed.block().stateRoot())) {
                throw new IllegalStateException("Observation rebuild diverged at height " + height
                        + "; reject this destination and retain the source");
            }
        }
        if (source.tipHeight() != targetHeight) {
            throw new IllegalStateException("Source advanced during offline observation rebuild");
        }
        destination.verifyObservationIndexes();
        return targetHeight;
    }

    /** Explicit offline install preserves every non-observation-index column family. */
    static void install(AppLedgerStore candidate, AppLedgerStore original) {
        if (candidate.metaBytes(AppLedgerStore.OBS_REBUILD_CANDIDATE) == null
                || candidate == original || candidate.tipHeight() != original.tipHeight()
                || !Arrays.equals(candidate.tipHash(), original.tipHash())
                || !Arrays.equals(candidate.stateRoot(), original.stateRoot())) {
            throw new IllegalArgumentException("Observation repair candidate does not match the original ledger tip");
        }
        candidate.verifyObservationIndexes();
        original.replaceObservationIndexesFrom(candidate);
    }
}
