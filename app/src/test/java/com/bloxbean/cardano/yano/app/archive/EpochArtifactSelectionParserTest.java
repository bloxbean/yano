package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContracts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochArtifactSelectionParserTest {
    @Test
    void allNoneAndSubsetsAreDistinct() {
        var shipped = ProjectionArtifactContracts.shipped();
        assertThat(EpochArtifactSelectionParser.parse("all", shipped)).isEqualTo(shipped);
        assertThat(EpochArtifactSelectionParser.parse("none", shipped).isEmpty()).isTrue();
        assertThat(EpochArtifactSelectionParser.parse(
                "reward:v1, ada-pot:v1,reward:v1", shipped).contracts().keySet())
                .containsExactlyInAnyOrder(ArchiveDatasetId.REWARD, ArchiveDatasetId.ADA_POT);
    }

    @Test
    void blankUnknownWrongVersionAndMixedAliasesFail() {
        var shipped = ProjectionArtifactContracts.shipped();
        for (String invalid : java.util.List.of("", " ", "reward:v2", "transaction:v1",
                "all,reward:v1", "none,reward:v1", "reward:v1,")) {
            assertThatThrownBy(() -> EpochArtifactSelectionParser.parse(invalid, shipped))
                    .as(invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void enrollmentEpochFollowsEachBoundaryProducersSemanticEpoch() {
        var shipped = ProjectionArtifactContracts.shipped();
        var stake = shipped.contractFor(ArchiveDatasetId.EPOCH_STAKE).orElseThrow();
        var reward = shipped.contractFor(ArchiveDatasetId.REWARD).orElseThrow();
        var adaPot = shipped.contractFor(ArchiveDatasetId.ADA_POT).orElseThrow();

        // At the next 520 -> 521 boundary the snapshot describes 520, while reward describes 521.
        assertThat(ProjectionHistoryService.firstEligibleArtifactEpoch(stake, 520, 0))
                .isEqualTo(520);
        assertThat(ProjectionHistoryService.firstEligibleArtifactEpoch(reward, 520, 0))
                .isEqualTo(521);

        // Mainnet's first fully Shelley source boundary is 208 -> 209. A Shelley-only devnet
        // starts at epoch zero, but the final AdaPot producer begins at epoch two.
        assertThat(ProjectionHistoryService.firstEligibleArtifactEpoch(stake, -1, 208))
                .isEqualTo(208);
        assertThat(ProjectionHistoryService.firstEligibleArtifactEpoch(reward, -1, 208))
                .isEqualTo(209);
        assertThat(ProjectionHistoryService.firstEligibleArtifactEpoch(adaPot, -1, 0))
                .isEqualTo(2);
    }
}
