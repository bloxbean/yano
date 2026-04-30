package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.util.CardanoBech32Ids;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceBlockProcessor;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.DRepStateRecord;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.GovActionRecord;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * RocksDB-backed read projection for REST APIs.
 *
 * <p>This class intentionally avoids write batches and rollback journals. Ledger
 * processing continues to use {@link DefaultAccountStateStore}; API-only reads
 * are kept here for lower maintenance risk.
 */
class DefaultAccountStateReadStore implements AccountStateReadStore {
    private final RocksDB db;
    private final ColumnFamilyHandle cfEpochSnapshot;
    private final Supplier<GovernanceBlockProcessor> governanceProcessorSupplier;
    private final Logger log;

    DefaultAccountStateReadStore(RocksDB db,
                                 ColumnFamilyHandle cfEpochSnapshot,
                                 Supplier<GovernanceBlockProcessor> governanceProcessorSupplier,
                                 Logger log) {
        this.db = db;
        this.cfEpochSnapshot = cfEpochSnapshot;
        this.governanceProcessorSupplier = governanceProcessorSupplier;
        this.log = log;
    }

    @Override
    public Optional<EpochStake> getEpochStake(int epoch, int credType, String credentialHash) {
        try {
            byte[] key = snapshotKey(epoch, credType, credentialHash);
            byte[] val = db.get(cfEpochSnapshot, key);
            if (val == null) return Optional.empty();
            var snapshot = AccountStateCborCodec.decodeEpochDelegSnapshot(val);
            return Optional.of(new EpochStake(epoch, credType, credentialHash, snapshot.poolHash(), snapshot.amount()));
        } catch (Exception e) {
            log.warn("getEpochStake failed for epoch={}, credType={}, credHash={}: {}",
                    epoch, credType, credentialHash, e.toString());
            throw new IllegalStateException("getEpochStake failed", e);
        }
    }

    @Override
    public Optional<BigInteger> getTotalActiveStake(int epoch) {
        try {
            SnapshotScanResult scan = scanSnapshot(epoch, null);
            return scan.seenAny() ? Optional.of(scan.total()) : Optional.empty();
        } catch (Exception e) {
            log.warn("getTotalActiveStake failed for epoch={}: {}", epoch, e.toString());
            throw new IllegalStateException("getTotalActiveStake failed", e);
        }
    }

    @Override
    public Optional<PoolStake> getPoolActiveStake(int epoch, String poolHash) {
        try {
            SnapshotScanResult scan = scanSnapshot(epoch, poolHash);
            if (!scan.seenAny() || !scan.seenPool()) return Optional.empty();
            return Optional.of(new PoolStake(epoch, poolHash, scan.total()));
        } catch (Exception e) {
            log.warn("getPoolActiveStake failed for epoch={}, poolHash={}: {}", epoch, poolHash, e.toString());
            throw new IllegalStateException("getPoolActiveStake failed", e);
        }
    }

    @Override
    public List<PoolStakeDelegator> listPoolStakeDelegators(int epoch, String poolHash, int page, int count,
                                                           String order) {
        try {
            List<PoolStakeDelegator> delegators = new ArrayList<>();
            byte[] epochPrefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();

            try (RocksIterator it = db.newIterator(cfEpochSnapshot)) {
                it.seek(epochPrefix);
                while (it.isValid()) {
                    byte[] key = it.key();
                    if (key.length < 5) break;
                    int keyEpoch = ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                    if (keyEpoch != epoch) break;

                    var snapshot = AccountStateCborCodec.decodeEpochDelegSnapshot(it.value());
                    if (poolHash.equals(snapshot.poolHash())) {
                        int credType = key[4] & 0xFF;
                        String credHash = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(
                                java.util.Arrays.copyOfRange(key, 5, key.length));
                        delegators.add(new PoolStakeDelegator(epoch, credType, credHash, poolHash, snapshot.amount()));
                    }
                    it.next();
                }
            }

            Comparator<PoolStakeDelegator> comparator = Comparator
                    .comparingInt(PoolStakeDelegator::credType)
                    .thenComparing(PoolStakeDelegator::credentialHash);
            if ("desc".equalsIgnoreCase(order)) comparator = comparator.reversed();

            long offset = (long) (page - 1) * count;
            return delegators.stream()
                    .sorted(comparator)
                    .skip(offset)
                    .limit(count)
                    .toList();
        } catch (Exception e) {
            log.warn("listPoolStakeDelegators failed for epoch={}, poolHash={}: {}", epoch, poolHash, e.toString());
            throw new IllegalStateException("listPoolStakeDelegators failed", e);
        }
    }

