package com.bloxbean.cardano.yano.runtime.genesis;

import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that ConwayGenesisParser extracts every governance-relevant field from
 * conway-genesis.json. Specifically: no hardcoded protocol params leak into production
 * — the parser MUST carry drepVotingThresholds and poolVotingThresholds through.
 */
class ConwayGenesisParserTest {

    /**
     * Mimics preview's conway-genesis.json with every documented field populated.
     * Values intentionally distinct (not round numbers like 0.75) to catch
     * copy-paste or hardcoded-default bugs.
     */
    private static final String PREVIEW_LIKE_GENESIS = """
            {
              "poolVotingThresholds": {
                "committeeNormal": 0.51,
                "committeeNoConfidence": 0.52,
                "hardForkInitiation": 0.53,
                "motionNoConfidence": 0.54,
                "ppSecurityGroup": 0.55
              },
              "dRepVotingThresholds": {
                "motionNoConfidence": 0.60,
                "committeeNormal": 0.61,
                "committeeNoConfidence": 0.62,
                "updateToConstitution": 0.63,
                "hardForkInitiation": 0.64,
                "ppNetworkGroup": 0.65,
                "ppEconomicGroup": 0.66,
                "ppTechnicalGroup": 0.67,
                "ppGovGroup": 0.75,
                "treasuryWithdrawal": 0.68
              },
              "committeeMinSize": 3,
              "committeeMaxTermLength": 146,
              "govActionLifetime": 30,
              "govActionDeposit": 100000000000,
              "dRepDeposit": 500000000,
              "dRepActivity": 20
            }
            """;

    @Test
    void parsesAllDrepVotingThresholdFields() throws IOException {
        ConwayGenesisData data = ConwayGenesisParser.parse(
                new ByteArrayInputStream(PREVIEW_LIKE_GENESIS.getBytes(StandardCharsets.UTF_8)));

        assertThat(data.drepVotingThresholds()).isNotNull();
        var dv = data.drepVotingThresholds();

        assertRatio(dv.getDvtMotionNoConfidence(), "0.6");
        assertRatio(dv.getDvtCommitteeNormal(), "0.61");
        assertRatio(dv.getDvtCommitteeNoConfidence(), "0.62");
        assertRatio(dv.getDvtUpdateToConstitution(), "0.63");
        assertRatio(dv.getDvtHardForkInitiation(), "0.64");
        assertRatio(dv.getDvtPPNetworkGroup(), "0.65");
        assertRatio(dv.getDvtPPEconomicGroup(), "0.66");
        assertRatio(dv.getDvtPPTechnicalGroup(), "0.67");
        assertRatio(dv.getDvtPPGovGroup(), "0.75");
        assertRatio(dv.getDvtTreasuryWithdrawal(), "0.68");
    }

    @Test
    void parsesAllPoolVotingThresholdFields() throws IOException {
        ConwayGenesisData data = ConwayGenesisParser.parse(
                new ByteArrayInputStream(PREVIEW_LIKE_GENESIS.getBytes(StandardCharsets.UTF_8)));

        assertThat(data.poolVotingThresholds()).isNotNull();
        var pv = data.poolVotingThresholds();

        assertRatio(pv.getPvtCommitteeNormal(), "0.51");
        assertRatio(pv.getPvtCommitteeNoConfidence(), "0.52");
        assertRatio(pv.getPvtHardForkInitiation(), "0.53");
        assertRatio(pv.getPvtMotionNoConfidence(), "0.54");
        assertRatio(pv.getPvtPPSecurityGroup(), "0.55");
    }

    @Test
    void parsesBaseConwayFields() throws IOException {
        ConwayGenesisData data = ConwayGenesisParser.parse(
                new ByteArrayInputStream(PREVIEW_LIKE_GENESIS.getBytes(StandardCharsets.UTF_8)));

        assertThat(data.govActionLifetime()).isEqualTo(30);
        assertThat(data.govActionDeposit()).isEqualTo(new BigInteger("100000000000"));
        assertThat(data.dRepDeposit()).isEqualTo(new BigInteger("500000000"));
        assertThat(data.dRepActivity()).isEqualTo(20);
        assertThat(data.committeeMinSize()).isEqualTo(3);
        assertThat(data.committeeMaxTermLength()).isEqualTo(146);
    }

    @Test
    void thresholdFieldsReturnNullWhenJsonLacksThem() throws IOException {
        String minimal = """
                {
                  "govActionLifetime": 6,
                  "govActionDeposit": 1,
                  "dRepDeposit": 1,
                  "dRepActivity": 20,
                  "committeeMinSize": 0,
                  "committeeMaxTermLength": 0
                }
                """;

        ConwayGenesisData data = ConwayGenesisParser.parse(
                new ByteArrayInputStream(minimal.getBytes(StandardCharsets.UTF_8)));

        assertThat(data.drepVotingThresholds()).isNull();
        assertThat(data.poolVotingThresholds()).isNull();
    }

    @Test
    void decimalToUnitIntervalPreservesPrecision() {
        // 0.75 → 75/100 (or reduced equivalent)
        UnitInterval q = ConwayGenesisParser.decimalToUnitInterval(new BigDecimal("0.75"));
        assertThat(q.safeRatio()).isEqualByComparingTo("0.75");

        // 0.6666666666666667 should survive round-trip at reasonable precision
        UnitInterval twoThirds = ConwayGenesisParser.decimalToUnitInterval(new BigDecimal("0.6666666666666667"));
        assertThat(twoThirds.safeRatio().toPlainString()).startsWith("0.6666666");

        // null in → null out
        assertThat(ConwayGenesisParser.decimalToUnitInterval(null)).isNull();
    }

    @Test
    void realPreviewConwayGenesisFilesAreParseable() throws IOException {
        // Sanity: actual on-disk preview conway-genesis.json must produce non-null thresholds.
        // This test prevents regression if anyone silently drops fields from the parser again.
        java.io.File f = new java.io.File("../node-app/config/network/preview/conway-genesis.json");
        if (!f.exists()) {
            // alternative path if test is run from repo root
            f = new java.io.File("node-app/config/network/preview/conway-genesis.json");
        }
        if (!f.exists()) return; // skip when not running from a checkout (e.g., jar-only env)

        ConwayGenesisData data = ConwayGenesisParser.parse(f);
        assertThat(data.drepVotingThresholds())
                .as("preview conway-genesis.json must populate dRepVotingThresholds")
                .isNotNull();
        assertThat(data.poolVotingThresholds())
                .as("preview conway-genesis.json must populate poolVotingThresholds")
                .isNotNull();
        // preview's ppGovGroup is 0.75 per Cardano spec — direct regression guard on the
        // preview-epoch-967 root cause (was falling back to hardcoded 0.67).
        assertRatio(data.drepVotingThresholds().getDvtPPGovGroup(), "0.75");
    }

    private static void assertRatio(UnitInterval ui, String expectedDecimal) {
        assertThat(ui)
                .as("UnitInterval should not be null — parser dropped field")
                .isNotNull();
        assertThat(ui.safeRatio()).isEqualByComparingTo(expectedDecimal);
    }
}
