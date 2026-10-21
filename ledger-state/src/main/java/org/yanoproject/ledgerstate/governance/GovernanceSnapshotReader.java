package org.yanoproject.ledgerstate.governance;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore.CredentialKey;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;
import org.yanoproject.ledgerstate.governance.model.DRepStateRecord;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only governance state bound to one RocksDB read snapshot (ADR-056 §3).
 *
 * <p>It reuses the key layout of {@link GovernanceStateStore} and the {@link GovernanceCborCodec}
 * decoders, but every read goes through the {@link ReadOptions} it was created with. It never
 * writes. The owner of the {@code ReadOptions} (a canonical snapshot) keeps them and the RocksDB
 * snapshot alive for as long as this reader is used.</p>
 */
public final class GovernanceSnapshotReader {

    /**
     * A stored proposal with its governance-action payload as stored.
     *
     * @param txHash        proposing transaction id, lowercase hex
     * @param index         index in the transaction's proposal procedures
     * @param record        the decoded proposal state
     * @param govActionCbor the governance action CBOR as the store recorded it (yaci's
     *                      re-serialisation of the parsed action, not guaranteed to be the on-chain
     *                      bytes), or {@code null} when the record has no payload
     */
    public record StoredProposal(String txHash, int index, GovActionRecord record, byte[] govActionCbor) {
        public StoredProposal {
            Objects.requireNonNull(txHash, "txHash");
            Objects.requireNonNull(record, "record");
            govActionCbor = govActionCbor != null ? govActionCbor.clone() : null;
        }

        @Override
        public byte[] govActionCbor() {
            return govActionCbor != null ? govActionCbor.clone() : null;
        }
    }

    private static final int MAX_GOV_ACTION_INDEX = 0xFFFF;

    private final RocksDB db;
    private final ColumnFamilyHandle cfState;
    private final ReadOptions reads;

