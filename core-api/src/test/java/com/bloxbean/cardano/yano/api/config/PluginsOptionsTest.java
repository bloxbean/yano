package com.bloxbean.cardano.yano.api.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class PluginsOptionsTest {

    @Test
    @SuppressWarnings("unchecked")
    void recursivelySnapshotsSupportedConfigurationValues() {
        List<Object> endpoints = new ArrayList<>(List.of("first"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("endpoints", endpoints);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("nested", nested);
        source.put("enabled", true);
        source.put("whole", new BigInteger("42"));
        source.put("decimal", new BigDecimal("1.25"));

        PluginsOptions options = new PluginsOptions(
                true, false, Set.of(), Set.of(), source);
        endpoints.add("late");
        nested.put("late", "not-copied");
        source.put("late", "not-copied");

        Map<String, Object> copiedNested =
                (Map<String, Object>) options.config().get("nested");
        List<Object> copiedEndpoints =
                (List<Object>) copiedNested.get("endpoints");
        assertThat(copiedEndpoints).containsExactly("first");
        assertThat(copiedNested).doesNotContainKey("late");
        assertThat(options.config()).doesNotContainKey("late");
        assertThat(options.config()).containsEntry("whole", new BigInteger("42"));
        assertThat(options.config()).containsEntry("decimal", new BigDecimal("1.25"));
        assertThatThrownBy(() -> copiedNested.put("next", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> copiedEndpoints.add("next"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsCyclesUnsupportedValuesAndNonFiniteNumbersWithoutLeakingSecrets() {
        String secret = "credential-value-must-not-appear";
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("cycle", cyclic);

        Throwable cycleFailure = catchThrowable(() -> options(cyclic));
        assertThat(cycleFailure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference cycle");

        Object unsupported = new Object() {
            @Override
            public String toString() {
                return secret;
            }
        };
        Throwable unsupportedFailure = catchThrowable(() ->
                options(Map.of("credential", unsupported)));
        assertThat(unsupportedFailure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable scalars");
        assertThat(unsupportedFailure.getMessage()).doesNotContain(secret);

        assertThatThrownBy(() -> options(Map.of("mutable-number", new AtomicInteger(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable scalars");
        for (Number value : List.of(
                Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            Throwable failure = catchThrowable(() -> options(Map.of("number", value)));
            assertThat(failure)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("numbers must be finite");
            assertThat(failure.getMessage()).doesNotContain(value.toString());
        }

        Map raw = new LinkedHashMap();
        raw.put(7, secret);
        Throwable keyFailure = catchThrowable(() -> options(raw));
        assertThat(keyFailure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string keys");
        assertThat(keyFailure.getMessage()).doesNotContain(secret);
    }

    @Test
    void enforcesDepthNodeCollectionAndTextBoundsWithoutRenderingContent() {
        String secret = "secret-text-over-limit";
        assertThatThrownBy(() -> PluginConfigValues.immutableCopy(
                Map.of("outer", Map.of("inner", "value")),
                limits(1, 20, 100, 20, 20, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum depth");
        assertThatThrownBy(() -> PluginConfigValues.immutableCopy(
                Map.of("first", true, "second", false),
                limits(4, 1, 100, 20, 20, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more values");
        assertThatThrownBy(() -> PluginConfigValues.immutableCopy(
                Map.of("first", true, "second", false),
                limits(4, 20, 100, 1, 20, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entry limit");

        Throwable textFailure = catchThrowable(() -> PluginConfigValues.immutableCopy(
                Map.of("value", secret),
                limits(4, 20, 10, 20, 20, 100)));
        assertThat(textFailure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate character limit");
        assertThat(textFailure.getMessage()).doesNotContain(secret);
    }

    @Test
    void toStringReportsOnlyPolicyCounts() {
        String allowedId = "com.example.allowed-sentinel";
        String deniedId = "com.example.denied-sentinel";
        String secretKey = "credential-key-sentinel";
        String secretValue = "credential-value-sentinel";
        PluginsOptions options = new PluginsOptions(
                true, false, Set.of(allowedId), Set.of(deniedId),
                Map.of(secretKey, secretValue));

        assertThat(options.toString())
                .contains("enabled=true", "autoRegisterAnnotated=false",
                        "allowListEntries=1", "denyListEntries=1", "configEntries=1")
                .doesNotContain(allowedId, deniedId, secretKey, secretValue);
    }

    private static PluginsOptions options(Map<String, Object> config) {
        return new PluginsOptions(true, false, Set.of(), Set.of(), config);
    }

    private static PluginConfigValues.Limits limits(
            int depth,
            int nodes,
            int text,
            int entries,
            int key,
            int value
    ) {
        return new PluginConfigValues.Limits(
                depth, nodes, text, entries, key, value);
    }
}
