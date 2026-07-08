import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.VoterType;
import com.bloxbean.cardano.yano.ledgerstate.AccountStateCfNames;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yano.ledgerstate.governance.epoch.DRepExpiryCalculator;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.DRepStateRecord;
import org.rocksdb.*;

import java.io.BufferedWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ProposalDrepDump {
    private static final int DREP_KEY = 0;
    private static final int DREP_SCRIPT = 1;
    private static final int DREP_ABSTAIN = 2;
    private static final int DREP_NO_CONF = 3;

    private static final int VOTE_NO = 0;
    private static final int VOTE_YES = 1;
    private static final int VOTE_ABSTAIN = 2;

    record Row(
            int drepType,
            String drepHash,
            BigInteger stake,
            Integer vote,
            String bucket,
            boolean storedActive,
            Integer storedExpiry,
            Integer storedLastInteraction,
            boolean recomputedActive,
            int recomputedExpiry,
            int fallbackExpiry,
            boolean fallbackActive
    ) {
    }

    public static void main(String[] args) throws Exception {
        String chainstate = args.length > 0 ? args[0] : "./chainstate";
        String proposalTxHash = args.length > 1 ? args[1] : "8ad3d454f3496a35cb0d07b0fd32f687f66338b7d60e787fc0a22939e5d8833e";
        int proposalIndex = args.length > 2 ? Integer.parseInt(args[2]) : 26;
        int epoch = args.length > 3 ? Integer.parseInt(args[3]) : 575;
        int drepActivity = args.length > 4 ? Integer.parseInt(args[4]) : 20;
        int govActionLifetime = args.length > 5 ? Integer.parseInt(args[5]) : 6;
        int conwayFirstEpoch = args.length > 6 ? Integer.parseInt(args[6]) : 507;
        Path outDir = Path.of(args.length > 7 ? args[7] : ".");

        RocksDB.loadLibrary();

        var cfDescriptors = List.of(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                new ColumnFamilyDescriptor("blocks".getBytes()),
                new ColumnFamilyDescriptor("headers".getBytes()),
                new ColumnFamilyDescriptor("number_by_slot".getBytes()),
                new ColumnFamilyDescriptor("slot_by_number".getBytes()),
                new ColumnFamilyDescriptor("slot_to_hash".getBytes()),
                new ColumnFamilyDescriptor("metadata".getBytes()),
                new ColumnFamilyDescriptor("ebb_by_slot0".getBytes()),
                new ColumnFamilyDescriptor("utxo_unspent".getBytes()),
                new ColumnFamilyDescriptor("utxo_spent".getBytes()),
                new ColumnFamilyDescriptor("utxo_addr".getBytes()),
                new ColumnFamilyDescriptor("utxo_block_delta".getBytes()),
                new ColumnFamilyDescriptor("utxo_meta".getBytes()),
                new ColumnFamilyDescriptor("script_ref".getBytes()),
                new ColumnFamilyDescriptor(AccountStateCfNames.ACCT_STATE.getBytes()),
                new ColumnFamilyDescriptor(AccountStateCfNames.ACCT_DELTA.getBytes()),
                new ColumnFamilyDescriptor(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT.getBytes()),
                new ColumnFamilyDescriptor(AccountStateCfNames.EPOCH_PARAMS.getBytes())
        );

        var handles = new ArrayList<ColumnFamilyHandle>();
        try (var db = RocksDB.openReadOnly(chainstate, cfDescriptors, handles)) {
            ColumnFamilyHandle cfState = handles.get(14);
            var store = new GovernanceStateStore(db, cfState);
            var expiryCalculator = new DRepExpiryCalculator();

            Map<GovernanceStateStore.CredentialKey, BigInteger> drepDist = store.getDRepDistribution(epoch);
            Map<GovernanceStateStore.VoterKey, Integer> votes = store.getVotesForProposal(proposalTxHash, proposalIndex);
            Map<GovernanceStateStore.CredentialKey, DRepStateRecord> allStates = store.getAllDRepStates();
            Set<Integer> dormantEpochs = store.getDormantEpochs();

            List<Row> rows = new ArrayList<>();

            for (var entry : drepDist.entrySet()) {
                var key = entry.getKey();
                int drepType = key.credType();
                String drepHash = key.hash();
                BigInteger stake = entry.getValue();

                Integer vote = explicitVote(votes, drepType, drepHash);
                String bucket;
                boolean storedActive = false;
                Integer storedExpiry = null;
                Integer storedLastInteraction = null;
                boolean recomputedActive = false;
                int recomputedExpiry = -1;
                int fallbackExpiry = -1;
                boolean fallbackActive = false;

                if (drepType == DREP_ABSTAIN) {
                    bucket = "VIRTUAL_ABSTAIN";
                } else if (drepType == DREP_NO_CONF) {
                    bucket = "VIRTUAL_NO";
                } else {
                    var stateKey = new GovernanceStateStore.CredentialKey(drepType, drepHash);
                    DRepStateRecord state = allStates.get(stateKey);
                    if (state == null) {
                        bucket = "MISSING_STATE";
                    } else {
                        storedActive = state.active();
                        storedExpiry = state.expiryEpoch();
                        storedLastInteraction = state.lastInteractionEpoch();

                        var proposalInfo = store.findLatestProposalUpToSlot(state.registeredAtSlot());
                        recomputedExpiry = expiryCalculator.calculateExpiry(
                                state, dormantEpochs, drepActivity, conwayFirstEpoch, epoch, proposalInfo, govActionLifetime);
                        recomputedActive = recomputedExpiry >= epoch;

                        fallbackExpiry = expiryCalculator.calculateExpiry(
                                state, dormantEpochs, drepActivity, epoch, epoch, proposalInfo, govActionLifetime);
                        fallbackActive = fallbackExpiry >= epoch;

                        if (!recomputedActive) {
                            bucket = "EXCLUDED_INACTIVE";
                        } else if (vote == null) {
                            bucket = "NON_VOTER_NO";
                        } else if (vote == VOTE_YES) {
                            bucket = "YES";
                        } else if (vote == VOTE_NO) {
                            bucket = "NO";
                        } else if (vote == VOTE_ABSTAIN) {
                            bucket = "ABSTAIN";
                        } else {
                            bucket = "UNKNOWN_VOTE_" + vote;
                        }
                    }
                }

                rows.add(new Row(
                        drepType,
                        drepHash,
                        stake,
                        vote,
                        bucket,
                        storedActive,
                        storedExpiry,
                        storedLastInteraction,
                        recomputedActive,
                        recomputedExpiry,
                        fallbackExpiry,
                        fallbackActive
                ));
            }

            rows.sort(Comparator.comparing(Row::stake).reversed());

            Files.createDirectories(outDir);
            Path tsv = outDir.resolve("proposal-" + proposalTxHash.substring(0, 8) + "-" + proposalIndex + "-epoch-" + epoch + "-drep-dump.tsv");
            Path summary = outDir.resolve("proposal-" + proposalTxHash.substring(0, 8) + "-" + proposalIndex + "-epoch-" + epoch + "-summary.txt");

            try (BufferedWriter w = Files.newBufferedWriter(tsv)) {
                w.write("drep_type\tdrep_hash\tstake\tvote\tbucket\tstored_active\tstored_expiry\tstored_last_interaction\trecomputed_active\trecomputed_expiry\tfallback_expiry\tfallback_active");
                w.newLine();
                for (Row row : rows) {
                    w.write(row.drepType() + "\t" + row.drepHash() + "\t" + row.stake() + "\t" + nullable(row.vote())
                            + "\t" + row.bucket() + "\t" + row.storedActive() + "\t" + nullable(row.storedExpiry())
                            + "\t" + nullable(row.storedLastInteraction()) + "\t" + row.recomputedActive()
                            + "\t" + row.recomputedExpiry() + "\t" + row.fallbackExpiry() + "\t" + row.fallbackActive());
                    w.newLine();
                }
            }

            Map<String, BigInteger> bucketSums = new LinkedHashMap<>();
            for (Row row : rows) {
                bucketSums.merge(row.bucket(), row.stake(), BigInteger::add);
            }

            try (BufferedWriter w = Files.newBufferedWriter(summary)) {
                w.write("proposal=" + proposalTxHash + "/" + proposalIndex + ", epoch=" + epoch);
                w.newLine();
                w.write("rows=" + rows.size());
                w.newLine();
                for (var entry : bucketSums.entrySet()) {
                    w.write(entry.getKey() + "=" + entry.getValue());
                    w.newLine();
                }
                w.newLine();
                w.write("top_non_voter_no");
                w.newLine();
                for (Row row : rows) {
                    if ("NON_VOTER_NO".equals(row.bucket())) {
                        w.write(row.drepHash() + "\t" + row.stake() + "\tstoredExpiry=" + nullable(row.storedExpiry())
                                + "\tlastInteraction=" + nullable(row.storedLastInteraction()));
                        w.newLine();
                    }
                }
            }

            System.out.println("Wrote: " + tsv);
            System.out.println("Wrote: " + summary);
            for (var entry : bucketSums.entrySet()) {
                System.out.println(entry.getKey() + "=" + entry.getValue());
            }
        } finally {
            for (var handle : handles) {
                try {
                    handle.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Integer explicitVote(Map<GovernanceStateStore.VoterKey, Integer> votes, int drepType, String drepHash) {
        if (drepType == DREP_KEY) {
            return votes.get(new GovernanceStateStore.VoterKey(VoterType.DREP_KEY_HASH.ordinal(), drepHash));
        } else if (drepType == DREP_SCRIPT) {
            return votes.get(new GovernanceStateStore.VoterKey(VoterType.DREP_SCRIPT_HASH.ordinal(), drepHash));
        } else {
            return null;
        }
    }

    private static String nullable(Object value) {
        return value == null ? "" : value.toString();
    }
}
