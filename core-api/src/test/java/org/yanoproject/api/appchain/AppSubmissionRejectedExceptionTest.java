package org.yanoproject.api.appchain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppSubmissionRejectedExceptionTest {
    @Test
    void retainsOnlyBoundedSymbolicReasons() {
        for (String reason : new String[]{"EVENT_PAYLOAD_TOO_LARGE", "A".repeat(32), "_"}) {
            var rejection = new AppSubmissionRejectedException(reason);
            assertEquals(reason, rejection.code());
            assertEquals(reason, rejection.getMessage());
        }
        for (String reason : new String[]{null, "", "secret=private", "CODE\n", "A".repeat(33), "CODE1"}) {
            var rejection = new AppSubmissionRejectedException(reason);
            assertEquals("APPLICATION_REJECTED", rejection.code());
            assertEquals("APPLICATION_REJECTED", rejection.getMessage());
            assertNull(rejection.getCause());
        }
    }
}
