package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainLegacyRegistryLifecycleTest {
    private static final byte[] KEY = seed(91);
    private static final String KEY_HEX = HexUtil.encodeHexString(KEY);
    private static final String PUBLIC_KEY = HexUtil.encodeHexString(
            KeyGenUtil.getPublicKeyFromPrivateKey(KEY));

    @TempDir
    Path tempDirectory;

    @AfterEach
    void reset() {
        ClosingSinkFactory.CLOSES.set(0);
    }

    @Test
    void directConstructorOwnsAndTerminallyClosesCompatibilityRegistry()
            throws Exception {
        try (URLClassLoader loader = serviceLoader()) {
            AppChainSubsystem subsystem = new AppChainSubsystem(
                    config("legacy-owner"), 42, null, new NoopStateMachine(),
                    tempDirectory.toString(), loader,
                    LoggerFactory.getLogger(getClass()));

            subsystem.start();
            subsystem.stop();
            assertThat(ClosingSinkFactory.CLOSES).hasValue(0);

            subsystem.start();
            subsystem.close();
            subsystem.close();
            assertThat(ClosingSinkFactory.CLOSES).hasValue(1);
            assertThatThrownBy(subsystem::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed app-chain subsystem");
        }
    }

    @Test
    void failedDirectConstructionClosesCompatibilityRegistry() throws Exception {
        AppChainConfig invalid = AppChainConfig.builder("legacy-invalid")
                .signingKeyHex(KEY_HEX)
                .memberKeysHex(Set.of("00".repeat(32)))
                .proposerKeyHex("00".repeat(32))
                .threshold(1)
                .build();
        try (URLClassLoader loader = serviceLoader()) {
            assertThatThrownBy(() -> new AppChainSubsystem(
                    invalid, 42, null, new NoopStateMachine(),
                    tempDirectory.toString(), loader,
                    LoggerFactory.getLogger(getClass())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not in the configured member list");
        }
        assertThat(ClosingSinkFactory.CLOSES).hasValue(1);
    }

    @Test
    void catalogAwareConstructorDoesNotCloseExternallyOwnedRegistry() {
        CountingRegistry registry = new CountingRegistry();
        AppChainSubsystem subsystem = new AppChainSubsystem(
                config("catalog-owner"), 42, null, new NoopStateMachine(),
                tempDirectory.toString(), null, registry,
                LoggerFactory.getLogger(getClass()));

        subsystem.close();
        subsystem.close();

        assertThat(registry.closes).hasValue(0);
    }

    @Test
    void managerTerminalCloseReleasesDirectChildRegistry() throws Exception {
        try (URLClassLoader loader = serviceLoader()) {
            AppChainSubsystem subsystem = new AppChainSubsystem(
                    config("managed-legacy-owner"), 42, null,
                    new NoopStateMachine(), tempDirectory.toString(), loader,
                    LoggerFactory.getLogger(getClass()));
            AppChainManager manager = new AppChainManager(
                    List.of(subsystem), LoggerFactory.getLogger(getClass()));

            manager.close();
            manager.close();

            assertThat(ClosingSinkFactory.CLOSES).hasValue(1);
        }
    }

    private URLClassLoader serviceLoader() throws IOException {
        Path services = Files.createDirectories(
                tempDirectory.resolve("META-INF/services"));
        Files.writeString(services.resolve(FinalizedStreamSinkFactory.class.getName()),
                ClosingSinkFactory.class.getName());
        return new ServiceOnlyClassLoader(
                new URL[]{tempDirectory.toUri().toURL()}, getClass().getClassLoader());
    }

    private static AppChainConfig config(String chainId) {
        return AppChainConfig.builder(chainId)
                .signingKeyHex(KEY_HEX)
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1)
                .build();
    }

    private static byte[] seed(int value) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) value);
        return seed;
    }

    public static final class ClosingSinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        private static final AtomicInteger CLOSES = new AtomicInteger();

        @Override public String scheme() { return "lifecycle-test"; }

        @Override
        public List<FinalizedStreamSink> create(
                String chainId,
                Map<String, String> config
        ) {
            return List.of();
        }

        @Override public void close() { CLOSES.incrementAndGet(); }
    }

    private static final class NoopStateMachine implements AppStateMachine {
        @Override public String id() { return "noop"; }
        @Override public void apply(AppBlock block, AppStateWriter writer) { }
    }

    private static final class CountingRegistry
            implements PluginProviderRegistry, AutoCloseable {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public <P> Optional<P> find(Class<P> providerType, String selector) {
            return Optional.empty();
        }

        @Override
        public <P> List<String> names(Class<P> providerType) {
            return List.of();
        }

        @Override public void close() { closes.incrementAndGet(); }
    }

    private static final class ServiceOnlyClassLoader extends URLClassLoader {
        private ServiceOnlyClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (name.startsWith("META-INF/services/")) {
                return findResources(name);
            }
            return super.getResources(name);
        }

        @Override
        public URL getResource(String name) {
            if (name.startsWith("META-INF/services/")) {
                return findResource(name);
            }
            return super.getResource(name);
        }
    }
}
