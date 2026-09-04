package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.l1view.EpochObservationManifest;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochState;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochStateProvider;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsView;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.rocksdb.WriteBatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L1EpochObservationCoordinatorTest {

    @Test
    void publisherIsNonBlockingAndTwoMembersBuildIdenticalDurableRecords(@TempDir Path dir)
            throws Exception {
        L1EpochBoundary boundary = new L1EpochBoundary(10, 11, 1_000,
                bytes(0x11), 500);
        FakeProvider provider = new FakeProvider(boundary);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        L1EpochObserver slow = observer(entered, release);
        List<L1Observation> first = new CopyOnWriteArrayList<>();
        List<L1Observation> second = new CopyOnWriteArrayList<>();

        try (AppLedgerStore ledgerA = ledger(dir.resolve("a"));
             AppLedgerStore ledgerB = ledger(dir.resolve("b"));
             L1EpochObservationCoordinator memberA = coordinator(
                     slow, provider, ledgerA, first);
             L1EpochObservationCoordinator memberB = coordinator(
                     observer(null, null), provider, ledgerB, second)) {
            memberA.start();
            memberB.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            memberA.onBlockApplied(1_010, 503, bytes(0x12));
            assertThat(System.nanoTime() - started)
                    .isLessThan(TimeUnit.MILLISECONDS.toNanos(50));

            release.countDown();
            // Member A already observed a stable block while preparation was paused, so the
            // completed record can move directly from READY to OFFERED without exposing READY
            // to this polling thread. Assert the durable outcome instead of that transient state.
            await(() -> first.size() == 1, memberA::status);
            await(() -> memberB.status().toString().contains("ready=1"), memberB::status);
            memberA.onBlockApplied(1_020, 510, bytes(0x13));
            memberB.onBlockApplied(1_020, 510, bytes(0x13));
            await(() -> first.size() == 1 && second.size() == 1);
            assertThat(first.getFirst().encode()).isEqualTo(second.getFirst().encode());
        }
    }

    @Test
    void offeredRecordResumesAfterRestartAndFinalizationAdvancesNextChunk(@TempDir Path dir)
            throws Exception {
        Path path = dir.resolve("ledger");
        L1EpochBoundary boundary = new L1EpochBoundary(20, 21, 2_000,
                bytes(0x21), 700);
        FakeProvider provider = new FakeProvider(boundary);
        List<L1Observation> offered = new CopyOnWriteArrayList<>();

        try (AppLedgerStore ledger = ledger(path);
             L1EpochObservationCoordinator first = coordinator(
                     observer(null, null), provider, ledger, offered)) {
            first.start();
            first.onBlockApplied(2_100, 710, bytes(0x22));
            await(() -> offered.size() == 1, first::status);
        }

        List<L1Observation> resumed = new CopyOnWriteArrayList<>();
        try (AppLedgerStore ledger = ledger(path);
             L1EpochObservationCoordinator second = coordinator(
                     observer(null, null), provider, ledger, resumed)) {
            second.start();
            second.onBlockApplied(2_100, 710, bytes(0x22));
            await(() -> resumed.size() == 1, second::status);
            assertThat(resumed.getFirst().encode()).isEqualTo(offered.getFirst().encode());
            second.onFinalized(resumed.getFirst());
            second.onBlockApplied(2_101, 711, bytes(0x23));
            await(() -> resumed.size() == 2);
            assertThat(resumed.get(1).encode()).isNotEqualTo(resumed.getFirst().encode());
        }
    }

    @Test
    void proposerRotationDoesNotLetANonProposerDiscardVerificationRecords(@TempDir Path dir)
            throws Exception {
        L1EpochBoundary boundary = new L1EpochBoundary(30, 31, 3_000,
                bytes(0x31), 900);
        FakeProvider provider = new FakeProvider(boundary);
        AtomicBoolean proposer = new AtomicBoolean(false);
        List<L1Observation> offered = new CopyOnWriteArrayList<>();
        try (AppLedgerStore ledger = ledger(dir.resolve("rotation"));
             L1EpochObservationCoordinator coordinator = new L1EpochObservationCoordinator(
                     List.of(observer(null, null)), provider,
                     new EpochObservationSpool(ledger, 1_000_000), 2, 1, 65_536,
                     proposer::get, observation -> {
                         offered.add(observation);
                         return true;
                     }, ignored -> { }, "rotation", LoggerFactory.getLogger("epoch-test"))) {
            coordinator.start();
            coordinator.onBlockApplied(3_100, 910, bytes(0x32));
            await(() -> coordinator.status().toString().contains("ready=1"));
            assertThat(offered).isEmpty();
            proposer.set(true);
            coordinator.onBlockApplied(3_101, 911, bytes(0x33));
            await(() -> offered.size() == 1);
            assertThat(coordinator.verify(offered.getFirst(), false))
                    .isEqualTo(AppChainEngine.L1RefVerdict.OK);
        }
    }

    @Test
    void rollbackAboveAnUnfinalizedBoundaryDiscardsItsDurableJob(@TempDir Path dir) {
        L1EpochBoundary boundary = new L1EpochBoundary(40, 41, 4_000,
                bytes(0x41), 1_100);
        EpochObservationManifest manifest = new EpochObservationManifest(
                1, "synthetic", 40, 41, 41, 0, 1, 0, bytes(0x42));
        try (AppLedgerStore ledger = ledger(dir.resolve("unfinalized"))) {
            EpochObservationSpool spool = new EpochObservationSpool(ledger, 1_000_000);
            spool.begin(boundary, manifest);
            spool.append(boundary, manifest, 0, new byte[]{0});
            spool.complete(manifest);

            spool.rollback(3_999);

            assertThat(spool.prepared("synthetic", 41)).isFalse();
            assertThat(spool.status().toString()).contains("ready=0");
        }
    }

    @Test
    void verificationDistinguishesOlderThanWindowFromMismatchAndAhead(@TempDir Path dir) {
        try (AppLedgerStore ledger = ledger(dir.resolve("verification-window"))) {
            EpochObservationSpool spool = new EpochObservationSpool(ledger, 1_000_000);
            addPreparedObservation(spool, 4, new byte[]{4});
            addPreparedObservation(spool, 5, new byte[]{5});

            assertThat(spool.verify(observation(3, new byte[]{3}), false))
                    .isEqualTo(AppChainEngine.L1RefVerdict.UNKNOWN);
            assertThat(spool.verify(observation(4, new byte[]{99}), false))
                    .isEqualTo(AppChainEngine.L1RefVerdict.MISMATCH);
            assertThat(spool.verify(observation(6, new byte[]{6}), false))
                    .isEqualTo(AppChainEngine.L1RefVerdict.AHEAD);
        }
    }

    @Test
    void certifiedCatchUpAcceptsAMissingLocalEpochButNotAContradictoryJob(@TempDir Path dir) {
        try (AppLedgerStore ledger = ledger(dir.resolve("verification-gap"))) {
            EpochObservationSpool spool = new EpochObservationSpool(ledger, 1_000_000);
            addPreparedObservation(spool, 4, new byte[]{4});
            addPreparedObservation(spool, 6, new byte[]{6});

            assertThat(spool.verify(observation(5, new byte[]{5}), true))
                    .isEqualTo(AppChainEngine.L1RefVerdict.UNKNOWN);
            assertThat(spool.verify(observation(5, new byte[]{5}), false))
                    .isEqualTo(AppChainEngine.L1RefVerdict.MISMATCH);
            assertThat(spool.verify(observation(4, new byte[]{99}), true))
                    .isEqualTo(AppChainEngine.L1RefVerdict.MISMATCH);
        }
    }

    @Test
    void spoolOffersOnlyTheEarliestUnfinalizedEpochForEachObserver(@TempDir Path dir) {
        try (AppLedgerStore ledger = ledger(dir.resolve("ordered-offers"))) {
            EpochObservationSpool spool = new EpochObservationSpool(ledger, 1_000_000);
            addPreparedObservation(spool, 4, new byte[]{4});
            addPreparedObservation(spool, 5, new byte[]{5});

            List<EpochObservationSpool.Offered> first =
                    spool.offer(Long.MAX_VALUE, 10, 65_536);
            assertThat(first).singleElement()
                    .satisfies(offered -> assertThat(
                            offered.observation().epochAnchor().newEpoch()).isEqualTo(4));

            // Repeated offer passes must not skip around an outstanding job.
            assertThat(spool.offer(Long.MAX_VALUE, 10, 65_536)).isEmpty();
            assertThat(spool.acknowledge(first.getFirst().observation())).isTrue();

            assertThat(spool.offer(Long.MAX_VALUE, 10, 65_536)).singleElement()
                    .satisfies(offered -> assertThat(
                            offered.observation().epochAnchor().newEpoch()).isEqualTo(5));
        }
    }

    @Test
    void rollbackBelowFinalizedEpochPermanentlyHaltsCoordinator(@TempDir Path dir)
            throws Exception {
        L1EpochBoundary boundary = new L1EpochBoundary(50, 51, 5_000,
                bytes(0x51), 1_300);
        FakeProvider provider = new FakeProvider(boundary);
        List<L1Observation> offered = new CopyOnWriteArrayList<>();
        try (AppLedgerStore ledger = ledger(dir.resolve("deep-rollback"));
             L1EpochObservationCoordinator coordinator = coordinator(
                     observer(null, null), provider, ledger, offered)) {
            coordinator.start();
            coordinator.onBlockApplied(5_100, 1_310, bytes(0x52));
            for (int index = 0; index < 3; index++) {
                int expected = index + 1;
                await(() -> offered.size() == expected, coordinator::status);
                coordinator.onFinalized(offered.get(index));
                coordinator.onBlockApplied(5_101 + index, 1_311 + index, bytes(0x53));
            }
            await(() -> coordinator.status().toString().contains("finalized=1"),
                    coordinator::status);

            coordinator.onRollback(4_999);
            await(() -> !coordinator.healthy(), coordinator::status);

            assertThat(coordinator.status().get("unhealthyReason"))
                    .isEqualTo("DEEP_ROLLBACK_BELOW_FINALIZED_EPOCH_ATTESTATION");
            coordinator.onBlockApplied(5_200, 1_400, bytes(0x54));
            Thread.sleep(50);
            assertThat(coordinator.healthy()).isFalse();
        }
    }

    @Test
    void spoolRejectsRollbackBelowFinalizedBoundary(@TempDir Path dir) {
        L1EpochBoundary boundary = new L1EpochBoundary(60, 61, 6_000,
                bytes(0x61), 1_500);
        EpochObservationManifest manifest = new EpochObservationManifest(
                1, "synthetic", 60, 61, 61, 1, 1, 1, bytes(0x62));
        try (AppLedgerStore ledger = ledger(dir.resolve("finalized"))) {
            EpochObservationSpool spool = new EpochObservationSpool(ledger, 1_000_000);
            spool.begin(boundary, manifest);
            spool.append(boundary, manifest, 0, new byte[]{0});
            spool.append(boundary, manifest, 1, new byte[]{1});
            spool.complete(manifest);
            for (EpochObservationSpool.Offered offered : spool.offer(1_500, 2, 65_536)) {
                assertThat(spool.acknowledge(offered.observation())).isTrue();
            }

            assertThatThrownBy(() -> spool.rollback(5_999))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DEEP_ROLLBACK_BELOW_FINALIZED_EPOCH_ATTESTATION");
            assertThat(spool.prepared("synthetic", 61)).isTrue();
        }
    }

    @Test
    void restartReconcilesFollowerReadyRecordAlreadyCommittedInAppHistory(@TempDir Path dir) {
        L1EpochBoundary boundary = new L1EpochBoundary(70, 71, 7_000,
                bytes(0x71), 1_700);
        EpochObservationManifest manifest = new EpochObservationManifest(
                1, "synthetic", 70, 71, 71, 0, 1, 0, bytes(0x72));
        L1Observation observation = L1Observation.epoch(
                "synthetic", 71, 7_000, bytes(0x71), new byte[]{9});
        try (AppLedgerStore ledger = ledger(dir.resolve("ready-crash"))) {
            EpochObservationSpool spool = new EpochObservationSpool(ledger, 1_000_000);
            spool.begin(boundary, manifest);
            spool.append(boundary, manifest, 0, new byte[]{9});
            spool.complete(manifest);

            AppMessage message = AppMessage.builder().version(1).messageId(bytes(0x73))
                    .chainId("epoch-test").topic(observation.topic()).sender(bytes(0x74))
                    .senderSeq(1).expiresAt(Long.MAX_VALUE).body(observation.encode())
                    .authScheme(0).authProof(new byte[64]).build();
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "epoch-test", 1,
                    AppBlock.GENESIS_PREV_HASH, 7_000, bytes(0x71), 1,
                    AppBlockCodec.messagesRoot(List.of(message)), bytes(0x75),
                    List.of(message), bytes(0x76), FinalityCert.empty());
            try (WriteBatch batch = new WriteBatch()) {
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), block.stateRoot(), batch);
            }

            EpochObservationManifest later = new EpochObservationManifest(
                    1, "synthetic", 71, 72, 72, 0, 1, 0, bytes(0x77));
            L1EpochBoundary laterBoundary = new L1EpochBoundary(
                    71, 72, 7_100, bytes(0x78), 1_701);
            spool.begin(laterBoundary, later);
            spool.append(laterBoundary, later, 0, new byte[]{10});
            spool.complete(later);

            assertThat(spool.status().toString()).contains("ready=2");
            assertThat(spool.reconcileFinalizedBlocks()).isEqualTo(1);
            assertThat(spool.status().toString()).contains("finalized=1");
            assertThat(spool.offer(Long.MAX_VALUE, 1, 65_536)).singleElement()
                    .satisfies(offered -> assertThat(offered.observation().epochAnchor().newEpoch())
                            .isEqualTo(72));
        }
    }

    @Test
    void statusRetainsTheUnderlyingAsynchronousFailure(@TempDir Path dir) throws Exception {
        L1EpochBoundary boundary = new L1EpochBoundary(80, 81, 8_000,
                bytes(0x81), 1_900);
        FakeProvider provider = new FakeProvider(boundary);
        L1EpochObserver failing = new L1EpochObserver() {
            @Override public String observerId() { return "failing"; }

            @Override
            public EpochObservationManifest prepare(L1EpochBoundary value, L1EpochState state) {
                return new EpochObservationManifest(1, observerId(), 80, 81,
                        80, 1, 1, 1, bytes(0x82));
            }

            @Override
            public void writeObservations(EpochObservationManifest manifest,
                                          L1EpochState state,
                                          com.bloxbean.cardano.yano.api.appchain.l1view
                                                  .L1EpochObservationSink sink) {
                throw new IllegalStateException("diagnostic detail");
            }
        };

        try (AppLedgerStore ledger = ledger(dir.resolve("failure-diagnostic"));
             L1EpochObservationCoordinator coordinator = coordinator(
                     failing, provider, ledger, new CopyOnWriteArrayList<>())) {
            coordinator.start();
            await(() -> !coordinator.healthy(), coordinator::status);
            assertThat(coordinator.status())
                    .containsEntry("unhealthyReason", "asynchronous reconciliation")
                    .containsEntry("lastFailure",
                            "IllegalStateException: diagnostic detail");
        }
    }

    @Test
    void ignoresPreLedgerStateEpochsAndReconcilesTheFirstObservableBoundary(
            @TempDir Path dir) throws Exception {
        L1EpochBoundary boundary = new L1EpochBoundary(3, 4, 86_400,
                bytes(0x04), 100);
        AtomicBoolean completed = new AtomicBoolean();
        L1EpochStateProvider provider = new L1EpochStateProvider() {
            @Override public boolean persistent() { return true; }
            @Override public int snapshotRetentionEpochs() { return 8; }
            @Override public long epochAtSlot(long slot) { return slot < 86_400 ? 3 : 4; }
            @Override public long firstObservableEpoch() { return 4; }
            @Override public List<L1EpochBoundary> completedBoundaries(long after, int limit) {
                return completed.get() && boundary.newEpoch() > after
                        ? List.of(boundary) : List.of();
            }
            @Override public Optional<L1EpochState> open(L1EpochBoundary ignored) {
                return Optional.of(new EmptyState(boundary));
            }
        };

        try (AppLedgerStore ledger = ledger(dir.resolve("pre-ledger-state"));
             L1EpochObservationCoordinator coordinator = new L1EpochObservationCoordinator(
                     List.of(observer(null, null)), provider,
                     new EpochObservationSpool(ledger, 1_000_000), 2, 1, 65_536,
                     () -> false, ignored -> false, ignored -> { }, "pre-ledger-state",
                     LoggerFactory.getLogger("epoch-test"))) {
            coordinator.start();
            coordinator.onBlockApplied(64_800, 90, bytes(0x03));
            await(() -> Long.valueOf(90L).equals(
                    coordinator.status().get("latestAppliedBlockNumber")));

            assertThat(coordinator.healthy()).isTrue();
            assertThat(coordinator.status()).containsEntry("failures", 0L);
            assertThat(coordinator.status().toString()).contains("ready=0");

            completed.set(true);
            coordinator.onBlockApplied(86_400, 100, bytes(0x04));
            await(() -> coordinator.status().toString().contains("ready=1"),
                    coordinator::status);

            assertThat(coordinator.healthy()).isTrue();
            assertThat(coordinator.status()).containsEntry("completedJobs", 1L);
        }
    }

    @Test
    void restartResumesGeneratingJobOutsideTheCurrentBoundaryScan(@TempDir Path dir)
            throws Exception {
        L1EpochBoundary boundary = new L1EpochBoundary(90, 91, 9_000,
                bytes(0x91), 2_100);
        L1EpochObserver observer = observer(null, null);
        L1EpochStateProvider retainedState = new L1EpochStateProvider() {
            @Override public boolean persistent() { return true; }
            @Override public int snapshotRetentionEpochs() { return 2; }
            @Override public long epochAtSlot(long slot) { return 92; }
            @Override public List<L1EpochBoundary> completedBoundaries(long after, int limit) {
                return List.of();
            }
            @Override public Optional<L1EpochState> open(L1EpochBoundary requested) {
                return requested.equals(boundary)
                        ? Optional.of(new EmptyState(boundary)) : Optional.empty();
            }
        };
        try (AppLedgerStore ledger = ledger(dir.resolve("resume-generating"))) {
            EpochObservationSpool spool = new EpochObservationSpool(ledger, 1_000_000);
            EpochObservationManifest manifest = observer.prepare(
                    boundary, new EmptyState(boundary));
            spool.begin(boundary, manifest);
            spool.append(boundary, manifest, 0, new byte[]{0x01});

            try (L1EpochObservationCoordinator coordinator = new L1EpochObservationCoordinator(
                    List.of(observer), retainedState, spool, 2, 1, 65_536,
                    () -> false, ignored -> false, ignored -> { }, "resume-generating",
                    LoggerFactory.getLogger("epoch-test"))) {
                coordinator.start();
                await(() -> Long.valueOf(1L).equals(
                                coordinator.status().get("completedJobs")),
                        coordinator::status);
                assertThat(coordinator.status()).containsEntry("completedJobs", 1L);
                assertThat(coordinator.status().toString()).contains("ready=1");
                assertThat(coordinator.healthy()).isTrue();
            }
        }
    }

    @Test
    void restartDiscardsGeneratingJobWhenPinnedStateExpired(@TempDir Path dir)
            throws Exception {
        L1EpochBoundary boundary = new L1EpochBoundary(90, 91, 9_000,
                bytes(0x91), 2_100);
        L1EpochObserver observer = observer(null, null);
        L1EpochObserver unavailableObserver = new L1EpochObserver() {
            @Override public String observerId() { return observer.observerId(); }
            @Override
            public EpochObservationManifest prepare(L1EpochBoundary ignored,
                                                    L1EpochState state) {
                throw new PluginActivationException("wrapped unavailable dataset",
                        new NoSuchElementException(
                                "L1_EPOCH_DATASET_UNAVAILABLE: expired stake snapshot"));
            }
            @Override
            public void writeObservations(EpochObservationManifest manifest,
                                          L1EpochState state,
                                          com.bloxbean.cardano.yano.api.appchain.l1view
                                                  .L1EpochObservationSink sink) {
                throw new AssertionError("unavailable dataset cannot be written");
            }
        };
        L1EpochStateProvider expiredState = new L1EpochStateProvider() {
            @Override public boolean persistent() { return true; }
            @Override public int snapshotRetentionEpochs() { return 2; }
            @Override public long epochAtSlot(long slot) { return 92; }
            @Override public List<L1EpochBoundary> completedBoundaries(long after, int limit) {
                return List.of();
            }
            @Override public Optional<L1EpochState> open(L1EpochBoundary requested) {
                return requested.equals(boundary)
                        ? Optional.of(new EmptyState(boundary)) : Optional.empty();
            }
        };
        try (AppLedgerStore ledger = ledger(dir.resolve("discard-expired-generating"))) {
            EpochObservationSpool spool = new EpochObservationSpool(ledger, 1_000_000);
            EpochObservationManifest manifest = observer.prepare(
                    boundary, new EmptyState(boundary));
            spool.begin(boundary, manifest);
            spool.append(boundary, manifest, 0, new byte[]{0x01});

            try (L1EpochObservationCoordinator coordinator = new L1EpochObservationCoordinator(
                    List.of(unavailableObserver), expiredState, spool, 2, 1, 65_536,
                    () -> false, ignored -> false, ignored -> { }, "discard-expired-generating",
                    LoggerFactory.getLogger("epoch-test"))) {
                coordinator.start();
                await(() -> Long.valueOf(1L).equals(
                                coordinator.status().get("discardedExpiredJobs")),
                        coordinator::status);
                assertThat(coordinator.status())
                        .containsEntry("discardedExpiredJobs", 1L)
                        .containsEntry("completedJobs", 0L);
                assertThat(coordinator.status().toString()).contains("generating=0");
                assertThat(coordinator.healthy()).isTrue();
            }
        }
    }

    private static L1EpochObservationCoordinator coordinator(
            L1EpochObserver observer, L1EpochStateProvider provider, AppLedgerStore ledger,
            List<L1Observation> offered) {
        return new L1EpochObservationCoordinator(List.of(observer), provider,
                new EpochObservationSpool(ledger, 1_000_000), 2, 1, 65_536,
                () -> true, observation -> {
                    offered.add(observation);
                    return true;
                }, ignored -> { }, "test", LoggerFactory.getLogger("epoch-test"));
    }

    private static L1EpochObserver observer(CountDownLatch entered, CountDownLatch release) {
        return new L1EpochObserver() {
            @Override public String observerId() { return "synthetic"; }

            @Override
            public EpochObservationManifest prepare(L1EpochBoundary boundary, L1EpochState state) {
                if (entered != null) entered.countDown();
                if (release != null) {
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return new EpochObservationManifest(1, observerId(),  boundary.previousEpoch(),
                        boundary.newEpoch(), boundary.newEpoch(), 2, 1, 2, bytes(0x44));
            }

            @Override
            public void writeObservations(EpochObservationManifest manifest,
                                          L1EpochState state,
                                          com.bloxbean.cardano.yano.api.appchain.l1view
                                                  .L1EpochObservationSink sink) {
                sink.write(0, new byte[]{0x01});
                sink.write(1, new byte[]{0x02});
                sink.write(2, new byte[]{0x03});
            }
        };
    }

    private static AppLedgerStore ledger(Path path) {
        return new AppLedgerStore(path.toString(), LoggerFactory.getLogger("epoch-test"));
    }

    private static void addPreparedObservation(EpochObservationSpool spool, long epoch,
                                               byte[] claim) {
        L1EpochBoundary boundary = new L1EpochBoundary(
                epoch - 1, epoch, epoch * 100, bytes((int) epoch), epoch * 10);
        EpochObservationManifest manifest = new EpochObservationManifest(
                1, "synthetic", epoch - 1, epoch, epoch, 0, 1, 0, bytes((int) epoch));
        spool.begin(boundary, manifest);
        spool.append(boundary, manifest, 0, claim);
        spool.complete(manifest);
    }

    private static L1Observation observation(long epoch, byte[] claim) {
        return L1Observation.epoch("synthetic", epoch, epoch * 100,
                bytes((int) epoch), claim);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        await(condition, () -> "condition did not become true");
    }

    private static void await(java.util.function.BooleanSupplier condition,
                              java.util.function.Supplier<?> diagnostic) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as(String.valueOf(diagnostic.get())).isTrue();
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private static final class FakeProvider implements L1EpochStateProvider {
        private final L1EpochBoundary boundary;

        private FakeProvider(L1EpochBoundary boundary) {
            this.boundary = boundary;
        }

        @Override public boolean persistent() { return true; }
        @Override public int snapshotRetentionEpochs() { return 8; }
        @Override public long epochAtSlot(long slot) { return boundary.newEpoch(); }
        @Override public List<L1EpochBoundary> completedBoundaries(long after, int limit) {
            return boundary.newEpoch() > after ? List.of(boundary) : List.of();
        }
        @Override public Optional<L1EpochState> open(L1EpochBoundary ignored) {
            return Optional.of(new EmptyState(boundary));
        }
    }

    private record EmptyState(L1EpochBoundary boundary) implements L1EpochState {
        @Override public long previousEpoch() { return boundary.previousEpoch(); }
        @Override public long newEpoch() { return boundary.newEpoch(); }
        @Override public ProtocolParamsView protocolParams(long epoch) {
            return new ProtocolParamsView(epoch, new byte[]{(byte) epoch});
        }
        @Override public boolean hasStakeSnapshot(long epoch) { return true; }
        @Override public void forEachStakeEntry(long epoch, StakeEntryConsumer consumer) { }
        @Override public boolean hasProposalStatusSnapshot(long epoch) { return true; }
        @Override public boolean hasDRepDistributionSnapshot(long epoch) { return true; }
        @Override public void forEachProposalStatus(long epoch, ProposalStatusConsumer consumer) { }
        @Override public void forEachDRepDistributionEntry(
                long epoch, DRepDistributionConsumer consumer) { }
    }
}
