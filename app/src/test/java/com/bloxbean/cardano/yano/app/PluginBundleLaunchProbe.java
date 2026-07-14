package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle;
import com.bloxbean.cardano.yano.runtime.plugins.PluginRuntimeEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/** Process-level probe for the documented self-contained plugin-directory layout. */
public final class PluginBundleLaunchProbe {
    private static final Set<String> EXPECTED_BUNDLES = Set.of(
            "com.bloxbean.cardano.yano.appchain.kafka",
            "com.bloxbean.cardano.yano.appchain.effects.cardano",
            "com.bloxbean.cardano.yano.appchain.zk");

    private PluginBundleLaunchProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != EXPECTED_BUNDLES.size()) {
            throw new IllegalArgumentException("Expected exactly three plugin bundle paths");
        }
        Path directory = Files.createTempDirectory("yano-plugin-bundle-probe-");
        ClassLoader originalTccl = Thread.currentThread().getContextClassLoader();
        ClassLoader callerTccl = new ClassLoader(originalTccl) { };
        Thread.currentThread().setContextClassLoader(callerTccl);
        try {
            for (String argument : args) {
                Path source = Path.of(argument);
                Path copy = directory.resolve(source.getFileName());
                Files.copy(source, copy,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
            try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                    PluginsOptions.defaults(), PluginLoaderHandle.directory(
                            directory, PluginBundleLaunchProbe.class.getClassLoader()))) {
                requireTccl(callerTccl, "catalog construction");
                if (!environment.selectedBundleIds().containsAll(EXPECTED_BUNDLES)) {
                    throw new IllegalStateException("Plugin directory catalog misses first-party bundles: "
                            + environment.selectedBundleIds());
                }
                require(environment, FinalizedStreamSinkFactory.class, "kafka");
                require(environment, AppEffectExecutorFactory.class, "cardano");
                for (String machine : List.of("credential-registry", "zk-gate", "zk-membership")) {
                    require(environment, AppStateMachineProvider.class, machine);
                }
                requireTccl(callerTccl, "provider construction and metadata");
                // Force representative third-party linkage and prove it came
                // from each bundle, not a coincidental host dependency.
                requireOwnedClass(environment,
                        "org.apache.kafka.clients.producer.KafkaProducer",
                        "com.bloxbean.cardano.yano.appchain.kafka");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService",
                        "com.bloxbean.cardano.yano.appchain.effects.cardano");
                requireOwnedClass(environment,
                        "com.bloxbean.cardano.zeroj.verifier.core.VerifierOrchestrator",
                        "com.bloxbean.cardano.yano.appchain.zk");

                // Exercise the real ZeroJ default-ServiceLoader path through a
                // plugin factory callback. The context assertion proves the
                // facade installed the directory loader while ZeroJ discovers
                // its verifier backends; the postcondition proves restoration.
                Path vk = directory.resolve("probe.vk");
                byte[] vkBytes = "yano-zk-backend-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Files.write(vk, vkBytes);
                Map<String, String> settings = Map.of(
                        "zk.circuits[0].id", "probe",
                        "zk.circuits[0].vk-file", vk.toString(),
                        "zk.circuits[0].vk-hash",
                        HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(vkBytes)),
                        "zk.circuits[0].proof-system", "groth16",
                        "zk.circuits[0].curve", "bls12381");
                ClassLoader pluginLoader = environment.classLoader();
                AppStateMachineProvider zkProvider = require(
                        environment, AppStateMachineProvider.class, "zk-gate");
                AppStateMachine zkMachine = zkProvider.create(new AppStateMachineContext() {
                    @Override
                    public String chainId() {
                        requireTccl(pluginLoader, "ZK provider chain context");
                        return "probe";
                    }

                    @Override
                    public Map<String, String> settings() {
                        requireTccl(pluginLoader, "ZK provider settings context");
                        return settings;
                    }
                });
                if (!"zk-gate".equals(zkMachine.id())) {
                    throw new IllegalStateException("ZK provider returned the wrong state machine");
                }
                requireTccl(callerTccl, "ZK provider and state-machine callbacks");
                instantiateZkVerifierBackends(environment,
                        "com.bloxbean.cardano.yano.appchain.zk");
                requireTccl(callerTccl, "ZK backend probe");
            }
            requireTccl(callerTccl, "plugin environment close");
        } finally {
            Thread.currentThread().setContextClassLoader(originalTccl);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static <P> P require(PluginRuntimeEnvironment environment,
                                 Class<P> type,
                                 String name) {
        return environment.providers().find(type, name).orElseThrow(() ->
                new IllegalStateException("Missing provider " + type.getName() + "/" + name));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void instantiateZkVerifierBackends(
            PluginRuntimeEnvironment environment,
            String expectedBundleId) throws Exception {
        ClassLoader loader = environment.classLoader();
        Class serviceType = Class.forName(
                "com.bloxbean.cardano.zeroj.backend.spi.ZkVerifier", true, loader);
        List<ServiceLoader.Provider<?>> providers = (List) ServiceLoader
                .load(serviceType, loader).stream().toList();
        if (providers.isEmpty()) {
            throw new IllegalStateException("ZK bundle exposes no verifier backend services");
        }
        for (ServiceLoader.Provider<?> provider : providers) {
            Object backend = provider.get();
            requireOwnedClass(environment, backend.getClass().getName(), expectedBundleId);
            Object descriptor = backend.getClass().getMethod("descriptor").invoke(backend);
            if (descriptor == null) {
                throw new IllegalStateException("ZK verifier backend returned no descriptor");
            }
        }
    }

    private static void requireTccl(ClassLoader expected, String operation) {
        if (Thread.currentThread().getContextClassLoader() != expected) {
            throw new IllegalStateException(operation + " did not restore the calling TCCL");
        }
    }

    private static void requireOwnedClass(PluginRuntimeEnvironment environment,
                                          String className,
                                          String expectedBundleId) throws Exception {
        ClassLoader loader = environment.classLoader();
        Class<?> dependency = Class.forName(className, false, loader);
        Path actual = codeSource(dependency);
        List<Path> providerSources = environment.catalog().bundles().stream()
                .filter(bundle -> bundle.selected() && bundle.id().equals(expectedBundleId))
                .flatMap(bundle -> bundle.contributions().stream())
                .map(contribution -> contribution.providerClass())
                .map(providerClass -> {
                    try {
                        return codeSource(Class.forName(providerClass, false, loader));
                    } catch (Exception failure) {
                        throw new IllegalStateException(
                                "Cannot resolve provider source for " + providerClass, failure);
                    }
                })
                .distinct()
                .toList();
        if (providerSources.isEmpty()) {
            throw new IllegalStateException("Missing selected bundle mapping for "
                    + expectedBundleId);
        }
        boolean owned = false;
        for (Path providerSource : providerSources) {
            if (Files.isSameFile(actual, providerSource)) {
                owned = true;
                break;
            }
        }
        if (!owned) {
            throw new IllegalStateException("Dependency " + className
                    + " resolved outside its bundle: " + actual.getFileName());
        }
    }

    private static Path codeSource(Class<?> type) throws Exception {
        var codeSource = type.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IllegalStateException("Class has no code source: " + type.getName());
        }
        return Path.of(codeSource.getLocation().toURI());
    }
}
