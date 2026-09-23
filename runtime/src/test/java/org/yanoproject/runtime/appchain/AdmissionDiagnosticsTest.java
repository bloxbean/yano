package org.yanoproject.runtime.appchain;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AdmissionDiagnosticsTest {
    @Test
    void warningHasActionableLocationsWithoutThrowableMessagesOrObjects() {
        Logger logger = mock(Logger.class);
        Throwable failure = new IllegalStateException("SECRET body", new IllegalArgumentException("SECRET token"));
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("plugin.Admission", "admit", "X", 42)});
        new AdmissionDiagnostics(logger).failed(7, failure);
        ArgumentCaptor<Object> locations = ArgumentCaptor.forClass(Object.class);
        verify(logger).warn(eq("Local application admission failed (candidateHeight={}, locations={})"),
                eq(7L), locations.capture());
        assertThat(locations.getValue()).isInstanceOf(String.class);
        assertThat((String) locations.getValue())
                .contains("java.lang.IllegalStateException", "plugin.Admission.admit:42",
                        "cause=java.lang.IllegalArgumentException").doesNotContain("SECRET", "token");
    }

    @Test
    void optedInDebugRetainsBoundedEscapedProseWhileCodeStaysGeneric() {
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        String reason = "schema field missing\nfor item\r\t" + "x".repeat(1000);
        new AdmissionDiagnostics(logger).rejected(8, reason);
        verify(logger).debug("Local application admission rejected (candidateHeight={}, code={}, reason={})",
                8L, "APPLICATION_REJECTED", AdmissionDiagnostics.escaped(reason, 512));
        assertThat(AdmissionDiagnostics.escaped(reason, 512))
                .startsWith("schema field missing\\u000afor item\\u000d\\u0009")
                .endsWith("...").hasSizeLessThanOrEqualTo(512).doesNotContain("\n", "\r", "\t");
    }

    @Test
    void controlsBidiAndEscapesCannotInjectLogLines() {
        assertThat(AdmissionDiagnostics.escaped("a\u0000\u202e\"\\b", 512))
                .isEqualTo("a\\u0000\\u202e\\\"\\\\b");
        assertThat(AdmissionDiagnostics.escaped("\n".repeat(1000), 512)).hasSizeLessThanOrEqualTo(512)
                .endsWith("...").doesNotContain("\n");
    }

    @Test
    void rejectionDoesNotLogAtWarnOrInfoWhenDebugIsDisabled() {
        Logger logger = mock(Logger.class);
        new AdmissionDiagnostics(logger).rejected(1, "private rejection prose");
        verify(logger).isDebugEnabled();
        verifyNoMoreInteractions(logger);
    }

    @Test
    void nonfatalDiagnosticFailureDoesNotChangeAdmissionOutcome() {
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenThrow(new IllegalStateException("broken logger"));
        assertThatCode(() -> new AdmissionDiagnostics(logger).rejected(1, "REJECTED"))
                .doesNotThrowAnyException();
    }
}