    @Override
    public List<GovernanceProposal> listGovernanceProposals() {
        GovernanceStateStore store = governanceStore().orElse(null);
        if (store == null) return List.of();

        try {
            Set<GovActionId> pendingEnactments = new HashSet<>(store.getPendingEnactments());
            Set<GovActionId> pendingDrops = new HashSet<>(store.getPendingDrops());
            List<GovernanceProposal> proposals = new ArrayList<>();
            for (var entry : store.getAllActiveProposals().entrySet()) {
                proposals.add(toProposal(entry.getKey(), entry.getValue(), pendingEnactments, pendingDrops));
            }
            proposals.sort(Comparator.comparingLong(GovernanceProposal::proposalSlot)
                    .thenComparing(GovernanceProposal::txHash)
                    .thenComparingInt(GovernanceProposal::certIndex));
            return proposals;
        } catch (RocksDBException e) {
            log.warn("listGovernanceProposals failed: {}", e.toString());
            throw new IllegalStateException("listGovernanceProposals failed", e);
        }
    }

    @Override
    public Optional<GovernanceProposal> getGovernanceProposal(String txHash, int certIndex) {
        GovernanceStateStore store = governanceStore().orElse(null);
        if (store == null) return Optional.empty();

        try {
            GovActionId id = new GovActionId(txHash, certIndex);
            Set<GovActionId> pendingEnactments = new HashSet<>(store.getPendingEnactments());
            Set<GovActionId> pendingDrops = new HashSet<>(store.getPendingDrops());
            return store.getProposal(txHash, certIndex)
                    .map(record -> toProposal(id, record, pendingEnactments, pendingDrops));
        } catch (RocksDBException e) {
            log.warn("getGovernanceProposal failed for {}#{}: {}", txHash, certIndex, e.toString());
            throw new IllegalStateException("getGovernanceProposal failed", e);
        }
    }

    @Override
    public List<GovernanceVote> getGovernanceProposalVotes(String txHash, int certIndex) {
        GovernanceStateStore store = governanceStore().orElse(null);
        if (store == null) return List.of();

        try {
            List<GovernanceVote> votes = new ArrayList<>();
            for (var entry : store.getVotesForProposal(txHash, certIndex).entrySet()) {
                votes.add(new GovernanceVote(entry.getKey().voterType(), entry.getKey().voterHash(), entry.getValue()));
            }
            votes.sort(Comparator.comparingInt(GovernanceVote::voterType).thenComparing(GovernanceVote::voterHash));
            return votes;
        } catch (RocksDBException e) {
            log.warn("getGovernanceProposalVotes failed for {}#{}: {}", txHash, certIndex, e.toString());
            throw new IllegalStateException("getGovernanceProposalVotes failed", e);
        }
    }

    @Override
    public List<DRepInfo> listDReps() {
        GovernanceStateStore store = governanceStore().orElse(null);
        if (store == null) return List.of();

        try {
            List<DRepInfo> dreps = new ArrayList<>();
            for (var entry : store.getAllDRepStates().entrySet()) {
                dreps.add(toDRepInfo(entry.getKey().credType(), entry.getKey().hash(), entry.getValue()));
            }
            dreps.sort(Comparator.comparing(DRepInfo::drepHash).thenComparingInt(DRepInfo::drepType));
            return dreps;
        } catch (RocksDBException e) {
            log.warn("listDReps failed: {}", e.toString());
            throw new IllegalStateException("listDReps failed", e);
        }
    }

    @Override
    public Optional<DRepInfo> getDRep(int drepType, String drepHash) {
        GovernanceStateStore store = governanceStore().orElse(null);
        if (store == null) return Optional.empty();

        try {
            return store.getDRepState(drepType, drepHash)
                    .map(record -> toDRepInfo(drepType, drepHash, record));
        } catch (RocksDBException e) {
            log.warn("getDRep failed for type={}, hash={}: {}", drepType, drepHash, e.toString());
            throw new IllegalStateException("getDRep failed", e);
        }
    }

