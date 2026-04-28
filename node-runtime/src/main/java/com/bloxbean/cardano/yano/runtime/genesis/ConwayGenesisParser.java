package com.bloxbean.cardano.yano.runtime.genesis;

import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Parser for standard Cardano conway-genesis.json files.
 * Extracts Conway-era governance parameters, including DRep / SPO voting
 * thresholds.
 */
@Slf4j
public class ConwayGenesisParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Top-level keys
    private static final String POOL_VOTING_THRESHOLDS = "poolVotingThresholds";
    private static final String D_REP_VOTING_THRESHOLDS = "dRepVotingThresholds";

    // Pool voting threshold sub-fields
    private static final String PVT_COMMITTEE_NORMAL = "committeeNormal";
    private static final String PVT_COMMITTEE_NO_CONFIDENCE = "committeeNoConfidence";
    private static final String PVT_HARD_FORK_INITIATION = "hardForkInitiation";
    private static final String PVT_MOTION_NO_CONFIDENCE = "motionNoConfidence";
    private static final String PVT_PP_SECURITY_GROUP = "ppSecurityGroup";

    // DRep voting threshold sub-fields
    private static final String DVT_MOTION_NO_CONFIDENCE = "motionNoConfidence";
    private static final String DVT_COMMITTEE_NORMAL = "committeeNormal";
    private static final String DVT_COMMITTEE_NO_CONFIDENCE = "committeeNoConfidence";
    private static final String DVT_UPDATE_TO_CONSTITUTION = "updateToConstitution";
    private static final String DVT_HARD_FORK_INITIATION = "hardForkInitiation";
    private static final String DVT_PPNETWORK_GROUP = "ppNetworkGroup";
    private static final String DVT_PPECONOMIC_GROUP = "ppEconomicGroup";
    private static final String DVT_PPTECHNICAL_GROUP = "ppTechnicalGroup";
    private static final String DVT_PPGOV_GROUP = "ppGovGroup";
    private static final String DVT_TREASURY_WITHDRAWAL = "treasuryWithdrawal";

    /**
     * Convert a JSON decimal (e.g. 0.75) to a UnitInterval. Picks the smallest power
     * of 10 denominator that captures the decimal; NULL in → NULL out.
     */
    static UnitInterval decimalToUnitInterval(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = stripped.scale();
        if (scale <= 0) {
            return new UnitInterval(stripped.toBigIntegerExact(), BigInteger.ONE);
        }
        BigInteger denom = BigInteger.TEN.pow(scale);
        BigInteger num = stripped.movePointRight(scale).toBigInteger();
        return new UnitInterval(num, denom);
    }

    public static ConwayGenesisData parse(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        return parseRoot(root);
    }

    public static ConwayGenesisData parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        return parseRoot(root);
    }

    private static ConwayGenesisData parseRoot(JsonNode root) {
        int govActionLifetime = root.path("govActionLifetime").asInt(0);
        BigInteger govActionDeposit = parseBigInteger(root, "govActionDeposit", BigInteger.ZERO);
        BigInteger dRepDeposit = parseBigInteger(root, "dRepDeposit", BigInteger.ZERO);
        int dRepActivity = root.path("dRepActivity").asInt(0);
        int committeeMinSize = root.path("committeeMinSize").asInt(0);
        int committeeMaxTermLength = root.path("committeeMaxTermLength").asInt(0);

        DrepVoteThresholds drepVotingThresholds = parseDrepVotingThresholds(root.get(D_REP_VOTING_THRESHOLDS));
        PoolVotingThresholds poolVotingThresholds = parsePoolVotingThresholds(root.get(POOL_VOTING_THRESHOLDS));

        log.info("Parsed conway genesis: govActionLifetime={}, govActionDeposit={}, dRepDeposit={}, " +
                        "dRepActivity={}, committeeMinSize={}, committeeMaxTermLength={}, drepVotingThresholds={}, poolVotingThresholds={}",
                govActionLifetime, govActionDeposit, dRepDeposit,
                dRepActivity, committeeMinSize, committeeMaxTermLength,
                drepVotingThresholds != null ? "present" : "absent",
                poolVotingThresholds != null ? "present" : "absent");

        return new ConwayGenesisData(govActionLifetime, govActionDeposit, dRepDeposit,
                dRepActivity, committeeMinSize, committeeMaxTermLength,
                drepVotingThresholds, poolVotingThresholds);
    }

    private static DrepVoteThresholds parseDrepVotingThresholds(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return DrepVoteThresholds.builder()
                .dvtMotionNoConfidence(decimalField(node, DVT_MOTION_NO_CONFIDENCE))
                .dvtCommitteeNormal(decimalField(node, DVT_COMMITTEE_NORMAL))
                .dvtCommitteeNoConfidence(decimalField(node, DVT_COMMITTEE_NO_CONFIDENCE))
                .dvtUpdateToConstitution(decimalField(node, DVT_UPDATE_TO_CONSTITUTION))
                .dvtHardForkInitiation(decimalField(node, DVT_HARD_FORK_INITIATION))
                .dvtPPNetworkGroup(decimalField(node, DVT_PPNETWORK_GROUP))
                .dvtPPEconomicGroup(decimalField(node, DVT_PPECONOMIC_GROUP))
                .dvtPPTechnicalGroup(decimalField(node, DVT_PPTECHNICAL_GROUP))
                .dvtPPGovGroup(decimalField(node, DVT_PPGOV_GROUP))
                .dvtTreasuryWithdrawal(decimalField(node, DVT_TREASURY_WITHDRAWAL))
                .build();
    }

    private static PoolVotingThresholds parsePoolVotingThresholds(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return PoolVotingThresholds.builder()
                .pvtMotionNoConfidence(decimalField(node, PVT_MOTION_NO_CONFIDENCE))
                .pvtCommitteeNormal(decimalField(node, PVT_COMMITTEE_NORMAL))
                .pvtCommitteeNoConfidence(decimalField(node, PVT_COMMITTEE_NO_CONFIDENCE))
                .pvtHardForkInitiation(decimalField(node, PVT_HARD_FORK_INITIATION))
                .pvtPPSecurityGroup(decimalField(node, PVT_PP_SECURITY_GROUP))
                .build();
    }

    private static UnitInterval decimalField(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) return null;
        BigDecimal d = n.decimalValue();
        return decimalToUnitInterval(d);
    }

    private static BigInteger parseBigInteger(JsonNode root, String field, BigInteger defaultValue) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return defaultValue;
        try {
            return new BigInteger(node.asText());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
