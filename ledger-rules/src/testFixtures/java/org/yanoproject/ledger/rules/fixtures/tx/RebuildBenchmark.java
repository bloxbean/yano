package org.yanoproject.ledger.rules.fixtures.tx;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.OverlayLedgerView;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine part of a mempool rebuild (ADR-056 decision 3), for engines the runtime's mempool benchmark cannot
 * load (Scalus, Amaru): {@code count} signed non-Plutus payments from independent inputs are validated in full, in
 * order, over an overlay of the {@link MutationWorld} (the admissions), then folded again over a fresh overlay with
 * each transaction's {@code ValidatedTx} as {@code previous} (an off-lane rebuild with re-application only). The
 * mempool's own bookkeeping adds about 20 ms per 10,000 transactions on top (the runtime benchmark measures it with
 * the java engine).
 */
public final class RebuildBenchmark {

    /**
     * @param fullMillis      the full-validation fold
     * @param reapplyMillis   the fold with {@code previous}
     * @param reapplied       how many transactions the second fold re-applied
     * @param kept            how many transactions the second fold kept
     */
    public record Result(long count, double fullMillis, double reapplyMillis, long reapplied, long kept) {
    }

    private RebuildBenchmark() {
    }

    /** Builds {@code count} payments (signed; takes a few seconds for 10,000). */
    public static Workload workload(int count) {
        InMemoryLedgerView.Builder builder = MutationWorld.builder(MutationWorld.protocolParams());
        String owner = TestKey.DEV_42.enterpriseAddress(MutationWorld.NETWORK);
        List<TransactionInput> inputs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            TransactionInput in = new TransactionInput(String.format("%064x", 0xE000_0000L + i), 0);
            inputs.add(in);
            builder.utxo(in.getTransactionId(), 0, MutationWorld.output(owner, BigInteger.valueOf(10_000_000)));
        }
        InMemoryLedgerView world = builder.build();
        List<byte[]> txs = new ArrayList<>(count);
        for (TransactionInput in : inputs) {
            TxSpec spec = new TxSpec();
            spec.inputs.add(in);
            spec.outputs.add(MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK),
                    BigInteger.valueOf(2_000_000)));
            spec.changeAddress = owner;
            spec.ttl = MutationWorld.TTL;
            spec.signers.add(TestKey.DEV_42);
            txs.add(ConwayTxBuilder.build(spec, world).cbor());
        }
        return new Workload(world, txs);
    }

    /** The world and its transactions. */
    public record Workload(InMemoryLedgerView world, List<byte[]> txs) {
    }

    public static Result run(LedgerValidationEngine engine, Workload workload) {
        ValidationEnv env = MutationWorld.env();
        List<ValidatedTx> provenance = new ArrayList<>(workload.txs().size());
        OverlayLedgerView overlay = OverlayLedgerView.over(workload.world());
        long started = System.nanoTime();
        for (byte[] tx : workload.txs()) {
            TxValidationOutcome outcome = engine.validate(new TxValidationRequest(tx, overlay, env,
                    TxValidationRequest.Rule.MEMPOOL, TxValidationRequest.Origin.LOCAL, null));
            if (!(outcome instanceof TxValidationOutcome.Valid valid)) {
                throw new IllegalStateException("benchmark transaction rejected: " + outcome);
            }
            overlay = overlay.apply(valid.effects());
            provenance.add(valid.validated());
        }
        double full = (System.nanoTime() - started) / 1e6;

        OverlayLedgerView rebuilt = OverlayLedgerView.over(workload.world());
        long reapplied = 0;
        long kept = 0;
        started = System.nanoTime();
        for (int i = 0; i < workload.txs().size(); i++) {
            TxValidationOutcome outcome = engine.validate(new TxValidationRequest(workload.txs().get(i), rebuilt, env,
                    TxValidationRequest.Rule.MEMPOOL, TxValidationRequest.Origin.LOCAL, provenance.get(i)));
            if (outcome instanceof TxValidationOutcome.Valid valid) {
                rebuilt = rebuilt.apply(valid.effects());
                kept++;
                if (valid.reapplied()) {
                    reapplied++;
                }
            }
        }
        double reapply = (System.nanoTime() - started) / 1e6;
        return new Result(workload.txs().size(), full, reapply, reapplied, kept);
    }
}
