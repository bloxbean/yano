package com.bloxbean.cardano.yano.appchain.eutxo.indexer.testing;

import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexEvent;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoProjector;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexCoverage;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexIdentity;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexedAccount;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.SourcePoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reusable behavioral suite for every EUTxO index-store implementation. */
public abstract class EutxoIndexStoreConformance {
    protected abstract EutxoIndexStore open(IndexIdentity identity);

    @Test
    void liveApplyAndFullReplayProduceTheSameNormalizedProjection() {
        String liveDigest;
        try (EutxoIndexStore live = open(EutxoIndexFixtures.identity())) {
            applyFixture(live);
            liveDigest = live.reader().normalizedDigest();
            IndexedAccount alice = live.reader().account(
                    EutxoIndexFixtures.ALICE, 10);
            assertThat(alice.lovelace()).isEqualTo(100);
            assertThat(alice.utxos()).hasSize(1);
            assertThat(live.reader().lineage(
                    EutxoIndexFixtures.hex(3), 4, 20).nodes())
                    .hasSize(3);
        }
        try (EutxoIndexStore replay = open(EutxoIndexFixtures.identity())) {
            applyFixture(replay);
            assertThat(replay.reader().normalizedDigest()).isEqualTo(liveDigest);
        }
    }

    @Test
    void duplicateReplayIsIdempotentAndBlockGapsFailClosed() {
        try (EutxoIndexStore store = open(EutxoIndexFixtures.identity())) {
            List<EutxoIndexEvent> first =
                    EutxoIndexFixtures.splitMergeEvents().getFirst();
            EutxoProjector projector = new EutxoProjector(store);
            projector.apply(
                    EutxoIndexFixtures.point(1), first, IndexCoverage.FULL);
            String digest = store.reader().normalizedDigest();
            projector.apply(
                    EutxoIndexFixtures.point(1), first, IndexCoverage.FULL);
            assertThat(store.reader().normalizedDigest()).isEqualTo(digest);
            assertThatThrownBy(() -> projector.apply(
                    EutxoIndexFixtures.point(3),
                    EutxoIndexFixtures.splitMergeEvents().getLast(),
                    IndexCoverage.FULL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("gap");
        }
    }

    @Test
    void rollbackRequiresExactSourceIdentityAndReplayRestoresDigest() {
        try (EutxoIndexStore store = open(EutxoIndexFixtures.identity())) {
            EutxoProjector projector = new EutxoProjector(store);
            List<List<EutxoIndexEvent>> fixture =
                    EutxoIndexFixtures.splitMergeEvents();
            projector.apply(
                    EutxoIndexFixtures.point(1),
                    fixture.getFirst(),
                    IndexCoverage.FULL);
            String atOne = store.reader().normalizedDigest();
            projector.apply(
                    EutxoIndexFixtures.point(2),
                    fixture.getLast(),
                    IndexCoverage.FULL);
            String complete = store.reader().normalizedDigest();

            assertThatThrownBy(() -> store.rollbackTo(new SourcePoint(
                    1,
                    EutxoIndexFixtures.hex(99),
                    101,
                    EutxoIndexFixtures.hex(101))))
                    .isInstanceOf(IllegalStateException.class);
            store.rollbackTo(EutxoIndexFixtures.point(1));
            assertThat(store.reader().normalizedDigest()).isEqualTo(atOne);
            projector.apply(
                    EutxoIndexFixtures.point(2),
                    fixture.getLast(),
                    IndexCoverage.FULL);
            assertThat(store.reader().normalizedDigest()).isEqualTo(complete);
        }
    }

    @Test
    void paginationAndTraversalAreBounded() {
        try (EutxoIndexStore store = open(EutxoIndexFixtures.identity())) {
            applyFixture(store);
            var first = store.reader().transactions(0, 2);
            assertThat(first.items()).hasSize(2);
            assertThat(first.hasMore()).isTrue();
            assertThat(store.reader().transactions(
                    first.nextBefore(), 2).items()).hasSize(1);
            assertThat(store.reader().lineage(
                    EutxoIndexFixtures.hex(3), 0, 1).truncated()).isTrue();
            assertThatThrownBy(() -> store.reader().lineage(
                    EutxoIndexFixtures.hex(3), 21, 20))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void conflictingIdentityAtTheSameHeightFailsClosed() {
        try (EutxoIndexStore store = open(EutxoIndexFixtures.identity())) {
            EutxoProjector projector = new EutxoProjector(store);
            projector.apply(
                    EutxoIndexFixtures.point(1),
                    EutxoIndexFixtures.splitMergeEvents().getFirst(),
                    IndexCoverage.FULL);
            SourcePoint conflicting = new SourcePoint(
                    1, EutxoIndexFixtures.hex(999), 101,
                    EutxoIndexFixtures.hex(101));
            assertThatThrownBy(() -> store.begin(conflicting))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("differs");
        }
    }

    private static void applyFixture(EutxoIndexStore store) {
        EutxoProjector projector = new EutxoProjector(store);
        List<List<EutxoIndexEvent>> fixture =
                EutxoIndexFixtures.splitMergeEvents();
        projector.apply(
                EutxoIndexFixtures.point(1),
                fixture.getFirst(),
                IndexCoverage.FULL);
        projector.apply(
                EutxoIndexFixtures.point(2),
                fixture.getLast(),
                IndexCoverage.FULL);
    }
}
