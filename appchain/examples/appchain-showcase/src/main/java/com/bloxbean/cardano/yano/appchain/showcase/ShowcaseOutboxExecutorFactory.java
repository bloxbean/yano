package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Factory for the demo-only local outbox executor. */
public final class ShowcaseOutboxExecutorFactory implements AppEffectExecutorFactory {
    public static final String SCHEME = "showcase-outbox";
    private static final Set<String> KEYS = Set.of("enabled", "directory");

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
        config.keySet().stream().filter(key -> !KEYS.contains(key)).sorted().findFirst()
                .ifPresent(key -> {
                    throw new IllegalArgumentException("Unknown showcase-outbox setting: " + key);
                });
        String rawEnabled = config.getOrDefault("enabled", "false").trim();
        if (!rawEnabled.equals("true") && !rawEnabled.equals("false")) {
            throw new IllegalArgumentException("showcase-outbox.enabled must be true or false");
        }
        if (!Boolean.parseBoolean(rawEnabled)) {
            return List.of();
        }
        String directory = config.get("directory");
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("showcase-outbox.directory is required when enabled");
        }
        Path path = Path.of(directory.trim());
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("showcase-outbox.directory must be absolute");
        }
        return List.of(new ShowcaseOutboxExecutor(path.normalize()));
    }
}
