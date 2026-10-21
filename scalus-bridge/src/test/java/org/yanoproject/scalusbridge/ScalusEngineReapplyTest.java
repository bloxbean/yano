package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.conway.ReapplyPolicy;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.RebuildBenchmark;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 6a (review MAJOR-1): the Scalus engine adapter records the resolved-inputs digest and re-applies a
 * reusable {@code previous} with Scalus's dynamic rules only (no signatures, no Plutus).
 */
class ScalusEngineReapplyTest {

    private final ScalusLedgerValidationEngine engine = new ScalusLedgerValidationEngine(null);
    private final InMemoryLedgerView world = MutationWorld.view();

    private TxValidationOutcome validate(byte[] tx, LedgerView view, ValidationEnv env, ValidatedTx previous) {
        return engine.validate(new TxValidationRequest(tx, view, env, TxValidationRequest.Rule.MEMPOOL,
                TxValidationRequest.Origin.LOCAL, previous));
    }

    @Test
    void aValidatedTransactionIsReappliedWithItsProvenance() {
        byte[] tx = ConwayTxBuilder.build(MutationWorld.simpleSpec(), world).cbor();

        TxValidationOutcome.Valid first = (TxValidationOutcome.Valid) validate(tx, world, MutationWorld.env(), null);
        assertThat(first.reapplied()).isFalse();
        assertThat(first.validated().resolvedInputsDigest()).isNotNull();

        TxValidationOutcome.Valid again = (TxValidationOutcome.Valid) validate(tx, world, MutationWorld.env(),
                first.validated());
        assertThat(again.reapplied()).isTrue();
        assertThat(again.validated()).isEqualTo(first.validated());

        // A protocol-major change validates in full (ReapplyPolicy).
        TxValidationOutcome atEleven = validate(tx, MutationWorld.view(11), MutationWorld.env(11), first.validated());
        assertThat(atEleven).isInstanceOfSatisfying(TxValidationOutcome.Valid.class,
                v -> assertThat(v.reapplied()).isFalse());
    }

    @Test
    void reapplicationSkipsSignaturesAndPlutus() throws Exception {
        TxSpec badSignature = MutationWorld.simpleSpec();
        badSignature.corruptFirstSignature = true;
        byte[] signed = ConwayTxBuilder.build(badSignature, world).cbor();
        assertThat(validate(signed, world, MutationWorld.env(), null).isValid()).isFalse();
        assertThat(validate(signed, world, MutationWorld.env(), provenance(signed)).isValid())
                .as("signatures are static: skipped on re-application").isTrue();

        TxSpec failing = MutationWorld.scriptSpec();
        failing.inputs.set(1, MutationWorld.FAIL_SCRIPT_INPUT);
        failing.plutusScripts.set(0, MutationWorld.ALWAYS_FAILS);
        byte[] script = ConwayTxBuilder.build(failing, world).cbor();
        assertThat(validate(script, world, MutationWorld.env(), null).isValid()).isFalse();
        assertThat(validate(script, world, MutationWorld.env(), provenance(script)).isValid())
                .as("Plutus execution is static: skipped on re-application").isTrue();
    }

    /** A provenance as a full validation of {@code tx} against the world would have recorded it. */
    private ValidatedTx provenance(byte[] tx) throws Exception {
        Transaction decoded = Transaction.deserialize(tx);
        Map<Outpoint, UtxoEntry> resolved = new HashMap<>();
        for (Outpoint o : ReapplyPolicy.allInputs(decoded.getBody())) {
            world.utxo(o).orElseThrowUnavailable().ifPresent(e -> resolved.put(o, e));
        }
        ValidationEnv env = MutationWorld.env();
        return new ValidatedTx(tx, TxIdentity.txId(tx), 10, env.currentEpoch(), env.phase2EnvDigest(), true,
                TxValidationRequest.Origin.LOCAL,
                ReapplyPolicy.resolvedInputsDigest(ReapplyPolicy.allInputs(decoded.getBody()), resolved));
    }

    @Test
    @Tag("benchmark")
    @EnabledIfSystemProperty(named = "yano.mempool.benchmark", matches = "true")
    void rebuildBenchmark() {
        RebuildBenchmark.Workload workload = RebuildBenchmark.workload(10_000);
        RebuildBenchmark.run(engine, RebuildBenchmark.workload(1_000)); // warm-up
        RebuildBenchmark.Result result = RebuildBenchmark.run(engine, workload);
        System.out.printf("scalus engine, %d txs: full %.0f ms, re-application %.0f ms (%d re-applied)%n",
                result.count(), result.fullMillis(), result.reapplyMillis(), result.reapplied());
        assertThat(result.reapplied()).isEqualTo(result.count());
    }
}
