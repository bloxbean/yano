package org.yanoproject.runtime.assembly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginApiVersion;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.api.plugin.PluginDigestMode;
import org.yanoproject.catalog.BundleContribution;
import org.yanoproject.catalog.BundleManifest;
import org.yanoproject.catalog.BundleManifestParser;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.catalog.IndexedBundle;
import org.yanoproject.catalog.PluginIndex;
import org.yanoproject.catalog.PluginIndexCodec;
import org.yanoproject.catalog.SemVersion;
import org.yanoproject.catalog.YanoApiRange;
import org.yanoproject.runtime.plugins.PluginLoaderHandle;
import org.yanoproject.x.framework.cardano.CardanoAppLedgerPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real catalog and both host assemblies, not an adapter-only mock context. */
@Timeout(30)
class DeferredAppLedgerAssemblyTest {
    private static final String LATE_BUNDLE = "zz.example.late-application";
    private static final String MACHINE = "late-initialized-machine";
    private static final List<String> calls = new ArrayList<>();
    private static PluginContext lateContext;
    private static boolean failInitialization;

    @TempDir Path directory;

    @Test
    void factoriesRunOnlyAfterEveryNodePluginHasInitializedInBothModes() throws Exception {
        for (RuntimeMode mode : RuntimeMode.values()) {
            reset(false);
            try (URLClassLoader loader = catalogLoader(mode)) {
                try (Yano node = build(mode, loader)) {
                    assertThat(node.appChain()).isPresent();
                    assertThat(calls).as("construction order in %s", mode)
                            .containsExactly("late.init", "factory.create");
                }
                assertThat(calls).as("construction/close order in %s", mode)
                        .containsExactly("late.init", "factory.create", "late.close");
            }
        }
    }

    @Test
    void laterPluginInitFailurePreventsEveryLedgerFactoryCallInBothModes() throws Exception {
        for (RuntimeMode mode : RuntimeMode.values()) {
            reset(true);
            try (URLClassLoader loader = catalogLoader(mode)) {
                assertThatThrownBy(() -> build(mode, loader))
                        .hasStackTraceContaining("late initialization failed");
            }
            assertThat(calls).as("failed initialization in %s", mode)
                    .containsExactly("late.init", "late.close");
            assertThat(directory.resolve(mode.name()).resolve("app-ledger")).doesNotExist();
        }
    }

    private static void reset(boolean fail) {
        calls.clear();
        lateContext = null;
        failInitialization = fail;
    }

    private Yano build(RuntimeMode mode, ClassLoader loader) throws IOException {
        Path root = Files.createDirectories(directory.resolve(mode.name()));
        YanoConfig config = YanoConfig.builder().mode(mode).enableClient(false).enableServer(false)
                .useRocksDB(true).rocksDBPath(root.resolve("l1").toString())
                .appChainStoragePath(root.resolve("app-ledger").toString()).build();
        Map<String, Object> globals = new LinkedHashMap<>();
        globals.put("yano.app-chain.enabled", true);
        globals.put("yano.app-chain.network-magic", 424242);
        globals.put("yano.app-chain.transport.mode", "dedicated");
        globals.put("yano.app-chain.chain-id", "ordering");
        globals.put("yano.app-chain.state-machine", MACHINE);
        globals.put("yano.app-chain.signing-key",
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        globals.put("yano.app-chain.members",
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, new byte[32]).settings()
                .forEach((key, value) -> globals.put("yano.app-chain." + key, value));
        PluginsOptions plugins = new PluginsOptions(true, false,
                Set.of("org.yanoproject.x.app-ledger", LATE_BUNDLE), Set.of(), Map.of());
        return YanoAssembly.fromConfig(config).runtimeOptions(new RuntimeOptions(null, plugins, globals))
                .pluginLoader(PluginLoaderHandle.packagedClasspath(loader)).build();
    }

    private URLClassLoader catalogLoader(RuntimeMode mode) throws IOException {
        String manifestPath = "META-INF/yano/plugins/org.yanoproject.x.app-ledger.json";
        IndexedBundle framework;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(manifestPath)) {
            assertThat(input).as("published Cardano framework manifest").isNotNull();
            framework = new IndexedBundle(new BundleManifestParser().parse(manifestPath, input),
                    "sha256:" + "a".repeat(64), PluginDigestMode.ARTIFACT_CLOSURE);
        }
        IndexedBundle application = bundle(LATE_BUNDLE, "1.0.0", List.of(
                new BundleContribution(ContributionKind.NODE_PLUGIN, LATE_BUNDLE, LatePlugin.class.getName()),
                new BundleContribution(ContributionKind.APP_STATE_MACHINE, MACHINE, LateStateMachineProvider.class.getName())));
        PluginIndex index = new PluginIndex(1, List.of(framework, application), List.of());
        Path jar = directory.resolve("catalog-" + mode + ".jar");
        Map<String, byte[]> entries = Map.of(
                PluginIndex.RESOURCE_PATH, new PluginIndexCodec().write(index),
                "META-INF/services/" + NodePlugin.class.getName(),
                (CardanoAppLedgerPlugin.class.getName() + "\n" + LatePlugin.class.getName() + "\n")
                        .getBytes(StandardCharsets.UTF_8),
                "META-INF/services/" + AppStateMachineProvider.class.getName(),
                (LateStateMachineProvider.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader()) {
            @Override public Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals(PluginIndex.RESOURCE_PATH) || name.startsWith("META-INF/services/")) {
                    return findResources(name);
                }
                return super.getResources(name);
            }
        };
    }

    private static IndexedBundle bundle(String id, String version, List<BundleContribution> contributions) {
        return new IndexedBundle(new BundleManifest(1, id, SemVersion.parse(version),
                new YanoApiRange(PluginApiVersion.CURRENT_MAJOR, PluginApiVersion.CURRENT_MAJOR,
                        PluginApiVersion.CURRENT_LEVEL), List.of(), contributions),
                "sha256:" + "a".repeat(64), PluginDigestMode.ARTIFACT_CLOSURE);
    }

    public static final class LatePlugin implements NodePlugin {
        public String id() { return LATE_BUNDLE; }
        public String version() { return "1.0.0"; }
        public void init(PluginContext context) {
            calls.add("late.init");
            if (failInitialization) throw new IllegalStateException("late initialization failed");
            context.registerService("late.ready", Boolean.TRUE);
            lateContext = context;
        }
        public void start() { calls.add("late.start"); }
        public void stop() { calls.add("late.stop"); }
        public void close() { calls.add("late.close"); }
    }

    public static final class LateStateMachineProvider implements AppStateMachineProvider {
        public String id() { return MACHINE; }
        public AppStateMachine create() {
            calls.add("factory.create");
            if (lateContext == null || !lateContext.getService("late.ready", Boolean.class).orElse(false)) {
                throw new IllegalStateException("factory ran before its NodePlugin initialized");
            }
            return new AppStateMachine() {
                public String id() { return MACHINE; }
                public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) { }
            };
        }
    }
}
