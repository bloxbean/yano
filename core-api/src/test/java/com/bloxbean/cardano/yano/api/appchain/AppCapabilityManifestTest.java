package com.bloxbean.cardano.yano.api.appchain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppCapabilityManifestTest {
    @Test
    void orderingIsCanonicalAndDigestIsStable() {
        var first = AppCapabilityManifest.builder("demo", "1.0.0")
                .crossCutting(capability("z:test"))
                .crossCutting(capability("a:test"))
                .build();
        var second = AppCapabilityManifest.builder("demo", "1.0.0")
                .crossCutting(capability("a:test"))
                .crossCutting(capability("z:test"))
                .build();

        assertThat(first.crossCutting()).extracting(value -> value.capabilityId())
                .containsExactly("a:test", "z:test");
        assertThat(first.manifestDigest()).isEqualTo(second.manifestDigest())
                .matches("[0-9a-f]{64}");
    }

    @Test
    void duplicateStableIdsFailClosed() {
        assertThatThrownBy(() -> AppCapabilityManifest.builder("demo", "1.0.0")
                .crossCutting(capability("a:test"))
                .crossCutting(capability("a:test"))
                .build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate capability");
    }

    @Test
    void attributeInsertionOrderCannotChangeTheDigest() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("a", "1");
        forward.put("z", "2");
        Map<String, String> reverse = new LinkedHashMap<>();
        reverse.put("z", "2");
        reverse.put("a", "1");

        var first = AppCapabilityManifest.builder("demo", "1.0.0")
                .crossCutting(new AppCapabilityManifest.CrossCutting(
                        "a:test", "1", true, "config", forward,
                        AppCapabilityManifest.Origin.INTRINSIC)).build();
        var second = AppCapabilityManifest.builder("demo", "1.0.0")
                .crossCutting(new AppCapabilityManifest.CrossCutting(
                        "a:test", "1", true, "config", reverse,
                        AppCapabilityManifest.Origin.INTRINSIC)).build();

        assertThat(first.manifestDigest()).isEqualTo(second.manifestDigest());
        assertThat(first.crossCutting().getFirst().attributes().keySet())
                .containsExactly("a", "z");
    }

    private static AppCapabilityManifest.CrossCutting capability(String id) {
        return new AppCapabilityManifest.CrossCutting(id, "1.0.0", true,
                "intrinsic-v1", Map.of("mode", "test"),
                AppCapabilityManifest.Origin.INTRINSIC);
    }
}
