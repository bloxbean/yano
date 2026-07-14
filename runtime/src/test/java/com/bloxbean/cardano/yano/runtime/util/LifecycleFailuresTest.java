package com.bloxbean.cardano.yano.runtime.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleFailuresTest {
    @Test
    void processFatalWinsOverEarlierAssertionAndRetainsItAsContext() {
        AssertionError assertion = new AssertionError("assertion");
        TestVirtualMachineError fatal = new TestVirtualMachineError();

        Throwable merged = LifecycleFailures.merge(assertion, fatal);

        assertThat(merged).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).containsExactly(assertion);
    }

    @Test
    void mergeDoesNotCreateSuppressionCycles() {
        AssertionError earlier = new AssertionError("earlier");
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        earlier.addSuppressed(fatal);

        Throwable merged = LifecycleFailures.merge(earlier, fatal);

        assertThat(merged).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).isEmpty();
    }

    @Test
    void hostileCauseInspectionCannotAbortCleanupMerging() {
        AssertionError primary = new AssertionError("primary") {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("hostile cause accessor");
            }
        };
        RuntimeException cleanup = new RuntimeException("cleanup");

        Throwable merged = LifecycleFailures.merge(primary, cleanup);

        assertThat(merged).isSameAs(primary);
        assertThat(primary.getSuppressed()).isEmpty();
    }

    @Test
    void infinitelyFreshCauseGraphIsBoundedAndSkippedConservatively() {
        RuntimeException primary = new FreshCauseFailure();
        RuntimeException cleanup = new RuntimeException("cleanup");

        Throwable merged = LifecycleFailures.merge(primary, cleanup);

        assertThat(merged).isSameAs(primary);
        assertThat(primary.getSuppressed()).isEmpty();
    }

    @Test
    void processFatalCauseInspectionEscapesInsteadOfBeingSwallowed() {
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        RuntimeException primary = new RuntimeException("primary") {
            @Override
            public synchronized Throwable getCause() {
                throw fatal;
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> LifecycleFailures.merge(primary,
                                new RuntimeException("cleanup")))
                .isSameAs(fatal);
    }

    @Test
    void reachableProcessFatalIsPromotedThroughCauseAndSuppressedEdges() {
        TestVirtualMachineError causeFatal = new TestVirtualMachineError();
        RuntimeException causeWrapper = new RuntimeException("wrapper", causeFatal);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> LifecycleFailures.rethrowIfProcessFatalReachable(causeWrapper))
                .isSameAs(causeFatal);

        TestVirtualMachineError suppressedFatal = new TestVirtualMachineError();
        RuntimeException suppressedWrapper = new RuntimeException("wrapper");
        suppressedWrapper.addSuppressed(suppressedFatal);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> LifecycleFailures.rethrowIfProcessFatalReachable(suppressedWrapper))
                .isSameAs(suppressedFatal);
    }

    private static final class FreshCauseFailure extends RuntimeException {
        @Override
        public synchronized Throwable getCause() {
            return new FreshCauseFailure();
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
    }
}
