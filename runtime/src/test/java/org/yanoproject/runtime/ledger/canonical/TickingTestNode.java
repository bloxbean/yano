package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.archive.EpochArchiveStagingSink;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledgerstate.AccountStateCfNames;
import org.yanoproject.ledgerstate.DefaultAccountStateStore;
import org.yanoproject.ledgerstate.EpochBoundaryProcessor;
import org.yanoproject.ledgerstate.EpochParamTracker;
import org.yanoproject.ledgerstate.EpochRewardCalculator;
import org.yanoproject.ledgerstate.governance.GovernanceBlockProcessor;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.epoch.DRepDistributionCalculator;
import org.yanoproject.ledgerstate.governance.epoch.DRepExpiryCalculator;
import org.yanoproject.ledgerstate.governance.epoch.GovernanceEpochProcessor;
import org.yanoproject.ledgerstate.governance.ratification.EnactmentProcessor;
import org.yanoproject.ledgerstate.governance.ratification.ProposalDropService;
import org.yanoproject.ledgerstate.governance.ratification.RatificationEngine;
import org.yanoproject.ledgerstate.governance.ratification.VoteTallyCalculator;
import org.yanoproject.runtime.chain.DirectRocksDBChainState;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node's ledger-state pipeline over one real RocksDB, wired as {@code LedgerStateSubsystem} wires
 * it: account store, parameter tracker, reward calculator, epoch boundary processor and (optionally)
 * the governance block and epoch processors, plus the canonical gate with the production snapshot
 * source. Epoch boundaries run through the real {@code handleEpochTransition} path.
 */
final class TickingTestNode implements AutoCloseable {

    static final long EPOCH_LENGTH = 432_000L;
    static final BigInteger KEY_DEPOSIT = BigInteger.valueOf(2_000_000L);
    static final BigInteger POOL_DEPOSIT = BigInteger.valueOf(500_000_000L);
    private static final Logger LOG = LoggerFactory.getLogger(TickingTestNode.class);

    final DirectRocksDBChainState chain;
    final EpochParamProvider params;
    final EpochParamTracker tracker;
    final DefaultAccountStateStore accounts;
    final GovernanceStateStore governance;
    final EpochBoundaryProcessor boundary;
    /** The governance epoch processor; null unless both governance and the boundary are enabled. */
    final GovernanceEpochProcessor governanceEpoch;
    final CanonicalStateGate gate;
    private long blockNumber;
    private long chainBlocks;

    /**
     * @param governanceEnabled wire governance tracking and epoch processing
     * @param boundaryEnabled   wire the epoch boundary processor
     */
    TickingTestNode(Path directory, boolean governanceEnabled, boolean boundaryEnabled) {
        this(directory, baseParams(), 1L, governanceEnabled, boundaryEnabled);
    }

    /**
     * @param directory    chain-state directory (created, or an existing copy)
     * @param params       genesis-level parameters and slot layout ({@link EpochParamProvider#getEpochSlotCalc()})
     * @param networkMagic known network magic for the rewards network configuration (1 preprod, 2 preview,
     *                     764824073 mainnet)
     */
    TickingTestNode(Path directory, EpochParamProvider params, long networkMagic,
                    boolean governanceEnabled, boolean boundaryEnabled) {
        chain = new DirectRocksDBChainState(directory.toString());
        this.params = params;
        tracker = new EpochParamTracker(params, true, db(), cf(AccountStateCfNames.EPOCH_PARAMS));
        accounts = new DefaultAccountStateStore(db(), this::cf, LOG, true, params);
        accounts.setParamTracker(tracker);
        governance = new GovernanceStateStore(db(), cfState());
        if (governanceEnabled) {
            accounts.setGovernanceBlockProcessor(new GovernanceBlockProcessor(governance, tracker));
        }
        if (boundaryEnabled) {
            EpochRewardCalculator rewards = new EpochRewardCalculator(
                    db(), cfState(), cf(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT), true);
            rewards.setLedgerStateProvider(accounts);
            rewards.setAccountStateStore(accounts);
            rewards.setCfNetworkConfig(EpochRewardCalculator.resolveNetworkConfig(networkMagic));
            boundary = new EpochBoundaryProcessor(null, rewards, tracker, params, networkMagic,
                    EpochRewardCalculator.resolveNetworkConfig(networkMagic));
            accounts.setEpochBoundaryProcessor(boundary);
            boundary.setSnapshotCreator(accounts);
            if (governanceEnabled) {
                DRepDistributionCalculator drepDistribution = new DRepDistributionCalculator(
                        db(), cfState(), cf(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT), governance);
                // No UTxO store here, so no coordinate-bound stake view: the DRep distribution (Phase 2,
                // not part of the ticked effects) falls back to the delegation snapshot.
                GovernanceEpochProcessor epochProcessor = new GovernanceEpochProcessor(
                        db(), cfState(), cf(AccountStateCfNames.ACCT_DELTA), governance, drepDistribution,
                        new DRepExpiryCalculator(),
                        new RatificationEngine(governance, new VoteTallyCalculator()),
                        new EnactmentProcessor(governance, tracker), new ProposalDropService(),
                        params, tracker, accounts.getAdaPotTracker(), accounts::resolvePoolStakeForEpoch,
                        accounts.asRewardRestStore(), null);
                epochProcessor.setBoundaryDeltaWriter(accounts::commitBoundaryDelta);
                boundary.setGovernanceEpochProcessor(epochProcessor);
                governanceEpoch = epochProcessor;
            } else {
                governanceEpoch = null;
            }
        } else {
            boundary = null;
            governanceEpoch = null;
        }
        gate = chain.canonicalStateGate();
        gate.configureLedgerEpochReader(() -> RocksCanonicalSnapshotSource.completedBoundaryEpoch(accounts));
        gate.configureEpochCalculator(slot -> params.getEpochSlotCalc().slotToEpoch(slot));
        gate.installSnapshotSource(new RocksCanonicalSnapshotSource(
                this::db, () -> accounts, () -> null, accounts::getProtocolParameters, () -> false));
    }

