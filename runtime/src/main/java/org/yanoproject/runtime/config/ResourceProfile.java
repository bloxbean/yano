package org.yanoproject.runtime.config;

import org.yanoproject.api.config.YanoPropertyKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit runtime footprint profile. It changes performance defaults only;
 * every individual property remains authoritative when supplied.
 */
public enum ResourceProfile {
    DEFAULT("default"),
    LOW_MEMORY("low-memory");

    private static final String ENV_NAME = "YANO_RESOURCE_PROFILE";
    private static final Logger log = LoggerFactory.getLogger(ResourceProfile.class);
    private static final AtomicBoolean lowMemoryDeprecationLogged = new AtomicBoolean();

    private final String externalName;

    ResourceProfile(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    public boolean isLowMemory() {
        return this == LOW_MEMORY;
    }

    public static ResourceProfile current() {
        String value = System.getProperty(YanoPropertyKeys.RESOURCE_PROFILE);
        if (value == null || value.isBlank()) {
            value = System.getenv(ENV_NAME);
        }
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ResourceProfile profile : values()) {
            if (profile.externalName.equals(normalized)) {
                if (profile == LOW_MEMORY && lowMemoryDeprecationLogged.compareAndSet(false, true)) {
                    log.warn("Resource profile 'low-memory' is deprecated; use the explicit "
                            + "Quarkus application profile 'xsmall' instead");
                }
                return profile;
            }
        }
        throw new IllegalArgumentException(YanoPropertyKeys.RESOURCE_PROFILE
                + " must be default or low-memory, but was: " + value);
    }
}
