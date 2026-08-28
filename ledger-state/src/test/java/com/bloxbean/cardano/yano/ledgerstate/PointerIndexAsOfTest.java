package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource.PointerCoordinate;
import com.bloxbean.cardano.yano.api.archive.PointerCredentialSource.PointerCredential;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Store-level tests for the ADR-039 as-of pointer index against a real RocksDB.
 *
 * <p>Covers the index semantics, the completeness marker, and the crash/idempotency
 * properties of cleanup. The interval logic itself is exercised more broadly in
 * archive-core's differential suite; this asserts the persistence behaves as that logic
 * assumes.
 */
class PointerIndexAsOfTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private DefaultAccountStateStore store;

    private static final String CRED_A = "77".repeat(28);
    private static final String CRED_B = "88".repeat(28);

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(PointerIndexAsOfTest.class), true);
    }

    @AfterEach
    void tearDown() { rocks.close(); }

    /** Writes an index entry the way the deregistration path does. */
    private void indexDeregistration(String credHash, long slot, int txIdx, int certIdx) throws Exception {
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            batch.put(rocks.cfState(),
                    DefaultAccountStateStore.acctDeregCoordKey(0, credHash, slot, txIdx, certIdx),
                    new byte[0]);
            rocks.db().write(wo, batch);
        }
    }

    private static PointerCredential credential(String hash) {
        return new PointerCredential(0, hash);
    }

    private static PointerCoordinate at(long slot, int tx, int cert) {
        return new PointerCoordinate(slot, tx, cert);
    }

    // ----------------------------------------------------------- interval logic

    @Test
    void aDeregistrationInsideTheIntervalIsFound() throws Exception {
        indexDeregistration(CRED_A, 75, 0, 0);
        assertThat(store.deregisteredWithin(credential(CRED_A), at(50, 0, 0),
                PointerCoordinate.endOfBlock(100))).isTrue();
    }

    @Test
    void aDeregistrationAfterTheUpperBoundIsIgnored() throws Exception {
        // This is what makes replay deterministic: the store may be far ahead, but a
        // deregistration later than the projected block must not affect it.
        indexDeregistration(CRED_A, 5_000, 0, 0);
        assertThat(store.deregisteredWithin(credential(CRED_A), at(50, 0, 0),
                PointerCoordinate.endOfBlock(100))).isFalse();
    }

    @Test
    void aDeregistrationBeforeTheRegistrationIsIgnored() throws Exception {
        // A credential deregistered before this coordinate was registered cannot invalidate it;
        // the coordinate is from a later re-registration.
        indexDeregistration(CRED_A, 20, 0, 0);
        assertThat(store.deregisteredWithin(credential(CRED_A), at(50, 0, 0),
                PointerCoordinate.endOfBlock(100))).isFalse();
    }

    @Test
    void reRegistrationKeepsTheOldCoordinateDeadAndTheNewCoordinateLive() throws Exception {
        indexDeregistration(CRED_A, 75, 0, 0);

        assertThat(store.deregisteredWithin(credential(CRED_A), at(50, 0, 0),
                PointerCoordinate.endOfBlock(100))).isTrue();
        assertThat(store.deregisteredWithin(credential(CRED_A), at(90, 0, 0),
                PointerCoordinate.endOfBlock(100))).isFalse();
    }

    @Test
    void sameBlockReRegistrationUsesTheFullCertificateCoordinate() throws Exception {
        indexDeregistration(CRED_A, 100, 1, 0);

        assertThat(store.deregisteredWithin(credential(CRED_A), at(50, 0, 0),
                PointerCoordinate.endOfBlock(100))).isTrue();
        assertThat(store.deregisteredWithin(credential(CRED_A), at(100, 2, 0),
                PointerCoordinate.endOfBlock(100))).isFalse();
    }

    @Test
    void anotherCredentialsDeregistrationIsIgnored() throws Exception {
        indexDeregistration(CRED_B, 75, 0, 0);
        assertThat(store.deregisteredWithin(credential(CRED_A), at(50, 0, 0),
                PointerCoordinate.endOfBlock(100))).isFalse();
    }

    @Test
    void sameSlotDeregistrationsDoNotCollide() throws Exception {
        // Two deregistrations of one credential in one slot, different transactions. A
        // slot-only key would have kept one and lost the other.
        indexDeregistration(CRED_A, 75, 0, 0);
        indexDeregistration(CRED_A, 75, 4, 0);
        assertThat(store.pointerDeregIndexEntryCount()).isEqualTo(2);

        // A coordinate registered between them is invalidated only by the later one.
        assertThat(store.deregisteredWithin(credential(CRED_A), at(75, 2, 0),
                PointerCoordinate.endOfBlock(100))).isTrue();
        // ...and not by anything at or before itself.
        assertThat(store.deregisteredWithin(credential(CRED_A), at(75, 4, 0),
                PointerCoordinate.endOfBlock(100))).isFalse();
    }

    @Test
    void theBoundaryIsInclusiveAtTheUpperEndAndExclusiveAtTheLower() throws Exception {
        indexDeregistration(CRED_A, 100, 3, 1);
        // exactly at the upper bound -> included
        assertThat(store.deregisteredWithin(credential(CRED_A), at(50, 0, 0), at(100, 3, 1))).isTrue();
        // exactly at the lower bound -> excluded
        assertThat(store.deregisteredWithin(credential(CRED_A), at(100, 3, 1), at(200, 0, 0))).isFalse();
    }

    @Test
    void anInvertedIntervalResolvesToFalseRatherThanScanning() throws Exception {
        indexDeregistration(CRED_A, 75, 0, 0);
        assertThat(store.deregisteredWithin(credential(CRED_A), at(200, 0, 0), at(100, 0, 0))).isFalse();
    }

    // ------------------------------------------------------ completeness marker

    @Test
    void anUnmarkedStoreReportsIncomplete() {
        assertThat(store.completeness())
                .isEqualTo(PointerCredentialSource.IndexCompleteness.INCOMPLETE);
    }

    @Test
    void markingAtGenesisReportsComplete() {
        store.markPointerIndexFromGenesis();
        assertThat(store.completeness())
                .isEqualTo(PointerCredentialSource.IndexCompleteness.COMPLETE);
    }

    @Test
    void aCleanedStoreReportsCleanedNotComplete() {
        store.markPointerIndexFromGenesis();
        store.markPointerIndexCleaned(50_000_000);
        // CLEANED must not read as COMPLETE: resolution has to fail closed, not silently
        // return "unresolved" from an index that no longer exists.
        assertThat(store.completeness())
                .isEqualTo(PointerCredentialSource.IndexCompleteness.CLEANED);
        assertThat(store.pointerIndexStatus().cleanedThroughSlot()).hasValue(50_000_000L);
    }

    // ------------------------------------------------------------ cleanup

    @Test
    void cleanupIsBoundedAndResumable() throws Exception {
        for (int i = 0; i < 25; i++) indexDeregistration(CRED_A, 100 + i, 0, 0);
        assertThat(store.pointerDeregIndexEntryCount()).isEqualTo(25);

        long first = store.cleanupPointerIndex(10_000, 10);
        assertThat(first).isEqualTo(10);
        assertThat(store.pointerDeregIndexEntryCount()).isEqualTo(15);

        long second = store.cleanupPointerIndex(10_000, 10);
        assertThat(second).isEqualTo(10);
        assertThat(store.pointerDeregIndexEntryCount()).isEqualTo(5);
    }

    @Test
    void cleanupRespectsTheSlotBound() throws Exception {
        indexDeregistration(CRED_A, 100, 0, 0);
        indexDeregistration(CRED_A, 5_000, 0, 0);
        long removed = store.cleanupPointerIndex(1_000, 100);
        assertThat(removed).isEqualTo(1);
        assertThat(store.pointerDeregIndexEntryCount()).isEqualTo(1);
        // the entry beyond the bound survives
        assertThat(store.deregisteredWithin(credential(CRED_A), at(0, 0, 0),
                PointerCoordinate.endOfBlock(10_000))).isTrue();
    }

    @Test
    void cleanupIsIdempotentOnRetry() throws Exception {
        for (int i = 0; i < 5; i++) indexDeregistration(CRED_A, 100 + i, 0, 0);
        assertThat(store.cleanupPointerIndex(10_000, 100)).isEqualTo(5);
        // A retry after a crash simply finds nothing left.
        assertThat(store.cleanupPointerIndex(10_000, 100)).isZero();
        assertThat(store.cleanupPointerIndex(10_000, 100)).isZero();
        assertThat(store.pointerDeregIndexEntryCount()).isZero();
    }

    @Test
    void aPartialCleanupDoesNotRecordCompletion() throws Exception {
        for (int i = 0; i < 20; i++) indexDeregistration(CRED_A, 100 + i, 0, 0);
        store.cleanupPointerIndex(10_000, 5);
        // Crash here: the marker was never written, so completeness is unchanged and the
        // remaining entries are still queryable.
        assertThat(store.pointerIndexStatus().cleanedThroughSlot()).isEmpty();
        assertThat(store.pointerDeregIndexEntryCount()).isEqualTo(15);
    }

    @Test
    void cleanupDoesNotTouchTheAuthoritativeStakeEventLog() throws Exception {
        // The event log is read by reward calculation and deregistered-account detection.
        // Cleanup is scoped strictly to the derived index.
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            batch.put(rocks.cfState(),
                    DefaultAccountStateStore.stakeEventKey(100, 0, 0, 0, CRED_A),
                    AccountStateCborCodec.encodeStakeEvent(AccountStateCborCodec.EVENT_DEREGISTRATION));
            rocks.db().write(wo, batch);
        }
        indexDeregistration(CRED_A, 100, 0, 0);

        store.cleanupPointerIndex(10_000, 100);

        assertThat(store.pointerDeregIndexEntryCount()).isZero();
        assertThat(rocks.db().get(rocks.cfState(),
                DefaultAccountStateStore.stakeEventKey(100, 0, 0, 0, CRED_A)))
                .as("authoritative stake event must survive index cleanup")
                .isNotNull();
    }

    @Test
    void aZeroOrNegativeBoundRemovesNothing() throws Exception {
        indexDeregistration(CRED_A, 100, 0, 0);
        assertThat(store.cleanupPointerIndex(10_000, 0)).isZero();
        assertThat(store.cleanupPointerIndex(10_000, -1)).isZero();
        assertThat(store.pointerDeregIndexEntryCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------ accounting

    @Test
    void statusReportsEntryCountAndBytesForDiskAccounting() throws Exception {
        for (int i = 0; i < 8; i++) indexDeregistration(CRED_A, 100 + i, 0, 0);
        var status = store.pointerIndexStatus();
        assertThat(status.entryCount()).isEqualTo(8);
        // 1 prefix + 1 credType + 28 hash + 8 slot + 2 tx + 2 cert = 42 bytes per key, no value
        assertThat(status.logicalBytes()).isEqualTo(8 * 42);
    }
}
