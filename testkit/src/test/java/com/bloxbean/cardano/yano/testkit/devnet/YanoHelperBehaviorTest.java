package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.model.FundingRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YanoHelperBehaviorTest {
    @Test
    void faucetRejectsInvalidRequests() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(TestkitFakes.status(false, false))) {
            assertThrows(IllegalArgumentException.class, () -> kit.faucet().fund("", 1));
            assertThrows(IllegalArgumentException.class, () -> kit.faucet().fund("addr", 0));
            assertThrows(IllegalArgumentException.class,
                    () -> kit.faucet().fundAll(List.of(new FundingRequest("addr", -1))));
        }
    }

    @Test
    void snapshotsRejectBlankNames() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(TestkitFakes.status(false, false))) {
            assertThrows(IllegalArgumentException.class, () -> kit.snapshots().create(""));
            assertThrows(IllegalArgumentException.class, () -> kit.snapshots().restore(" "));
            assertThrows(IllegalArgumentException.class, () -> kit.snapshots().delete(null));
        }
    }

    @Test
    void timeRejectsNonPositiveValues() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(TestkitFakes.status(false, false))) {
            assertThrows(IllegalArgumentException.class, () -> kit.time().advanceSlots(0));
            assertThrows(IllegalArgumentException.class, () -> kit.time().advanceSeconds(-1));
            assertThrows(IllegalArgumentException.class, () -> kit.time().shiftGenesisAndStartProducer(0));
        }
    }

    @Test
    void awaitTimesOutWhenConditionDoesNotPass() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(TestkitFakes.status(false, false))) {
            YanoAwait await = kit.await()
                    .withTimeout(Duration.ofMillis(20))
                    .withPollInterval(Duration.ofMillis(1));

            assertThrows(AssertionError.class, () -> await.until(() -> false, "condition"));
        }
    }

    @Test
    void runtimeHealthAssertionsFailClosedOnMissingStatus() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(null)) {
            assertThrows(AssertionError.class, () -> kit.assertions().runtimeNotDegraded());
            assertThrows(AssertionError.class, () -> kit.await()
                    .withTimeout(Duration.ofMillis(20))
                    .withPollInterval(Duration.ofMillis(1))
                    .untilNotDegraded());
        }
    }

    @Test
    void runtimeHealthAssertionsRejectDegradedStatus() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(TestkitFakes.status(true, true))) {
            assertThrows(AssertionError.class, () -> kit.assertions().runtimeNotDegraded());
        }
    }

    @Test
    void runtimeHealthAssertionsAcceptHealthyStatus() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(TestkitFakes.status(true, false))) {
            assertDoesNotThrow(() -> kit.assertions().runtimeNotDegraded());
        }
    }
}