    /** First slot of {@code epoch} in the synthetic layout (432000-slot epochs from slot 0). */
    static long slot(int epoch) {
        return epoch * EPOCH_LENGTH;
    }

    /** First slot of {@code epoch} in this node's slot layout. */
    long epochStartSlot(int epoch) {
        return params.getEpochSlotCalc().epochToStartSlot(epoch);
    }

    RocksDB db() {
        return (RocksDB) chain.getDb();
    }

    ColumnFamilyHandle cf(String name) {
        return (ColumnFamilyHandle) chain.getColumnFamilyHandle(name);
    }

    ColumnFamilyHandle cfState() {
        return cf(AccountStateCfNames.ACCT_STATE);
    }

    /** Finalizes the tracker's parameters for {@code epoch} (as the boundary into it would have). */
    void finalizeParams(int epoch) {
        gate.runWrite(() -> tracker.finalizeEpoch(epoch));
    }

    /** Stores a chain tip block at {@code slot}, as a canonical write. */
    void setTip(long slot) {
        long number = ++chainBlocks;
        byte[] hash = HexUtil.decodeHexString(String.format("%064x", number));
        gate.runWrite(() -> chain.storeBlock(hash, number, slot, new byte[]{0}));
    }

    /** Applies a block with one transaction per entry of {@code transactions}. */
    void apply(long slot, List<TransactionBody> transactions) {
        long number = ++blockNumber;
        List<TransactionBody> txs = new ArrayList<>();
        for (int i = 0; i < transactions.size(); i++) {
            TransactionBody tx = transactions.get(i);
            txs.add(tx.getTxHash() != null ? tx
                    : tx.toBuilder().txHash(String.format("%060x%04x", number, i)).build());
        }
        Block block = Block.builder().transactionBodies(txs).build();
        gate.runWrite(() -> accounts.applyBlock(new BlockAppliedEvent(Era.Conway, slot, number, "hash" + number, block)));
    }

    void applyCerts(long slot, Certificate... certificates) {
        apply(slot, List.of(TransactionBody.builder()
                .certificates(new ArrayList<>(Arrays.asList(certificates))).build()));
    }