    public GovernanceSnapshotReader(RocksDB db, ColumnFamilyHandle cfState, ReadOptions reads) {
        this.db = Objects.requireNonNull(db, "db");
        this.cfState = Objects.requireNonNull(cfState, "cfState");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    /** @return the proposal while it is stored (active, or awaiting removal at the next boundary) */
    public Optional<StoredProposal> proposal(String txHash, int index) throws RocksDBException {
        if (index < 0 || index > MAX_GOV_ACTION_INDEX) {
            return Optional.empty();
        }
        byte[] val = db.get(cfState, reads, GovernanceStateStore.govActionKey(txHash, index));
        return val == null ? Optional.empty() : Optional.of(decodeProposal(txHash, index, val));
    }

    /** @return every stored proposal, in key order */
    public List<StoredProposal> proposals() {
        List<StoredProposal> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState, reads)) {
            it.seek(new byte[]{GovernanceStateStore.PREFIX_GOV_ACTION});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 35 || key[0] != GovernanceStateStore.PREFIX_GOV_ACTION) {
                    break;
                }
                String txHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, 33));
                int index = ((key[33] & 0xFF) << 8) | (key[34] & 0xFF);
                result.add(decodeProposal(txHash, index, it.value()));
                it.next();
            }
        }
        return result;
    }

    /** @return the committee record for a cold credential (members and hot-key placeholders) */
    public Optional<CommitteeMemberRecord> committeeMember(int credType, String coldHash) throws RocksDBException {
        byte[] val = db.get(cfState, reads, GovernanceStateStore.committeeMemberKey(credType, coldHash));
        return val == null ? Optional.empty() : Optional.of(GovernanceCborCodec.decodeCommitteeMember(val));
    }

    /** @return every committee record, in key order */
    public Map<CredentialKey, CommitteeMemberRecord> committeeMembers() {
        Map<CredentialKey, CommitteeMemberRecord> result = new LinkedHashMap<>();
        try (RocksIterator it = db.newIterator(cfState, reads)) {
            it.seek(new byte[]{GovernanceStateStore.PREFIX_COMMITTEE_MEMBER});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 30 || key[0] != GovernanceStateStore.PREFIX_COMMITTEE_MEMBER) {
                    break;
                }
                int credType = key[1] & 0xFF;
                String coldHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, 30));
                result.put(new CredentialKey(credType, coldHash),
                        GovernanceCborCodec.decodeCommitteeMember(it.value()));
                it.next();
            }
        }
        return result;
    }

    public Optional<GovernanceCborCodec.ConstitutionRecord> constitution() throws RocksDBException {
        byte[] val = db.get(cfState, reads, GovernanceStateStore.constitutionKey());
        return val == null ? Optional.empty() : Optional.of(GovernanceCborCodec.decodeConstitution(val));
    }

    /** @return the stored dormant-epoch counter; 0 when never written (same default as the store) */
    public int numDormantEpochs() throws RocksDBException {
        byte[] val = db.get(cfState, reads, GovernanceStateStore.numDormantEpochsKey());
        return val != null ? ByteBuffer.wrap(val, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt() : 0;
    }

    /**
     * @param purposeType the purpose key the enactment processor stores under
     *                    ({@code UPDATE_COMMITTEE} for both committee actions)
     */
    public Optional<GovernanceCborCodec.LastEnactedAction> lastEnacted(GovActionType purposeType)
            throws RocksDBException {
        byte[] val = db.get(cfState, reads, GovernanceStateStore.lastEnactedKey(purposeType));
        return val == null ? Optional.empty() : Optional.of(GovernanceCborCodec.decodeLastEnactedAction(val));
    }

    /** @return every DRep state record (including tombstones), in key order */
    public Map<CredentialKey, DRepStateRecord> drepStates() {
        Map<CredentialKey, DRepStateRecord> result = new LinkedHashMap<>();
        try (RocksIterator it = db.newIterator(cfState, reads)) {
            it.seek(new byte[]{GovernanceStateStore.PREFIX_DREP_STATE});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 30 || key[0] != GovernanceStateStore.PREFIX_DREP_STATE) {
                    break;
                }
                result.put(new CredentialKey(key[1] & 0xFF, HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, 30))),
                        GovernanceCborCodec.decodeDRepState(it.value()));
                it.next();
            }
        }
        return result;
    }

    /** @return the DRep state record, including tombstones of deregistered DReps */
    public Optional<DRepStateRecord> drepState(int credType, String hash) throws RocksDBException {
        byte[] val = db.get(cfState, reads, GovernanceStateStore.drepStateKey(credType, hash));
        return val == null ? Optional.empty() : Optional.of(GovernanceCborCodec.decodeDRepState(val));
    }

    /**
     * @return every stored proposal as the boundary processor reads it
     *         ({@link GovernanceStateStore#getAllActiveProposals()}): key order, decoded records
     */
    public Map<GovActionId, GovActionRecord> proposalRecords() {
        Map<GovActionId, GovActionRecord> result =
                new LinkedHashMap<>();
        try (RocksIterator it = db.newIterator(cfState, reads)) {
            it.seek(new byte[]{GovernanceStateStore.PREFIX_GOV_ACTION});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 35 || key[0] != GovernanceStateStore.PREFIX_GOV_ACTION) {
                    break;
                }
                String txHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, 33));
                int index = ((key[33] & 0xFF) << 8) | (key[34] & 0xFF);
                result.put(new GovActionId(txHash, index),
                        GovernanceCborCodec.decodeGovAction(it.value()));
                it.next();
            }
        }
        return result;
    }

    /** @return the proposals ratified at the previous boundary, awaiting enactment, in key order */
    public List<GovActionId> pendingEnactments() {
        return pendingIds(GovernanceStateStore.PREFIX_RATIFIED_IN_EPOCH);
    }

    /** @return the proposals expired at the previous boundary, awaiting removal, in key order */
    public List<GovActionId> pendingDrops() {
        return pendingIds(GovernanceStateStore.PREFIX_EXPIRED_IN_EPOCH);
    }

    private List<GovActionId> pendingIds(byte prefix) {
        List<GovActionId> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfState, reads)) {
            it.seek(new byte[]{prefix});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 35 || key[0] != prefix) {
                    break;
                }
                String txHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, 33));
                int index = ((key[33] & 0xFF) << 8) | (key[34] & 0xFF);
                result.add(new GovActionId(txHash, index));
                it.next();
            }
        }
        return result;
    }

    /** @return the committee quorum threshold; empty when none is stored */
    public Optional<GovernanceCborCodec.CommitteeThreshold> committeeThreshold() throws RocksDBException {
        byte[] val = db.get(cfState, reads, GovernanceStateStore.committeeThresholdKey());
        return val == null ? Optional.empty() : Optional.of(GovernanceCborCodec.decodeCommitteeThreshold(val));
    }

    /** @return false after a NoConfidence enactment (Haskell {@code SNothing} committee); true otherwise */
    public boolean committeePresent() throws RocksDBException {
        byte[] val = db.get(cfState, reads, GovernanceStateStore.committeePresentKey());
        return val == null || val.length == 0 || val[0] != 0;
    }

    /** @return the recorded first Conway epoch (protocol major 9); -1 when not recorded */
    public int conwayFirstEpoch() throws RocksDBException {
        byte[] val = db.get(cfState, reads, GovernanceStateStore.eraFirstEpochKey(9));
        return val != null ? ByteBuffer.wrap(val, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt() : -1;
    }

    private static StoredProposal decodeProposal(String txHash, int index, byte[] val) {
        GovActionRecord record = GovernanceCborCodec.decodeGovAction(val);
        return new StoredProposal(txHash, index, record, govActionPayload(val));
    }

    /** Key 7 of the stored proposal map holds the governance action CBOR (see the codec). */
    private static byte[] govActionPayload(byte[] storedProposal) {
        // Fully qualified: the CBOR map type collides with java.util.Map.
        co.nstant.in.cbor.model.Map map =
                (co.nstant.in.cbor.model.Map) CborSerializationUtil.deserializeOne(storedProposal);
        DataItem payload = map.get(new UnsignedInteger(7));
        return payload instanceof ByteString bytes ? bytes.getBytes() : null;
    }
}
