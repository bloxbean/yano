package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.ProtocolVersion;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Update;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.HardForkInitiationAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.InfoAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.era.EraProvider;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledgerstate.EpochBoundaryPreview;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.runtime.ledger.canonical.TickingTestNode.slot;

/**
 * {@link TickedLedgerView}: the epoch decision (from the ledger epoch, not the tip slot), the
 * fail-closed rules, the per-snapshot memoization of the dry run, and ownership of the snapshot.
 */
class TickedLedgerViewTest {

    private static final String CRED = "11".repeat(28);
    private static final String POOL = "aa".repeat(28);
    private static final String VRF = "bb".repeat(32);
    private static final String TX = "ee".repeat(32);

    /** Every read of {@link LedgerView}, for "all reads" assertions. */
    private static final List<Function<LedgerView, Lookup<?>>> ALL_READS = List.of(
            v -> v.utxo(new Outpoint(TX, 0)),
            v -> v.account(CredentialKey.key(CRED)),
            v -> v.pool(new PoolId(POOL)),
            v -> v.poolByVrfKeyHash(VRF),
            v -> v.drep(CredentialKey.key(CRED)),
            v -> v.committeeMemberByCold(CredentialKey.key(CRED)),
            v -> v.committeeMembersByHot(CredentialKey.key(CRED)),
            v -> v.committeeCandidates(),
            // Fully qualified: yaci's GovActionId is imported for the store writes.
            v -> v.proposal(new org.yanoproject.ledger.rules.view.model.GovActionId(TX, 0)),
            LedgerView::committeeMembers,
            LedgerView::activeProposals,
            LedgerView::enactedRoots,
            LedgerView::guardrailScriptHash,
            LedgerView::dormantEpochs,
            LedgerView::treasury,
            LedgerView::protocolParams);

    @TempDir
    Path tempDir;

