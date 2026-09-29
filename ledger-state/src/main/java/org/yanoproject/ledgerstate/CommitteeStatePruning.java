package org.yanoproject.ledgerstate;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.yanoproject.ledgerstate.DefaultAccountStateStore.DeltaOp;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore.CredentialKey;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Haskell's {@code updateCommitteeState} (cardano-ledger f649f975, {@code Conway/Rules/Epoch.hs:343},
 * {@code :419-423}): at every Conway epoch boundary, after the enactments, the committee state keeps only
 * the cold credentials of the committee's members ({@code Map.intersection} with the members). A hot-key
 * authorisation or a resignation of anyone else (a potential future member whose UpdateCommittee was not
 * enacted at this boundary, a removed member) is dropped.
 *
 * <p>In Yano a committee member is a governance committee record with a term: the Conway genesis and
 * UpdateCommittee enactments always write the member's term epoch, and removals and NoConfidence delete
 * the record. A record with term 0 is the pre-enrollment placeholder that a hot-key authorisation or a
 * resignation of a non-member creates. The certificate path's hot-key (0x30) and resignation (0x31)
 * entries of a dropped credential go with it.</p>
 *
 * <p>The real boundary ({@link #prune}) and the boundary preview ({@link #coldsToPrune}) share the rule.</p>
 */
public final class CommitteeStatePruning {

    private CommitteeStatePruning() {
    }

    /**
     * @param records              the governance committee records after the enactments
     * @param certificatePathColds cold credentials with a certificate-path hot key or resignation
     * @return the cold credentials whose committee state the boundary drops
     */
    public static Set<CredentialKey> coldsToPrune(Map<CredentialKey, CommitteeMemberRecord> records,
                                                  Set<CredentialKey> certificatePathColds) {
        Set<CredentialKey> pruned = new LinkedHashSet<>();
        records.forEach((cold, record) -> {
            if (!isMember(record)) {
                pruned.add(cold);
            }
        });
        for (CredentialKey cold : certificatePathColds) {
            CommitteeMemberRecord record = records.get(cold);
            if (record == null || !isMember(record)) {
                pruned.add(cold);
            }
        }
        return pruned;
    }

    /** @return the cold credentials with a certificate-path hot key or resignation */
    static Set<CredentialKey> certificatePathColds(LedgerStateSnapshotReader ledger) {
        Set<CredentialKey> colds = new LinkedHashSet<>(ledger.committeeHotKeys().keySet());
        colds.addAll(ledger.committeeResignations());
        return colds;
    }

    /**
     * Drops, from committed state, the committee state of every cold credential that is not a member,
     * journalling each delete in {@code deltaOps} so a rollback of the boundary restores it.
     */
    public static void prune(RocksDB db, ColumnFamilyHandle cfState, GovernanceStateStore governance,
                             WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        Set<CredentialKey> certificatePath;
        try (ReadOptions reads = new ReadOptions()) {
            certificatePath = certificatePathColds(new LedgerStateSnapshotReader(db, cfState, reads, false));
        }
        for (CredentialKey cold : coldsToPrune(governance.getAllCommitteeMembers(), certificatePath)) {
            governance.removeCommitteeMember(cold.credType(), cold.hash(), batch, deltaOps);
            deleteCommitted(db, cfState, DefaultAccountStateStore.committeeHotKey(cold.credType(), cold.hash()),
                    batch, deltaOps);
            deleteCommitted(db, cfState, DefaultAccountStateStore.committeeResignKey(cold.credType(), cold.hash()),
                    batch, deltaOps);
        }
    }

    private static boolean isMember(CommitteeMemberRecord record) {
        return record.expiryEpoch() > 0;
    }

    private static void deleteCommitted(RocksDB db, ColumnFamilyHandle cfState, byte[] key, WriteBatch batch,
                                        List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] prev = db.get(cfState, key);
        if (prev != null) {
            batch.delete(cfState, key);
            deltaOps.add(new DeltaOp(DefaultAccountStateStore.OP_DELETE, key, prev));
        }
    }
}
