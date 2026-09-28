package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.GovActionId;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-056 ownership gate: reference counting, transfer, use-after-release, leak visibility and the
 * live-snapshot cap.
 */
class CanonicalSnapshotOwnershipTest {

    private final AtomicInteger captures = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private CanonicalStateGate gate;

    @BeforeEach
    void setUp() {
        gate = new CanonicalStateGate(() -> new ChainTip(10, new byte[]{7}, 1));
        gate.installSnapshotSource(tip -> {
            captures.incrementAndGet();
            return new CanonicalSnapshotSource.Captured(null, null, null, releases::incrementAndGet);
        });
    }

    @Test
    void referencesAreCountedAndTheSnapshotIsFreedExactlyOnce() {
        CanonicalSnapshot snapshot = acquire(SnapshotPurpose.ADMISSION);
        assertThat(snapshot.refCount()).isEqualTo(1);
        assertThat(gate.liveSnapshotCount()).isEqualTo(1);

        // Handing a view to another owner transfers a reference; the view never closes the base.
        CanonicalLedgerView view = CanonicalLedgerView.over(snapshot);
        assertThat(snapshot.refCount()).isEqualTo(2);
        snapshot.release();
        assertThat(snapshot.refCount()).isEqualTo(1);
        assertThat(releases).hasValue(0);
        assertThat(snapshot.read("probe", s -> Lookup.present("ok"))).isEqualTo(Lookup.present("ok"));

        view.close();
        view.close();  // idempotent: the view releases its reference once
        assertThat(snapshot.refCount()).isZero();
        assertThat(releases).hasValue(1);
        assertThat(gate.liveSnapshotCount()).isZero();

        // Double release is guarded: no negative count, no second native release.
        snapshot.release();
        snapshot.close();
        assertThat(snapshot.refCount()).isZero();
        assertThat(releases).hasValue(1);

        assertThatThrownBy(snapshot::retain).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CanonicalLedgerView.over(snapshot)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsAfterReleaseAreUnavailable() {
        CanonicalSnapshot snapshot = acquire(SnapshotPurpose.ADMISSION);
        CanonicalLedgerView view = CanonicalLedgerView.over(snapshot);
        snapshot.release();
        view.close();

        assertThat(snapshot.read("probe", s -> Lookup.present("ok"))).isInstanceOf(Lookup.Unavailable.class);
        assertThat(view.account(CredentialKey.key("11".repeat(28)))).isInstanceOf(Lookup.Unavailable.class);
        assertThat(view.protocolParams()).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void readFailuresAreUnavailableNotAbsent() {
        try (CanonicalSnapshot snapshot = acquire(SnapshotPurpose.ADMISSION);
             CanonicalLedgerView view = CanonicalLedgerView.over(snapshot)) {
            assertThat(snapshot.read("boom", s -> {
                throw new IllegalStateException("decode failed");
            })).isInstanceOfSatisfying(Lookup.Unavailable.class,
                    u -> assertThat(u.reason()).contains("decode failed"));
            // Disabled stores (null readers) answer unavailable, never absent.
            assertThat(view.account(CredentialKey.key("11".repeat(28)))).isInstanceOf(Lookup.Unavailable.class);
            assertThat(view.utxo(new Outpoint("ab".repeat(32), 0)))
                    .isInstanceOf(Lookup.Unavailable.class);
            assertThat(view.proposal(new GovActionId("ab".repeat(32), 0)))
                    .isInstanceOf(Lookup.Unavailable.class);
            assertThat(view.poolByVrfKeyHash("cd".repeat(32))).isInstanceOf(Lookup.Unavailable.class);
        }
    }

    @Test
    void invalidationFreesNativeStateButKeepsTheHandleCountedUntilReleased() {
        CanonicalSnapshot snapshot = acquire(SnapshotPurpose.REBUILD);
        gate.invalidateSnapshots("database closing");

        assertThat(releases).hasValue(1);
        assertThat(snapshot.read("probe", s -> Lookup.present(1)))
                .isInstanceOfSatisfying(Lookup.Unavailable.class,
                        u -> assertThat(u.reason()).contains("database closing"));
        assertThat(gate.liveSnapshotCount()).isEqualTo(1);

        snapshot.release();
        assertThat(releases).hasValue(1);
        assertThat(gate.liveSnapshotCount()).isZero();
    }

    @Test
    void capRefusesShadowRequestsOnly() {
        gate.setMaxLiveSnapshots(2);
        CanonicalSnapshot a = acquire(SnapshotPurpose.ADMISSION);
        CanonicalSnapshot b = acquire(SnapshotPurpose.SHADOW);

        assertThat(gate.acquireSnapshot(SnapshotPurpose.SHADOW))
                .isInstanceOfSatisfying(Lookup.Unavailable.class, u -> assertThat(u.reason()).contains("cap"));
        assertThat(gate.shadowRefusals()).isEqualTo(1);

        CanonicalSnapshot c = acquire(SnapshotPurpose.ADMISSION);
        CanonicalSnapshot d = acquire(SnapshotPurpose.BLOCK_BUILD);
        CanonicalSnapshot e = acquire(SnapshotPurpose.REBUILD);
        assertThat(gate.liveSnapshotCount()).isEqualTo(5);
        assertThat(gate.overCapAcquisitions()).isEqualTo(3);

        for (CanonicalSnapshot snapshot : new CanonicalSnapshot[]{a, b, c, d, e}) {
            snapshot.release();
        }
        assertThat(gate.liveSnapshotCount()).isZero();
        assertThat(acquire(SnapshotPurpose.SHADOW).refCount()).isEqualTo(1);
    }

    @Test
    void liveSnapshotsAreReportedPerGenerationAndReturnToBaseline() {
        CanonicalSnapshot first = acquire(SnapshotPurpose.ADMISSION);
        gate.runWrite(() -> { });
        CanonicalSnapshot second = acquire(SnapshotPurpose.ADMISSION);
        CanonicalSnapshot third = acquire(SnapshotPurpose.SHADOW);

        assertThat(first.generation()).isZero();
        assertThat(second.generation()).isEqualTo(1);
        assertThat(second.tip().slot()).isEqualTo(10);
        assertThat(gate.liveSnapshotsByGeneration()).isEqualTo(Map.of(0L, 1, 1L, 2));
        assertThat(first.ageMillis()).isGreaterThanOrEqualTo(0);

        first.release();
        second.release();
        third.release();
        assertThat(gate.liveSnapshotsByGeneration()).isEmpty();
        assertThat(releases.get()).isEqualTo(captures.get());
    }

    @Test
    void memoizedValuesAreComputedOncePerSnapshotAndOnlyWhenPresent() {
        AtomicInteger computed = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        try (CanonicalSnapshot snapshot = acquire(SnapshotPurpose.ADMISSION)) {
            for (int i = 0; i < 3; i++) {
                assertThat(snapshot.memoized("index", "index", s -> {
                    computed.incrementAndGet();
                    return Lookup.present("value");
                })).isEqualTo(Lookup.present("value"));
                assertThat(snapshot.memoized("broken", "broken", s -> {
                    failures.incrementAndGet();
                    return Lookup.unavailable("not yet");
                }).isUnavailable()).isTrue();
            }
            assertThat(computed).hasValue(1);
            assertThat(failures).hasValue(3);

            try (CanonicalSnapshot other = acquire(SnapshotPurpose.ADMISSION)) {
                other.memoized("index", "index", s -> {
                    computed.incrementAndGet();
                    return Lookup.present("other");
                });
                assertThat(computed).hasValue(2);
            }
        }
    }

    private CanonicalSnapshot acquire(SnapshotPurpose purpose) {
        Lookup<CanonicalSnapshot> acquired = gate.acquireSnapshot(purpose);
        assertThat(acquired).isInstanceOf(Lookup.Present.class);
        return ((Lookup.Present<CanonicalSnapshot>) acquired).value();
    }
}
