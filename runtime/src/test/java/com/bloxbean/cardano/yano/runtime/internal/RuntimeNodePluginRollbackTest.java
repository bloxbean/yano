package com.bloxbean.cardano.yano.runtime.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RuntimeNodePluginRollbackTest {

    @Test
    void repeatedFatalStopFailureCannotSelfSuppressAndStillSealsContributions() {
        FatalPluginError fatal = new FatalPluginError();
        AtomicInteger sealCalls = new AtomicInteger();

        Throwable thrown = catchThrowable(() -> {
            throw RuntimeNode.rollbackPluginStartup(
                    fatal,
                    () -> { throw fatal; },
                    sealCalls::incrementAndGet);
        });

        assertThat(thrown).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).isEmpty();
        assertThat(sealCalls).hasValue(1);
    }

    @Test
    void cleanupFatalIsPromotedButLaterSealIsStillAttempted() {
        IllegalStateException primary = new IllegalStateException("filter");
        FatalPluginError fatal = new FatalPluginError();
        AtomicInteger sealCalls = new AtomicInteger();

        Throwable thrown = catchThrowable(() -> {
            throw RuntimeNode.rollbackPluginStartup(
                    primary,
                    () -> { throw fatal; },
                    () -> {
                        sealCalls.incrementAndGet();
                        throw new IllegalArgumentException("seal");
                    });
        });

        assertThat(thrown).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).hasSize(2);
        assertThat(fatal.getSuppressed()[0]).isSameAs(primary);
        assertThat(fatal.getSuppressed()[1])
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("seal");
        assertThat(sealCalls).hasValue(1);
    }

    @Test
    void laterFatalPluginEnvironmentCloseFailureReplacesOrdinaryManagerFailure() {
        IllegalStateException managerFailure = new IllegalStateException("manager close");
        FatalPluginError environmentFailure = new FatalPluginError();

        Throwable outcome = RuntimeNode.recordPluginCleanupFailure(null, managerFailure);
        outcome = RuntimeNode.recordPluginCleanupFailure(outcome, environmentFailure);

        assertThat(outcome).isSameAs(environmentFailure);
        assertThat(environmentFailure.getSuppressed()).containsExactly(managerFailure);
    }

    @Test
    void laterProcessFatalReplacesEarlierAssertionDuringRuntimeCleanup() {
        AssertionError assertion = new AssertionError("assertion");
        TestVirtualMachineError fatal = new TestVirtualMachineError();

        Throwable outcome = RuntimeNode.recordPluginCleanupFailure(assertion, fatal);

        assertThat(outcome).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).containsExactly(assertion);
    }

    private static final class FatalPluginError extends Error {
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
    }
}