    private TickingTestNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.close();
        }
    }

    @Test
    void decidesFromTheLedgerEpoch() throws Exception {
        node = baseState(true, true);
        try (CanonicalLedgerView base = view()) {
            assertThat(base.snapshot().tip().ledgerEpoch()).isEqualTo(10);
            try (TickedLedgerView same = TickedLedgerView.of(base, slot(10) + 5)) {
                assertThat(same.mode()).isEqualTo(TickedLedgerView.Mode.CANONICAL);
                assertThat(same.protocolParams().require("params"))
                        .usingRecursiveComparison().isEqualTo(base.protocolParams().require("params"));
                assertThat(same.account(CredentialKey.key(CRED))).isEqualTo(base.account(CredentialKey.key(CRED)));
                assertThat(same.boundaryPreview()).isInstanceOf(Lookup.Unavailable.class);
            }
            try (TickedLedgerView next = TickedLedgerView.of(base, slot(11))) {
                assertThat(next.mode()).isEqualTo(TickedLedgerView.Mode.TICKED);
                assertThat(next.targetEpoch()).isEqualTo(11);
                assertThat(next.protocolParams()).isInstanceOf(Lookup.Present.class);
                assertThat(next.pool(new PoolId(POOL))).isInstanceOf(Lookup.Present.class);
                assertThat(reason(next.account(CredentialKey.key(CRED)))).contains("reward balances", "retry");
                assertThat(reason(next.treasury())).contains("rewards", "retry");
            }
            try (TickedLedgerView twoAhead = TickedLedgerView.of(base, slot(12))) {
                assertThat(twoAhead.mode()).isEqualTo(TickedLedgerView.Mode.UNAVAILABLE);
                assertAllUnavailable(twoAhead, "more than one boundary ahead");
            }
            try (TickedLedgerView behind = TickedLedgerView.of(base, slot(9))) {
                assertAllUnavailable(behind, "precedes the ledger epoch");
            }
            try (TickedLedgerView unknown = TickedLedgerView.of(base, -1)) {
                assertAllUnavailable(unknown, "is unknown");
            }
        }
    }

    @Test
    void producerBoundarySectionIsNotTickedTwice() throws Exception {
        node = baseState(true, true);
        // A block producer applies the boundary 10 -> 11 in its own write section, before selecting
        // the block that crosses it: the ledger is in epoch 11 while the tip slot is still in 10.
        node.crossBoundary(11);
        assertThat(node.gate.tip().tipSlotEpoch()).isEqualTo(10);
        assertThat(node.gate.tip().ledgerEpoch()).isEqualTo(11);

        try (CanonicalLedgerView base = view();
             TickedLedgerView firstSlotOf11 = TickedLedgerView.of(base, slot(11) + 1);
             TickedLedgerView nextBoundary = TickedLedgerView.of(base, slot(12))) {
            assertThat(firstSlotOf11.mode()).isEqualTo(TickedLedgerView.Mode.CANONICAL);
            assertThat(firstSlotOf11.protocolParams().require("params")).usingRecursiveComparison()
                    .isEqualTo(base.protocolParams().require("params"));
            assertThat(firstSlotOf11.account(CredentialKey.key(CRED))).isInstanceOf(Lookup.Present.class);
            assertThat(nextBoundary.mode()).isEqualTo(TickedLedgerView.Mode.TICKED);
            assertThat(nextBoundary.targetEpoch()).isEqualTo(12);
        }
    }

    @Test
    void pendingHardForkMakesTheWholeTickedViewUnavailable() throws Exception {
        node = baseState(true, true);
        GovActionId hardFork = new GovActionId("f1".repeat(32), 0);
        node.writeGovernance((store, batch, ops) -> {
            store.storeProposal(hardFork, new GovActionRecord(BigInteger.TEN, "e0" + CRED, 9, 15,
                    GovActionType.HARD_FORK_INITIATION_ACTION, null, null,
                    new HardForkInitiationAction(null, new ProtocolVersion(11, 0)), slot(9) + 1), batch, ops);
            store.storePendingEnactment(hardFork, batch, ops);
        });
        try (CanonicalLedgerView base = view(); TickedLedgerView ticked = TickedLedgerView.of(base, slot(11))) {
            assertThat(ticked.boundaryPreview().require("preview").hardForkEnacted()).isTrue();
            assertAllUnavailable(ticked, "HardForkInitiation");
            // The canonical view of the same snapshot is unaffected.
            assertThat(base.protocolParams()).isInstanceOf(Lookup.Present.class);
        }
    }

    @Test
    void governanceDisabledFailsGovernanceReadsAndParametersClosed() throws Exception {
        node = baseState(false, true);
        try (CanonicalLedgerView base = view(); TickedLedgerView ticked = TickedLedgerView.of(base, slot(11))) {
            assertThat(ticked.mode()).isEqualTo(TickedLedgerView.Mode.TICKED);
            for (Function<LedgerView, Lookup<?>> read : List.<Function<LedgerView, Lookup<?>>>of(
                    v -> v.drep(CredentialKey.key(CRED)), LedgerView::dormantEpochs, LedgerView::committeeMembers,
                    LedgerView::activeProposals, LedgerView::enactedRoots, LedgerView::guardrailScriptHash,
                    LedgerView::committeeCandidates)) {
                assertThat(reason(read.apply(ticked))).contains("governance tracking is disabled");
            }
            // Protocol version 10: enactments decide the parameters, and they are unknown.
            assertThat(reason(ticked.protocolParams())).contains("governance enactments", "unknown");
            // Non-governance boundary effects are still ticked.
            assertThat(ticked.pool(new PoolId(POOL))).isInstanceOf(Lookup.Present.class);
            assertThat(ticked.poolByVrfKeyHash(VRF)).isEqualTo(Lookup.present(new PoolId(POOL)));
        }
    }

    @Test
    void missingBoundaryProcessingFailsEveryTickedReadClosed() throws Exception {
        node = baseState(true, false);
        try (CanonicalLedgerView base = view(); TickedLedgerView ticked = TickedLedgerView.of(base, slot(11))) {
            assertThat(ticked.mode()).isEqualTo(TickedLedgerView.Mode.TICKED);
            assertAllUnavailable(ticked, "epoch boundary processing is not configured");
        }
    }

    @Test
    void conwayGenesisBootstrapAtTheBoundaryIsNotDryRun() throws Exception {
        node = baseState(true, true);
        node.boundary.getGovernanceEpochProcessor().setEraProvider(new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == 7 ? 11 : 0;
            }
        });
        try (CanonicalLedgerView base = view(); TickedLedgerView ticked = TickedLedgerView.of(base, slot(11))) {
            assertThat(reason(ticked.activeProposals())).contains("Conway genesis bootstrap");
            assertThat(reason(ticked.protocolParams())).contains("Conway genesis bootstrap");
            assertThat(ticked.pool(new PoolId(POOL))).isInstanceOf(Lookup.Present.class);
        }
        // Once the bootstrap is recorded, governance is ticked.
        node.writeGovernance((store, batch, ops) -> {
            store.storeEraFirstEpoch(9, 11, batch, ops);
            store.storeCommitteeThreshold(BigInteger.TWO, BigInteger.valueOf(3), batch, ops);
            store.storeConstitution(new GovernanceCborCodec.ConstitutionRecord("u", "00".repeat(32), null),
                    batch, ops);
        });
        try (CanonicalLedgerView base = view(); TickedLedgerView ticked = TickedLedgerView.of(base, slot(11))) {
            assertThat(ticked.activeProposals()).isInstanceOf(Lookup.Present.class);
            assertThat(ticked.guardrailScriptHash()).isInstanceOf(Lookup.Absent.class);
            assertThat(ticked.protocolParams()).isInstanceOf(Lookup.Present.class);
        }
    }

    @Test
    void conwayTransitionNotYetKnownToTheEraProviderIsNotDryRun() throws Exception {
        node = baseState(true, true);
        // Era metadata learns Conway only when its first block is applied (after the boundary).
        AtomicReference<Integer> firstConway = new AtomicReference<>(null);
        EraProvider eras = new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Conway.getValue() ? firstConway.get() : Integer.valueOf(0);
            }
        };
        node.tracker.setEraProvider(eras);
        node.boundary.getGovernanceEpochProcessor().setEraProvider(eras);

        try (CanonicalLedgerView base = view(); TickedLedgerView ticked = TickedLedgerView.of(base, slot(11))) {
            // The era provider learns Conway after the capture: the dry run must not see it.
            firstConway.set(11);
            assertThat(reason(ticked.protocolParams())).contains("Conway transition");
            assertThat(reason(ticked.activeProposals())).contains("Conway transition");
            assertThat(reason(ticked.drep(CredentialKey.key(CRED)))).contains("Conway transition");
            assertThat(ticked.pool(new PoolId(POOL))).isInstanceOf(Lookup.Present.class);
        }
        // A snapshot captured once Conway is known (and bootstrapped) is ticked normally.
        firstConway.set(3);
        node.writeGovernance((store, batch, ops) -> {
            store.storeEraFirstEpoch(9, 3, batch, ops);
            store.storeCommitteeThreshold(BigInteger.TWO, BigInteger.valueOf(3), batch, ops);
        });
        try (CanonicalLedgerView base = view(); TickedLedgerView ticked = TickedLedgerView.of(base, slot(11))) {
            assertThat(ticked.protocolParams()).isInstanceOf(Lookup.Present.class);
        }
    }

    @Test
    void protocolVersionChangeByUpdateProposalIsNotDryRun() throws Exception {
        node = baseState(true, true);
        node.apply(slot(10) + 10, List.of(TransactionBody.builder()
                .txHash("77".repeat(32))
                .update(new Update(Map.of("genesis",
                        ProtocolParamUpdate.builder().protocolMajorVer(11).protocolMinorVer(0).build()), 10))
                .build()));
        try (CanonicalLedgerView base = view(); TickedLedgerView ticked = TickedLedgerView.of(base, slot(11))) {
            assertThat(reason(ticked.protocolParams())).contains("protocol version changes", "10 -> 11");
            assertThat(reason(ticked.enactedRoots())).contains("protocol version changes");
            assertThat(ticked.pool(new PoolId(POOL))).isInstanceOf(Lookup.Present.class);
        }
    }

    @Test
    void dryRunIsMemoizedPerSnapshotAndOwnedByIt() throws Exception {
        node = baseState(true, true);
        int baseline = node.gate.liveSnapshotCount();
        CanonicalLedgerView base = view();
        EpochBoundaryPreview first;
        try (TickedLedgerView a = TickedLedgerView.of(base, slot(11));
             TickedLedgerView b = TickedLedgerView.of(base, slot(11) + 99)) {
            assertThat(base.snapshot().refCount()).isEqualTo(3);
            first = a.boundaryPreview().require("preview");
            assertThat(b.boundaryPreview().require("preview")).isSameAs(first);
        }
        // Closing the ticked views released only their own references.
        assertThat(base.snapshot().refCount()).isEqualTo(1);
        assertThat(base.protocolParams()).isInstanceOf(Lookup.Present.class);
        try (TickedLedgerView again = TickedLedgerView.of(base, slot(11))) {
            assertThat(again.boundaryPreview().require("preview")).isSameAs(first);
        }
        CanonicalSnapshot snapshot = base.snapshot();
        base.close();
        assertThat(snapshot.refCount()).isZero();
        assertThat(node.gate.liveSnapshotCount()).isEqualTo(baseline);

        // A new generation computes its own dry run.
        node.applyCerts(slot(10) + 150, StakeRegistration.builder().stakeCredential(StakeCredential.builder()
                .type(StakeCredType.ADDR_KEYHASH).hash("12".repeat(28)).build()).build());
        try (CanonicalLedgerView next = view(); TickedLedgerView ticked = TickedLedgerView.of(next, slot(11))) {
            assertThat(ticked.boundaryPreview().require("preview")).isNotSameAs(first);
        }
    }

    /**
     * ADR-056 step 1d (M5): successive snapshots of one generation (one per admission) share the boundary dry
     * run; a new generation computes it again.
     */
    @Test
    void dryRunIsSharedAcrossSnapshotsOfOneGeneration() throws Exception {
        node = baseState(true, true);
        long before = node.gate.generationMemoComputations();
        EpochBoundaryPreview first;
        try (CanonicalLedgerView a = view(); TickedLedgerView ticked = TickedLedgerView.of(a, slot(11))) {
            first = ticked.boundaryPreview().require("preview");
        }
        try (CanonicalLedgerView b = view(); TickedLedgerView ticked = TickedLedgerView.of(b, slot(11))) {
            assertThat(ticked.boundaryPreview().require("preview")).isSameAs(first);
        }
        assertThat(node.gate.generationMemoComputations() - before).isEqualTo(1);

        node.applyCerts(slot(10) + 150, StakeRegistration.builder().stakeCredential(StakeCredential.builder()
                .type(StakeCredType.ADDR_KEYHASH).hash("13".repeat(28)).build()).build());
        try (CanonicalLedgerView c = view(); TickedLedgerView ticked = TickedLedgerView.of(c, slot(11))) {
            assertThat(ticked.boundaryPreview().require("preview")).isNotSameAs(first);
        }
        assertThat(node.gate.generationMemoComputations() - before).isEqualTo(2);
    }

    @Test
    void closedViewIsUnavailable() throws Exception {
        node = baseState(true, true);
        try (CanonicalLedgerView base = view()) {
            TickedLedgerView ticked = TickedLedgerView.of(base, slot(11));
            ticked.close();
            ticked.close();
            assertAllUnavailable(ticked, "closed");
            assertThat(base.snapshot().refCount()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Epoch 10 tip with a registered credential, a pool, and one active proposal. */
    private TickingTestNode baseState(boolean governance, boolean boundary) throws Exception {
        TickingTestNode n = new TickingTestNode(tempDir.resolve("db"), governance, boundary);
        n.finalizeParams(10);
        n.applyCerts(slot(10) + 1,
                StakeRegistration.builder().stakeCredential(StakeCredential.builder()
                        .type(StakeCredType.ADDR_KEYHASH).hash(CRED).build()).build(),
                PoolRegistration.builder().poolParams(PoolParams.builder()
                        .operator(POOL).vrfKeyHash(VRF).pledge(BigInteger.ONE).cost(BigInteger.valueOf(340_000_000L))
                        .rewardAccount("e0" + CRED).poolOwners(Set.of(CRED)).build()).build());
        n.writeGovernance((store, batch, ops) -> store.storeProposal(new GovActionId(TX, 0),
                new GovActionRecord(BigInteger.TEN, "e0" + CRED, 10, 16, GovActionType.INFO_ACTION, null, null,
                        new InfoAction(), slot(10) + 2), batch, ops));
        n.setTip(slot(10) + 100);
        return n;
    }

    private CanonicalLedgerView view() {
        CanonicalSnapshot snapshot = node.acquire();
        CanonicalLedgerView view = CanonicalLedgerView.over(snapshot);
        snapshot.release();
        return view;
    }

    private static String reason(Lookup<?> lookup) {
        assertThat(lookup).isInstanceOf(Lookup.Unavailable.class);
        return ((Lookup.Unavailable<?>) lookup).reason();
    }

    private static void assertAllUnavailable(LedgerView view, String reasonFragment) {
        for (Function<LedgerView, Lookup<?>> read : ALL_READS) {
            assertThat(reason(read.apply(view))).contains(reasonFragment);
        }
    }
}
