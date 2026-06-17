package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import lombok.extern.slf4j.Slf4j;

import java.security.Security;
import java.util.Map;
import java.util.Objects;

/**
 * Applies Yano's JVM-wide DNS cache policy before upstream peer connections are
 * created.
 * <p>
 * Java's DNS cache settings are process-global and are read by the JDK's
 * {@code InetAddress} implementation. Yano applies these values early in the
 * runtime constructor so embedded/library users get the same behavior as the
 * packaged application. The policy is best-effort: applications that resolve
 * hostnames before constructing {@code Yano} may have already initialized the
 * JDK DNS cache policy.
 * <p>
 * Precedence is:
 * <ol>
 *     <li>{@value #DNS_CACHE_TTL_KEY} / {@value #DNS_CACHE_NEGATIVE_TTL_KEY}</li>
 *     <li>existing {@code sun.net.inetaddr.*} system properties</li>
 *     <li>existing {@code networkaddress.cache.*} security properties</li>
 *     <li>Yano defaults: {@value #DEFAULT_DNS_CACHE_TTL_SECONDS}s and
 *     {@value #DEFAULT_DNS_CACHE_NEGATIVE_TTL_SECONDS}s</li>
 * </ol>
 */
@Slf4j
public final class DnsCachePolicy {
    public static final String DNS_CACHE_TTL_KEY = YanoPropertyKeys.Dns.CACHE_TTL;
    public static final String DNS_CACHE_NEGATIVE_TTL_KEY = YanoPropertyKeys.Dns.CACHE_NEGATIVE_TTL;

    public static final int DEFAULT_DNS_CACHE_TTL_SECONDS = 60;
    public static final int DEFAULT_DNS_CACHE_NEGATIVE_TTL_SECONDS = 10;

    private static final String SECURITY_CACHE_TTL_KEY = "networkaddress.cache.ttl";
    private static final String SECURITY_CACHE_NEGATIVE_TTL_KEY = "networkaddress.cache.negative.ttl";
    private static final String SYSTEM_CACHE_TTL_KEY = "sun.net.inetaddr.ttl";
    private static final String SYSTEM_CACHE_NEGATIVE_TTL_KEY = "sun.net.inetaddr.negative.ttl";

    private DnsCachePolicy() {
    }

    /**
     * Configure DNS cache TTLs for client-mode Yano instances.
     *
     * @param globals runtime globals, optionally containing {@code yano.dns.*}
     * @param clientEnabled whether this Yano instance will create outbound peer connections
     */
    public static void configureForClientMode(Map<String, Object> globals, boolean clientEnabled) {
        if (!clientEnabled) {
            return;
        }

        ResolvedPolicy policy = resolve(globals);
        apply(SECURITY_CACHE_TTL_KEY, SYSTEM_CACHE_TTL_KEY, policy.cacheTtlSeconds());
        apply(SECURITY_CACHE_NEGATIVE_TTL_KEY, SYSTEM_CACHE_NEGATIVE_TTL_KEY, policy.negativeCacheTtlSeconds());

        log.info("Configured JVM DNS cache policy: {}={}s, {}={}s",
                DNS_CACHE_TTL_KEY, policy.cacheTtlSeconds(),
                DNS_CACHE_NEGATIVE_TTL_KEY, policy.negativeCacheTtlSeconds());
    }

    static ResolvedPolicy resolve(Map<String, Object> globals) {
        Map<String, Object> safeGlobals = globals == null ? Map.of() : globals;
        int ttl = resolveTtl(
                safeGlobals,
                DNS_CACHE_TTL_KEY,
                SYSTEM_CACHE_TTL_KEY,
                SECURITY_CACHE_TTL_KEY,
                DEFAULT_DNS_CACHE_TTL_SECONDS);
        int negativeTtl = resolveTtl(
                safeGlobals,
                DNS_CACHE_NEGATIVE_TTL_KEY,
                SYSTEM_CACHE_NEGATIVE_TTL_KEY,
                SECURITY_CACHE_NEGATIVE_TTL_KEY,
                DEFAULT_DNS_CACHE_NEGATIVE_TTL_SECONDS);
        return new ResolvedPolicy(ttl, negativeTtl);
    }

    private static void apply(String securityKey, String systemKey, int value) {
        String text = Integer.toString(value);
        Security.setProperty(securityKey, text);
        System.setProperty(systemKey, text);
    }

    private static int resolveTtl(
            Map<String, Object> globals,
            String yanoKey,
            String systemKey,
            String securityKey,
            int defaultValue) {
        Object configured = globals.get(yanoKey);
        if (configured != null) {
            return parseTtl(configured, yanoKey);
        }

        String systemValue = System.getProperty(systemKey);
        if (hasText(systemValue)) {
            return parseTtl(systemValue, systemKey);
        }

        String securityValue = Security.getProperty(securityKey);
        if (hasText(securityValue)) {
            return parseTtl(securityValue, securityKey);
        }

        return defaultValue;
    }

    private static int parseTtl(Object value, String source) {
        Objects.requireNonNull(value, source + " cannot be null");

        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                throw new IllegalArgumentException(source + " cannot be blank");
            }
            try {
                parsed = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(source + " must be an integer number of seconds", e);
            }
        }

        if (parsed < -1) {
            throw new IllegalArgumentException(source + " must be >= -1 seconds");
        }
        return parsed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    record ResolvedPolicy(int cacheTtlSeconds, int negativeCacheTtlSeconds) {
    }
}
