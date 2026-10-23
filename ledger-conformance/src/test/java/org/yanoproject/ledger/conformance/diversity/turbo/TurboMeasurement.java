package org.yanoproject.ledger.conformance.diversity.turbo;

import org.julclang.core.Program;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.java.JavaVmProvider;
import org.yanoproject.ledger.conformance.diversity.uplc.UplcEvaluation;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Measures one script (ADR-060 §5): {@value #WARMUP} unmeasured evaluations, then {@value #SAMPLES}, of which the best
 * {@value #RETAINED} of each dimension are averaged, as the scenario prescribes. Time is {@link System#nanoTime()}
 * around the evaluation alone; memory is what the evaluating thread allocated
 * ({@link com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes()}).
 *
 * @param cpuUs     mean evaluation time in microseconds, rounded up
 * @param memBytes  mean allocated bytes, rounded up
 * @param budget    the consumed budget
 * @param succeeded whether the script evaluated without error
 */
record TurboMeasurement(long cpuUs, long memBytes, ExBudget budget, boolean succeeded) {

    static final int WARMUP = 5;
    static final int SAMPLES = 50;
    static final int RETAINED = 30;

    /**
     * Unlimited, as the reference reports measure (Amaru's {@code ExBudget::max}): the scenario page's 10B / 10M would
     * stop five scripts that use more than 10M memory units, whose whole budgets the cardano-node and Amaru reports
     * carry, and the site compares budgets with the majority.
     */
    static final ExBudget BUDGET = new ExBudget(Long.MAX_VALUE, Long.MAX_VALUE);

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Evaluates once, unmeasured (JIT warm-up). */
    static void evaluate(JavaVmProvider provider, Program program, LedgerEvaluationTarget target) {
        provider.evaluate(program, target, BUDGET, UplcEvaluation.OPTIONS);
    }

    static TurboMeasurement measure(JavaVmProvider provider, Program program, LedgerEvaluationTarget target) {
        for (int i = 0; i < WARMUP; i++) {
            evaluate(provider, program, target);
        }
        long[] nanos = new long[SAMPLES];
        long[] bytes = new long[SAMPLES];
        EvalResult result = null;
        for (int i = 0; i < SAMPLES; i++) {
            long allocated = THREADS.getCurrentThreadAllocatedBytes();
            long start = System.nanoTime();
            result = provider.evaluate(program, target, BUDGET, UplcEvaluation.OPTIONS);
            nanos[i] = System.nanoTime() - start;
            bytes[i] = THREADS.getCurrentThreadAllocatedBytes() - allocated;
        }
        ExBudget consumed = switch (result) {
            case EvalResult.Success s -> s.consumed();
            case EvalResult.Failure f -> f.consumed();
            case EvalResult.BudgetExhausted b -> b.consumed();
        };
        return new TurboMeasurement(divideUp(meanOfBest(nanos), 1000), meanOfBest(bytes), consumed,
                result instanceof EvalResult.Success);
    }

    /** @return the mean of the {@value #RETAINED} smallest values, rounded up */
    static long meanOfBest(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int count = Math.min(RETAINED, sorted.length);
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += sorted[i];
        }
        return divideUp(sum, count);
    }

    private static long divideUp(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}
