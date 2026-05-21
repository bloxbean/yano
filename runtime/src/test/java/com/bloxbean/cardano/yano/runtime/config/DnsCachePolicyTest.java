package com.bloxbean.cardano.yano.runtime.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DnsCachePolicyTest {
    private String originalPositiveSystemTtl;
    private String originalNegativeSystemTtl;

    @BeforeEach
    void captureSystemProperties() {
        originalPositiveSystemTtl = System.getProperty("sun.net.inetaddr.ttl");
        originalNegativeSystemTtl = System.getProperty("sun.net.inetaddr.negative.ttl");
    }

    @AfterEach
    void restoreSystemProperties() {
        restore("sun.net.inetaddr.ttl", originalPositiveSystemTtl);
        restore("sun.net.inetaddr.negative.ttl", originalNegativeSystemTtl);
    }

    @Test
    void yanoConfigOverridesExistingJvmProperties() {
        System.setProperty("sun.net.inetaddr.ttl", "300");
        System.setProperty("sun.net.inetaddr.negative.ttl", "30");

        var policy = DnsCachePolicy.resolve(Map.of(
                DnsCachePolicy.DNS_CACHE_TTL_KEY, 60,
                DnsCachePolicy.DNS_CACHE_NEGATIVE_TTL_KEY, 10));

        assertEquals(60, policy.cacheTtlSeconds());
        assertEquals(10, policy.negativeCacheTtlSeconds());
    }

    @Test
    void existingJvmPropertiesAreUsedWhenYanoConfigIsAbsent() {
        System.setProperty("sun.net.inetaddr.ttl", "45");
        System.setProperty("sun.net.inetaddr.negative.ttl", "6");

        var policy = DnsCachePolicy.resolve(Map.of());

        assertEquals(45, policy.cacheTtlSeconds());
        assertEquals(6, policy.negativeCacheTtlSeconds());
    }

    @Test
    void invalidTtlFailsFast() {
        assertThrows(IllegalArgumentException.class, () ->
                DnsCachePolicy.resolve(Map.of(DnsCachePolicy.DNS_CACHE_TTL_KEY, "-2")));
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
