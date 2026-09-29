package org.yanoproject.scalusbridge;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import com.bloxbean.cardano.client.common.model.SlotConfig;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scalus engine fixes found by the ADR-056 Phase 2 baseline, on Amaru's Haskell-checked scenarios (skipped
 * without {@code AMARU_SCENARIOS_DIR}):
 *
 * <ul>
 *   <li>00040: a transaction exactly at {@code maxTxSize} is valid (Haskell sizes it without {@code is_valid};
 *       {@link YanoTransactionSizeValidator});</li>
 *   <li>00059/00060: Scalus's {@code DRepException} is {@code GOVCERT.ConwayDRepAlreadyRegistered};</li>
 *   <li>00031: an {@code UpdateCommittee} proposal whose removed members carry a set tag, which Scalus's decoder
 *       rejects, decodes without it ({@code ScalusTransactions}, ADR-056 Phase 7c) and is valid, as in Haskell;
 *       bytes that do not decode at all are {@code ENGINE.DecodingFailure}, not an engine crash.</li>
 * </ul>
 */
class ScalusEngineCorpusFixesTest {

    @Test
    void transactionExactlyAtMaxTxSizeIsValid() {
        assertThat(validate(scenario("00040")).isValid()).isTrue();
    }

    @Test
    void drepAlreadyRegisteredIsNamedAfterHaskell() {
        for (String prefix : new String[]{"00059", "00060"}) {
            LedgerFailure failure = firstFailure(validate(scenario(prefix)));
            assertThat(failure.qualifiedName()).as(prefix).isEqualTo("GOVCERT.ConwayDRepAlreadyRegistered");
            assertThat(failure.phase()).isEqualTo(LedgerFailure.Phase.PHASE_1);
        }
    }

    @Test
    void aSetTaggedUpdateCommitteeProposalDecodesAndUndecodableBytesAreADecodingFailure() {
        AmaruScenario scenario = scenario("00031");
        assertThat(validate(scenario).isValid()).isTrue();
        // [1, 2, 3, 4] is no transaction.
        LedgerFailure failure = firstFailure(validate(scenario, new byte[]{(byte) 0x84, 1, 2, 3, 4}));
        assertThat(failure.qualifiedName()).isEqualTo("ENGINE." + ScalusLedgerValidationEngine.DECODING_FAILURE);
    }

    private static TxValidationOutcome validate(AmaruScenario scenario) {
        return validate(scenario, scenario.txCbor());
    }

    private static TxValidationOutcome validate(AmaruScenario scenario, byte[] txCbor) {
        SlotConfig slotConfig = scenario.env().slotConfig();
        AmaruScenario.EraSummary era = scenario.network().eras().getLast();
        SlotConfigSupplier geometry = new SlotConfigSupplier() {
            @Override
            public SlotConfig getSlotConfig() {
                return slotConfig;
            }

            @Override
            public EpochSlotCalc getEpochSlotCalc() {
                return new EpochSlotCalc(era.epochSizeSlots(), era.epochSizeSlots(), 0);
            }
        };
        return new ScalusLedgerValidationEngine(geometry).validate(new TxValidationRequest(txCbor,
                scenario.view(), scenario.env(), TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC,
                null));
    }

    private static LedgerFailure firstFailure(TxValidationOutcome outcome) {
        assertThat(outcome).isInstanceOf(TxValidationOutcome.Invalid.class);
        return ((TxValidationOutcome.Invalid) outcome).failures().getFirst();
    }

    private static AmaruScenario scenario(String prefix) {
        AmaruScenarioLoader loader = AmaruScenarioLoader.fromEnvironment();
        Path file = loader.scenarioFiles().stream()
                .filter(p -> p.getFileName().toString().startsWith(prefix + "-"))
                .findFirst().orElseThrow(() -> new IllegalStateException("no scenario " + prefix));
        return loader.load(file);
    }
}
