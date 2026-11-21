package org.yanoproject.ledgerstate.governance;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.InfoAction;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.governance.GovernanceProposalStatus;
import org.yanoproject.api.governance.GovernanceProposalStatusReason;
import org.yanoproject.ledgerstate.DefaultAccountStateStore;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;
import org.yanoproject.ledgerstate.governance.model.ProposalLifecycleRecord;
import org.yanoproject.ledgerstate.test.TestRocksDBHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.api.governance.GovernanceProposalStatus.ACTIVE;
import static org.yanoproject.api.governance.GovernanceProposalStatus.DROPPED;
import static org.yanoproject.api.governance.GovernanceProposalStatus.ENACTED;
import static org.yanoproject.api.governance.GovernanceProposalStatus.EXPIRED;
import static org.yanoproject.api.governance.GovernanceProposalStatus.RATIFIED;

/**
 * Reads governance rows written by the baseline (ebd3eeea4) store API with the current build. The fixture
 * {@code baseline-store.kv} is the {@code acct_state} dump produced by the committed
 * {@code BaselineGovernanceStoreGenerator.java}.
 */
class BaselineGovernanceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void baselineWrittenRowsReadIdentically() throws Exception {
        Map<String, String> rows = baselineRows();
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            // Initialise the store on the empty DB (as the node does), then add the baseline rows.
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            try (var batch = new WriteBatch(); var options = new WriteOptions()) {
                for (var row : rows.entrySet()) {
                    batch.put(rocks.cfState(), HexUtil.decodeHexString(row.getKey()),
                            HexUtil.decodeHexString(row.getValue()));
                }
                rocks.db().write(options, batch);
            }
            GovernanceStateStore governance = rocks.governanceStore();

            assertThat(governance.getAllActiveProposals()).containsExactly(
                    Map.entry(id("aa", 0), new GovActionRecord(new BigInteger("100000000000"),
                            "e0" + "11".repeat(28), 165, 171, GovActionType.PARAMETER_CHANGE_ACTION,
                            null, null, null, 71_280_000L)),
                    Map.entry(id("aa", 1), new GovActionRecord(new BigInteger("100000000000"),
                            "e1" + "22".repeat(28), 164, 170, GovActionType.NO_CONFIDENCE,
                            "bb".repeat(32), 3, null, 70_900_123L)),
                    Map.entry(id("cc", 0), new GovActionRecord(new BigInteger("50000000000"),
                            "e0" + "33".repeat(28), 166, 172, GovActionType.INFO_ACTION,
                            null, null, new InfoAction(), 71_700_000L)),
                    Map.entry(id("dd", 2), new GovActionRecord(new BigInteger("100000000000"),
                            "e0" + "44".repeat(28), 163, 169, GovActionType.TREASURY_WITHDRAWALS_ACTION,
                            null, null, null, 70_500_000L)));

            Map<GovActionId, ProposalLifecycleRecord> epoch170 = new LinkedHashMap<>();
            epoch170.put(id("11", 0), lifecycle(GovActionType.PARAMETER_CHANGE_ACTION, ACTIVE,
                    GovernanceProposalStatusReason.NONE, 165, 171));
            epoch170.put(id("11", 1), lifecycle(GovActionType.HARD_FORK_INITIATION_ACTION, RATIFIED,
                    GovernanceProposalStatusReason.RATIFIED, 164, 170));
            epoch170.put(id("22", 0), lifecycle(GovActionType.TREASURY_WITHDRAWALS_ACTION, ENACTED,
                    GovernanceProposalStatusReason.ENACTED, 163, 169));
            epoch170.put(id("22", 1), lifecycle(GovActionType.NO_CONFIDENCE, EXPIRED,
                    GovernanceProposalStatusReason.EXPIRED, 160, 166));
            epoch170.put(id("33", 0), lifecycle(GovActionType.UPDATE_COMMITTEE, DROPPED,
                    GovernanceProposalStatusReason.SUPERSEDED, 162, 168));
            epoch170.put(id("33", 1), lifecycle(GovActionType.NEW_CONSTITUTION, DROPPED,
                    GovernanceProposalStatusReason.INVALIDATED, 161, 167));
            epoch170.put(new GovActionId("44".repeat(32), 258), lifecycle(GovActionType.INFO_ACTION, DROPPED,
                    GovernanceProposalStatusReason.REMOVED, 165, 171));
            assertThat(governance.hasProposalLifecycleSnapshot(170)).isTrue();
            assertThat(governance.getProposalLifecycleSnapshot(170)).containsExactlyEntriesOf(epoch170);

            assertThat(governance.hasProposalLifecycleSnapshot(171)).isFalse();
            assertThat(governance.getProposalLifecycleSnapshot(171)).containsExactly(Map.entry(id("11", 0),
                    lifecycle(GovActionType.PARAMETER_CHANGE_ACTION, EXPIRED,
                            GovernanceProposalStatusReason.EXPIRED, 165, 171)));

            List<ProposalLifecycleRecord> viewed = new ArrayList<>();
            try (var view = store.openHistoricalEpochStateView()) {
                assertThat(view.hasProposalStatusSnapshot(170)).isTrue();
                view.forEachProposalStatus(170, (tx, index, action, status, reason, proposed, expires) ->
                        viewed.add(lifecycle(action, status, reason, (int) proposed, (int) expires)));
            }
            assertThat(viewed).containsExactlyElementsOf(epoch170.values());

            // The current codec writes the baseline bytes back unchanged.
            for (var row : rows.entrySet()) {
                byte[] value = HexUtil.decodeHexString(row.getValue());
                byte[] reencoded = switch (row.getKey().substring(0, 2)) {
                    case "60" -> GovernanceCborCodec.encodeGovAction(GovernanceCborCodec.decodeGovAction(value));
                    case "71" -> GovernanceCborCodec.encodeProposalLifecycle(
                            GovernanceCborCodec.decodeProposalLifecycle(value));
                    default -> value;
                };
                assertThat(reencoded).as(row.getKey()).isEqualTo(value);
            }
        }
    }

    private static GovActionId id(String txByte, int index) {
        return new GovActionId(txByte.repeat(32), index);
    }

    private static ProposalLifecycleRecord lifecycle(GovActionType action, GovernanceProposalStatus status,
                                                     GovernanceProposalStatusReason reason,
                                                     int proposed, int expires) {
        return new ProposalLifecycleRecord(action, status, reason, proposed, expires);
    }

    private static Map<String, String> baselineRows() throws Exception {
        var stream = Objects.requireNonNull(BaselineGovernanceStoreTest.class.getResourceAsStream(
                "/governance/baseline-store/baseline-store.kv"));
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            Map<String, String> rows = new LinkedHashMap<>();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(" ");
                rows.put(parts[0], parts[1]);
            }
            return rows;
        }
    }
}
