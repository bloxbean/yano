package org.yanoproject.ledger.amaru;

import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakePoolId;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Divergences between Amaru (pinned tag) and Haskell found by the ADR-057 Phase C devnet matrix, pinned so a module
 * bump that fixes them is noticed (the parity test's allow-list then shrinks).
 */
class AmaruKnownDivergencesTest {

    private final AmaruTransactionValidator engine = new AmaruTransactionValidator(
            LedgerValidationEngines.AMARU_SCALUS, AmaruEngineConfig.defaults(2),
            ScenarioSupport.network(MutationWorld.network()), new ScalusScriptPhaseEvaluator(),
            AmaruLedgerConstants.HASKELL, ScenarioSupport.wasm());
    private final InMemoryLedgerView world = MutationWorld.view();

    @AfterEach
    void close() {
        engine.close();
    }

    /**
     * Haskell rejects a pool delegation from an unregistered credential with {@code DELEG.StakeKeyNotRegisteredDELEG}
     * ({@code Conway/Rules/Deleg.hs}). Amaru's {@code DefaultValidationContext.delegate_pool} (eaf8ac3) only rejects a
     * source unregistered earlier in the same transaction ({@code DiffBind::bind_left}), so the module accepts; the
     * adapter then cannot derive the effects (the account is absent) and fails closed with
     * {@code ENGINE.AmaruEngineFailure}. The transaction is rejected either way.
     */
    @Test
    void aDelegationFromAnUnregisteredCredentialFailsClosedInsteadOfStakeKeyNotRegistered() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.certs.add(new StakeDelegation(MutationWorld.stakeCredential(TestKey.DEV_42),
                new StakePoolId(HexUtil.decodeHexString(TestKey.DEV_77.keyHash()))));
        byte[] tx = ConwayTxBuilder.build(spec, world).cbor();
        TxValidationRequest request = new TxValidationRequest(tx, world, MutationWorld.env(),
                TxValidationRequest.Rule.MEMPOOL, TxValidationRequest.Origin.LOCAL, null);

        TxValidationOutcome amaru = engine.validate(request);
        TxValidationOutcome java = new JavaLedgerValidationEngine(new ScalusScriptPhaseEvaluator()).validate(request);

        assertThat(java).isInstanceOfSatisfying(TxValidationOutcome.Invalid.class, invalid -> assertThat(
                invalid.failures()).extracting(LedgerFailure::qualifiedName)
                .contains("DELEG.StakeKeyNotRegisteredDELEG"));
        assertThat(amaru).isInstanceOfSatisfying(TxValidationOutcome.Invalid.class, invalid -> {
            assertThat(invalid.failures()).extracting(LedgerFailure::qualifiedName)
                    .containsExactly("ENGINE.AmaruEngineFailure");
            assertThat(invalid.failures().getFirst().detail()).contains("Amaru accepted the transaction")
                    .contains("account");
        });
    }
}