    /** Writes raw governance state in one committed batch. */
    void writeGovernance(GovernanceWrite write) throws Exception {
        gate.runWrite(() -> {
            try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
                write.apply(governance, batch, new ArrayList<>());
                db().write(options, batch);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @FunctionalInterface
    interface GovernanceWrite {
        void apply(GovernanceStateStore store, WriteBatch batch, List<DefaultAccountStateStore.DeltaOp> ops)
                throws Exception;
    }

    /** Runs the real epoch transition {@code newEpoch - 1 → newEpoch} (pre, snapshot, post) in one write section. */
    void crossBoundary(int newEpoch) {
        preEpochTransition(newEpoch, false);
        postEpochTransition(newEpoch);
    }

    /**
     * The real {@code PreEpochTransition} step ({@code handleEpochTransition}: rewards, SNAP, POOLREAP,
     * governance) as one canonical write.
     *
     * @param skipRewards resume the boundary after its reward step (recorded as done), as crash
     *                    recovery does; for real-data runs where rewards are not compared
     */
    void preEpochTransition(int newEpoch, boolean skipRewards) {
        int previous = newEpoch - 1;
        gate.runWrite(() -> {
            accounts.prepareEpochBoundary(previous, newEpoch, epochStartSlot(newEpoch), ++blockNumber);
            if (skipRewards) {
                accounts.setBoundaryStarted(new EpochArchiveStagingSink.Boundary(
                        previous, newEpoch, epochStartSlot(newEpoch), blockNumber));
                accounts.setBoundaryStep(newEpoch, EpochBoundaryProcessor.STEP_REWARDS);
            }
            accounts.handleEpochTransition(previous, newEpoch);
        });
    }

    /** The real {@code EpochTransition} (prune) and {@code PostEpochTransition} (reward_rest credit) steps. */
    void postEpochTransition(int newEpoch) {
        int previous = newEpoch - 1;
        gate.runWrite(() -> {
            accounts.handleEpochTransitionSnapshot(previous, newEpoch);
            accounts.handlePostEpochTransition(previous, newEpoch);
        });
    }

    CanonicalSnapshot acquire() {
        Lookup<CanonicalSnapshot> acquired = gate.acquireSnapshot(SnapshotPurpose.ADMISSION);
        if (!(acquired instanceof Lookup.Present<CanonicalSnapshot> present)) {
            throw new IllegalStateException("snapshot unavailable: " + acquired);
        }
        return present.value();
    }

    @Override
    public void close() {
        chain.close();
    }

    /** Conway-era genesis parameters with cost models for all three Plutus versions. */
    static EpochParamProvider baseParams() {
        return baseParams(EPOCH_LENGTH, 0);
    }

    /**
     * Same parameters with a network's slot layout (21600-slot Byron epochs before
     * {@code shelleyStartSlot}).
     */
    static EpochParamProvider baseParams(long epochLength, long shelleyStartSlot) {
        return baseParams(epochLength, shelleyStartSlot, 10);
    }

    /** Same parameters at genesis protocol version {@code protocolMajor}. */
    static EpochParamProvider baseParams(long epochLength, long shelleyStartSlot, int protocolMajor) {
        return new EpochParamProvider() {
            @Override
            public long getEpochLength() {
                return epochLength;
            }

            @Override
            public long getShelleyStartSlot() {
                return shelleyStartSlot;
            }

            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return KEY_DEPOSIT;
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return POOL_DEPOSIT;
            }

            @Override
            public Integer getMinFeeA(long epoch) {
                return 44;
            }

            @Override
            public Integer getMinFeeB(long epoch) {
                return 155_381;
            }

            @Override
            public Integer getMaxTxSize(long epoch) {
                return 16_384;
            }

            @Override
            public Integer getMaxBlockSize(long epoch) {
                return 90_112;
            }

            @Override
            public Integer getMaxBlockHeaderSize(long epoch) {
                return 1_100;
            }

            @Override
            public BigDecimal getPriceMem(long epoch) {
                return new BigDecimal("0.0577");
            }

            @Override
            public BigDecimal getPriceStep(long epoch) {
                return new BigDecimal("0.0000721");
            }

            @Override
            public BigInteger getMaxTxExMem(long epoch) {
                return BigInteger.valueOf(14_000_000L);
            }

            @Override
            public BigInteger getMaxTxExSteps(long epoch) {
                return BigInteger.valueOf(10_000_000_000L);
            }

            @Override
            public Integer getCollateralPercent(long epoch) {
                return 150;
            }

            @Override
            public Integer getMaxCollateralInputs(long epoch) {
                return 3;
            }

            @Override
            public BigInteger getCoinsPerUtxoWord(long epoch) {
                return BigInteger.valueOf(4_310L);
            }

            @Override
            public BigDecimal getMinFeeRefScriptCostPerByte(long epoch) {
                return BigDecimal.valueOf(15);
            }

            @Override
            public Map<String, Object> getCostModels(long epoch) {
                Map<String, Object> models = new LinkedHashMap<>();
                models.put("PlutusV1", List.of(100, 200, 300));
                models.put("PlutusV2", List.of(400, 500, 600, 700));
                return models;
            }

            @Override
            public Map<String, Object> getConwayCostModels(long epoch) {
                return Map.of("PlutusV3", List.of(1, 2, 3, 4, 5));
            }

            @Override
            public int getProtocolMajor(long epoch) {
                return protocolMajor;
            }
        };
    }
}
