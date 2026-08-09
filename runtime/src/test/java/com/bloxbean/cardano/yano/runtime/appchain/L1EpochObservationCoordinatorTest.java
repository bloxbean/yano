package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.l1view.EpochObservationManifest;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochState;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochStateProvider;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsView;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

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
            await(() -> memberA.status().toString().contains("ready=1"));
            await(() -> memberB.status().toString().contains("ready=1"));
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
                     }, "rotation", LoggerFactory.getLogger("epoch-test"))) {
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

    private static L1EpochObservationCoordinator coordinator(
            L1EpochObserver observer, FakeProvider provider, AppLedgerStore ledger,
            List<L1Observation> offered) {
        return new L1EpochObservationCoordinator(List.of(observer), provider,
                new EpochObservationSpool(ledger, 1_000_000), 2, 1, 65_536,
                () -> true, observation -> {
                    offered.add(observation);
                    return true;
                }, "test", LoggerFactory.getLogger("epoch-test"));
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
