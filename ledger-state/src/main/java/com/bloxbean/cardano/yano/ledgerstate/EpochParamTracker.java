package com.bloxbean.cardano.yano.ledgerstate;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Number;
import co.nstant.in.cbor.model.Special;
import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.era.EraProvider;
import com.bloxbean.cardano.yano.api.util.CostModelUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.rocksdb.WriteBatch;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks protocol parameter updates and provides epoch-resolved params.
 * <p>
 * Two sources of parameter changes depending on era:
 * <ul>
 *   <li><b>Pre-Conway (Byron–Babbage):</b> {@code Update} field in transaction body containing
 *       {@link ProtocolParamUpdate} proposals. Takes effect at the specified epoch + 1.</li>
 *   <li><b>Conway+:</b> {@code ParameterChangeAction} governance proposals. These go through
 *       voting → ratification → enactment. When enacted by {@code GovernanceEpochProcessor},
 *       it calls {@link #applyEnactedParamChange(int, ProtocolParamUpdate)} to apply the
 *       update for the target epoch.</li>
 * </ul>
 * <p>
 * Falls back to the base {@link EpochParamProvider} for any parameters not tracked from blocks.
 */
public class EpochParamTracker implements EpochParamProvider {
    private static final Logger log = LoggerFactory.getLogger(EpochParamTracker.class);

    private static final ObjectMapper JSON = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .addMixIn(Tuple.class, TupleJsonMixin.class);
    }

    private abstract static class TupleJsonMixin {
        @JsonCreator
        TupleJsonMixin(@JsonProperty("_1") Object first, @JsonProperty("_2") Object second) {
        }
    }

    private final EpochParamProvider baseProvider;
    private final boolean enabled;

    // RocksDB persistence — dedicated column family (null = in-memory only, e.g. tests)
    private final RocksDB db;
    private final ColumnFamilyHandle cfEpochParams;
    private volatile EraProvider eraProvider;

    // Accumulated per-epoch resolved params (epoch → full effective snapshot stored as ProtocolParamUpdate)
    private final ConcurrentHashMap<Integer, ProtocolParamUpdate> epochParams = new ConcurrentHashMap<>();

    // Pending proposals for next epoch (pre-Conway Update mechanism)
    private final ConcurrentHashMap<Integer, ProtocolParamUpdate> pendingUpdates = new ConcurrentHashMap<>();

    // Prefix byte for pending-update keys in cfEpochParams (ASCII 'P')
    private static final byte KEY_PENDING_UPDATE = 0x50;
    private static final BigInteger WORDS_PER_UTXO_BYTE = BigInteger.valueOf(8);

    /**
     * Build a pending-update key: 'P' | effectiveEpoch(4 BE) | sourceSlot(8 BE) | txIdx(4 BE)
     * Total: 17 bytes. Sorts by effectiveEpoch, then chronologically by source.
     */
    static byte[] pendingUpdateKey(int effectiveEpoch, long sourceSlot, int txIdx) {
        return ByteBuffer.allocate(1 + 4 + 8 + 4)
                .order(ByteOrder.BIG_ENDIAN)
                .put(KEY_PENDING_UPDATE)
                .putInt(effectiveEpoch)
                .putLong(sourceSlot)
                .putInt(txIdx)
                .array();
    }

    /**
     * Create a tracker with RocksDB persistence. On construction, loads all persisted
     * epoch params into the in-memory map so lookups work immediately after restart.
     *
     * @param baseProvider   Fallback provider for params not tracked from blocks
     * @param enabled        Whether tracking is enabled
     * @param db             RocksDB instance
     * @param cfEpochParams  Dedicated column family for epoch params (key: epoch(4 BE), value: JSON bytes)
     */
    public EpochParamTracker(EpochParamProvider baseProvider, boolean enabled,
                             RocksDB db, ColumnFamilyHandle cfEpochParams) {
        this.baseProvider = baseProvider;
        this.enabled = enabled;
        this.db = db;
        this.cfEpochParams = cfEpochParams;
        if (enabled && db != null && cfEpochParams != null) {
            loadPersistedParams();
        }
    }

    /** Create a tracker without RocksDB persistence (in-memory only, for tests). */
    public EpochParamTracker(EpochParamProvider baseProvider, boolean enabled) {
        this(baseProvider, enabled, null, null);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEraProvider(EraProvider eraProvider) {
        this.eraProvider = eraProvider;
    }

    /**
     * Process a transaction body to extract pre-Conway protocol parameter update proposals.
     * <p>
     * In pre-Conway eras, the {@code Update} field in the transaction body contains proposed
     * parameter changes with a target epoch. These take effect at epoch + 1.
     * The pending update is persisted atomically through the caller's {@link WriteBatch}
     * so it survives restarts.
     * <p>
     * In Conway+, parameter changes come through governance (ParameterChangeAction proposals).
     * These are NOT processed here — they go through ratification in GovernanceEpochProcessor
     * and are applied via {@link #applyEnactedParamChange(int, ProtocolParamUpdate)}.
     *
     * @param tx    Transaction body to inspect
     * @param slot  Slot of the block containing this transaction (for pending key)
     * @param txIdx Index of the transaction within the block (for pending key)
     * @param batch WriteBatch for atomic persistence; required when RocksDB is active
     * @throws IllegalStateException if RocksDB persistence is active but batch is null
     */
    public void processTransaction(TransactionBody tx, long slot, int txIdx, WriteBatch batch) {
        if (!enabled) return;

        if (db != null && cfEpochParams != null && batch == null) {
            throw new IllegalStateException(
                    "WriteBatch is required to durably persist protocol parameter updates");
        }

        // Pre-Conway: Update field
        // The epoch field in the Update CBOR is the proposal epoch (current epoch).
        // The update takes effect at the start of epoch + 1 (next epoch boundary).
        var update = tx.getUpdate();
        if (update != null && update.getProtocolParamUpdates() != null) {
            int effectiveEpoch = (int) update.getEpoch() + 1;

            // Merge all genesis-key proposals into a single update for this tx
            ProtocolParamUpdate txMerged = null;
            for (var entry : update.getProtocolParamUpdates().values()) {
                txMerged = (txMerged == null) ? entry : mergeUpdates(txMerged, entry);
            }

            if (txMerged != null) {
                pendingUpdates.merge(effectiveEpoch, txMerged, this::mergeUpdates);

                // Persist pending update through the caller's WriteBatch.
                // Failures must propagate — silently losing the pending key is the bug we're fixing.
                if (db != null && cfEpochParams != null) {
                    try {
                        byte[] key = pendingUpdateKey(effectiveEpoch, slot, txIdx);
                        byte[] val = JSON.writeValueAsBytes(txMerged);
                        batch.put(cfEpochParams, key, val);
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "Failed to write pending param update to batch for epoch " + effectiveEpoch, e);
                    }
                }

                log.debug("Tracked pre-Conway param update for epoch {} (proposal epoch {}, slot {}, txIdx {})",
                        effectiveEpoch, update.getEpoch(), slot, txIdx);
            }
        }
        // Conway ParameterChangeAction proposals are handled by GovernanceEpochProcessor
        // after ratification. They call applyEnactedParamChange() at epoch boundary.
    }

    /**
     * Apply a ratified and enacted ParameterChangeAction for the given epoch.
     * Called by GovernanceEpochProcessor when a ParameterChangeAction is enacted at epoch boundary.
     * <p>
     * In Conway+, the flow is:
     * <ol>
     *   <li>ParameterChangeAction proposal submitted</li>
     *   <li>Voted on during voting window</li>
     *   <li>Ratified at epoch N boundary (thresholds met)</li>
     *   <li>Enacted at epoch N+1 boundary → this method is called with epoch=N+1</li>
     *   <li>New params take effect for epoch N+1 onwards</li>
     * </ol>
     *
     * @param epoch  The epoch at which the enacted params take effect
     * @param update The protocol parameter update from the enacted ParameterChangeAction
     */
    public void applyEnactedParamChange(int epoch, ProtocolParamUpdate update) {
        try {
            applyEnactedParamChange(epoch, update, null);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to persist enacted protocol params for epoch " + epoch, e);
        }
    }

    /**
     * Apply a ratified and enacted ParameterChangeAction through the caller's batch.
     * This keeps governance enactment and protocol-parameter persistence in the same
     * RocksDB commit when invoked from the governance epoch boundary.
     */
    public void applyEnactedParamChange(int epoch, ProtocolParamUpdate update, WriteBatch batch) throws RocksDBException {
        if (!enabled || update == null) return;

        ProtocolParamUpdate base = epochParams.get(epoch);
        if (base == null) {
            base = materializeEffectiveParams(epoch, null);
        }
        if (base == null) {
            log.warn("Cannot apply enacted governance param change for epoch {} before Shelley params are available", epoch);
            return;
        }

        ProtocolParamUpdate resolved = mergeUpdates(base, update);
        epochParams.put(epoch, resolved);
        persistEpochParams(epoch, resolved, batch);
        log.info("Applied enacted governance param change for epoch {} (full snapshot update)", epoch);
    }

    /**
     * Finalize parameters for an epoch. Called at epoch boundary.
     * Merges any pending updates into the resolved epoch params and persists to RocksDB.
     */
    public void finalizeEpoch(int epoch) {
        if (!enabled) return;
        var pending = pendingUpdates.remove(epoch);

        ProtocolParamUpdate resolved = materializeEffectiveParams(epoch, pending);
        if (resolved == null) return;

        epochParams.put(epoch, resolved);
        persistEpochParams(epoch, resolved);

        if (pending != null) {
            log.info("Finalized full protocol params for epoch {} from block updates", epoch);
        } else {
            log.debug("Finalized carried full protocol params for epoch {}", epoch);
        }
    }

    /**
     * Bootstrap protocol parameters for the first non-Byron epoch.
     * <p>
     * This uses the same materialization path as epoch-boundary finalization, but
     * without consuming pending updates. It is intentionally restricted to the
     * first non-Byron epoch and idempotent, so restarts with already persisted
     * epoch params are no-ops.
     *
     * @return true if a new genesis snapshot was materialized and persisted
     */
    public boolean bootstrapEpochIfNeeded(int epoch) {
        if (!enabled) return false;
        if (epoch != firstNonByronEpoch()) return false;
        if (epochParams.containsKey(epoch)) return false;

        ProtocolParamUpdate resolved = materializeEffectiveParams(epoch, null);
        if (resolved == null) return false;

        epochParams.put(epoch, resolved);
        persistEpochParams(epoch, resolved);
        log.info("Bootstrapped genesis protocol params for epoch {}", epoch);
        return true;
    }

    /**
     * Get the resolved ProtocolParamUpdate for an epoch, or null if none tracked.
     */
    public ProtocolParamUpdate getResolvedParams(int epoch) {
        return epochParams.get(epoch);
    }

    private ProtocolParamUpdate materializeEffectiveParams(int epoch, ProtocolParamUpdate pending) {
        if (epoch < firstNonByronEpoch()) {
            return null;
        }

        ProtocolParamUpdate resolved = epochParams.get(epoch);
        if (resolved == null) {
            ProtocolParamUpdate previous = previousSnapshot(epoch);
            if (previous != null) {
                resolved = copyUpdate(previous);
                if (isEraTransition(epoch, Era.Alonzo)) {
                    resolved = mergeUpdates(resolved, alonzoOverlay(epoch));
                    resolved = applyAlonzoRules(resolved);
                }
                if (isEraTransition(epoch, Era.Babbage)) {
                    resolved = applyBabbageRules(resolved);
                }
                if (isEraTransition(epoch, Era.Conway)) {
                    resolved = mergeUpdates(resolved, conwayOverlay(epoch));
                }
            } else {
                resolved = materializeGenesisSnapshot(epoch);
            }
        } else {
            resolved = copyUpdate(resolved);
        }

        if (pending != null) {
            resolved = mergeUpdates(resolved, pending);
        }

        return resolved;
    }

    private ProtocolParamUpdate materializeGenesisSnapshot(int epoch) {
        ProtocolParamUpdate resolved = shelleyOverlay(epoch);

        if (isEraOrLater(epoch, Era.Alonzo)) {
            resolved = mergeUpdates(resolved, alonzoOverlay(epoch));
            resolved = applyAlonzoRules(resolved);
        }
        if (isEraOrLater(epoch, Era.Babbage)) {
            resolved = applyBabbageRules(resolved);
        }
        if (isEraOrLater(epoch, Era.Conway)) {
            resolved = mergeUpdates(resolved, conwayOverlay(epoch));
        }

        return resolved;
    }

    private ProtocolParamUpdate shelleyOverlay(int epoch) {
        String extraEntropy = baseProvider.getExtraEntropy(epoch);
        return ProtocolParamUpdate.builder()
                .minFeeA(baseProvider.getMinFeeA(epoch))
                .minFeeB(baseProvider.getMinFeeB(epoch))
                .maxBlockSize(baseProvider.getMaxBlockSize(epoch))
                .maxTxSize(baseProvider.getMaxTxSize(epoch))
                .maxBlockHeaderSize(baseProvider.getMaxBlockHeaderSize(epoch))
                .keyDeposit(baseProvider.getKeyDeposit(epoch))
                .poolDeposit(baseProvider.getPoolDeposit(epoch))
                .maxEpoch(baseProvider.getMaxEpoch(epoch))
                .nOpt(baseProvider.getNOpt(epoch))
                .poolPledgeInfluence(baseProvider.getA0Interval(epoch))
                .expansionRate(baseProvider.getRhoInterval(epoch))
                .treasuryGrowthRate(baseProvider.getTauInterval(epoch))
                .decentralisationParam(baseProvider.getDecentralizationInterval(epoch))
                .extraEntropy(extraEntropy != null ? new Tuple<>(0, extraEntropy) : null)
                .protocolMajorVer(baseProvider.getProtocolMajor(epoch))
                .protocolMinorVer(baseProvider.getProtocolMinor(epoch))
                .minUtxo(baseProvider.getMinUtxo(epoch))
                .minPoolCost(baseProvider.getMinPoolCost(epoch))
                .build();
    }

    private ProtocolParamUpdate alonzoOverlay(int epoch) {
        return ProtocolParamUpdate.builder()
                .costModels(namedCostModels(baseProvider.getAlonzoCostModels(epoch)))
                .priceMem(baseProvider.getPriceMemInterval(epoch))
                .priceStep(baseProvider.getPriceStepInterval(epoch))
                .maxTxExMem(baseProvider.getMaxTxExMem(epoch))
                .maxTxExSteps(baseProvider.getMaxTxExSteps(epoch))
                .maxBlockExMem(baseProvider.getMaxBlockExMem(epoch))
                .maxBlockExSteps(baseProvider.getMaxBlockExSteps(epoch))
                .maxValSize(toLong(baseProvider.getMaxValSize(epoch)))
                .collateralPercent(baseProvider.getCollateralPercent(epoch))
                .maxCollateralInputs(baseProvider.getMaxCollateralInputs(epoch))
                .adaPerUtxoByte(baseProvider.getCoinsPerUtxoWord(epoch))
                .build();
    }

    private ProtocolParamUpdate conwayOverlay(int epoch) {
        return ProtocolParamUpdate.builder()
                .costModels(namedCostModels(baseProvider.getConwayCostModels(epoch)))
                .govActionLifetime(baseProvider.getGovActionLifetime(epoch))
                .govActionDeposit(baseProvider.getGovActionDeposit(epoch))
                .drepDeposit(baseProvider.getDRepDeposit(epoch))
                .drepActivity(baseProvider.getDRepActivity(epoch))
                .committeeMinSize(baseProvider.getCommitteeMinSize(epoch))
                .committeeMaxTermLength(baseProvider.getCommitteeMaxTermLength(epoch))
                .poolVotingThresholds(baseProvider.getPoolVotingThresholds(epoch))
                .drepVotingThresholds(baseProvider.getDrepVotingThresholds(epoch))
                .minFeeRefScriptCostPerByte(baseProvider.getMinFeeRefScriptCostPerByteInterval(epoch))
                .build();
    }

    private ProtocolParamUpdate applyAlonzoRules(ProtocolParamUpdate params) {
        return ProtocolParamUpdate.builder()
                .minFeeA(params.getMinFeeA())
                .minFeeB(params.getMinFeeB())
                .maxBlockSize(params.getMaxBlockSize())
                .maxTxSize(params.getMaxTxSize())
                .maxBlockHeaderSize(params.getMaxBlockHeaderSize())
                .keyDeposit(params.getKeyDeposit())
                .poolDeposit(params.getPoolDeposit())
                .maxEpoch(params.getMaxEpoch())
                .nOpt(params.getNOpt())
                .poolPledgeInfluence(params.getPoolPledgeInfluence())
                .expansionRate(params.getExpansionRate())
                .treasuryGrowthRate(params.getTreasuryGrowthRate())
                .decentralisationParam(params.getDecentralisationParam())
                .extraEntropy(params.getExtraEntropy())
                .protocolMajorVer(params.getProtocolMajorVer())
                .protocolMinorVer(params.getProtocolMinorVer())
                .minUtxo(null)
                .minPoolCost(params.getMinPoolCost())
                .adaPerUtxoByte(params.getAdaPerUtxoByte())
                .costModels(params.getCostModels())
                .costModelsHash(params.getCostModelsHash())
                .priceMem(params.getPriceMem())
                .priceStep(params.getPriceStep())
                .maxTxExMem(params.getMaxTxExMem())
                .maxTxExSteps(params.getMaxTxExSteps())
                .maxBlockExMem(params.getMaxBlockExMem())
                .maxBlockExSteps(params.getMaxBlockExSteps())
                .maxValSize(params.getMaxValSize())
                .collateralPercent(params.getCollateralPercent())
                .maxCollateralInputs(params.getMaxCollateralInputs())
                .govActionLifetime(params.getGovActionLifetime())
                .govActionDeposit(params.getGovActionDeposit())
                .drepDeposit(params.getDrepDeposit())
                .drepActivity(params.getDrepActivity())
                .committeeMinSize(params.getCommitteeMinSize())
                .committeeMaxTermLength(params.getCommitteeMaxTermLength())
                .poolVotingThresholds(params.getPoolVotingThresholds())
                .drepVotingThresholds(params.getDrepVotingThresholds())
                .minFeeRefScriptCostPerByte(params.getMinFeeRefScriptCostPerByte())
                .build();
    }

    private ProtocolParamUpdate applyBabbageRules(ProtocolParamUpdate params) {
        BigInteger coinsPerUtxoByte = params.getAdaPerUtxoByte();
        if (coinsPerUtxoByte != null && coinsPerUtxoByte.compareTo(BigInteger.valueOf(10_000)) > 0) {
            coinsPerUtxoByte = coinsPerUtxoByte.divide(WORDS_PER_UTXO_BYTE);
        }

        return ProtocolParamUpdate.builder()
                .minFeeA(params.getMinFeeA())
                .minFeeB(params.getMinFeeB())
                .maxBlockSize(params.getMaxBlockSize())
                .maxTxSize(params.getMaxTxSize())
                .maxBlockHeaderSize(params.getMaxBlockHeaderSize())
                .keyDeposit(params.getKeyDeposit())
                .poolDeposit(params.getPoolDeposit())
                .maxEpoch(params.getMaxEpoch())
                .nOpt(params.getNOpt())
                .poolPledgeInfluence(params.getPoolPledgeInfluence())
                .expansionRate(params.getExpansionRate())
                .treasuryGrowthRate(params.getTreasuryGrowthRate())
                .decentralisationParam(null)
                .extraEntropy(null)
                .protocolMajorVer(params.getProtocolMajorVer())
                .protocolMinorVer(params.getProtocolMinorVer())
                .minUtxo(params.getMinUtxo())
                .minPoolCost(params.getMinPoolCost())
                .adaPerUtxoByte(coinsPerUtxoByte)
                .costModels(params.getCostModels())
                .costModelsHash(params.getCostModelsHash())
                .priceMem(params.getPriceMem())
                .priceStep(params.getPriceStep())
                .maxTxExMem(params.getMaxTxExMem())
                .maxTxExSteps(params.getMaxTxExSteps())
                .maxBlockExMem(params.getMaxBlockExMem())
                .maxBlockExSteps(params.getMaxBlockExSteps())
                .maxValSize(params.getMaxValSize())
                .collateralPercent(params.getCollateralPercent())
                .maxCollateralInputs(params.getMaxCollateralInputs())
                .govActionLifetime(params.getGovActionLifetime())
                .govActionDeposit(params.getGovActionDeposit())
                .drepDeposit(params.getDrepDeposit())
                .drepActivity(params.getDrepActivity())
                .committeeMinSize(params.getCommitteeMinSize())
                .committeeMaxTermLength(params.getCommitteeMaxTermLength())
                .poolVotingThresholds(params.getPoolVotingThresholds())
                .drepVotingThresholds(params.getDrepVotingThresholds())
                .minFeeRefScriptCostPerByte(params.getMinFeeRefScriptCostPerByte())
                .build();
    }

    private ProtocolParamUpdate copyUpdate(ProtocolParamUpdate params) {
        return ProtocolParamUpdate.builder()
                .minFeeA(params.getMinFeeA())
                .minFeeB(params.getMinFeeB())
                .maxBlockSize(params.getMaxBlockSize())
                .maxTxSize(params.getMaxTxSize())
                .maxBlockHeaderSize(params.getMaxBlockHeaderSize())
                .keyDeposit(params.getKeyDeposit())
                .poolDeposit(params.getPoolDeposit())
                .maxEpoch(params.getMaxEpoch())
                .nOpt(params.getNOpt())
                .poolPledgeInfluence(params.getPoolPledgeInfluence())
                .expansionRate(params.getExpansionRate())
                .treasuryGrowthRate(params.getTreasuryGrowthRate())
                .decentralisationParam(params.getDecentralisationParam())
                .extraEntropy(params.getExtraEntropy())
                .protocolMajorVer(params.getProtocolMajorVer())
                .protocolMinorVer(params.getProtocolMinorVer())
                .minUtxo(params.getMinUtxo())
                .minPoolCost(params.getMinPoolCost())
                .adaPerUtxoByte(params.getAdaPerUtxoByte())
                .costModels(params.getCostModels() != null ? new LinkedHashMap<>(params.getCostModels()) : null)
                .costModelsHash(params.getCostModelsHash())
                .priceMem(params.getPriceMem())
                .priceStep(params.getPriceStep())
                .maxTxExMem(params.getMaxTxExMem())
                .maxTxExSteps(params.getMaxTxExSteps())
                .maxBlockExMem(params.getMaxBlockExMem())
                .maxBlockExSteps(params.getMaxBlockExSteps())
                .maxValSize(params.getMaxValSize())
                .collateralPercent(params.getCollateralPercent())
                .maxCollateralInputs(params.getMaxCollateralInputs())
                .govActionLifetime(params.getGovActionLifetime())
                .govActionDeposit(params.getGovActionDeposit())
                .drepDeposit(params.getDrepDeposit())
                .drepActivity(params.getDrepActivity())
                .committeeMinSize(params.getCommitteeMinSize())
                .committeeMaxTermLength(params.getCommitteeMaxTermLength())
                .poolVotingThresholds(params.getPoolVotingThresholds())
                .drepVotingThresholds(params.getDrepVotingThresholds())
                .minFeeRefScriptCostPerByte(params.getMinFeeRefScriptCostPerByte())
                .build();
    }

    private int firstNonByronEpoch() {
        return baseProvider.getEpochSlotCalc().slotToEpoch(baseProvider.getShelleyStartSlot());
    }

    private ProtocolParamUpdate previousSnapshot(int epoch) {
        return epochParams.entrySet().stream()
                .filter(entry -> entry.getKey() < epoch)
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private boolean isEraTransition(int epoch, Era era) {
        return isEraOrLater(epoch, era) && (epoch == 0 || !isEraOrLater(epoch - 1, era));
    }

    private boolean isEraOrLater(int epoch, Era era) {
        if (era == Era.Shelley) {
            return epoch >= firstNonByronEpoch();
        }
        if (eraProvider != null) {
            return eraProvider.isEraOrLater(epoch, era.getValue());
        }
        return true;
    }

    private static Long toLong(BigInteger value) {
        return value != null ? value.longValue() : null;
    }

    private Map<Integer, String> namedCostModels(Map<String, Object> costModels) {
        if (costModels == null || costModels.isEmpty()) return null;

        Map<Integer, String> result = new LinkedHashMap<>();
        costModels.forEach((language, model) -> {
            Integer key = plutusLanguageKey(language);
            if (key != null) {
                try {
                    result.put(key, JSON.writeValueAsString(CostModelUtil.canonicalCostModelList(model)));
                } catch (Exception e) {
                    log.warn("Failed to serialize {} cost model from genesis: {}", language, e.getMessage());
                }
            }
        });
        return result;
    }

    private Integer plutusLanguageKey(String language) {
        if (language == null) return null;
        return switch (language) {
            case "PlutusV1" -> 0;
            case "PlutusV2" -> 1;
            case "PlutusV3" -> 2;
            default -> null;
        };
    }

    // --- Rollback support ---

    /**
     * Enqueue rollback deletes into the caller's {@link WriteBatch} without mutating
     * in-memory state or committing. Call {@link #reloadAfterRollback()} after the
     * batch is committed to rebuild in-memory maps from rolled-back RocksDB state.
     *
     * @param targetSlot  Rollback target slot — pending keys with sourceSlot &gt; targetSlot are deleted
     * @param targetEpoch Rollback target epoch — finalized keys with epoch &gt; targetEpoch are deleted
     * @param batch       The caller's WriteBatch (same batch used for cfState/cfDelta rollback)
     */
    public void addRollbackOps(long targetSlot, int targetEpoch, WriteBatch batch) throws RocksDBException {
        if (db == null || cfEpochParams == null) return;

        int deletedPending = 0;
        int deletedFinalized = 0;

        try (RocksIterator it = db.newIterator(cfEpochParams)) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] key = it.key();

                if (key.length == 17 && key[0] == KEY_PENDING_UPDATE) {
                    // Pending key: 'P' | effectiveEpoch(4) | sourceSlot(8) | txIdx(4)
                    ByteBuffer buf = ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN);
                    buf.get(); // skip prefix
                    buf.getInt(); // skip effectiveEpoch
                    long sourceSlot = buf.getLong();

                    if (sourceSlot > targetSlot) {
                        batch.delete(cfEpochParams, Arrays.copyOf(key, key.length));
                        deletedPending++;
                    }
                } else if (key.length == 4) {
                    // Finalized key: epoch(4 BE)
                    int epoch = ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN).getInt();
                    if (epoch > targetEpoch) {
                        batch.delete(cfEpochParams, Arrays.copyOf(key, key.length));
                        deletedFinalized++;
                    }
                }

                it.next();
            }
        }

        if (deletedPending > 0 || deletedFinalized > 0) {
            log.info("Rollback to slot {}/epoch {}: queued deletion of {} pending + {} finalized epoch param keys",
                    targetSlot, targetEpoch, deletedPending, deletedFinalized);
        }
    }

    /**
     * Rebuild in-memory maps from RocksDB after a rollback batch has been committed.
     * Must be called after {@link #addRollbackOps} and {@code db.write(batch)}.
     */
    public void reloadAfterRollback() {
        epochParams.clear();
        pendingUpdates.clear();
        if (db != null && cfEpochParams != null) {
            loadPersistedParams();
        }
    }

    // --- EpochParamProvider delegation ---

    @Override
    public BigInteger getKeyDeposit(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getKeyDeposit();
        return baseProvider.getKeyDeposit(epoch);
    }

    @Override
    public BigInteger getPoolDeposit(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getPoolDeposit();
        return baseProvider.getPoolDeposit(epoch);
    }

    @Override
    public Integer getMinFeeA(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMinFeeA();
        return baseProvider.getMinFeeA(epoch);
    }

    @Override
    public Integer getMinFeeB(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMinFeeB();
        return baseProvider.getMinFeeB(epoch);
    }

    @Override
    public Integer getMaxBlockSize(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMaxBlockSize();
        return baseProvider.getMaxBlockSize(epoch);
    }

    @Override
    public Integer getMaxTxSize(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMaxTxSize();
        return baseProvider.getMaxTxSize(epoch);
    }

    @Override
    public Integer getMaxBlockHeaderSize(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMaxBlockHeaderSize();
        return baseProvider.getMaxBlockHeaderSize(epoch);
    }

    @Override
    public Integer getMaxEpoch(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMaxEpoch();
        return baseProvider.getMaxEpoch(epoch);
    }

    @Override
    public String getExtraEntropy(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getExtraEntropy() != null ? update.getExtraEntropy()._2 : null;
        return baseProvider.getExtraEntropy(epoch);
    }

    @Override
    public BigInteger getMinUtxo(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMinUtxo();
        return baseProvider.getMinUtxo(epoch);
    }

    @Override
    public Map<String, Object> getCostModels(long epoch) {
        var update = epochParams.get((int) epoch);
        Map<String, Object> mapped = costModels(update);
        if (update != null) return mapped;
        return baseProvider.getCostModels(epoch);
    }

    @Override
    public Map<String, Object> getCostModelsRaw(long epoch) {
        var update = epochParams.get((int) epoch);
        Map<String, Object> mapped = rawCostModels(update);
        if (update != null) return mapped;
        return baseProvider.getCostModelsRaw(epoch);
    }

    @Override
    public BigDecimal getPriceMem(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getPriceMem() != null ? update.getPriceMem().safeRatio() : null;
        return baseProvider.getPriceMem(epoch);
    }

    @Override
    public BigDecimal getPriceStep(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getPriceStep() != null ? update.getPriceStep().safeRatio() : null;
        return baseProvider.getPriceStep(epoch);
    }

    @Override
    public BigInteger getMaxTxExMem(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMaxTxExMem();
        return baseProvider.getMaxTxExMem(epoch);
    }

    @Override
    public BigInteger getMaxTxExSteps(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMaxTxExSteps();
        return baseProvider.getMaxTxExSteps(epoch);
    }

    @Override
    public BigInteger getMaxBlockExMem(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMaxBlockExMem();
        return baseProvider.getMaxBlockExMem(epoch);
    }

    @Override
    public BigInteger getMaxBlockExSteps(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMaxBlockExSteps();
        return baseProvider.getMaxBlockExSteps(epoch);
    }

    @Override
    public BigInteger getMaxValSize(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMaxValSize() != null ? BigInteger.valueOf(update.getMaxValSize()) : null;
        return baseProvider.getMaxValSize(epoch);
    }

    @Override
    public Integer getCollateralPercent(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getCollateralPercent();
        return baseProvider.getCollateralPercent(epoch);
    }

    @Override
    public Integer getMaxCollateralInputs(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMaxCollateralInputs();
        return baseProvider.getMaxCollateralInputs(epoch);
    }

    @Override
    public BigInteger getCoinsPerUtxoSize(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) {
            return update.getAdaPerUtxoByte() != null && isEraOrLater((int) epoch, Era.Babbage)
                    ? update.getAdaPerUtxoByte()
                    : null;
        }
        return baseProvider.getCoinsPerUtxoSize(epoch);
    }

    @Override
    public BigInteger getCoinsPerUtxoWord(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) {
            return update.getAdaPerUtxoByte() != null && !isEraOrLater((int) epoch, Era.Babbage)
                    ? update.getAdaPerUtxoByte()
                    : null;
        }
        return baseProvider.getCoinsPerUtxoWord(epoch);
    }

    @Override
    public BigDecimal getMinFeeRefScriptCostPerByte(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) {
            return update.getMinFeeRefScriptCostPerByte() != null
                    ? update.getMinFeeRefScriptCostPerByte().safeRatio()
                    : null;
        }
        return baseProvider.getMinFeeRefScriptCostPerByte(epoch);
    }

    @Override
    public long getEpochLength() {
        return baseProvider.getEpochLength();
    }

    @Override
    public long getByronSlotsPerEpoch() {
        return baseProvider.getByronSlotsPerEpoch();
    }

    @Override
    public long getShelleyStartSlot() {
        return baseProvider.getShelleyStartSlot();
    }

    @Override
    public BigDecimal getRho(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getExpansionRate() != null ? update.getExpansionRate().safeRatio() : null;
        return baseProvider.getRho(epoch);
    }

    @Override
    public BigDecimal getTau(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getTreasuryGrowthRate() != null ? update.getTreasuryGrowthRate().safeRatio() : null;
        return baseProvider.getTau(epoch);
    }

    @Override
    public BigDecimal getA0(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getPoolPledgeInfluence() != null ? update.getPoolPledgeInfluence().safeRatio() : null;
        return baseProvider.getA0(epoch);
    }

    @Override
    public BigDecimal getDecentralization(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getDecentralisationParam() != null
                ? update.getDecentralisationParam().safeRatio()
                : null;
        return baseProvider.getDecentralization(epoch);
    }

    @Override
    public int getNOpt(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getNOpt() != null) return update.getNOpt();
        return baseProvider.getNOpt(epoch);
    }

    @Override
    public BigInteger getMinPoolCost(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getMinPoolCost();
        return baseProvider.getMinPoolCost(epoch);
    }

    @Override
    public int getProtocolMajor(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getProtocolMajorVer() != null) return update.getProtocolMajorVer();
        return baseProvider.getProtocolMajor(epoch);
    }

    @Override
    public int getProtocolMinor(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getProtocolMinorVer() != null) return update.getProtocolMinorVer();
        return baseProvider.getProtocolMinor(epoch);
    }

    // --- Conway governance parameters ---

    @Override
    public int getGovActionLifetime(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getGovActionLifetime() != null) return update.getGovActionLifetime();
        return baseProvider.getGovActionLifetime(epoch);
    }

    @Override
    public int getDRepActivity(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getDrepActivity() != null) return update.getDrepActivity();
        return baseProvider.getDRepActivity(epoch);
    }

    @Override
    public BigInteger getGovActionDeposit(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getGovActionDeposit();
        return baseProvider.getGovActionDeposit(epoch);
    }

    @Override
    public BigInteger getDRepDeposit(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getDrepDeposit();
        return baseProvider.getDRepDeposit(epoch);
    }

    @Override
    public int getCommitteeMinSize(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getCommitteeMinSize() != null) return update.getCommitteeMinSize();
        return baseProvider.getCommitteeMinSize(epoch);
    }

    @Override
    public int getCommitteeMaxTermLength(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null && update.getCommitteeMaxTermLength() != null) return update.getCommitteeMaxTermLength();
        return baseProvider.getCommitteeMaxTermLength(epoch);
    }

    @Override
    public DrepVoteThresholds getDrepVotingThresholds(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getDrepVotingThresholds();
        return baseProvider.getDrepVotingThresholds(epoch);
    }

    @Override
    public PoolVotingThresholds getPoolVotingThresholds(long epoch) {
        var update = epochParams.get((int) epoch);
        if (update != null) return update.getPoolVotingThresholds();
        return baseProvider.getPoolVotingThresholds(epoch);
    }

    // ===== RocksDB Persistence (dedicated epoch_params column family) =====

    /** Key: epoch as 4-byte big-endian int */
    private static byte[] epochKey(int epoch) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
    }

    private void persistEpochParams(int epoch, ProtocolParamUpdate params) {
        try {
            persistEpochParams(epoch, params, null);
        } catch (RocksDBException e) {
            log.warn("Failed to persist epoch params for epoch {}: {}", epoch, e.getMessage());
        }
    }

    private void persistEpochParams(int epoch, ProtocolParamUpdate params, WriteBatch batch) throws RocksDBException {
        if (db == null || cfEpochParams == null) return;
        try {
            byte[] val = JSON.writeValueAsBytes(params);
            if (batch != null) {
                batch.put(cfEpochParams, epochKey(epoch), val);
            } else {
                db.put(cfEpochParams, epochKey(epoch), val);
            }
        } catch (RocksDBException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize epoch params for epoch " + epoch, e);
        }
    }

    /**
     * Load all persisted epoch params from RocksDB into the in-memory maps.
     * Called at construction and after rollback to restore state.
     * <p>
     * Two passes:
     * <ol>
     *   <li>Load 4-byte finalized epoch keys into {@code epochParams}.</li>
     *   <li>Load 17-byte pending keys where effectiveEpoch &gt; maxFinalizedEpoch
     *       into {@code pendingUpdates}, merging in RocksDB iteration order.</li>
     * </ol>
     */
    private void loadPersistedParams() {
        int finalizedCount = 0;
        int pendingCount = 0;
        int maxFinalizedEpoch = -1;

        // Pass 1: load finalized 4-byte epoch keys
        try (RocksIterator it = db.newIterator(cfEpochParams)) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length == 4) {
                    int epoch = ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN).getInt();
                    try {
                        ProtocolParamUpdate params = JSON.readValue(it.value(), ProtocolParamUpdate.class);
                        epochParams.put(epoch, params);
                        finalizedCount++;
                        if (epoch > maxFinalizedEpoch) maxFinalizedEpoch = epoch;
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "Failed to deserialize finalized epoch params for epoch " + epoch
                                        + ". Refusing to continue with partial protocol parameter state.", e);
                    }
                }
                it.next();
            }
        }

        // Pass 2: load pending keys (17 bytes, prefix 'P') where effectiveEpoch > maxFinalizedEpoch
        try (RocksIterator it = db.newIterator(cfEpochParams)) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length == 17 && key[0] == KEY_PENDING_UPDATE) {
                    ByteBuffer buf = ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN);
                    buf.get(); // skip prefix
                    int effectiveEpoch = buf.getInt();

                    if (effectiveEpoch > maxFinalizedEpoch) {
                        try {
                            ProtocolParamUpdate params = JSON.readValue(it.value(), ProtocolParamUpdate.class);
                            pendingUpdates.merge(effectiveEpoch, params, this::mergeUpdates);
                            pendingCount++;
                        } catch (Exception e) {
                            throw new IllegalStateException(
                                    "Failed to deserialize pending param update for effective epoch " + effectiveEpoch
                                            + ". Refusing to continue with partial protocol parameter state.", e);
                        }
                    }
                }
                it.next();
            }
        }

        if (finalizedCount > 0) {
            int minEpoch = epochParams.keySet().stream().mapToInt(Integer::intValue).min().orElse(-1);
            log.info("Loaded {} finalized epoch params from RocksDB (epochs {}-{})",
                    finalizedCount, minEpoch, maxFinalizedEpoch);
        }
        if (pendingCount > 0) {
            log.info("Loaded {} pending param update entries from RocksDB into {} effective epochs",
                    pendingCount, pendingUpdates.size());
        }
    }

    /**
     * Merge two ProtocolParamUpdate instances. Non-null fields in {@code newer} override {@code older}.
     */
    private ProtocolParamUpdate mergeUpdates(ProtocolParamUpdate older, ProtocolParamUpdate newer) {
        return ProtocolParamUpdate.builder()
                // Pre-Conway fields
                .minFeeA(newer.getMinFeeA() != null ? newer.getMinFeeA() : older.getMinFeeA())
                .minFeeB(newer.getMinFeeB() != null ? newer.getMinFeeB() : older.getMinFeeB())
                .maxBlockSize(newer.getMaxBlockSize() != null ? newer.getMaxBlockSize() : older.getMaxBlockSize())
                .maxTxSize(newer.getMaxTxSize() != null ? newer.getMaxTxSize() : older.getMaxTxSize())
                .maxBlockHeaderSize(newer.getMaxBlockHeaderSize() != null ? newer.getMaxBlockHeaderSize() : older.getMaxBlockHeaderSize())
                .keyDeposit(newer.getKeyDeposit() != null ? newer.getKeyDeposit() : older.getKeyDeposit())
                .poolDeposit(newer.getPoolDeposit() != null ? newer.getPoolDeposit() : older.getPoolDeposit())
                .maxEpoch(newer.getMaxEpoch() != null ? newer.getMaxEpoch() : older.getMaxEpoch())
                .nOpt(newer.getNOpt() != null ? newer.getNOpt() : older.getNOpt())
                .poolPledgeInfluence(newer.getPoolPledgeInfluence() != null ? newer.getPoolPledgeInfluence() : older.getPoolPledgeInfluence())
                .expansionRate(newer.getExpansionRate() != null ? newer.getExpansionRate() : older.getExpansionRate())
                .treasuryGrowthRate(newer.getTreasuryGrowthRate() != null ? newer.getTreasuryGrowthRate() : older.getTreasuryGrowthRate())
                .decentralisationParam(newer.getDecentralisationParam() != null ? newer.getDecentralisationParam() : older.getDecentralisationParam())
                .extraEntropy(newer.getExtraEntropy() != null ? newer.getExtraEntropy() : older.getExtraEntropy())
                .protocolMajorVer(newer.getProtocolMajorVer() != null ? newer.getProtocolMajorVer() : older.getProtocolMajorVer())
                .protocolMinorVer(newer.getProtocolMinorVer() != null ? newer.getProtocolMinorVer() : older.getProtocolMinorVer())
                .minUtxo(newer.getMinUtxo() != null ? newer.getMinUtxo() : older.getMinUtxo())
                .minPoolCost(newer.getMinPoolCost() != null ? newer.getMinPoolCost() : older.getMinPoolCost())
                .costModels(mergeCostModels(older.getCostModels(), newer.getCostModels()))
                .costModelsHash(newer.getCostModelsHash() != null ? newer.getCostModelsHash() : older.getCostModelsHash())
                .priceMem(newer.getPriceMem() != null ? newer.getPriceMem() : older.getPriceMem())
                .priceStep(newer.getPriceStep() != null ? newer.getPriceStep() : older.getPriceStep())
                .maxTxExMem(newer.getMaxTxExMem() != null ? newer.getMaxTxExMem() : older.getMaxTxExMem())
                .maxTxExSteps(newer.getMaxTxExSteps() != null ? newer.getMaxTxExSteps() : older.getMaxTxExSteps())
                .maxBlockExMem(newer.getMaxBlockExMem() != null ? newer.getMaxBlockExMem() : older.getMaxBlockExMem())
                .maxBlockExSteps(newer.getMaxBlockExSteps() != null ? newer.getMaxBlockExSteps() : older.getMaxBlockExSteps())
                .maxValSize(newer.getMaxValSize() != null ? newer.getMaxValSize() : older.getMaxValSize())
                .collateralPercent(newer.getCollateralPercent() != null ? newer.getCollateralPercent() : older.getCollateralPercent())
                .maxCollateralInputs(newer.getMaxCollateralInputs() != null ? newer.getMaxCollateralInputs() : older.getMaxCollateralInputs())
                .adaPerUtxoByte(newer.getAdaPerUtxoByte() != null ? newer.getAdaPerUtxoByte() : older.getAdaPerUtxoByte())
                // Conway governance fields
                .govActionLifetime(newer.getGovActionLifetime() != null ? newer.getGovActionLifetime() : older.getGovActionLifetime())
                .govActionDeposit(newer.getGovActionDeposit() != null ? newer.getGovActionDeposit() : older.getGovActionDeposit())
                .drepDeposit(newer.getDrepDeposit() != null ? newer.getDrepDeposit() : older.getDrepDeposit())
                .drepActivity(newer.getDrepActivity() != null ? newer.getDrepActivity() : older.getDrepActivity())
                .committeeMinSize(newer.getCommitteeMinSize() != null ? newer.getCommitteeMinSize() : older.getCommitteeMinSize())
                .committeeMaxTermLength(newer.getCommitteeMaxTermLength() != null ? newer.getCommitteeMaxTermLength() : older.getCommitteeMaxTermLength())
                .poolVotingThresholds(newer.getPoolVotingThresholds() != null ? newer.getPoolVotingThresholds() : older.getPoolVotingThresholds())
                .drepVotingThresholds(newer.getDrepVotingThresholds() != null ? newer.getDrepVotingThresholds() : older.getDrepVotingThresholds())
                .minFeeRefScriptCostPerByte(newer.getMinFeeRefScriptCostPerByte() != null ? newer.getMinFeeRefScriptCostPerByte() : older.getMinFeeRefScriptCostPerByte())
                .build();
    }

    private Map<String, Object> costModels(ProtocolParamUpdate update) {
        if (update == null || update.getCostModels() == null || update.getCostModels().isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        update.getCostModels().forEach((language, model) ->
                result.put(plutusLanguageName(language), decodeCostModel(model)));
        return CostModelUtil.canonicalCostModels(result);
    }

    private Map<String, Object> rawCostModels(ProtocolParamUpdate update) {
        if (update == null || update.getCostModels() == null || update.getCostModels().isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        update.getCostModels().forEach((language, model) ->
                result.put(plutusLanguageName(language), decodeCostModel(model)));
        return CostModelUtil.canonicalRawCostModels(result);
    }

    private Map<Integer, String> mergeCostModels(Map<Integer, String> older, Map<Integer, String> newer) {
        if ((older == null || older.isEmpty()) && (newer == null || newer.isEmpty())) {
            return null;
        }
        Map<Integer, String> merged = new LinkedHashMap<>();
        if (older != null) merged.putAll(older);
        if (newer != null) merged.putAll(newer);
        return merged;
    }

    private Object decodeCostModel(String model) {
        if (model == null || model.isBlank()) return model;
        String trimmed = model.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                return JSON.readValue(trimmed, Object.class);
            } catch (Exception ignored) {
                return model;
            }
        }

        try {
            Array array = (Array) CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(trimmed));
            List<Long> costs = new ArrayList<>();
            for (DataItem di : array.getDataItems()) {
                if (di == Special.BREAK) continue;
                BigInteger val = ((Number) di).getValue();
                costs.add(val.longValue());
            }
            return costs;
        } catch (Exception ignored) {
            return model;
        }
    }

    private String plutusLanguageName(Integer language) {
        if (language == null) return "Unknown";
        return switch (language) {
            case 0 -> "PlutusV1";
            case 1 -> "PlutusV2";
            case 2 -> "PlutusV3";
            default -> "PlutusV" + language;
        };
    }
}
