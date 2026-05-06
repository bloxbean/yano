package com.bloxbean.cardano.yano.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.ledgerstate.EpochParamTracker;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceCborCodec.CommitteeThreshold;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.CommitteeMemberRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceEpochProcessorTest {

    @Test
    @DisplayName("Ratification active check uses previous epoch boundary semantics")
    void drepActiveForRatification_usesPreviousEpoch() {
        assertThat(GovernanceEpochProcessor.isDRepActiveForRatification(961, 962)).isTrue();
        assertThat(GovernanceEpochProcessor.isDRepActiveForRatification(960, 962)).isFalse();
    }

    @Test
    @DisplayName("Export active check expires after the expiry epoch")
    void drepActiveForExport_expiresAfterExpiryEpoch() {
        assertThat(GovernanceEpochProcessor.isDRepActiveForExport(961, 961)).isTrue();
        assertThat(GovernanceEpochProcessor.isDRepActiveForExport(961, 962)).isFalse();
        assertThat(GovernanceEpochProcessor.isDRepActiveForExport(962, 962)).isTrue();
    }

    @Test
    @DisplayName("Dormant flush does not revive already-expired DReps")
    void dormantFlush_nonRevivalGuard() {
        // Already-expired DRep: actualExpiry 960 < currentEpoch 962 → keep stored.
        assertThat(GovernanceEpochProcessor.applyDormantFlushNonRevivalGuard(959, 1, 962)).isEqualTo(959);
        // Preview-967 drift pattern: stored=960, numDormant=1 at boundary 961→962
        // (currentEpoch = newEpoch = 962 per Haskell Certs.hs). actualExpiry 961 < 962 → keep 960.
        assertThat(GovernanceEpochProcessor.applyDormantFlushNonRevivalGuard(960, 1, 962)).isEqualTo(960);
        // Still-active DRep: actualExpiry ≥ currentEpoch → bump proceeds.
        assertThat(GovernanceEpochProcessor.applyDormantFlushNonRevivalGuard(961, 1, 961)).isEqualTo(962);
    }

    @Test
    @DisplayName("Committee size params use effective tracker values before genesis fallback")
    void committeeSizeParams_trackerWins() {
        EpochParamProvider genesisProvider = new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.ZERO;
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.ZERO;
            }

            @Override
            public int getCommitteeMinSize(long epoch) {
                return 7;
            }

            @Override
            public int getCommitteeMaxTermLength(long epoch) {
                return 146;
            }
        };
        EpochParamTracker tracker = new EpochParamTracker(genesisProvider, true) {
            @Override
            public int getCommitteeMinSize(long epoch) {
                return 3;
            }

            @Override
            public int getCommitteeMaxTermLength(long epoch) {
                return 90;
            }
        };
        GovernanceEpochProcessor processor = new GovernanceEpochProcessor(
                null, null, null, null, null, null, null, null, null,
                genesisProvider, tracker, null, null, null, null);

        assertThat(processor.resolveCommitteeMinSize(251)).isEqualTo(3);
        assertThat(processor.resolveCommitteeMaxTermLength(251)).isEqualTo(90);
    }

    @Test
    @DisplayName("Committee state remains normal through member expiry epoch")
    void committeeState_expiryEpochInclusive() throws Exception {
        GovernanceEpochProcessor processor = new GovernanceEpochProcessor(
                null, null, null, null, null, null, null, null, null,
                zeroProvider(), null, null, null, null, null);
        Map<GovernanceStateStore.CredentialKey, CommitteeMemberRecord> members = new HashMap<>();
        members.put(new GovernanceStateStore.CredentialKey(0, "cold"),
                new CommitteeMemberRecord(0, "hot", 232, false));

        Method method = GovernanceEpochProcessor.class.getDeclaredMethod("resolveCommitteeState", Map.class, int.class);
        method.setAccessible(true);

        assertThat(method.invoke(processor, members, 232)).isEqualTo("NORMAL");
        assertThat(method.invoke(processor, members, 233)).isEqualTo("NO_CONFIDENCE");
    }

    /**
     * Regression guard: the preview epoch-967 bug was caused by a hardcoded 0.67 literal
     * in resolveDRepThresholds' fallback branch being used instead of the Conway genesis
     * ppGovGroup (0.75). This source-level test fails the build if any hardcoded numeric
     * BigDecimal threshold value creeps back into GovernanceEpochProcessor.
     * <p>
     * The only allowed BigDecimal literals in this file are constants sourced from other
     * code (BigDecimal.ZERO / ONE / TEN), never "0.67" / "0.75" / "0.60" etc.
     */
    @Test
    @DisplayName("GovernanceEpochProcessor source must not contain hardcoded threshold literals")
    void governanceEpochProcessor_hasNoHardcodedThresholdLiterals() throws Exception {
        Path src = Paths.get("src/main/java/com/bloxbean/cardano/yano/ledgerstate/governance/epoch/GovernanceEpochProcessor.java");
        if (!Files.exists(src)) {
            // running from repo root vs module dir
            src = Paths.get("ledger-state").resolve(src);
        }
        assertThat(Files.exists(src))
                .as("source file must be resolvable from either repo root or module dir; " +
                        "a silent miss would let hardcoded literals slip past the guard")
                .isTrue();
        String source = Files.readString(src);

        // Detect any 'new BigDecimal("0.xx")' or 'new BigDecimal("0.60")' etc. literals
        // that look like voting-threshold values (0.40–0.99). Also catches the
        // committee-threshold shape "0.667" (2/3) — see preview-967 bug-class.
        Pattern pattern = Pattern.compile("new\\s+BigDecimal\\s*\\(\\s*\"(0\\.[4-9]\\d{0,3})\"");
        Matcher m = pattern.matcher(source);
        StringBuilder offenders = new StringBuilder();
        while (m.find()) {
            offenders.append("  ").append(m.group()).append('\n');
        }
        assertThat(offenders.toString())
                .as("No threshold-shaped BigDecimal literals allowed in GovernanceEpochProcessor. " +
                        "Thresholds must come from Conway genesis / EpochParamTracker / governanceStore, " +
                        "not hardcoded fallbacks. Offending literals:\n" + offenders)
                .isEmpty();
    }

    // ============================================================
    // Behavioral tests for buildDRepThresholdMap / buildSPOThresholdMap
    //
    // These are the pure thresholds-resolution helpers. The instance methods
    // resolveDRepThresholds / resolveSPOThresholds delegate to these after
    // looking up effective thresholds from tracker → provider.
    // ============================================================

    @Test
    @DisplayName("buildDRepThresholdMap: bootstrap phase returns 0 for every action type")
    void buildDRepThresholdMap_bootstrap_allZero() {
        Map<GovActionType, BigDecimal> map = GovernanceEpochProcessor.buildDRepThresholdMap(
                /*isBootstrapPhase*/ true, null);
        for (GovActionType type : GovActionType.values()) {
            assertThat(map.get(type))
                    .as("bootstrap threshold for " + type)
                    .isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("buildDRepThresholdMap: non-bootstrap + null thresholds → every action 1.0 (fail-safe)")
    void buildDRepThresholdMap_nullThresholds_failSafe() {
        Map<GovActionType, BigDecimal> map = GovernanceEpochProcessor.buildDRepThresholdMap(
                /*isBootstrapPhase*/ false, null);
        for (GovActionType type : GovActionType.values()) {
            assertThat(map.get(type))
                    .as("fail-safe threshold for " + type +
                            " must be 1.0 — reverting to hardcoded 0.67 would regress preview-967")
                    .isEqualByComparingTo("1");
        }
    }

    @Test
    @DisplayName("buildDRepThresholdMap: real preview thresholds produce exact Cardano spec values")
    void buildDRepThresholdMap_previewThresholds_correctValues() {
        // These are the exact values from preview's conway-genesis.json (verified by
        // ConwayGenesisParserTest.realPreviewConwayGenesisFilesAreParseable).
        DrepVoteThresholds preview = DrepVoteThresholds.builder()
                .dvtMotionNoConfidence(ratio(67, 100))      // 0.67
                .dvtCommitteeNormal(ratio(67, 100))         // 0.67
                .dvtCommitteeNoConfidence(ratio(6, 10))     // 0.6
                .dvtUpdateToConstitution(ratio(75, 100))    // 0.75
                .dvtHardForkInitiation(ratio(6, 10))        // 0.6
                .dvtPPNetworkGroup(ratio(67, 100))          // 0.67
                .dvtPPEconomicGroup(ratio(67, 100))         // 0.67
                .dvtPPTechnicalGroup(ratio(67, 100))        // 0.67
                .dvtPPGovGroup(ratio(75, 100))              // 0.75 — the preview-967 fix
                .dvtTreasuryWithdrawal(ratio(67, 100))      // 0.67
                .build();

        Map<GovActionType, BigDecimal> map = GovernanceEpochProcessor.buildDRepThresholdMap(
                /*isBootstrapPhase*/ false, preview);

        assertThat(map.get(GovActionType.NO_CONFIDENCE)).isEqualByComparingTo("0.67");
        assertThat(map.get(GovActionType.UPDATE_COMMITTEE)).isEqualByComparingTo("0.67");
        assertThat(map.get(GovActionType.NEW_CONSTITUTION)).isEqualByComparingTo("0.75");
        assertThat(map.get(GovActionType.HARD_FORK_INITIATION_ACTION)).isEqualByComparingTo("0.6");
        assertThat(map.get(GovActionType.TREASURY_WITHDRAWALS_ACTION)).isEqualByComparingTo("0.67");
        // Preview-967 regression guard: PARAMETER_CHANGE_ACTION placeholder is ppGovGroup (0.75),
        // NOT the old 0.67 hardcoded fallback.
        assertThat(map.get(GovActionType.PARAMETER_CHANGE_ACTION))
                .as("PARAMETER_CHANGE_ACTION placeholder must be dvtPPGovGroup (0.75), " +
                        "not hardcoded 0.67 — regression guard for preview-967")
                .isEqualByComparingTo("0.75");
    }

    @Test
    @DisplayName("buildSPOThresholdMap: null thresholds → every action 1.0 (fail-safe)")
    void buildSPOThresholdMap_nullThresholds_failSafe() {
        Map<GovActionType, BigDecimal> map = GovernanceEpochProcessor.buildSPOThresholdMap(null);
        assertThat(map.get(GovActionType.NO_CONFIDENCE)).isEqualByComparingTo("1");
        assertThat(map.get(GovActionType.UPDATE_COMMITTEE)).isEqualByComparingTo("1");
        assertThat(map.get(GovActionType.HARD_FORK_INITIATION_ACTION)).isEqualByComparingTo("1");
        assertThat(map.get(GovActionType.PARAMETER_CHANGE_ACTION)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("buildSPOThresholdMap: real preview thresholds produce exact Cardano spec values")
    void buildSPOThresholdMap_previewThresholds_correctValues() {
        PoolVotingThresholds preview = PoolVotingThresholds.builder()
                .pvtMotionNoConfidence(ratio(51, 100))
                .pvtCommitteeNormal(ratio(51, 100))
                .pvtCommitteeNoConfidence(ratio(51, 100))
                .pvtHardForkInitiation(ratio(51, 100))
                .pvtPPSecurityGroup(ratio(51, 100))
                .build();

        Map<GovActionType, BigDecimal> map = GovernanceEpochProcessor.buildSPOThresholdMap(preview);

        assertThat(map.get(GovActionType.NO_CONFIDENCE)).isEqualByComparingTo("0.51");
        assertThat(map.get(GovActionType.UPDATE_COMMITTEE)).isEqualByComparingTo("0.51");
        assertThat(map.get(GovActionType.HARD_FORK_INITIATION_ACTION)).isEqualByComparingTo("0.51");
        // SPO PARAMETER_CHANGE_ACTION uses pvtPPSecurityGroup specifically
        assertThat(map.get(GovActionType.PARAMETER_CHANGE_ACTION)).isEqualByComparingTo("0.51");
    }

    @Test
    @DisplayName("buildDRepThresholdMap: handles partial thresholds (some sub-fields null)")
    void buildDRepThresholdMap_partialThresholds() {
        // DrepVoteThresholds object present but with only ppGovGroup set — sub-field nulls
        // must map to 0 via ratioToBigDecimal, not blow up or return fail-safe 1.0.
        DrepVoteThresholds partial = DrepVoteThresholds.builder()
                .dvtPPGovGroup(ratio(75, 100))
                .build();

        Map<GovActionType, BigDecimal> map = GovernanceEpochProcessor.buildDRepThresholdMap(
                /*isBootstrapPhase*/ false, partial);

        assertThat(map.get(GovActionType.PARAMETER_CHANGE_ACTION)).isEqualByComparingTo("0.75");
        // Other unpopulated fields → 0 (per ratioToBigDecimal null handling). This means
        // those action types would fail ratification trivially, which is the safer default.
        assertThat(map.get(GovActionType.NO_CONFIDENCE)).isEqualByComparingTo("0");
    }

    // ============================================================
    // decideCommitteeThreshold — pure helper. Ensures a missing or
    // malformed committee threshold fail-safes to BigDecimal.ONE
    // (never the old 0.667 hardcode). Preview-967 regression guard.
    // ============================================================

    @Test
    @DisplayName("Committee threshold: stored value wins (3/4 → 0.75)")
    void committeeThreshold_storedValueWins() {
        assertThat(GovernanceEpochProcessor.decideCommitteeThreshold(
                Optional.of(new CommitteeThreshold(BigInteger.valueOf(3), BigInteger.valueOf(4)))))
                .isEqualByComparingTo("0.75");
    }

    @Test
    @DisplayName("Committee threshold: absent store → fail-safe BigDecimal.ONE (not hardcoded 2/3)")
    void committeeThreshold_empty_failSafe() {
        assertThat(GovernanceEpochProcessor.decideCommitteeThreshold(Optional.empty()))
                .as("Empty store must fail-safe to 1.0, not 0.667 — preview-967 regression guard")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Committee threshold: zero denominator → fail-safe BigDecimal.ONE")
    void committeeThreshold_zeroDenom_failSafe() {
        assertThat(GovernanceEpochProcessor.decideCommitteeThreshold(
                Optional.of(new CommitteeThreshold(BigInteger.ONE, BigInteger.ZERO))))
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Committee threshold: negative denominator → fail-safe BigDecimal.ONE")
    void committeeThreshold_negativeDenom_failSafe() {
        // Makes the signum() > 0 guard's intent explicit — malformed store data
        // must never be divided through; fall to fail-safe.
        assertThat(GovernanceEpochProcessor.decideCommitteeThreshold(
                Optional.of(new CommitteeThreshold(BigInteger.ONE, BigInteger.valueOf(-3)))))
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Committee threshold: spec default 2/3 round-trips to Cardano spec value")
    void committeeThreshold_twoThirds() {
        // Preview/preprod/mainnet conway-genesis.json all ship {numerator:2, denominator:3}.
        // Verify the happy path produces the exact Cardano quorum value.
        BigDecimal got = GovernanceEpochProcessor.decideCommitteeThreshold(
                Optional.of(new CommitteeThreshold(BigInteger.valueOf(2), BigInteger.valueOf(3))));
        // 2/3 in DECIMAL128 — compare to reasonable precision, not string match.
        assertThat(got.doubleValue()).isCloseTo(0.6666666666666667, org.assertj.core.data.Offset.offset(1e-15));
    }

    private static UnitInterval ratio(long num, long denom) {
        return new UnitInterval(BigInteger.valueOf(num), BigInteger.valueOf(denom));
    }

    private static EpochParamProvider zeroProvider() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.ZERO;
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.ZERO;
            }
        };
    }
}
