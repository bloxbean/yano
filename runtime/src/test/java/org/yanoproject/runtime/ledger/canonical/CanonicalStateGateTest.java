package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.runtime.chain.InMemoryChainState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-056 step 1b: write sections, generation publication and post-release hooks.
 */
class CanonicalStateGateTest {

    private final AtomicReference<ChainTip> chainTip = new AtomicReference<>();
    private final CanonicalStateGate gate = new CanonicalStateGate(chainTip::get);

    @Test
    void outermostSectionPublishesOneGenerationWithTheTip() {
        assertThat(gate.tip()).isEqualTo(CanonicalTip.UNKNOWN);
        gate.configureEpochCalculator(slot -> (int) (slot / 100));

        gate.runWrite(() -> {
            chainTip.set(new ChainTip(250, new byte[]{1, 2}, 7));
            gate.runWrite(() -> { });  // nested apply path joins the section
            assertThat(gate.generation()).isZero();
        });

        assertThat(gate.tip()).isEqualTo(new CanonicalTip(1, 250, "0102", 2, 2));
        assertThat(gate.isWriteHeldByCurrentThread()).isFalse();
    }

    @Test
    void unchangedSectionsDoNotBumpTheGenerationButAnyChangedNestedSectionDoes() {
        try (CanonicalStateGate.WriteSection section = gate.enterWrite()) {
            section.markUnchanged();
        }
        assertThat(gate.generation()).isZero();

        try (CanonicalStateGate.WriteSection outer = gate.enterWrite()) {
            outer.markUnchanged();
            gate.runWrite(() -> { });
        }
        assertThat(gate.generation()).isEqualTo(1);
    }

    @Test
    void generationIsPublishedEvenWhenTheSectionFails() {
        assertThatThrownBy(() -> gate.runWrite(() -> {
            throw new IllegalStateException("listener failed");
        })).hasMessage("listener failed");
        assertThat(gate.generation()).isEqualTo(1);
        assertThat(gate.isWriteHeldByCurrentThread()).isFalse();
    }

    @Test
    void hooksRunAfterReleaseInOrderAndEvenAfterAFailure() {
        List<String> events = new ArrayList<>();
        assertThatThrownBy(() -> gate.runWrite(() -> {
            CanonicalStateGate.runAfterWriteRelease(() -> events.add("hook1 held=" + gate.isWriteHeldByCurrentThread()
                    + " gen=" + gate.generation()));
            gate.runWrite(() -> CanonicalStateGate.runAfterWriteRelease(() -> events.add("hook2")));
            CanonicalStateGate.runAfterWriteRelease(() -> {
                throw new IllegalStateException("hook failure is isolated");
            });
            CanonicalStateGate.runAfterWriteRelease(() -> events.add("hook4"));
            events.add("body");
            throw new IllegalStateException("apply failed");
        })).hasMessage("apply failed");

        assertThat(events).containsExactly("body", "hook1 held=false gen=1", "hook2", "hook4");
    }

    @Test
    void hookOutsideAWriteSectionRunsImmediately() {
        List<String> events = new ArrayList<>();
        CanonicalStateGate.runAfterWriteRelease(() -> events.add("now"));
        assertThat(events).containsExactly("now");
    }

    @Test
    void acquisitionIsUnavailableWithoutASourceOrInsideAWriteSection() {
        assertThat(gate.acquireSnapshot(SnapshotPurpose.ADMISSION)).isInstanceOf(Lookup.Unavailable.class);

        gate.installSnapshotSource(tip -> new CanonicalSnapshotSource.Captured(null, null, null, () -> { }));
        gate.runWrite(() -> assertThat(gate.acquireSnapshot(SnapshotPurpose.ADMISSION))
                .isInstanceOfSatisfying(Lookup.Unavailable.class,
                        u -> assertThat(u.reason()).contains("inside a canonical write section")));
    }

    @Test
    void captureFailureBecomesUnavailableWithItsReason() {
        gate.installSnapshotSource(tip -> {
            throw new IllegalStateException(RocksCanonicalSnapshotSource.ASYNC_UTXO_UNAVAILABLE);
        });
        assertThat(gate.acquireSnapshot(SnapshotPurpose.BLOCK_BUILD))
                .isInstanceOfSatisfying(Lookup.Unavailable.class,
                        u -> assertThat(u.reason()).contains("async UTxO apply enabled"));
        assertThat(gate.liveSnapshotCount()).isZero();
    }

