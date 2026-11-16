package org.yanoproject.runtime.plugins;

import org.yanoproject.api.plugin.spi.ContributionKindBinding;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.catalog.ContributionKindRegistry;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The contribution kinds of one catalog build and their runtime bindings.
 *
 * <p>Descriptor kinds and their bindings are read only from the trusted host
 * class loader: the bindings come from the (uber-JAR merged) ServiceLoader
 * file, and each owner they name is loaded from its owner-qualified descriptor
 * path. Every descriptor kind must have exactly one binding with the same key,
 * interface and owner.</p>
 */
final class PluginKinds {
    /** Built-in kinds only, for hosts and tests without descriptor-providing frameworks. */
    static final PluginKinds BUILT_INS = of(ContributionKindRegistry.builtIns(), List.of());

    private final ContributionKindRegistry registry;
    private final Map<ContributionKind, ContributionKindBinding<?>> bindings;

    private PluginKinds(
            ContributionKindRegistry registry,
            Map<ContributionKind, ContributionKindBinding<?>> bindings
    ) {
        this.registry = registry;
        this.bindings = Map.copyOf(bindings);
    }

    /**
     * Reads descriptors and bindings visible through the host loader.
     *
     * @param hostLoader trusted host (parent) class loader
     * @return validated kinds
     */
    static PluginKinds load(ClassLoader hostLoader) {
        Objects.requireNonNull(hostLoader, "hostLoader");
        Set<Path> hostArtifacts = new LinkedHashSet<>();
        List<ContributionKindBinding<?>> descriptorBindings = new ArrayList<>();
        try {
            ServiceLoader.load(ContributionKindBinding.class, hostLoader)
                    .forEach(descriptorBindings::add);
            Set<String> owners = new TreeSet<>();
            for (ContributionKindBinding<?> binding : descriptorBindings) {
                owners.add(Objects.requireNonNull(binding.descriptorOwner(),
                        "Contribution kind binding '" + binding.manifestKey() + "' names no descriptor owner"));
            }
            for (String owner : owners) {
                String path = ContributionKindRegistry.resourcePath(owner);
                Enumeration<URL> descriptors = hostLoader.getResources(path);
                if (!descriptors.hasMoreElements()) {
                    throw new IllegalStateException("Contribution kind bindings name descriptor owner '" + owner
                            + "' but the host class path has no " + path);
                }
                while (descriptors.hasMoreElements()) {
                    hostArtifacts.add(PluginRuntimeEnvironment.manifestArtifact(descriptors.nextElement(), path));
                }
            }
            return of(ContributionKindRegistry.fromHostArtifacts(hostArtifacts), descriptorBindings);
        } catch (IOException | ServiceConfigurationError e) {
            throw new IllegalStateException("Host contribution kinds could not be read", e);
        }
    }

    /**
     * Pairs every descriptor kind of {@code registry} with exactly one binding.
     *
     * @param registry kinds of this catalog build
     * @param descriptorBindings bindings for the registry's descriptor kinds
     * @return validated kinds
     */
    static PluginKinds of(
            ContributionKindRegistry registry,
            Collection<? extends ContributionKindBinding<?>> descriptorBindings
    ) {
        Map<ContributionKind, ContributionKindBinding<?>> bindings = new HashMap<>();
        for (ContributionKindBinding<?> binding : descriptorBindings) {
            String key = binding.manifestKey();
            ContributionKind kind;
            try {
                kind = registry.fromManifestKey(key);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalStateException("Contribution kind binding '" + key
                        + "' has no host contribution-kind descriptor");
            }
            if (PluginSpiFacades.BUILT_IN_BINDINGS.containsKey(key)) {
                throw new IllegalStateException("Contribution kind binding '" + key
                        + "' redefines a built-in kind");
            }
            String owner = registry.descriptorOwner(kind).orElse(null);
            String named = Objects.requireNonNull(binding.descriptorOwner(),
                    "Contribution kind binding '" + key + "' names no descriptor owner");
            if (!named.equals(owner)) {
                throw new IllegalStateException("Contribution kind binding '" + key + "' names descriptor owner '"
                        + named + "' but the kind is declared by '" + owner + "'");
            }
            if (!kind.serviceInterface().equals(binding.serviceType().getName())) {
                throw new IllegalStateException("Contribution kind binding '" + key + "' binds "
                        + binding.serviceType().getName() + " but its descriptor declares "
                        + kind.serviceInterface());
            }
            if (bindings.putIfAbsent(kind, binding) != null) {
                throw new IllegalStateException("Contribution kind '" + key + "' has more than one binding");
            }
        }
        for (ContributionKind kind : registry.kinds()) {
            ContributionKindBinding<?> builtIn = PluginSpiFacades.BUILT_IN_BINDINGS.get(kind.manifestKey());
            if (builtIn != null) {
                bindings.put(kind, builtIn);
            } else if (!bindings.containsKey(kind)) {
                throw new IllegalStateException("Contribution kind '" + kind.manifestKey()
                        + "' has no host binding");
            }
        }
        return new PluginKinds(registry, bindings);
    }

    ContributionKindRegistry registry() {
        return registry;
    }

    ContributionKindBinding<?> binding(ContributionKind kind) {
        return Objects.requireNonNull(bindings.get(kind), "unbound contribution kind");
    }

    /** Reads {@code provider}'s selector through its kind binding. */
    String selector(ContributionKind kind, Object provider) {
        return selector(binding(kind), provider);
    }

    private static <P> String selector(ContributionKindBinding<P> binding, Object provider) {
        return binding.selector(binding.serviceType().cast(provider));
    }

    /** Kind keys whose request operations are recorded in plugin operations data. */
    Set<String> operationsTrackedKinds() {
        return registry.kinds().stream()
                .filter(ContributionKind::operationsTracked)
                .map(ContributionKind::manifestKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
