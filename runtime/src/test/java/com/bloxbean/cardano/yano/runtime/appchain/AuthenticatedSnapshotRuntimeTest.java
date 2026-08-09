package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSeriesDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSourceCommitmentV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotCanonicalCodec;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotBuildTokenV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotEntry;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSourceBoundary;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedSnapshotRuntimeTest {
    private static final StateCommitmentIdentity IDENTITY = StateCommitmentIdentity.explicit(
            StateCommitmentProfiles.MPF, repeated(0x28));
    private static final AuthenticatedSnapshotSeriesDescriptorV1 SERIES =
            new AuthenticatedSnapshotSeriesDescriptorV1("daily", "balances-v1",
                    AuthenticatedSnapshotSeriesDescriptorV1.Trigger.APPLICATION_MESSAGE,
                    StateCommitmentProfiles.MPF.id(), StateCommitmentProfiles.MPF.formatFingerprint(),
                    StateCommitmentProfiles.MPF.proofEncodingId(),
                    AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.ON_CHAIN,
                    AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC,
                    "blake2b256", "balances-source-v1", 10, 4096, 256, 8192, 100,
                    AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET);

    @Test
    void descriptorAndSecondaryNodesCommitAtomicallyAndSurviveRestart(@TempDir Path directory) {
        Path path = directory.resolve("ledger");
        byte[] account = bytes("account/alice");
        byte[] value = bytes("100");
        SnapshotDescriptorV1 descriptor;

        try (AppLedgerStore ledger = ledger(path)) {
            var runtime = runtime(ledger);
            CandidateState candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
            new StateCommitmentGuard(IDENTITY).apply(1, candidate);
            var session = runtime.beginBlock(candidate);
            var handle = session.writer().capabilities().snapshotSeries("daily").orElseThrow();
            var token = handle.begin(0, "daily-0", new SnapshotSourceBoundary.AppHeight(1),
                    0, 0, 1, repeated(4), 1, 1);
            handle.appendChunk(token, 0, List.of(new SnapshotEntry(account, value)));
            handle.seal(token);

            try (WriteBatch batch = new WriteBatch()) {
                session.execute(batch, 1);
                StagedStateCommit prepared = (StagedStateCommit) candidate.prepare();
                try {
                    AppBlock block = block(prepared.stateRoot());
                    ledger.commitBlock(block, AppBlockCodec.blockHash(block), prepared, batch, List.of());
                } finally {
                    prepared.close();
                }
            }
            descriptor = SnapshotCanonicalCodec.decodeDescriptor(ledger.stateBackend().get(1,
                    AuthenticatedSnapshotRuntime.descriptorKey("daily", 0)).orElseThrow());
            assertThat(runtime.value(descriptor, account)).contains(value);
            assertThat(runtime.proof(descriptor, account)).isPresent();
        }

        try (AppLedgerStore reopened = ledger(path)) {
            var runtime = runtime(reopened);
            assertThat(runtime.value(descriptor, account)).contains(value);
            assertThat(runtime.proof(descriptor, account)).isPresent();
            assertThat(reopened.stateBackend().verifyIntegrity().valid()).isTrue();
        }
    }

    @Test
    void classicJmtSnapshotProvidesOffchainProofAfterRestart(@TempDir Path directory) {
        Path path = directory.resolve("jmt-ledger");
        byte[] account = bytes("account/bob");
        byte[] value = bytes("200");
        var jmtSeries = series(StateCommitmentProfiles.CLASSIC_JMT.id());
        SnapshotDescriptorV1 descriptor;
        try (AppLedgerStore ledger = ledger(path)) {
            var settings = settings();
            var runtime = AuthenticatedSnapshotRuntime.create(ledger, IDENTITY, settings,
                    List.of(jmtSeries), List.of(source(jmtSeries)), false, "test-chain").orElseThrow();
            CandidateState candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
            new StateCommitmentGuard(IDENTITY).apply(1, candidate);
            var session = runtime.beginBlock(candidate);
            var handle = session.writer().capabilities().snapshotSeries("daily").orElseThrow();
            var token = handle.begin(0, "daily-jmt-0", new SnapshotSourceBoundary.AppHeight(1),
                    0, 0, 1, repeated(4), 1, 1);
            handle.appendChunk(token, 0, List.of(new SnapshotEntry(account, value)));
            handle.seal(token);
            try (WriteBatch batch = new WriteBatch()) {
                session.execute(batch, 1);
                StagedStateCommit prepared = (StagedStateCommit) candidate.prepare();
                try {
                    AppBlock block = block(prepared.stateRoot());
                    ledger.commitBlock(block, AppBlockCodec.blockHash(block), prepared, batch, List.of());
                } finally { prepared.close(); }
            }
            descriptor = SnapshotCanonicalCodec.decodeDescriptor(ledger.stateBackend().get(1,
                    AuthenticatedSnapshotRuntime.descriptorKey("daily", 0)).orElseThrow());
            assertThat(runtime.stateProof(descriptor, account)).isPresent();
        }
        try (AppLedgerStore reopened = ledger(path)) {
            var runtime = AuthenticatedSnapshotRuntime.create(reopened, IDENTITY, settings(),
                    List.of(jmtSeries), List.of(source(jmtSeries)), false, "test-chain").orElseThrow();
            assertThat(runtime.value(descriptor, account)).contains(value);
            assertThat(runtime.stateProof(descriptor, account)).isPresent();
        }
    }

    @Test
    void archiveEvictAndRestorePreserveMpfProofBytes(@TempDir Path directory) {
        Path path = directory.resolve("archive-ledger");
        Path archives = directory.resolve("archives");
        byte[] account = bytes("account/carol");
        byte[] value = bytes("300");

        try (AppLedgerStore ledger = ledger(path)) {
            var runtime = runtime(ledger, archives, true);
            SnapshotDescriptorV1 descriptor = buildSnapshot(ledger, runtime, account, value);
            byte[] before = runtime.stateProof(descriptor, account).orElseThrow().nativeProof();

            Path archive = runtime.archive(descriptor, null);
            assertThat(archive).isRegularFile();
            assertThat(runtime.stateProof(descriptor, account)).isPresent();
            assertThat(runtime.evict(descriptor)).isPositive();
            assertThat(runtime.stateProof(descriptor, account)).isEmpty();

            runtime.restore(descriptor, null);
            var restored = runtime.stateProof(descriptor, account).orElseThrow();
            assertThat(restored.value()).isEqualTo(value);
            assertThat(restored.nativeProof()).isEqualTo(before);
        }
    }

    @Test
    void corruptArchiveNeverPublishesAPartialRestore(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("corrupt-ledger");
        Path archives = directory.resolve("archives");
        byte[] account = bytes("account/dave");
        try (AppLedgerStore ledger = ledger(path)) {
            var runtime = runtime(ledger, archives);
            SnapshotDescriptorV1 descriptor = buildSnapshot(ledger, runtime, account, bytes("400"));
            Path archive = runtime.archive(descriptor, null);
            runtime.evict(descriptor);
            byte[] encoded = Files.readAllBytes(archive);
            encoded[encoded.length - 1] ^= 1;
            Files.write(archive, encoded);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.restore(descriptor, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("digest mismatch");
            assertThat(runtime.stateProof(descriptor, account)).isEmpty();
        }
    }

    @Test
    void archiveIgnoresUnreachableGarbageButRejectsReachableCorruptionOutsideIdentityPath(
            @TempDir Path directory) throws Exception {
        try (AppLedgerStore ledger = ledger(directory.resolve("mpf-integrity-ledger"))) {
            var runtime = runtime(ledger, directory.resolve("archives"), false);
            SnapshotDescriptorV1 descriptor = buildSnapshot(
                    ledger, runtime, bytes("account/orphan"), bytes("450"));
            byte[] storagePrefix = runtime.stateProof(descriptor, bytes("account/orphan"))
                    .orElseThrow().snapshot().identity().genesisId();
            ledger.importSnapshotNodeBatch(storagePrefix, List.of(
                    new AppLedgerStore.SnapshotNodeRecord(repeated(0x77), new byte[]{1})));
            Path archive = runtime.archive(descriptor, null);
            assertThat(archive).isRegularFile();

            Files.delete(archive);
            SnapshotMpfIntegrity.Result reachable = new SnapshotMpfIntegrity(
                    key -> ledger.snapshotNode(java.nio.ByteBuffer.allocate(storagePrefix.length + key.length)
                            .put(storagePrefix).put(key).array()),
                    10_000, 10_000_000).verify(descriptor.snapshotRoot());
            byte[] identityKey = bytes("~snapshot/identity-v1");
            boolean corruptedOutsideIdentityPath = false;
            for (AppLedgerStore.SnapshotNodeRecord record : ledger.snapshotNodes(
                    storagePrefix, 10_000, 10_000_000)) {
                if (java.util.Arrays.equals(record.key(), descriptor.snapshotRoot())
                        || !reachable.contains(record.key())) continue;
                ledger.importSnapshotNodeBatch(storagePrefix, List.of(
                        new AppLedgerStore.SnapshotNodeRecord(record.key(), new byte[]{1})));
                try {
                    if (runtime.proof(descriptor, identityKey).isPresent()) {
                        corruptedOutsideIdentityPath = true;
                        break;
                    }
                } catch (RuntimeException ignored) {
                    ledger.importSnapshotNodeBatch(storagePrefix, List.of(record));
                    continue;
                }
                ledger.importSnapshotNodeBatch(storagePrefix, List.of(record));
            }
            assertThat(corruptedOutsideIdentityPath).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.archive(descriptor, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsReservedPrimaryKeysAndContinuationFromAnotherDraft(@TempDir Path directory) {
        try (AppLedgerStore ledger = ledger(directory.resolve("adversarial-ledger"))) {
            var runtime = runtime(ledger, directory.resolve("archives"));
            CandidateState candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
            new StateCommitmentGuard(IDENTITY).apply(1, candidate);
            var session = runtime.beginBlock(candidate);
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> session.writer().put(
                    bytes("snapshots/v1/daily/00000000000000000000"), bytes("forged"))))
                    .isInstanceOf(IllegalArgumentException.class);

            var handle = session.writer().capabilities().snapshotSeries("daily").orElseThrow();
            handle.begin(0, "daily-0", new SnapshotSourceBoundary.AppHeight(1),
                    0, 0, 1, repeated(4), 1, 1);
            SnapshotBuildTokenV1 forged = new SnapshotBuildTokenV1(0, repeated(0x7f));
            handle.appendChunk(forged, 0, List.of(new SnapshotEntry(bytes("account/x"), bytes("1"))));
            handle.seal(forged);
            try (WriteBatch batch = new WriteBatch()) {
                assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                        session.execute(batch, 1)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("draft digest mismatch");
            }
        }
    }

    @Test
    void catalogPageIsBoundToTheCommittedView(@TempDir Path directory) {
        try (AppLedgerStore ledger = ledger(directory.resolve("catalog-ledger"))) {
            var runtime = runtime(ledger, directory.resolve("archives"));
            buildSnapshot(ledger, runtime, bytes("account/eve"), bytes("500"));
            var page = runtime.list("daily", null, 20, ledger.tipHeight());
            assertThat(page.items()).hasSize(1);
            assertThat(page.viewHeight()).isEqualTo(1);
            assertThat(page.viewRoot()).isEqualTo(ledger.stateRoot());
            assertThat(page.nextCursor()).isNull();
        }
    }

    @Test
    void sealRejectsASourceCommitmentThatDoesNotMatchCanonicalChunks(@TempDir Path directory) {
        try (AppLedgerStore ledger = ledger(directory.resolve("source-ledger"))) {
            AuthenticatedSnapshotSourceCommitmentV1 rejecting = new AuthenticatedSnapshotSourceCommitmentV1() {
                @Override public String seriesId() { return SERIES.seriesId(); }
                @Override public String algorithm() { return SERIES.sourceCommitmentAlgorithm(); }
                @Override public String wireVersion() { return SERIES.sourceCommitmentWireVersion(); }
                @Override public byte[] initial(
                        com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorDraftV1 draft) {
                    return repeated(0x55);
                }
                @Override public byte[] append(byte[] accumulator, long chunkIndex,
                                               List<SnapshotEntry> entries) { return accumulator; }
                @Override public byte[] finish(byte[] accumulator, long chunks, long entries) {
                    return accumulator;
                }
            };
            var runtime = AuthenticatedSnapshotRuntime.create(ledger, IDENTITY,
                    settings(directory.resolve("archives")), List.of(SERIES),
                    List.of(rejecting), true, "test-chain").orElseThrow();
            CandidateState candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
            new StateCommitmentGuard(IDENTITY).apply(1, candidate);
            var session = runtime.beginBlock(candidate);
            var handle = session.writer().capabilities().snapshotSeries("daily").orElseThrow();
            var token = handle.begin(0, "daily-0", new SnapshotSourceBoundary.AppHeight(1),
                    0, 0, 1, repeated(4), 1, 1);
            handle.appendChunk(token, 0, List.of(new SnapshotEntry(bytes("account/x"), bytes("1"))));
            handle.seal(token);
            try (WriteBatch batch = new WriteBatch()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> session.execute(batch, 1))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("source dataset commitment mismatch");
            }
        }
    }

    @Test
    void ordinaryAndSnapshotWritesShareOneLogicalBlockBudget(@TempDir Path directory) {
        try (AppLedgerStore ledger = ledger(directory.resolve("shared-budget-ledger"))) {
            var constrained = new AuthenticatedSnapshotSettings(true, Set.of("daily"),
                    3, 100_000, repeated(9), 32, false, 10, true, 300,
                    false, directory.resolve("archives"), 1000, 10_000_000);
            var runtime = AuthenticatedSnapshotRuntime.create(ledger, IDENTITY, constrained,
                    List.of(SERIES), List.of(source(SERIES)), true, "test-chain").orElseThrow();
            CandidateState candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
            new StateCommitmentGuard(IDENTITY).apply(1, candidate);
            var session = runtime.beginBlock(candidate);
            var handle = session.writer().capabilities().snapshotSeries("daily").orElseThrow();
            var token = handle.begin(0, "daily-0", new SnapshotSourceBoundary.AppHeight(1),
                    0, 0, 1, repeated(4), 1, 1);
            handle.appendChunk(token, 0, List.of(
                    new SnapshotEntry(bytes("account/x"), bytes("1"))));
            handle.seal(token);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    session.writer().put(bytes("ordinary/key"), bytes("ordinary/value")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("shared block limits");
        }
    }

    @Test
    void nodeLocalArchiveLimitsCannotChangeSealConsensus(@TempDir Path directory) {
        try (AppLedgerStore ledger = ledger(directory.resolve("local-archive-limits-ledger"))) {
            var localLimits = new AuthenticatedSnapshotSettings(true, Set.of("daily"),
                    100, 100_000, repeated(9), 32, false, 10, true, 300,
                    false, directory.resolve("archives"), 1, 1);
            var runtime = AuthenticatedSnapshotRuntime.create(ledger, IDENTITY, localLimits,
                    List.of(SERIES), List.of(source(SERIES)), true, "test-chain").orElseThrow();

            SnapshotDescriptorV1 descriptor = buildSnapshot(
                    ledger, runtime, bytes("account/local"), bytes("1"));
            assertThat(descriptor.complete()).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.archive(descriptor, null))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void disputedLineagePersistsAndFailsProofsClosed(@TempDir Path directory) {
        Path path = directory.resolve("disputed-ledger");
        SnapshotDescriptorV1 descriptor;
        try (AppLedgerStore ledger = ledger(path)) {
            var runtime = runtime(ledger, directory.resolve("archives"));
            descriptor = buildSnapshot(ledger, runtime, bytes("account/gina"), bytes("700"));
            runtime.markDisputed("DEEP_ROLLBACK_BELOW_FINALIZED_EPOCH_ATTESTATION");
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    runtime.stateProof(descriptor, bytes("account/gina")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("DISPUTED");
        }
        try (AppLedgerStore ledger = ledger(path)) {
            var runtime = runtime(ledger, directory.resolve("archives"));
            assertThat(runtime.status(ledger.tipHeight()).get("disputed")).isEqualTo(true);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    runtime.stateProof(descriptor, bytes("account/gina")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("DISPUTED");
        }
    }

    @Test
    void restartReconcilesInterruptedRestoreToArchivedOnly(@TempDir Path directory) {
        Path path = directory.resolve("restore-reconcile-ledger");
        Path archives = directory.resolve("archives");
        byte[] account = bytes("account/frank");
        SnapshotDescriptorV1 descriptor;
        byte[] storagePrefix;
        try (AppLedgerStore ledger = ledger(path)) {
            var runtime = runtime(ledger, archives);
            descriptor = buildSnapshot(ledger, runtime, account, bytes("600"));
            storagePrefix = runtime.stateProof(descriptor, account).orElseThrow()
                    .snapshot().identity().genesisId();
            runtime.archive(descriptor, null);
            runtime.evict(descriptor);
            ledger.beginSnapshotRestore(storagePrefix);
        }
        try (AppLedgerStore ledger = ledger(path)) {
            var runtime = runtime(ledger, archives);
            assertThat(new String(ledger.snapshotLifecycle(storagePrefix), StandardCharsets.US_ASCII))
                    .isEqualTo("ARCHIVED_ONLY");
            assertThat(runtime.stateProof(descriptor, account)).isEmpty();
            runtime.restore(descriptor, null);
            assertThat(runtime.stateProof(descriptor, account)).isPresent();
        }
    }

    private static AuthenticatedSnapshotRuntime runtime(AppLedgerStore ledger) {
        return runtime(ledger, Path.of("build/snapshot-test-archives"));
    }

    private static AuthenticatedSnapshotRuntime runtime(AppLedgerStore ledger, Path archiveDirectory) {
        return runtime(ledger, archiveDirectory, false);
    }

    private static AuthenticatedSnapshotRuntime runtime(
            AppLedgerStore ledger, Path archiveDirectory, boolean mpfPruningEnabled) {
        return AuthenticatedSnapshotRuntime.create(ledger, IDENTITY,
                settings(archiveDirectory, mpfPruningEnabled),
                List.of(SERIES), List.of(source(SERIES)), true, "test-chain").orElseThrow();
    }

    private static AuthenticatedSnapshotSourceCommitmentV1 source(
            AuthenticatedSnapshotSeriesDescriptorV1 series) {
        return new AuthenticatedSnapshotSourceCommitmentV1() {
            @Override public String seriesId() { return series.seriesId(); }
            @Override public String algorithm() { return series.sourceCommitmentAlgorithm(); }
            @Override public String wireVersion() { return series.sourceCommitmentWireVersion(); }
            @Override public byte[] initial(
                    com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorDraftV1 draft) {
                return draft.sourceDatasetRoot();
            }
            @Override public byte[] append(byte[] accumulator, long chunkIndex,
                                           List<SnapshotEntry> entries) {
                return accumulator.clone();
            }
            @Override public byte[] finish(byte[] accumulator, long chunks, long entries) {
                return accumulator.clone();
            }
        };
    }

    private static AuthenticatedSnapshotSettings settings() {
        return settings(Path.of("build/snapshot-test-archives"));
    }

    private static AuthenticatedSnapshotSettings settings(Path archiveDirectory) {
        return settings(archiveDirectory, false);
    }

    private static AuthenticatedSnapshotSettings settings(
            Path archiveDirectory, boolean mpfPruningEnabled) {
        return new AuthenticatedSnapshotSettings(true, Set.of("daily"),
                100, 100_000, repeated(9), 32, false, 10, true, 300,
                mpfPruningEnabled, archiveDirectory,
                1000, 10_000_000);
    }

    private static SnapshotDescriptorV1 buildSnapshot(
            AppLedgerStore ledger, AuthenticatedSnapshotRuntime runtime,
            byte[] account, byte[] value) {
        CandidateState candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
        new StateCommitmentGuard(IDENTITY).apply(1, candidate);
        var session = runtime.beginBlock(candidate);
        var handle = session.writer().capabilities().snapshotSeries("daily").orElseThrow();
        var token = handle.begin(0, "daily-0", new SnapshotSourceBoundary.AppHeight(1),
                0, 0, 1, repeated(4), 1, 1);
        handle.appendChunk(token, 0, List.of(new SnapshotEntry(account, value)));
        handle.seal(token);
        try (WriteBatch batch = new WriteBatch()) {
            session.execute(batch, 1);
            StagedStateCommit prepared = (StagedStateCommit) candidate.prepare();
            try {
                AppBlock block = block(prepared.stateRoot());
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), prepared, batch, List.of());
            } finally {
                prepared.close();
            }
        }
        return SnapshotCanonicalCodec.decodeDescriptor(ledger.stateBackend().get(1,
                AuthenticatedSnapshotRuntime.descriptorKey("daily", 0)).orElseThrow());
    }

    private static AuthenticatedSnapshotSeriesDescriptorV1 series(String profileId) {
        var profile = StateCommitmentProfiles.require(profileId);
        return new AuthenticatedSnapshotSeriesDescriptorV1("daily", "balances-v1",
                AuthenticatedSnapshotSeriesDescriptorV1.Trigger.APPLICATION_MESSAGE,
                profile.id(), profile.formatFingerprint(), profile.proofEncodingId(),
                profile.equals(StateCommitmentProfiles.MPF)
                        ? AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.ON_CHAIN
                        : AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.OFF_CHAIN,
                AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC,
                "blake2b256", "balances-source-v1", 10, 4096, 256, 8192, 100,
                AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET);
    }

    private static AppBlock block(byte[] root) {
        return new AppBlock(AppBlock.BLOCK_VERSION, "snapshot-test", 1,
                AppBlock.GENESIS_PREV_HASH, 1, repeated(1), 1,
                AppBlockCodec.messagesRoot(List.of()), root, List.of(), new byte[32],
                FinalityCert.empty());
    }

    private static AppLedgerStore ledger(Path path) {
        return new AppLedgerStore(path.toString(), LoggerFactory.getLogger("snapshot-test"), IDENTITY);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] repeated(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }
}
