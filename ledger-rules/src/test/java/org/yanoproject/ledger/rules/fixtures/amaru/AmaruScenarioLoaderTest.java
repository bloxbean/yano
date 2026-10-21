package org.yanoproject.ledger.rules.fixtures.amaru;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The scenario loader reads the whole corpus; skipped when {@code AMARU_SCENARIOS_DIR} is not set. */
class AmaruScenarioLoaderTest {

    @Test
    void loadsEveryScenarioAtThePinnedTag() {
        AmaruScenarioLoader loader = AmaruScenarioLoader.fromEnvironment();
        List<AmaruScenario> scenarios = loader.loadAll();

        assertThat(scenarios).hasSize(AmaruScenarioLoader.EXPECTED_SCENARIOS);
        long passes = scenarios.stream().filter(s -> s.expected() instanceof Expected.Pass).count();
        long decoding = scenarios.stream().filter(s -> s.expected() instanceof Expected.DecodingFailure).count();
        assertThat(passes).isEqualTo(114);
        assertThat(decoding).isEqualTo(3);
        for (AmaruScenario scenario : scenarios) {
            assertThat(scenario.view().protocolParams().isPresent()).as(scenario.name()).isTrue();
            assertThat(scenario.network().eras()).as(scenario.name()).isNotEmpty();
            assertThat(scenario.env().protocolMajor()).as(scenario.name())
                    .isEqualTo(scenario.protocolParams().getProtocolMajorVer());
        }
    }

    @Test
    void mapsStateAndExpectation() {
        AmaruScenarioLoader loader = AmaruScenarioLoader.fromEnvironment();

        AmaruScenario delegation = loader.load("00005-pass-registered-credential-delegates-to-pool.json");
        assertThat(delegation.expected()).isInstanceOf(Expected.Pass.class);
        assertThat(delegation.network().networkMagic()).isEqualTo(1);
        assertThat(delegation.network().stabilityWindow()).isEqualTo(129_600);
        assertThat(delegation.state().utxo()).hasSize(1);
        UtxoEntry utxo = delegation.view().utxo(delegation.state().utxo().getFirst().outpoint())
                .require("fixture utxo");
        assertThat(utxo.output().getValue().getCoin()).isEqualTo(BigInteger.valueOf(5_000_000));
        CredentialKey account = CredentialKey.key("93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8");
        assertThat(delegation.view().account(account).require("account").deposit())
                .isEqualTo(BigInteger.valueOf(2_000_000));
        assertThat(delegation.constants().isNone()).isTrue();

        AmaruScenario refScripts = loader.load("00034-pass-reference-scripts-size-exactly-at-per-tx-limit.json");
        assertThat(refScripts.constants().maxRefScriptSizePerTx()).isEqualTo(50L);

        AmaruScenario tagMismatch = loader.loadAll().stream()
                .filter(s -> s.expected() instanceof Expected.Predicate p
                        && p.corpusName().equals("ValidationTagMismatch"))
                .findFirst().orElseThrow();
        Expected.Predicate predicate = (Expected.Predicate) tagMismatch.expected();
        assertThat(predicate.qualifiedName()).isEqualTo("UTXOS.ValidationTagMismatch");
        assertThat(predicate.description()).isIn("PassedUnexpectedly", "FailedUnexpectedly");

        AmaruScenario unelected = loader.load(
                "00154-pass-vote-cast-by-an-unelected-committee-member-with-an-authorized-hot-key-v10.json");
        List<CommitteeMemberState> committee = unelected.view().committeeMembers().require("committee");
        assertThat(committee).hasSize(1);
        assertThat(committee.getFirst().isElected()).isFalse();
        assertThat(committee.getFirst().hot()).isNotNull();
    }

    @Test
    void corpusNamesCoverTheHaskellAliases() {
        assertThat(AmaruCorpusNames.haskellName("TreasuryWithdrawalsAllZeros").orElseThrow().constructor())
                .isEqualTo("ZeroTreasuryWithdrawals");
        assertThat(AmaruCorpusNames.haskellName("StakeCredentialInvalidVoteDelegation").orElseThrow().rule())
                .isEqualTo(LedgerRuleName.DELEG);
        assertThat(AmaruCorpusNames.haskellName("NotACorpusName")).isEmpty();
        assertThat(Set.of(AmaruScenarioLoader.eraTag("Conway"), AmaruScenarioLoader.eraTag("Byron")))
                .containsExactlyInAnyOrder(7, 1);
    }
}