    @Test
    void asyncUtxoApplyMakesTheProductionSourceUnavailable() {
        RocksCanonicalSnapshotSource source = new RocksCanonicalSnapshotSource(
                () -> null, () -> null, () -> null, epoch -> Optional.empty(), () -> true);
        gate.installSnapshotSource(source);
        assertThat(gate.acquireSnapshot(SnapshotPurpose.ADMISSION))
                .isInstanceOfSatisfying(Lookup.Unavailable.class,
                        u -> assertThat(u.reason()).contains("yano.utxo.applyAsync=true"));
    }

    @Test
    void aCaptureCannotStartAWriteSection() {
        gate.installSnapshotSource(tip -> {
            gate.enterWrite();
            return null;
        });
        assertThat(gate.acquireSnapshot(SnapshotPurpose.ADMISSION))
                .isInstanceOfSatisfying(Lookup.Unavailable.class,
                        u -> assertThat(u.reason()).contains("while capturing a snapshot"));
    }

    @Test
    void chainStatesOwnTheirGate() {
        InMemoryChainState chain = new InMemoryChainState();
        assertThat(CanonicalStateGate.of(chain)).isSameAs(chain.canonicalStateGate());
    }

    @Test
    void ledgerEpochIsTheLaterOfTheTipEpochAndTheCompletedBoundary() {
        AtomicInteger boundary = new AtomicInteger(-1);
        gate.configureLedgerEpochReader(boundary::get);
        gate.configureEpochCalculator(slot -> (int) (slot / 100));
        chainTip.set(new ChainTip(250, new byte[]{1}, 3));

        gate.runWrite(() -> { });
        assertThat(gate.tip().tipSlotEpoch()).isEqualTo(2);
        assertThat(gate.tip().ledgerEpoch()).isEqualTo(2);

        boundary.set(3);  // a producer boundary section completed epoch 3 before its block
        gate.runWrite(() -> { });
        assertThat(gate.tip().tipSlotEpoch()).isEqualTo(2);
        assertThat(gate.tip().ledgerEpoch()).isEqualTo(3);

        boundary.set(1);  // an older boundary never lowers the ledger epoch below the tip's
        gate.runWrite(() -> { });
        assertThat(gate.tip().ledgerEpoch()).isEqualTo(2);
    }

    @Test
    void refreshTipRepublishesStartupChangesWithoutANewGeneration() {
        gate.configureEpochCalculator(slot -> (int) (slot / 100));
        gate.runWrite(() -> chainTip.set(new ChainTip(150, new byte[]{1}, 1)));
        assertThat(gate.tip()).isEqualTo(new CanonicalTip(1, 150, "01", 1, 1));

        // Startup recovery (bootstrap, adhoc rollback, boundary recovery) moves the tip outside a section.
        chainTip.set(new ChainTip(90, new byte[]{2}, 0));
        gate.refreshTip();
        assertThat(gate.tip()).isEqualTo(new CanonicalTip(1, 90, "02", 0, 0));

        gate.runWrite(() -> assertThatThrownBy(gate::refreshTip).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void unavailableSourceIsRefusedWithoutTakingTheReadLock() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        gate.installSnapshotSource(new CanonicalSnapshotSource() {
            @Override
            public Captured capture(CanonicalTip tip) {
                captures.incrementAndGet();
                return new Captured(null, null, null, () -> { });
            }

            @Override
            public String unavailableReason() {
                return "async UTxO apply enabled";
            }
        });
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            writer.submit(() -> gate.runWrite(() -> {
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

            // A writer holds the gate; the acquisition must still return at once.
            CompletableFuture<Lookup<CanonicalSnapshot>> acquired =
                    CompletableFuture.supplyAsync(() -> gate.acquireSnapshot(SnapshotPurpose.ADMISSION));
            assertThat(acquired.get(2, TimeUnit.SECONDS)).isInstanceOfSatisfying(Lookup.Unavailable.class,
                    u -> assertThat(u.reason()).contains("async UTxO apply enabled"));
            assertThat(captures).hasValue(0);
        } finally {
            release.countDown();
            writer.shutdown();
            assertThat(writer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
