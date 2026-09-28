package org.yanoproject.ledgerstate;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.yanoproject.ledgerstate.AccountStateCborCodec.DRepDelegationRecord;
import org.yanoproject.ledgerstate.AccountStateCborCodec.PoolRegistrationData;
import org.yanoproject.ledgerstate.AccountStateCborCodec.StakeAccount;
import org.yanoproject.ledgerstate.governance.GovernanceSnapshotReader;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore.CredentialKey;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Read-only account, pool, DRep, committee and pot state bound to one RocksDB read snapshot
 * (ADR-056 §3).
 *
 * <p>Created by {@link DefaultAccountStateStore#snapshotReader(RocksDB, ReadOptions)}. It reuses the
 * store's key layout and {@link AccountStateCborCodec} decoders and routes every read through the
 * given {@link ReadOptions}. It never writes. Missing records are {@code Optional.empty()}; read and
 * decoding failures propagate as exceptions so the caller can report them as unavailable.</p>
 */
public final class LedgerStateSnapshotReader {

    /**
     * A committee hot-key authorization as recorded by the certificate path (prefix 0x30).
     *
     * @param hotCredType 0 key hash, 1 script hash
     * @param hotHash     hot credential hash, lowercase hex
     */
    public record CommitteeHotAuthorization(int hotCredType, String hotHash) {
    }

    private final RocksDB db;
    private final ColumnFamilyHandle cfState;
    private final ReadOptions reads;
    private final GovernanceSnapshotReader governance;

    LedgerStateSnapshotReader(RocksDB db, ColumnFamilyHandle cfState, ReadOptions reads,
                              boolean governanceEnabled) {
        this.db = Objects.requireNonNull(db, "db");
        this.cfState = Objects.requireNonNull(cfState, "cfState");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.governance = governanceEnabled ? new GovernanceSnapshotReader(db, cfState, reads) : null;
    }

    /** @return the governance reader, or empty when governance tracking is disabled */
    public Optional<GovernanceSnapshotReader> governance() {
        return Optional.ofNullable(governance);
    }

    /** @return reward balance and deposit; empty when the credential is not registered */
    public Optional<StakeAccount> stakeAccount(int credType, String credentialHash) throws RocksDBException {
        byte[] val = get(DefaultAccountStateStore.accountKey(credType, credentialHash));
        return val == null ? Optional.empty() : Optional.of(AccountStateCborCodec.decodeStakeAccount(val));
    }

    /** @return the delegated pool id (hex); empty when not delegated */
    public Optional<String> delegatedPool(int credType, String credentialHash) throws RocksDBException {
        byte[] val = get(DefaultAccountStateStore.poolDelegKey(credType, credentialHash));
        return val == null
                ? Optional.empty()
                : Optional.of(AccountStateCborCodec.decodePoolDelegation(val).poolHash());
    }

    /**
     * @return the vote delegation; {@code drepType} is 0 key hash, 1 script hash, 2 always-abstain,
     *         3 always-no-confidence. Empty when not delegated
     */
    public Optional<DRepDelegationRecord> drepDelegation(int credType, String credentialHash)
            throws RocksDBException {
        byte[] val = get(DefaultAccountStateStore.drepDelegKey(credType, credentialHash));
        return val == null ? Optional.empty() : Optional.of(AccountStateCborCodec.decodeDRepDelegation(val));
    }

    /**
     * The live registration record (prefix 0x10). It holds the <em>latest</em> registered parameters,
     * which may be future parameters of a re-registration in the current epoch, and the deposit.
     *
     * @return the record; empty when the pool is not registered
     */
    public Optional<PoolRegistrationData> poolRegistration(String poolHash) throws RocksDBException {
        byte[] val = get(DefaultAccountStateStore.poolDepositKey(poolHash));
        return val == null ? Optional.empty() : Optional.of(AccountStateCborCodec.decodePoolRegistration(val));
    }

    /**
     * The newest pool-parameter history row (prefix 0x12) whose activation epoch is at most
     * {@code maxActiveEpoch}. Rows are keyed by the epoch the rewards calculation adopts them: the
     * registration epoch + 2 for a fresh registration and + 3 for a re-registration.
     *
     * @return the row; empty when the pool has no such row (for example pools registered before the
     *         history index existed)
     */
    public Optional<PoolRegistrationData> poolParamsHistoryAtOrBefore(String poolHash, int maxActiveEpoch) {
        byte[] seekKey = DefaultAccountStateStore.poolParamsHistKey(poolHash, maxActiveEpoch);
        byte[] poolHashBytes = HexUtil.decodeHexString(poolHash);
        try (RocksIterator it = db.newIterator(cfState, reads)) {
            it.seekForPrev(seekKey);
            if (it.isValid()) {
                byte[] key = it.key();
                if (key.length == 1 + poolHashBytes.length + 4
                        && key[0] == DefaultAccountStateStore.PREFIX_POOL_PARAMS_HIST
                        && Arrays.equals(poolHashBytes, 0, poolHashBytes.length, key, 1, 1 + poolHashBytes.length)) {
                    return Optional.of(AccountStateCborCodec.decodePoolRegistration(it.value()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return true when a history row exists for exactly {@code activeEpoch}
     */
    public boolean hasPoolParamsHistoryRow(String poolHash, int activeEpoch) throws RocksDBException {
        return get(DefaultAccountStateStore.poolParamsHistKey(poolHash, activeEpoch)) != null;
    }

    /**
     * @return true when the pool has a history row keyed strictly after {@code epoch} (a parameter
     *         set that is not yet active at {@code epoch - 2})
     */
    public boolean hasPoolParamsHistoryAfter(String poolHash, int epoch) {
        byte[] poolHashBytes = HexUtil.decodeHexString(poolHash);
        try (RocksIterator it = db.newIterator(cfState, reads)) {
            it.seek(DefaultAccountStateStore.poolParamsHistKey(poolHash, epoch + 1));
            if (!it.isValid()) {
                return false;
            }
            byte[] key = it.key();
            return key.length == 1 + poolHashBytes.length + 4
                    && key[0] == DefaultAccountStateStore.PREFIX_POOL_PARAMS_HIST
                    && Arrays.equals(poolHashBytes, 0, poolHashBytes.length, key, 1, 1 + poolHashBytes.length);
        }
    }

    /** @return every registered pool (prefix 0x10) keyed by pool id hex, in key order */
    public Map<String, PoolRegistrationData> pools() {
        Map<String, PoolRegistrationData> result = new LinkedHashMap<>();
        try (RocksIterator it = db.newIterator(cfState, reads)) {
            it.seek(new byte[]{DefaultAccountStateStore.PREFIX_POOL_DEPOSIT});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length == 0 || key[0] != DefaultAccountStateStore.PREFIX_POOL_DEPOSIT) {
                    break;
                }
                result.put(HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, key.length)),
                        AccountStateCborCodec.decodePoolRegistration(it.value()));
                it.next();
            }
        }
        return result;
    }

    /** @return every committee hot-key authorization recorded by the certificate path (prefix 0x30) */
    public Map<CredentialKey, CommitteeHotAuthorization> committeeHotKeys() {
        Map<CredentialKey, CommitteeHotAuthorization> result = new LinkedHashMap<>();
        scanCredentialPrefix(DefaultAccountStateStore.PREFIX_COMMITTEE_HOT, (cold, value) -> {
            var hot = AccountStateCborCodec.decodeCommitteeHotKey(value);
            result.put(cold, new CommitteeHotAuthorization(hot.hotCredType(), hot.hotHash()));
        });
        return result;
    }

    /** @return every committee cold credential with a recorded resignation (prefix 0x31) */
    public Set<CredentialKey> committeeResignations() {
        Set<CredentialKey> result = new LinkedHashSet<>();
        scanCredentialPrefix(DefaultAccountStateStore.PREFIX_COMMITTEE_RESIGN, (cold, value) -> result.add(cold));
        return result;
    }

    private void scanCredentialPrefix(byte prefix, BiConsumer<CredentialKey, byte[]> consumer) {
        try (RocksIterator it = db.newIterator(cfState, reads)) {
            it.seek(new byte[]{prefix});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != prefix) {
                    break;
                }
                consumer.accept(new CredentialKey(key[1] & 0xFF,
                        HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length))), it.value());
                it.next();
            }
        }
    }

    /** @return the epoch a pending retirement takes effect; empty when none */
    public Optional<Long> poolRetirementEpoch(String poolHash) throws RocksDBException {
        byte[] val = get(DefaultAccountStateStore.poolRetireKey(poolHash));
        return val == null ? Optional.empty() : Optional.of(AccountStateCborCodec.decodePoolRetirement(val));
    }

    /** @return the DRep deposit (prefix 0x20); empty when the DRep is not registered */
    public Optional<BigInteger> drepDeposit(int credType, String credentialHash) throws RocksDBException {
        byte[] val = get(DefaultAccountStateStore.drepRegKey(credType, credentialHash));
        return val == null ? Optional.empty() : Optional.of(AccountStateCborCodec.decodeDRepDeposit(val));
    }

    /** @return the hot key recorded by the certificate path (prefix 0x30); empty when none */
    public Optional<CommitteeHotAuthorization> committeeHotKey(int credType, String coldHash)
            throws RocksDBException {
        byte[] val = get(DefaultAccountStateStore.committeeHotKey(credType, coldHash));
        if (val == null) {
            return Optional.empty();
        }
        var hot = AccountStateCborCodec.decodeCommitteeHotKey(val);
        return Optional.of(new CommitteeHotAuthorization(hot.hotCredType(), hot.hotHash()));
    }

    /** @return true when a resignation is recorded by the certificate path (prefix 0x31) */
    public boolean committeeResigned(int credType, String coldHash) throws RocksDBException {
        return get(DefaultAccountStateStore.committeeResignKey(credType, coldHash)) != null;
    }

    /** @return the treasury of the AdaPot stored for {@code epoch}; empty when none is stored */
    public Optional<BigInteger> treasury(int epoch) throws RocksDBException {
        byte[] val = get(DefaultAccountStateStore.adaPotKey(epoch));
        return val == null ? Optional.empty() : Optional.of(AccountStateCborCodec.decodeAdaPot(val).treasury());
    }

    /**
     * @return the last epoch-boundary marker as {@code {epoch, step}} (steps are
     *         {@code EpochBoundaryProcessor.STEP_*}); empty when no boundary was recorded
     */
    public Optional<int[]> boundaryState() throws RocksDBException {
        byte[] val = get(DefaultAccountStateStore.META_BOUNDARY_STEP);
        if (val == null) {
            return Optional.empty();
        }
        if (val.length != 8) {
            throw new IllegalStateException("Malformed boundary step metadata length: " + val.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(val).order(ByteOrder.BIG_ENDIAN);
        return Optional.of(new int[]{buf.getInt(), buf.getInt()});
    }

    /**
     * A stored reward_rest entry (deferred reward credited at an epoch boundary).
     *
     * @param spendableEpoch epoch at whose boundary it is credited
     * @param type           {@code DefaultAccountStateStore.REWARD_REST_*}
     * @param credType       0 key hash, 1 script hash
     * @param credHash       credential hash, lowercase hex
     * @param amount         lovelace
     */
    public record RewardRestEntry(int spendableEpoch, byte type, int credType, String credHash, BigInteger amount) {
    }

    /** @return the reward_rest entries with {@code spendableEpoch <= maxSpendableEpoch}, in key order */
    public List<RewardRestEntry> rewardRest(int maxSpendableEpoch) {
        List<RewardRestEntry> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState, reads)) {
            it.seek(new byte[]{DefaultAccountStateStore.PREFIX_REWARD_REST});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 7 || key[0] != DefaultAccountStateStore.PREFIX_REWARD_REST) {
                    break;
                }
                int spendableEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (spendableEpoch > maxSpendableEpoch) {
                    break;
                }
                result.add(new RewardRestEntry(spendableEpoch, key[5], key[6] & 0xFF,
                        HexUtil.encodeHexString(Arrays.copyOfRange(key, 7, key.length)),
                        AccountStateCborCodec.decodeRewardRest(it.value()).amount()));
                it.next();
            }
        }
        return result;
    }

    /** @return the amount of one reward_rest entry; empty when none is stored under that key */
    public Optional<BigInteger> rewardRestAmount(int spendableEpoch, byte type, int credType, String credHash)
            throws RocksDBException {
        byte[] val = get(DefaultAccountStateStore.rewardRestKey(spendableEpoch, type, credType, credHash));
        return val == null ? Optional.empty() : Optional.of(AccountStateCborCodec.decodeRewardRest(val).amount());
    }

    RocksDB db() {
        return db;
    }

    ColumnFamilyHandle cfState() {
        return cfState;
    }

    ReadOptions reads() {
        return reads;
    }

    private byte[] get(byte[] key) throws RocksDBException {
        return db.get(cfState, reads, key);
    }
}
