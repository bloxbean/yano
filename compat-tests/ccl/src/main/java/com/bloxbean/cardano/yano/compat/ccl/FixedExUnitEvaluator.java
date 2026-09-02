package com.bloxbean.cardano.yano.compat.ccl;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Declares fixed, generous ExUnits instead of asking the node to evaluate.
 *
 * <p>Yano's {@code /utils/txs/evaluate} resolves inputs from canonical UtxoState
 * only ({@code TransactionEvaluationService:55}), so it cannot price a script
 * transaction whose input comes from an unconfirmed mempool parent. Over-declaring
 * ExUnits is valid as long as the declared budget is below max_tx_ex_units and the
 * script actually succeeds within it, so this lets the load test reach the node's
 * admission path for chained script transactions.
 */
public final class FixedExUnitEvaluator implements TransactionEvaluator {
    private final long mem;
    private final long steps;

    public FixedExUnitEvaluator(long mem, long steps) {
        this.mem = mem;
        this.steps = steps;
    }

    @Override
    public Result<List<EvaluationResult>> evaluateTx(byte[] txBytes, Set<Utxo> inputUtxos) {
        List<EvaluationResult> results = new ArrayList<>();
        try {
            Transaction tx = Transaction.deserialize(txBytes);
            List<Redeemer> redeemers = tx.getWitnessSet() != null
                    ? tx.getWitnessSet().getRedeemers() : null;
            if (redeemers != null) {
                for (Redeemer r : redeemers) {
                    results.add(new EvaluationResult(
                            r.getTag(),
                            r.getIndex().intValue(),
                            ExUnits.builder()
                                    .mem(BigInteger.valueOf(mem))
                                    .steps(BigInteger.valueOf(steps))
                                    .build()));
                }
            }
        } catch (Exception e) {
            return Result.error("fixed evaluator failed: " + e);
        }
        return Result.<List<EvaluationResult>>success("ok").withValue(results);
    }

    public long mem() {
        return mem;
    }

    public long steps() {
        return steps;
    }
}
