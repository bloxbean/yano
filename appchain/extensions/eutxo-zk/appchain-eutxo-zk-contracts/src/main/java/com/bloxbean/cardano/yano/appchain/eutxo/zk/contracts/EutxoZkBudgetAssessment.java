package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compares measured settlement cost with an explicitly pinned Cardano
 * protocol-parameter snapshot. It never assumes that repository defaults are
 * the current mainnet parameters.
 */
public final class EutxoZkBudgetAssessment {
    private static final Pattern DIGEST =
            Pattern.compile("[0-9a-f]{64}");

    private EutxoZkBudgetAssessment() {
    }

    public static Result assess(Envelope envelope, Measurement measurement) {
        List<String> failures = new ArrayList<>();
        if (!within(measurement.cpu(), envelope.maximumCpu(),
                envelope.safetyMarginBasisPoints())) {
            failures.add("cpu");
        }
        if (!within(measurement.memory(), envelope.maximumMemory(),
                envelope.safetyMarginBasisPoints())) {
            failures.add("memory");
        }
        if (!within(measurement.transactionBytes(),
                envelope.maximumTransactionBytes(),
                envelope.safetyMarginBasisPoints())) {
            failures.add("transaction-bytes");
        }
        return new Result(failures.isEmpty(), List.copyOf(failures));
    }

    private static boolean within(
            long measured,
            long maximum,
            int safetyMarginBasisPoints
    ) {
        long permitted = Math.multiplyExact(
                maximum, 10_000L - safetyMarginBasisPoints) / 10_000L;
        return measured <= permitted;
    }

    public record Envelope(
            String network,
            long epoch,
            String protocolParametersDigest,
            long maximumCpu,
            long maximumMemory,
            int maximumTransactionBytes,
            int safetyMarginBasisPoints
    ) {
        public Envelope {
            if (network == null || network.isBlank() || epoch < 0
                    || protocolParametersDigest == null
                    || !DIGEST.matcher(protocolParametersDigest).matches()
                    || maximumCpu < 1 || maximumMemory < 1
                    || maximumTransactionBytes < 1
                    || safetyMarginBasisPoints < 0
                    || safetyMarginBasisPoints >= 10_000) {
                throw new IllegalArgumentException(
                        "invalid Cardano budget envelope");
            }
        }
    }

    public record Measurement(
            long cpu,
            long memory,
            int transactionBytes
    ) {
        public Measurement {
            if (cpu < 0 || memory < 0 || transactionBytes < 0) {
                throw new IllegalArgumentException(
                        "invalid settlement measurement");
            }
        }
    }

    public record Result(
            boolean withinEnvelope,
            List<String> failures
    ) {
    }
}