    @Override
    public Optional<BigInteger> getDRepDistribution(int epoch, int drepType, String drepHash) {
        GovernanceStateStore store = governanceStore().orElse(null);
        if (store == null) return Optional.empty();

        try {
            return store.getDRepDistribution(epoch, drepType, drepHash);
        } catch (RocksDBException e) {
            log.warn("getDRepDistribution failed for epoch={}, type={}, hash={}: {}",
                    epoch, drepType, drepHash, e.toString());
            throw new IllegalStateException("getDRepDistribution failed", e);
        }
    }

    @Override
    public Optional<Integer> getLatestDRepDistributionEpoch(int maxEpoch) {
        GovernanceStateStore store = governanceStore().orElse(null);
        if (store == null) return Optional.empty();

        try {
            return store.getLatestDRepDistributionEpoch(maxEpoch);
        } catch (RocksDBException e) {
            log.warn("getLatestDRepDistributionEpoch failed for maxEpoch={}: {}", maxEpoch, e.toString());
            throw new IllegalStateException("getLatestDRepDistributionEpoch failed", e);
        }
    }

    private SnapshotScanResult scanSnapshot(int epoch, String poolHash) {
        boolean seenAny = false;
        boolean seenPool = false;
        BigInteger total = BigInteger.ZERO;
        byte[] epochPrefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();

        try (RocksIterator it = db.newIterator(cfEpochSnapshot)) {
            it.seek(epochPrefix);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5) break;
                int keyEpoch = ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;

                seenAny = true;
                var snapshot = AccountStateCborCodec.decodeEpochDelegSnapshot(it.value());
                if (poolHash == null || poolHash.equals(snapshot.poolHash())) {
                    total = total.add(snapshot.amount());
                    seenPool = true;
                }
                it.next();
            }
        }
        return new SnapshotScanResult(seenAny, seenPool, total);
    }

    private Optional<GovernanceStateStore> governanceStore() {
        GovernanceBlockProcessor processor = governanceProcessorSupplier != null ? governanceProcessorSupplier.get() : null;
        return processor != null ? Optional.ofNullable(processor.getGovernanceStore()) : Optional.empty();
    }

    private static byte[] snapshotKey(int epoch, int credType, String credentialHash) {
        byte[] hash = com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(credentialHash);
        byte[] key = new byte[4 + 1 + hash.length];
        ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        key[4] = (byte) credType;
        System.arraycopy(hash, 0, key, 5, hash.length);
        return key;
    }

    private static GovernanceProposal toProposal(GovActionId id, GovActionRecord record,
                                                 Set<GovActionId> pendingEnactments,
                                                 Set<GovActionId> pendingDrops) {
        String status = pendingEnactments.contains(id)
                ? "pending_ratified"
                : pendingDrops.contains(id) ? "pending_expired" : "active";
        return new GovernanceProposal(
                id.getTransactionId(),
                id.getGov_action_index(),
                CardanoBech32Ids.govActionId(id.getTransactionId(), id.getGov_action_index()),
                status,
                record.actionType() != null ? record.actionType().name() : null,
                record.deposit(),
                record.returnAddress(),
                record.proposedInEpoch(),
                record.expiresAfterEpoch(),
                record.prevActionTxHash(),
                record.prevActionIndex(),
                record.proposalSlot()
        );
    }

    private static DRepInfo toDRepInfo(int drepType, String drepHash, DRepStateRecord record) {
        return new DRepInfo(
                drepType,
                drepHash,
                record.deposit(),
                record.anchorUrl(),
                record.anchorHash(),
                record.registeredAtEpoch(),
                record.lastInteractionEpoch(),
                record.expiryEpoch(),
                record.active(),
                record.registeredAtSlot(),
                record.protocolVersionAtRegistration(),
                record.previousDeregistrationSlot()
        );
    }

    private record SnapshotScanResult(boolean seenAny, boolean seenPool, BigInteger total) {}
}
