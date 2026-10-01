package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.ProtocolVersion;
import com.bloxbean.cardano.yaci.core.model.certs.RegCert;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.VoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.HardForkInitiationAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksIterator;
import org.yanoproject.api.account.LedgerStateProvider;
import org.yanoproject.api.era.EraProvider;
import org.yanoproject.ledgerstate.AccountStateCfNames;
import org.yanoproject.ledgerstate.DefaultAccountStateStore;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.runtime.ledger.canonical.TickingTestNode.EPOCH_LENGTH;
import static org.yanoproject.runtime.ledger.canonical.TickingTestNode.slot;

/**
 * The PV 10 hard fork's {@code updateDRepDelegations} (cardano-ledger f649f975, {@code Conway/Rules/HardFork.hs:82-105})
 * runs at the boundary that enacts PV 10: {@code HARDFORK} follows enactment and precedes
 * {@code setFreshDRepPulsingState} ({@code Conway/Rules/Epoch.hs:367-372}). A PV 9 delegation to a DRep that was never
 * registered (allowed in the bootstrap phase, {@code Deleg.hs:225-226}) is removed there (HardFork.hs:97-98), so the
 * DRep registering during the first PV 10 epoch does not get the stake; a stale PV 9 member of a DRep's delegator set
 * (issue #4772) leaves the set.
 */
class DRepDelegationHardForkBoundaryTest {

    /** Delegates to a never-registered DRep (dangling at the fork). */
    private static final String DANGLING = "a1".repeat(28);
    /** Delegates to OLD, then to NEW, at PV 9: a stale member of OLD's set. */
    private static final String REDELEGATED = "a2".repeat(28);
    private static final String UNREGISTERED_DREP = "d3".repeat(28);
    private static final String OLD_DREP = "d4".repeat(28);
    private static final String NEW_DREP = "d5".repeat(28);
    private static final GovActionId HARD_FORK = new GovActionId("f1".repeat(32), 0);
    /** {@code DefaultAccountStateStore.PREFIX_DREP_DELEG_REVERSE} (package-private there). */
    private static final byte PREFIX_DREP_DELEG_REVERSE = 0x04;
    private static final long TIP = slot(11) + 400_000;

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
    void theBoundaryThatEnactsPv10RemovesADelegationToANeverRegisteredDRep() throws Exception {
        prepareHardForkIntoEpoch12();

        node.crossBoundary(12);

        assertThat(node.tracker.getProtocolMajor(12)).isEqualTo(10);
        assertThat(node.accounts.getDRepDelegation(0, DANGLING)).isEmpty();
        assertThat(reverseEntries()).containsOnlyKeys(reverseKey(NEW_DREP, REDELEGATED));

        // The DRep registers in the first PV 10 epoch; the next distribution has no stake for it.
        node.applyCerts(slot(12) + 5, regDRep(UNREGISTERED_DREP));
        node.crossBoundary(13);

        assertThat(node.accounts.getDRepDistribution(13, 0, UNREGISTERED_DREP)).isEmpty();
    }

    @Test
    void rollbackAcrossTheHardForkBoundaryRestoresThePv9SetsAndTheRebuildRunsAgain() throws Exception {
        prepareHardForkIntoEpoch12();
        Map<String, String> reverseBefore = reverseEntries();
        Map<String, String> forwardBefore = forwardDelegations();
        assertThat(reverseBefore).containsKey(reverseKey(OLD_DREP, REDELEGATED));

        node.crossBoundary(12);
        assertThat(reverseEntries()).isNotEqualTo(reverseBefore);
        assertThat(forwardDelegations()).isNotEqualTo(forwardBefore);

        node.gate.runWrite(() -> node.accounts.rollbackToSlot(TIP));

        assertThat(reverseEntries()).isEqualTo(reverseBefore);
        assertThat(forwardDelegations()).isEqualTo(forwardBefore);

        // The rebuild's marker was rolled back with it, so the replayed boundary rebuilds again.
        node.crossBoundary(12);
        assertThat(node.accounts.getDRepDelegation(0, DANGLING)).isEmpty();
        assertThat(reverseEntries()).containsOnlyKeys(reverseKey(NEW_DREP, REDELEGATED));
    }

    @Test
    void aCrashBetweenGovernancePhase1AndPhase2KeepsThePhase1JournalAndRollbackExact() throws Exception {
        prepareHardForkIntoEpoch12();
        Map<String, String> reverseBefore = reverseEntries();
        Map<String, String> forwardBefore = forwardDelegations();
        AtomicBoolean crash = new AtomicBoolean(true);
        node.governanceEpoch.setBoundaryDeltaWriter((slot, phase, batch, ops) -> {
            if (phase == DefaultAccountStateStore.PHASE_GOV_RATIFY && crash.getAndSet(false)) {
                throw new SimulatedCrash();
            }
            node.accounts.commitBoundaryDelta(slot, phase, batch, ops);
        });

        assertThatThrownBy(() -> node.crossBoundary(12)).hasRootCauseInstanceOf(SimulatedCrash.class);
        // Phase 1, with the rebuild, is committed; Phase 2 is not.
        assertThat(node.accounts.getDRepDelegation(0, DANGLING)).isEmpty();
        Map<String, String> phase1Journal = governanceJournal(DefaultAccountStateStore.PHASE_GOV_ENACT);
        assertThat(phase1Journal).isNotEmpty();
        assertThat(governanceJournal(DefaultAccountStateStore.PHASE_GOV_RATIFY)).isEmpty();

        // Recovery replays governance (Phase 1 again, then Phase 2); the first run's journal stays.
        node.crossBoundary(12);
        assertThat(governanceJournal(DefaultAccountStateStore.PHASE_GOV_ENACT)).containsAllEntriesOf(phase1Journal);
        assertThat(governanceJournal(DefaultAccountStateStore.PHASE_GOV_RATIFY)).isNotEmpty();

        node.gate.runWrite(() -> node.accounts.rollbackToSlot(TIP));

        assertThat(reverseEntries()).isEqualTo(reverseBefore);
        assertThat(forwardDelegations()).isEqualTo(forwardBefore);
        assertThat(governanceJournal(DefaultAccountStateStore.PHASE_GOV_ENACT)).isEmpty();
    }

