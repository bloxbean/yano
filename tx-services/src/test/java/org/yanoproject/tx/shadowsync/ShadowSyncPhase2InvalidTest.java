package org.yanoproject.tx.shadowsync;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.runtime.validation.shadowsync.SyncBlock;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.Expected;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.Kind;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.TxResult;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 7a with the real engine: a transaction whose Plutus script fails, listed in the block's
 * {@code invalid_transactions} ({@code is_valid = false}), must come out phase-2 invalid under origin {@code SYNC}
 * (the java engine, Plutus run by Scalus); the same transaction claiming {@code is_valid = true} is the engine's
 * {@code UTXOS.ValidationTagMismatch}, which shadow sync reports as a disagreement. Collateral effects only.
 */
class ShadowSyncPhase2InvalidTest {

    private final LedgerValidationEngine java = new JavaLedgerValidationEngine(new ScalusScriptPhaseEvaluator());

    @Test
    void aFailingScriptListedAsInvalidComesOutPhase2Invalid() {
        byte[] tx = failingScriptTx(false);
        SyncBlock block = new SyncBlock(List.of(tx), List.of(TxIdentity.txIdHex(tx)), Set.of(0));

        SyncBlockValidator.BlockResult result = new SyncBlockValidator().validate(block, MutationWorld.view(),
                MutationWorld.env(), List.of(java), null);

        TxResult only = result.engines().getFirst().txs().getFirst();
        assertThat(only.expected()).isEqualTo(Expected.PHASE2_INVALID);
        assertThat(only.kind()).as(only.failures().toString()).isEqualTo(Kind.AGREED);
        assertThat(only.actual()).isEqualTo("PHASE2_INVALID");
        assertThat(result.exUnits().checked()).isTrue();
        assertThat(result.exUnits().mem()).isPositive();
    }

    @Test
    void theSameScriptClaimingValidIsATagMismatchDisagreement() {
        byte[] tx = failingScriptTx(true);
        SyncBlock block = new SyncBlock(List.of(tx), List.of(TxIdentity.txIdHex(tx)), Set.of());

        TxResult only = new SyncBlockValidator().validate(block, MutationWorld.view(), MutationWorld.env(),
                List.of(java), null).engines().getFirst().txs().getFirst();

        assertThat(only.kind()).isEqualTo(Kind.DISAGREED);
        assertThat(only.actual()).isEqualTo("UTXOS.ValidationTagMismatch");
    }

    /** The script base spending the always-failing script's output instead of the always-succeeding one. */
    private static byte[] failingScriptTx(boolean isValid) {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.inputs.remove(MutationWorld.SCRIPT_INPUT);
        spec.inputs.add(MutationWorld.FAIL_SCRIPT_INPUT);
        spec.plutusScripts.clear();
        spec.plutusScripts.add(MutationWorld.ALWAYS_FAILS);
        spec.isValid = isValid;
        return ConwayTxBuilder.build(spec, MutationWorld.view()).cbor();
    }
}
