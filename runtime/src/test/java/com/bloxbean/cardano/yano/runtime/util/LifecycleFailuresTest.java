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
    void processFatalCauseInspectionIsRetainedForRethrowAfterCleanup() {
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        RuntimeException primary = new RuntimeException("primary") {
            @Override
            public synchronized Throwable getCause() {
                throw fatal;
            }
        };

        RuntimeException cleanup = new RuntimeException("cleanup");
        Throwable merged = LifecycleFailures.merge(primary, cleanup);

        assertThat(merged).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).containsExactly(cleanup);
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

    @Test
    void mergePromotesNestedFatalWithoutLosingEarlierCleanupFailure() {
        AssertionError earlier = new AssertionError("earlier");
        TestVirtualMachineError causeFatal = new TestVirtualMachineError();
        RuntimeException causeWrapper = new RuntimeException("wrapper", causeFatal);

        Throwable causeMerged = LifecycleFailures.merge(earlier, causeWrapper);

        assertThat(causeMerged).isSameAs(causeFatal);
        assertThat(causeFatal.getSuppressed()).containsExactly(earlier);

        AssertionError secondEarlier = new AssertionError("second-earlier");
        TestVirtualMachineError suppressedFatal = new TestVirtualMachineError();
        RuntimeException suppressedWrapper = new RuntimeException("wrapper");
        suppressedWrapper.addSuppressed(suppressedFatal);

        Throwable suppressedMerged = LifecycleFailures.merge(
                secondEarlier, suppressedWrapper);

        assertThat(suppressedMerged).isSameAs(suppressedFatal);
        assertThat(suppressedFatal.getSuppressed()).containsExactly(secondEarlier);
    }

    @Test
    void hostileOrdinaryCauseAccessorCannotHideSuppressedFatal() {
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        RuntimeException wrapper = new RuntimeException("wrapper") {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("hostile cause accessor");
            }
        };
        wrapper.addSuppressed(fatal);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> LifecycleFailures.rethrowIfProcessFatalReachable(wrapper))
                .isSameAs(fatal);
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
