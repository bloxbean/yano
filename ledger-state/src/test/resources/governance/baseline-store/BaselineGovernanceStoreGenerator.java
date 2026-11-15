import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.InfoAction;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.yanoproject.ledgerstate.AccountStateCfNames;
import org.yanoproject.ledgerstate.DefaultAccountStateStore;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;
import org.yanoproject.ledgerstate.governance.model.ProposalLifecycleRecord;

import java.io.PrintWriter;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates {@code baseline-store.kv}: governance rows written by the baseline (ebd3eeea4) ledger-state
 * store API, dumped from the {@code acct_state} column family as {@code keyHex valueHex} lines in RocksDB
 * key order. Documentation only; it is not compiled by the build.
 *
 * <p>Run against the baseline integrated jar (flat class path) from an empty temp directory:</p>
 * <pre>java -cp yano.jar BaselineGovernanceStoreGenerator.java &lt;db-dir&gt; &lt;out.kv&gt;</pre>
 *
 * <p>The baseline lifecycle record declares the enum types it persists; they are resolved from its record
 * components so this program names no type outside ledger-state.</p>
 */
public class BaselineGovernanceStoreGenerator {
    public static void main(String[] args) throws Exception {
        RocksDB.loadLibrary();
        List<String> names = List.of(new String(RocksDB.DEFAULT_COLUMN_FAMILY), AccountStateCfNames.ACCT_STATE);
        List<ColumnFamilyDescriptor> descriptors = names.stream()
                .map(n -> new ColumnFamilyDescriptor(n.getBytes())).toList();
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try (var options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
             var db = RocksDB.open(options, args[0], descriptors, handles)) {
            ColumnFamilyHandle state = handles.get(1);
            var store = new GovernanceStateStore(db, state);
            try (var batch = new WriteBatch(); var write = new WriteOptions()) {
                var delta = new ArrayList<DefaultAccountStateStore.DeltaOp>();
                store.storeProposal(new GovActionId("aa".repeat(32), 0), new GovActionRecord(
                        new BigInteger("100000000000"), "e0" + "11".repeat(28), 165, 171,
                        GovActionType.PARAMETER_CHANGE_ACTION, null, null, null, 71_280_000L), batch, delta);
                store.storeProposal(new GovActionId("aa".repeat(32), 1), new GovActionRecord(
                        new BigInteger("100000000000"), "e1" + "22".repeat(28), 164, 170,
                        GovActionType.NO_CONFIDENCE, "bb".repeat(32), 3, null, 70_900_123L), batch, delta);
                store.storeProposal(new GovActionId("cc".repeat(32), 0), new GovActionRecord(
                        new BigInteger("50000000000"), "e0" + "33".repeat(28), 166, 172,
                        GovActionType.INFO_ACTION, null, null, new InfoAction(), 71_700_000L), batch, delta);
                store.storeProposal(new GovActionId("dd".repeat(32), 2), new GovActionRecord(
                        new BigInteger("100000000000"), "e0" + "44".repeat(28), 163, 169,
                        GovActionType.TREASURY_WITHDRAWALS_ACTION, null, null, null, 70_500_000L), batch, delta);

                Map<GovActionId, ProposalLifecycleRecord> snapshot = new LinkedHashMap<>();
                snapshot.put(new GovActionId("11".repeat(32), 0),
                        lifecycle("PARAMETER_CHANGE", "ACTIVE", "NONE", 165, 171));
                snapshot.put(new GovActionId("11".repeat(32), 1),
                        lifecycle("HARD_FORK_INITIATION", "RATIFIED", "RATIFIED", 164, 170));
                snapshot.put(new GovActionId("22".repeat(32), 0),
                        lifecycle("TREASURY_WITHDRAWALS", "ENACTED", "ENACTED", 163, 169));
                snapshot.put(new GovActionId("22".repeat(32), 1),
                        lifecycle("NO_CONFIDENCE", "EXPIRED", "EXPIRED", 160, 166));
                snapshot.put(new GovActionId("33".repeat(32), 0),
                        lifecycle("UPDATE_COMMITTEE", "DROPPED", "SUPERSEDED", 162, 168));
                snapshot.put(new GovActionId("33".repeat(32), 1),
                        lifecycle("NEW_CONSTITUTION", "DROPPED", "INVALIDATED", 161, 167));
                snapshot.put(new GovActionId("44".repeat(32), 258),
                        lifecycle("INFO_ACTION", "DROPPED", "REMOVED", 165, 171));
                store.storeProposalLifecycleSnapshot(170, snapshot, batch, delta);
                // Phase-1 entries of the next boundary, without the snapshot marker.
                store.storeProposalLifecycleEntries(171, Map.of(new GovActionId("11".repeat(32), 0),
                        lifecycle("PARAMETER_CHANGE", "EXPIRED", "EXPIRED", 165, 171)), batch, delta);
                db.write(write, batch);
            }
            try (var out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])));
                 RocksIterator it = db.newIterator(state)) {
                out.println("# BaselineGovernanceStoreGenerator.java, Yano ebd3eeea4, acct_state CF");
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    out.println(HexUtil.encodeHexString(it.key()) + " " + HexUtil.encodeHexString(it.value()));
                }
            }
            handles.forEach(ColumnFamilyHandle::close);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ProposalLifecycleRecord lifecycle(String action, String status, String reason,
                                                     int proposed, int expires) throws Exception {
        RecordComponent[] components = ProposalLifecycleRecord.class.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) types[i] = components[i].getType();
        return ProposalLifecycleRecord.class.getDeclaredConstructor(types).newInstance(
                Enum.valueOf((Class) types[0], action), Enum.valueOf((Class) types[1], status),
                Enum.valueOf((Class) types[2], reason), proposed, expires);
    }
}