    // ------------------------------------------------------------------ fixture

    /**
     * PV 9 epoch 11: a delegation to a never-registered DRep, a redelegation between two registered DReps (a stale
     * member of the old DRep's set), and a HardForkInitiation to PV 10 pending enactment.
     */
    private void prepareHardForkIntoEpoch12() throws Exception {
        node = new TickingTestNode(tempDir.resolve("db"), TickingTestNode.baseParams(EPOCH_LENGTH, 0, 9), 1L,
                true, true);
        node.accounts.setEraProvider(new EraProvider() {
            @Override
            public boolean isConwayOrLater(int epoch) {
                return true;
            }
        });
        node.finalizeParams(10);
        node.finalizeParams(11);
        node.applyCerts(slot(11) + 5,
                regDRep(OLD_DREP), regDRep(NEW_DREP),
                RegCert.builder().stakeCredential(stake(DANGLING)).coin(TickingTestNode.KEY_DEPOSIT).build(),
                RegCert.builder().stakeCredential(stake(REDELEGATED)).coin(TickingTestNode.KEY_DEPOSIT).build(),
                voteDeleg(DANGLING, UNREGISTERED_DREP),
                voteDeleg(REDELEGATED, OLD_DREP));
        node.applyCerts(slot(11) + 10, voteDeleg(REDELEGATED, NEW_DREP));
        node.writeGovernance((store, batch, ops) -> {
            store.storeProposal(HARD_FORK, new GovActionRecord(BigInteger.TEN, "e0" + DANGLING, 10, 16,
                    GovActionType.HARD_FORK_INITIATION_ACTION, null, null,
                    new HardForkInitiationAction(null, new ProtocolVersion(10, 0)), slot(10) + 1), batch, ops);
            store.storePendingEnactment(HARD_FORK, batch, ops);
        });
        node.setTip(TIP);
        assertThat(node.accounts.getDRepDelegation(0, DANGLING))
                .contains(new LedgerStateProvider.DRepDelegation(0, UNREGISTERED_DREP));
        assertThat(reverseEntries()).containsOnlyKeys(
                reverseKey(OLD_DREP, REDELEGATED), reverseKey(NEW_DREP, REDELEGATED));
    }

    private Map<String, String> reverseEntries() {
        return stateRange(PREFIX_DREP_DELEG_REVERSE);
    }

    private Map<String, String> forwardDelegations() {
        return stateRange(DefaultAccountStateStore.PREFIX_DREP_DELEG);
    }

    private Map<String, String> stateRange(byte prefix) {
        Map<String, String> entries = new LinkedHashMap<>();
        try (RocksIterator it = node.db().newIterator(node.cfState())) {
            for (it.seek(new byte[]{prefix}); it.isValid() && it.key()[0] == prefix; it.next()) {
                entries.put(HexFormat.of().formatHex(it.key()), HexFormat.of().formatHex(it.value()));
            }
        }
        return entries;
    }

    /** The boundary journal entries of {@code phase} at the boundary into epoch 12, by key. */
    private Map<String, String> governanceJournal(byte phase) {
        byte[] prefix = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN).putLong(slot(12)).put(phase).array();
        Map<String, String> entries = new LinkedHashMap<>();
        try (RocksIterator it = node.db().newIterator(node.cf(AccountStateCfNames.ACCT_BOUNDARY_DELTA))) {
            for (it.seek(prefix); it.isValid() && startsWith(it.key(), prefix); it.next()) {
                entries.put(HexFormat.of().formatHex(it.key()), HexFormat.of().formatHex(it.value()));
            }
        }
        return entries;
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }

    private static String reverseKey(String drep, String delegator) {
        return "04" + "00" + drep + "00" + delegator;
    }

    private static StakeCredential stake(String hash) {
        return StakeCredential.builder().type(StakeCredType.ADDR_KEYHASH).hash(hash).build();
    }

    private static VoteDelegCert voteDeleg(String delegator, String drep) {
        return VoteDelegCert.builder().stakeCredential(stake(delegator)).drep(Drep.addrKeyHash(drep)).build();
    }

    private static RegDrepCert regDRep(String drep) {
        return RegDrepCert.builder().drepCredential(new Credential(StakeCredType.ADDR_KEYHASH, drep))
                .coin(BigInteger.valueOf(500_000_000L)).build();
    }

    private static final class SimulatedCrash extends RuntimeException {
    }
}
