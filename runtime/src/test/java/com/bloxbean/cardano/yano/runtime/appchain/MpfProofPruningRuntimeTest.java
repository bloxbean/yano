package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MpfProofPruningRuntimeTest {
    private static final StateCommitmentIdentity IDENTITY =
            StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF,
                    repeated(0x28, 32));
    private static final byte[] ABSENT = bytes("never-present");

    @Test
    void pruningRetainsEverySelectedRootAcrossRestartAndRecoversInterruptedMarksCf(
            @TempDir Path directory) throws Exception {
        Path path = directory.resolve("ledger");
        byte[][] retainedRoots = new byte[4][];

        try (AppLedgerStore ledger = ledger(path)) {
            for (int height = 1; height <= 8; height++) {
                commit(ledger, height);
                if (height >= 5) {
                    retainedRoots[height - 5] = ledger.stateRoot();
                }
            }
            assertProofs(ledger, 1, 8);

            int removed = ledger.pruneStateProofsBefore(5);

            assertThat(removed).isPositive();
            assertThat(ledger.stateBackend().oldestProvableHeight()).isEqualTo(5);
            assertThat(ledger.stateBackend().snapshot(4)).isEmpty();
            assertThat(ledger.stateBackend().prove(4, bytes("key-1"))).isEmpty();
            assertProofs(ledger, 5, 8);
            for (int index = 0; index < retainedRoots.length; index++) {
                assertThat(ledger.stateBackend().snapshot(index + 5).orElseThrow().stateRoot())
                        .isEqualTo(retainedRoots[index]);
            }

            // A candidate retains the shared read stamp through its prepared commit. GC must
            // not sweep a node that an in-flight block is about to reference or persist.
            CandidateState candidate = ledger.stateBackend().beginCandidate(
                    8, ledger.stateRoot(), 9);
            candidate.put(bytes("candidate"), bytes("prepared"));
            StagedStateCommit prepared = (StagedStateCommit) candidate.prepare();
            var executor = Executors.newSingleThreadExecutor();
            try {
                var pruning = executor.submit(() -> ledger.pruneStateProofsBefore(6));
                Thread.sleep(100);
                assertThat(pruning).isNotDone();
                prepared.close();
                assertThat(pruning.get(10, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(0);
            } finally {
                prepared.close();
                executor.shutdownNow();
            }

            try (ColumnFamilyOptions options = new ColumnFamilyOptions();
                 ColumnFamilyHandle ignored = ledger.mpfNodeStore().db().createColumnFamily(
                         new ColumnFamilyDescriptor(
                                 bytes("marks_interrupted_process_test"), options))) {
                // Closing the handle without dropping the family simulates process death after
                // CCL created its on-disk mark set.
            }
        }

        try (AppLedgerStore reopened = ledger(path)) {
            assertThat(reopened.stateBackend().oldestProvableHeight()).isEqualTo(6);
            assertThat(reopened.stateBackend().snapshot(5)).isEmpty();
            assertProofs(reopened, 6, 8);
            assertThat(reopened.stateBackend().verifyIntegrity().valid()).isTrue();
        }
        try (Options options = new Options()) {
            assertThat(RocksDB.listColumnFamilies(options, path.toString()).stream()
                    .map(name -> new String(name, StandardCharsets.UTF_8)))
                    .noneMatch(name -> name.startsWith("marks_"));
        }
    }

    @Test
    void watermarkIsMonotonicAndCurrentRootCanNeverBePruned(@TempDir Path directory) {
        try (AppLedgerStore ledger = ledger(directory.resolve("ledger"))) {
            commit(ledger, 1);
            commit(ledger, 2);
            ledger.pruneStateProofsBefore(2);

            assertThatThrownBy(() -> ledger.pruneStateProofsBefore(1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot restore");
            assertThatThrownBy(() -> ledger.pruneStateProofsBefore(3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finalized tip");
        }
    }

    @Test
    void incompleteGcAtTheVisibleWatermarkIsRetried(@TempDir Path directory) {
        try (AppLedgerStore ledger = ledger(directory.resolve("ledger"))) {
            commit(ledger, 1);
            commit(ledger, 2);
            commit(ledger, 3);

            // Simulate a process stopping after the fail-safe visible watermark was
            // persisted but before mark/sweep and its completion watermark finished.
            ledger.advanceMpfProofPruneWatermark(2);
            assertThat(ledger.mpfProofPruneWatermark()).isEqualTo(2);
            assertThat(ledger.mpfProofGcCompletedWatermark()).isZero();

            assertThat(ledger.pruneStateProofsBefore(2)).isGreaterThanOrEqualTo(0);

            assertThat(ledger.mpfProofGcCompletedWatermark()).isEqualTo(2);
            assertThat(ledger.stateBackend().snapshot(1)).isEmpty();
            assertProofs(ledger, 2, 3);
        }
    }

    private static void assertProofs(AppLedgerStore ledger, int first, int last) {
        for (int height = first; height <= last; height++) {
            byte[] root = ledger.stateBackend().snapshot(height).orElseThrow().stateRoot();
            StateProof inclusion = ledger.stateBackend().prove(height, bytes("key-1"))
                    .orElseThrow();
            StateProof absence = ledger.stateBackend().prove(height, ABSENT).orElseThrow();
            MpfTrie verifier = new MpfTrie(ledger.mpfNodeStore(), root);
            assertThat(verifier.verifyProofWire(root, inclusion.canonicalKey(),
                    inclusion.value(), true, inclusion.nativeProof())).isTrue();
            assertThat(verifier.verifyProofWire(root, ABSENT, null, false,
                    absence.nativeProof())).isTrue();
        }
    }

    private static void commit(AppLedgerStore ledger, long height) {
        byte[] baseRoot = ledger.stateRoot() != null ? ledger.stateRoot() : new byte[32];
        CandidateState candidate = ledger.stateBackend().beginCandidate(
                height - 1, baseRoot, height);
        StagedStateCommit prepared = null;
        try {
            new StateCommitmentGuard(IDENTITY).apply(height, candidate);
            candidate.put(bytes("key-" + height), bytes("value-" + height));
            candidate.put(bytes("changing"), bytes("at-" + height));
            prepared = (StagedStateCommit) candidate.prepare();
            List<com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage> messages =
                    List.of();
            byte[] previousHash = height == 1
                    ? AppBlock.GENESIS_PREV_HASH : ledger.tipHash();
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "mpf-pruning", height,
                    previousHash, height, repeated((int) height, 32),
                    1_700_000_000_000L + height, AppBlockCodec.messagesRoot(messages),
                    prepared.stateRoot(), messages, new byte[32], FinalityCert.empty());
            try (WriteBatch batch = new WriteBatch()) {
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), prepared, batch,
                        List.of());
            }
        } finally {
            if (prepared != null) {
                prepared.close();
            } else {
                candidate.discard();
            }
        }
    }

    private static AppLedgerStore ledger(Path path) {
        return new AppLedgerStore(path.toString(),
                LoggerFactory.getLogger("mpf-pruning-test"), IDENTITY);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repeated(int value, int length) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
