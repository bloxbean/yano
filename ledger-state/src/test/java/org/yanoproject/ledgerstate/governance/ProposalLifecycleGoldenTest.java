package org.yanoproject.ledgerstate.governance;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.governance.GovernanceProposalStatus;
import org.yanoproject.api.governance.GovernanceProposalStatusReason;
import org.yanoproject.ledgerstate.governance.model.ProposalLifecycleRecord;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proposal-lifecycle (prefix 0x71) bytes captured from the baseline build (ebd3eeea4) for every
 * action-type/status/reason triple. The baseline action-type column uses the former vocabulary without
 * {@code _ACTION} suffixes; the codec stores ordinals, so its constants map to yaci {@link GovActionType}
 * by ordinal position.
 */
class ProposalLifecycleGoldenTest {

    @Test
    void yaciActionTypeOrdinalsMatchTheBaselineVocabulary() throws Exception {
        assertThat(GovActionType.values()).containsExactly(
                GovActionType.PARAMETER_CHANGE_ACTION,
                GovActionType.HARD_FORK_INITIATION_ACTION,
                GovActionType.TREASURY_WITHDRAWALS_ACTION,
                GovActionType.NO_CONFIDENCE,
                GovActionType.UPDATE_COMMITTEE,
                GovActionType.NEW_CONSTITUTION,
                GovActionType.INFO_ACTION);
        // The capture iterated each baseline enum's values() in order, so first appearance is ordinal order.
        assertThat(distinctColumn(golden(), 0)).containsExactly(
                "PARAMETER_CHANGE", "HARD_FORK_INITIATION", "TREASURY_WITHDRAWALS",
                "NO_CONFIDENCE", "UPDATE_COMMITTEE", "NEW_CONSTITUTION", "INFO_ACTION");
        assertThat(distinctColumn(golden(), 1))
                .containsExactly(names(GovernanceProposalStatus.values()));
        assertThat(distinctColumn(golden(), 2))
                .containsExactly(names(GovernanceProposalStatusReason.values()));
    }

    @Test
    void everyGoldenVectorEncodesAndDecodesIdentically() throws Exception {
        List<String[]> rows = golden();
        assertThat(rows).hasSize(GovActionType.values().length
                * GovernanceProposalStatus.values().length
                * GovernanceProposalStatusReason.values().length);
        List<String> baselineActions = distinctColumn(rows, 0);
        for (String[] row : rows) {
            var record = new ProposalLifecycleRecord(
                    GovActionType.values()[baselineActions.indexOf(row[0])],
                    GovernanceProposalStatus.valueOf(row[1]),
                    GovernanceProposalStatusReason.valueOf(row[2]),
                    Integer.parseInt(row[3]),
                    Integer.parseInt(row[4]));
            byte[] bytes = HexUtil.decodeHexString(row[5]);
            assertThat(GovernanceCborCodec.encodeProposalLifecycle(record))
                    .as(String.join(",", row)).isEqualTo(bytes);
            assertThat(GovernanceCborCodec.decodeProposalLifecycle(bytes))
                    .as(String.join(",", row)).isEqualTo(record);
        }
    }

    private static List<String[]> golden() throws Exception {
        var stream = Objects.requireNonNull(ProposalLifecycleGoldenTest.class.getResourceAsStream(
                "/governance/proposal-lifecycle-golden.csv"));
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<String[]> rows = new ArrayList<>();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank()) rows.add(line.split(","));
            }
            return rows;
        }
    }

    private static List<String> distinctColumn(List<String[]> rows, int column) {
        var values = new LinkedHashSet<String>();
        rows.forEach(row -> values.add(row[column]));
        return List.copyOf(values);
    }

    private static String[] names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).toArray(String[]::new);
    }
}
