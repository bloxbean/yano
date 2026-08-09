package com.bloxbean.cardano.yano.ledgerstate.governance;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceActionType;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceProposalStatus;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceProposalStatusReason;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.ProposalLifecycleRecord;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceHistoryRollbackTest {
    @TempDir Path tempDir;

    @Test
    void boundaryRollbackAndReplayRestoresProposalAndDRepSnapshotsExactly() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var governance = rocks.governanceStore();
            var account = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            int epoch = 170;
            long slot = 10_000;
            GovActionId proposalId = new GovActionId("11".repeat(32), 2);
            ProposalLifecycleRecord proposal = new ProposalLifecycleRecord(
                    GovernanceActionType.PARAMETER_CHANGE, GovernanceProposalStatus.RATIFIED,
                    GovernanceProposalStatusReason.RATIFIED, 168, 174);

            apply(rocks.db(), governance, account, epoch, slot, proposalId, proposal);
            assertSnapshot(governance, epoch, proposalId, proposal);

            account.rollbackToSlot(slot - 1);
            assertThat(governance.hasProposalLifecycleSnapshot(epoch)).isFalse();
            assertThat(governance.hasDRepDistributionSnapshot(epoch)).isFalse();
            assertThat(governance.getProposalLifecycleSnapshot(epoch)).isEmpty();
            assertThat(governance.getDRepDistribution(epoch)).isEmpty();

            apply(rocks.db(), governance, account, epoch, slot, proposalId, proposal);
            assertSnapshot(governance, epoch, proposalId, proposal);
        }
    }

    private static void apply(org.rocksdb.RocksDB db, GovernanceStateStore governance,
                              DefaultAccountStateStore account,
                              int epoch, long slot, GovActionId proposalId,
                              ProposalLifecycleRecord proposal) throws Exception {
        var staged = new LinkedHashMap<GovActionId, ProposalLifecycleRecord>();
        staged.put(proposalId, proposal);
        var enactDelta = new ArrayList<DefaultAccountStateStore.DeltaOp>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            governance.storeProposalLifecycleEntries(epoch, staged, batch, enactDelta);
            account.commitBoundaryDelta(slot, DefaultAccountStateStore.PHASE_GOV_ENACT,
                    batch, enactDelta);
            db.write(options, batch);
        }
        assertThat(governance.hasProposalLifecycleSnapshot(epoch)).isFalse();
        assertThat(governance.getProposalLifecycleSnapshot(epoch)).containsEntry(proposalId, proposal);

        // Simulates Phase 2 after either the normal path or a restart following
        // the Phase-1 commit. The terminal entry remains recoverable in both cases.
        var ratifyDelta = new ArrayList<DefaultAccountStateStore.DeltaOp>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            governance.storeProposalLifecycleSnapshot(epoch,
                    governance.getProposalLifecycleSnapshot(epoch), batch, ratifyDelta);
            governance.storeDRepDistEntry(epoch, 0, "22".repeat(28),
                    BigInteger.valueOf(42_000_000), batch, ratifyDelta);
            governance.storeDRepDistributionSnapshotMarker(epoch, batch, ratifyDelta);
            account.commitBoundaryDelta(slot, DefaultAccountStateStore.PHASE_GOV_RATIFY,
                    batch, ratifyDelta);
            db.write(options, batch);
        }
    }

    private static void assertSnapshot(GovernanceStateStore governance, int epoch,
                                       GovActionId proposalId, ProposalLifecycleRecord proposal) throws Exception {
        assertThat(governance.hasProposalLifecycleSnapshot(epoch)).isTrue();
        assertThat(governance.hasDRepDistributionSnapshot(epoch)).isTrue();
        assertThat(governance.getProposalLifecycleSnapshot(epoch)).containsEntry(proposalId, proposal);
        assertThat(governance.getDRepDistribution(epoch)).hasSize(1);
    }
}
