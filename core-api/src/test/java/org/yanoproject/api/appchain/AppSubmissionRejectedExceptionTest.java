package org.yanoproject.api.appchain;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppSubmissionRejectedExceptionTest {
    @Test
    void retainsOnlyBoundedSymbolicReasons() {
        for (String reason : new String[]{"EVENT_PAYLOAD_TOO_LARGE", "A".repeat(32), "_"}) {
            var rejection = new AppSubmissionRejectedException(reason);
            assertEquals(reason, rejection.code());
            assertEquals(reason, rejection.getMessage());
            assertThat(rejection.details()).isEmpty();
        }
        for (String reason : new String[]{null, "", "secret=private", "CODE\n", "A".repeat(33), "CODE1"}) {
            var rejection = new AppSubmissionRejectedException(reason);
            assertEquals("APPLICATION_REJECTED", rejection.code());
            assertEquals("APPLICATION_REJECTED", rejection.getMessage());
            assertNull(rejection.getCause());
        }
    }

    @Test
    void keepsOnlyAllowlistedDetailsThatMatchTheirGrammar() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("write", 3);
        details.put("deny", "TRANSFER_LIMIT_EXCEEDED");
        details.put("rule", "transfer-limit");
        details.put("reason", "free text the client must never see");
        var rejection = new AppSubmissionRejectedException("ADMISSION_RULE_DENIED", details);
        assertThat(rejection.code()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(rejection.details()).containsExactly(Map.entry("rule", "transfer-limit"),
                Map.entry("deny", "TRANSFER_LIMIT_EXCEEDED"), Map.entry("write", 3L));
        assertThat(rejection.getMessage()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThatThrownBy(() -> rejection.details().put("reason", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(AppSubmissionRejectedException.DETAIL_KEYS).containsExactly("rule", "deny", "write");
        // Every platform integer width is accepted for write; an explicitly generic code keeps its details.
        for (Number write : List.of((short) 7, (byte) 7, 7, 7L)) {
            assertThat(new AppSubmissionRejectedException("APPLICATION_REJECTED", Map.of("write", write)).details())
                    .containsExactly(Map.entry("write", 7L));
        }
    }

    @Test
    void dropsHostileOrOversizedDetailsInsteadOfEchoingThem() {
        List<Object> badRules = List.of("Transfer-limit", "-limit", "a".repeat(64), "limit\n", "limit;drop",
                "lïmit", "", 42L);
        List<Object> badDenies = List.of("deny", "_DENY", "D".repeat(64), "DENY\u0000", "DENY-X", "", true);
        List<Object> badWrites = List.of(-1L, 65_536L, Long.MAX_VALUE, "3", 3.0, true, new byte[]{3});
        for (int index = 0; index < badRules.size(); index++) {
            Map<String, Object> details = new HashMap<>();
            details.put("rule", badRules.get(index));
            details.put("deny", badDenies.get(Math.min(index, badDenies.size() - 1)));
            details.put("write", badWrites.get(Math.min(index, badWrites.size() - 1)));
            assertThat(new AppSubmissionRejectedException("ADMISSION_RULE_DENIED", details).details())
                    .as("details %s", details).isEmpty();
        }
        assertThat(new AppSubmissionRejectedException("ADMISSION_RULE_ERROR", Map.of("write", 65_535)).details())
                .containsExactly(Map.entry("write", 65_535L));
        assertThat(new AppSubmissionRejectedException("ADMISSION_RULE_ERROR", null).details()).isEmpty();
    }

    @Test
    void aReplacedCodeKeepsNoDetails() {
        var details = Map.of("rule", "transfer-limit", "deny", "TRANSFER_LIMIT_EXCEEDED");
        for (String reason : new String[]{"ADMISSION_RULE_DENIED/transfer-limit/TRANSFER_LIMIT_EXCEEDED", "lower",
                "A".repeat(33), null}) {
            var rejection = new AppSubmissionRejectedException(reason, details);
            assertThat(rejection.code()).isEqualTo("APPLICATION_REJECTED");
            assertThat(rejection.details()).isEmpty();
        }
    }

    @Test
    void admissionResultsCarryBoundedUnsanitizedDetails() {
        assertThat(AdmissionResult.accept().details()).isEmpty();
        assertThat(AdmissionResult.reject("CODE").details()).isEmpty();
        Map<String, Object> many = new LinkedHashMap<>();
        for (int index = 0; index < 40; index++) many.put("k" + index, index);
        many.put("rule", "late-rule");
        var result = AdmissionResult.reject("ADMISSION_RULE_DENIED", many);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.reason()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(result.details()).hasSize(AdmissionResult.MAX_DETAILS).doesNotContainKey("rule");
        assertThatThrownBy(() -> result.details().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("rule", null);
        withNull.put("deny", "DENY");
        assertThat(AdmissionResult.reject("CODE", withNull).details()).containsExactly(Map.entry("deny", "DENY"));
        assertThat(AdmissionResult.reject("CODE", null).details()).isEmpty();
        // Plugin-defined objects are never retained, whatever their type claims to be.
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("write", new Number() {
            @Override public int intValue() { return 1; }
            @Override public long longValue() { return 1; }
            @Override public float floatValue() { return 1; }
            @Override public double doubleValue() { return 1; }
        });
        objects.put("rule", new StringBuilder("transfer-limit"));
        objects.put("deny", BigInteger.ONE);
        objects.put("note", "kept, but never allowlisted");
        assertThat(AdmissionResult.reject("ADMISSION_RULE_DENIED", objects).details())
                .containsOnlyKeys("note");
        // Unsanitized in the result; sanitized only where a client can see it.
        var hostile = AdmissionResult.reject("ADMISSION_RULE_DENIED", Map.of("rule", "BAD RULE", "deny", "OK"));
        assertThat(hostile.details()).containsEntry("rule", "BAD RULE");
        assertThat(new AppSubmissionRejectedException(hostile.reason(), hostile.details()).details())
                .containsExactly(Map.entry("deny", "OK"));
    }
}
