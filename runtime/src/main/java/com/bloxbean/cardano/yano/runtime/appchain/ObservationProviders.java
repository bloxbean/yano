package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProvider;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProviderFactory;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves one bounded acquisition provider for every enabled definition. */
final class ObservationProviders implements AutoCloseable {
    static final String HTTPS_ATTESTED = "https-attested-v1";
    static final String HTTPS_EXACT = "https-exact-v1";

    private final Map<String, ObservationProvider> providers;

    private ObservationProviders(Map<String, ObservationProvider> providers) {
        this.providers = Map.copyOf(providers);
    }

    static ObservationProviders of(Map<String, ObservationProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        providers.forEach((id, provider) -> {
            if (id == null || id.isBlank() || provider == null) {
                throw new IllegalArgumentException("Invalid observation provider mapping");
            }
        });
        return new ObservationProviders(providers);
    }

    static ObservationProviders from(ObservationProfileV1 profile,
                                     Map<String, String> pluginSettings,
                                     PluginProviderRegistry pluginProviders) {
        Objects.requireNonNull(profile, "profile");
        if (!profile.enabled()) {
            return new ObservationProviders(Map.of());
        }
        Map<String, ObservationProvider> resolved = new LinkedHashMap<>();
        try {
            for (ObservationDefinition definition : profile.definitions()) {
                String prefix = "observations.providers." + definition.id() + ".";
                Map<String, String> settings = new LinkedHashMap<>();
                pluginSettings.forEach((key, value) -> {
                    if (key.startsWith(prefix)) {
                        settings.put(key.substring(prefix.length()), value);
                    }
                });
                String type = settings.remove("type");
                if (type == null || type.isBlank()) {
                    throw new IllegalArgumentException("Observation definition '"
                            + definition.id() + "' has no provider type");
                }
                if (!type.trim().equals(definition.acquisitionAdapterId())) {
                    throw new IllegalArgumentException("Observation definition '"
                            + definition.id() + "' requires provider type '"
                            + definition.acquisitionAdapterId() + "'");
                }
                ObservationProvider provider = switch (type.trim()) {
                    case HTTPS_ATTESTED -> new RestrictedHttpsObservationProvider(
                            RestrictedHttpsObservationProvider.Mode.ATTESTED,
                            definition, settings);
                    case HTTPS_EXACT -> new RestrictedHttpsObservationProvider(
                            RestrictedHttpsObservationProvider.Mode.RAW_EXACT,
                            definition, settings);
                    default -> pluginProviders.require(
                            ObservationProviderFactory.class, type.trim())
                            .create(definition.id(), Map.copyOf(settings));
                };
                resolved.put(definition.id(), Objects.requireNonNull(provider,
                        "ObservationProviderFactory.create returned null"));
            }
            return new ObservationProviders(resolved);
        } catch (Throwable failure) {
            try {
                closeAll(resolved);
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    ObservationProvider require(String definitionId) {
        ObservationProvider provider = providers.get(definitionId);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "No observation provider for definition " + definitionId);
        }
        return provider;
    }

    @Override
    public void close() {
        closeAll(providers);
    }

    private static void closeAll(Map<String, ObservationProvider> providers) {
        RuntimeException failure = null;
        for (ObservationProvider provider : providers.values()) {
            try {
                provider.close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = new RuntimeException("Failed to close observation provider",
                            closeFailure);
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
