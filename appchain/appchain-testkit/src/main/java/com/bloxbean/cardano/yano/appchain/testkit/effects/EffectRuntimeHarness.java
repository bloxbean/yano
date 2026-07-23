package com.bloxbean.cardano.yano.appchain.testkit.effects;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectView;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-node, real Effect Runtime harness for connector integration tests.
 * The harness finalizes an app message through {@link AppChainSubsystem},
 * emits one effect per message, and exposes the runtime's actual status and
 * metrics views. The caller owns and cleans {@code storageDirectory}; this
 * class never deletes paths.
 */
public final class EffectRuntimeHarness implements AutoCloseable {
    private static final long TEST_PROTOCOL_MAGIC = 42;
    private static final String CHAIN_ID = "effect-runtime-test";
    private static final String TOPIC = "effect-test";
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final int MAX_EXECUTOR_SETTINGS = 64;
    private static final int MAX_EXECUTOR_SETTING_VALUE_CHARACTERS = 8 * 1024;

    private final String actionType;
    private final AppChainSubsystem subsystem;
    private final AtomicBoolean closed = new AtomicBoolean();

    private EffectRuntimeHarness(String actionType, AppChainSubsystem subsystem) {
        this.actionType = actionType;
        this.subsystem = subsystem;
    }

    /**
     * Start a real one-member app chain and select {@code factory} through its
     * normal plugin scheme. At least one executor setting is required because
     * the runtime deliberately discovers schemes from configured namespaces.
     *
     * @param actionType the exact connector effect type emitted by the harness
     * @param factory the executor factory selected through its normal plugin scheme
     * @param executorSettings connector settings below the selected scheme namespace
     * @param storageDirectory caller-owned runtime storage that this harness never deletes
     * @return the started harness
     */
    public static EffectRuntimeHarness start(String actionType,
                                             AppEffectExecutorFactory factory,
                                             Map<String, String> executorSettings,
                                             Path storageDirectory) {
        if (actionType == null || !actionType.matches("[a-z][a-z0-9.-]{0,63}")) {
            throw new IllegalArgumentException("actionType must be a bounded lowercase identifier");
        }
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(storageDirectory, "storageDirectory");
        Map<String, String> suppliedSettings = executorSettings != null
                ? executorSettings : Map.of();
        if (suppliedSettings.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one executor setting is required for scheme discovery");
        }
        if (suppliedSettings.size() > MAX_EXECUTOR_SETTINGS) {
            throw new IllegalArgumentException("too many executor settings");
        }
        Map<String, String> connectorSettings = Map.copyOf(suppliedSettings);
        String scheme = factory.scheme();
        if (scheme == null || !scheme.matches("[a-z][a-z0-9_-]{0,63}")
                || scheme.equals("webhook")) {
            throw new IllegalArgumentException("factory scheme is not selectable by this harness");
        }

        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        String signingKey = HexUtil.encodeHexString(seed);
        String memberKey = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed));

        Map<String, String> settings = runtimeSettings(actionType, scheme, connectorSettings);
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(signingKey)
                .memberKeysHex(Set.of(memberKey))
                .proposerKeyHex(memberKey)
                .threshold(1)
                .blockIntervalMs(25)
                .maxBlockMessages(1)
                .pluginSettings(settings)
                .build();
        PluginProviderRegistry providers = new SingleFactoryRegistry(scheme, factory);
        AppChainSubsystem subsystem = new AppChainSubsystem(
                config,
                TEST_PROTOCOL_MAGIC,
                null,
                emitting(actionType),
                storageDirectory.toAbsolutePath().normalize().toString(),
                null,
                providers,
                LoggerFactory.getLogger(EffectRuntimeHarness.class));
        try {
            subsystem.start();
            return new EffectRuntimeHarness(actionType, subsystem);
        } catch (Throwable startupFailure) {
            try {
                subsystem.close();
            } catch (Throwable closeFailure) {
                startupFailure.addSuppressed(closeFailure);
            }
            if (startupFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (startupFailure instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException("effect runtime harness startup failed", startupFailure);
        }
    }

    /**
     * Finalizes one message and returns its consensus-tier effect view.
     *
     * @param payload the effect payload, defensively copied
     * @param timeout the positive wait bound, at most five minutes
     * @return the finalized effect emitted for the message
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public EffectView submit(byte[] payload, Duration timeout) throws InterruptedException {
        ensureOpen();
        int payloadLength = payload != null ? payload.length : 0;
        if (payloadLength > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload exceeds the harness effect bound");
        }
        byte[] body = payload != null ? payload.clone() : new byte[0];
        Duration bound = requirePositive(timeout);
        String messageId = subsystem.submit(TOPIC, body);
        byte[] messageIdBytes = HexUtil.decodeHexString(messageId);
        String scope = scope(messageId);
        long started = System.nanoTime();
        long timeoutNanos = bound.toNanos();
        while (hasTimeRemaining(started, System.nanoTime(), timeoutNanos)) {
            Optional<Long> height = subsystem.messageHeight(messageIdBytes);
            if (height.isPresent()) {
                Optional<EffectView> effect = subsystem.effects(height.get(), 8).stream()
                        .filter(candidate -> candidate.height() == height.get())
                        .filter(candidate -> candidate.type().equals(actionType))
                        .filter(candidate -> candidate.scope().equals(scope))
                        .findFirst();
                if (effect.isPresent()) {
                    return effect.get();
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for finalized effect emission");
    }

    /**
     * Waits for an actual runtime status such as DONE, SUBMITTED, RETRY, or PARKED.
     *
     * @param effect the finalized effect to observe
     * @param expectedStatuses accepted runtime status names
     * @param timeout the positive wait bound, at most five minutes
     * @return the effect with actual runtime stats and status snapshots
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public RuntimeObservation awaitStatus(EffectView effect,
                                          Set<String> expectedStatuses,
                                          Duration timeout) throws InterruptedException {
        ensureOpen();
        Objects.requireNonNull(effect, "effect");
        Set<String> expected = expectedStatuses != null ? Set.copyOf(expectedStatuses) : Set.of();
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("at least one expected status is required");
        }
        Duration bound = requirePositive(timeout);
        long started = System.nanoTime();
        long timeoutNanos = bound.toNanos();
        while (hasTimeRemaining(started, System.nanoTime(), timeoutNanos)) {
            Optional<Map<String, Object>> status = subsystem.effectRuntimeStatus(
                    effect.height(), effect.ordinal());
            if (status.isPresent() && expected.contains(String.valueOf(status.get().get("status")))) {
                return new RuntimeObservation(effect, subsystem.effectStats(), status.get());
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for expected Effect Runtime status");
    }

    /**
     * Finalizes one message and waits until its executor reports DONE.
     *
     * @param payload the effect payload, defensively copied
     * @param timeout the positive wait bound used by both stages
     * @return the completed runtime observation
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public RuntimeObservation submitAndAwaitDone(byte[] payload, Duration timeout)
            throws InterruptedException {
        EffectView effect = submit(payload, timeout);
        return awaitStatus(effect, Set.of("DONE"), timeout);
    }

    /**
     * Returns the Effect Runtime's actual current stats view.
     *
     * @return a bounded runtime-stats snapshot
     */
    public Map<String, Object> stats() {
        ensureOpen();
        return subsystem.effectStats();
    }

    private static Map<String, String> runtimeSettings(String actionType,
                                                       String scheme,
                                                       Map<String, String> connectorSettings) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("effects.enabled", "true");
        settings.put("effects.max-per-block", "1");
        settings.put("effects.max-payload-bytes", Integer.toString(MAX_PAYLOAD_BYTES));
        settings.put("effects.executor.enabled", "true");
        settings.put("effects.executor.types", actionType);
        settings.put("effects.executor.tick-ms", "10");
        settings.put("effects.executor.max-parallel", "1");
        settings.put("effects.executor.max-attempts", "3");
        settings.put("effects.executor.backoff-initial-ms", "10");
        settings.put("effects.executor.backoff-max-ms", "25");
        settings.put("effects.executor.max-batch", "1");
        settings.put("effects.metrics.types", actionType);
        connectorSettings.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || value == null || value.length() > MAX_EXECUTOR_SETTING_VALUE_CHARACTERS) {
                throw new IllegalArgumentException("executor settings require bounded keys and values");
            }
            settings.put("effects.executors." + scheme + "." + key, value);
        });
        return Map.copyOf(settings);
    }

    private static AppStateMachine emitting(String actionType) {
        return new AppStateMachine() {
            @Override public String id() { return "effect-runtime-test-emitter"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }

            @Override
            public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
                for (AppMessage message : block.messages()) {
                    effects.emit(EffectIntent.of(actionType, message.getBody())
                            .scope(scope(message.getMessageIdHex()))
                            .gate(FinalityGate.APP_FINAL)
                            .result(ResultPolicy.NONE)
                            .build());
                }
            }
        };
    }

    private static String scope(String messageId) {
        return "testkit/" + messageId;
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be positive and no longer than five minutes");
        }
        return timeout;
    }

    /** Package-visible for deterministic wraparound regression tests. */
    static boolean hasTimeRemaining(long started, long now, long timeoutNanos) {
        return now - started < timeoutNanos;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("effect runtime harness is closed");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            subsystem.close();
        }
    }

    /**
     * Actual Effect Runtime views captured after a status transition.
     *
     * @param effect the consensus-tier effect
     * @param stats the runtime stats snapshot
     * @param status the effect-specific runtime status snapshot
     */
    public record RuntimeObservation(EffectView effect,
                                     Map<String, Object> stats,
                                     Map<String, Object> status) {
        /** Validates the effect and defensively snapshots both maps. */
        public RuntimeObservation {
            Objects.requireNonNull(effect, "effect");
            stats = Map.copyOf(stats);
            status = Map.copyOf(status);
        }
    }

    private static final class SingleFactoryRegistry implements PluginProviderRegistry {
        private final String scheme;
        private final AppEffectExecutorFactory factory;

        private SingleFactoryRegistry(String scheme, AppEffectExecutorFactory factory) {
            this.scheme = scheme;
            this.factory = factory;
        }

        @Override
        public <P> Optional<P> find(Class<P> providerType, String selector) {
            if (providerType == AppEffectExecutorFactory.class
                    && scheme.equals(selector)) {
                return Optional.of(providerType.cast(factory));
            }
            return Optional.empty();
        }

        @Override
        public <P> List<String> names(Class<P> providerType) {
            return providerType == AppEffectExecutorFactory.class
                    ? List.of(scheme) : List.of();
        }
    }
}
