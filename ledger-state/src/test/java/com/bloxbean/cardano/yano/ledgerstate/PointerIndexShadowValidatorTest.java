package com.bloxbean.cardano.yano.ledgerstate;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointerIndexShadowValidatorTest {
    private static final UtxoBalanceAggregator.CredentialKey CREDENTIAL =
            new UtxoBalanceAggregator.CredentialKey(0, "11".repeat(28));

    @Test
    void exactOverlayAndCountsPass() {
        var indexed = result(Map.of(CREDENTIAL, BigInteger.TEN), 1, 1, 0, "pointer-index");
        var scanned = result(Map.of(CREDENTIAL, BigInteger.TEN), 99, 1, 0, "pointer-scan");

        assertDoesNotThrow(() -> PointerIndexShadowValidator.requireParity(
                LoggerFactory.getLogger(getClass()), 100, indexed, scanned, false));
    }

    @Test
    void anyBalanceOrResolutionDifferenceFailsClosed() {
        var indexed = result(Map.of(CREDENTIAL, BigInteger.TEN), 1, 1, 0, "pointer-index");
        var wrongBalance = result(Map.of(CREDENTIAL, BigInteger.ONE), 1, 1, 0, "pointer-scan");
        var wrongCount = result(Map.of(CREDENTIAL, BigInteger.TEN), 1, 0, 1, "pointer-scan");

        assertThrows(IllegalStateException.class,
                () -> PointerIndexShadowValidator.requireParity(
                        LoggerFactory.getLogger(getClass()), 100,
                        indexed, wrongBalance, false));
        assertThrows(IllegalStateException.class,
                () -> PointerIndexShadowValidator.requireParity(
                        LoggerFactory.getLogger(getClass()), 100,
                        indexed, wrongCount, false));
    }

    @Test
    void backfillAlsoRequiresIdenticalPointerRowCount() {
        var indexed = result(Map.of(CREDENTIAL, BigInteger.TEN), 2, 1, 0, "pointer-index");
        var backfilled = result(Map.of(CREDENTIAL, BigInteger.TEN), 1, 1, 0, "pointer-backfill");

        assertThrows(IllegalStateException.class,
                () -> PointerIndexShadowValidator.requireParity(
                        LoggerFactory.getLogger(getClass()), 100,
                        indexed, backfilled, true));
    }

    private static UtxoBalanceAggregator.PointerAggregation result(
            Map<UtxoBalanceAggregator.CredentialKey, BigInteger> balances,
            long records,
            long resolved,
            long failed,
            String path) {
        return new UtxoBalanceAggregator.PointerAggregation(
                balances, records, resolved, failed, path);
    }
}
