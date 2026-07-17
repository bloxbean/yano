package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AppChainProviderRegistryTest {

    private static final Logger LOG = LoggerFactory.getLogger(AppChainProviderRegistryTest.class);
    private static final byte[] KEY = seed(74);
    private static final String KEY_HEX = HexUtil.encodeHexString(KEY);
    private static final String PUBLIC_KEY = HexUtil.encodeHexString(
            KeyGenUtil.getPublicKeyFromPrivateKey(KEY));

    @TempDir
    Path tempDir;

    @Test
    void allSixTypedProvidersResolveThroughOneRegistryWithFreshChainOutputs() {
        ProviderFixtures fixtures = new ProviderFixtures();
        TestRegistry registry = fixtures.registry();
        AppChainSubsystem first = subsystem("registry-a", tempDir.resolve("a"), registry,
                configuredSettings());
        AppChainSubsystem second = subsystem("registry-b", tempDir.resolve("b"), registry,
                configuredSettings());

        try {
            first.start();
            second.start();

            assertThat(first.health().status()).isEqualTo(SubsystemHealth.Status.UP);
            assertThat(second.health().status()).isEqualTo(SubsystemHealth.Status.UP);
            assertThat(fixtures.signers.get()).isEqualTo(2);
            assertThat(fixtures.stateMachines.get()).isEqualTo(2);
            assertThat(fixtures.sequencerModes.get()).isEqualTo(2);
            assertThat(fixtures.observers.get()).isEqualTo(2);
            assertThat(fixtures.sinks.get()).isEqualTo(2);
            assertThat(fixtures.executors.get()).isEqualTo(2);
        } finally {
            second.stop();
            first.stop();
        }

        awaitValue(fixtures.closedSinks, 2);
        awaitValue(fixtures.closedExecutors, 2);
    }

    @Test
    void configuredEffectFactoryFailureIsFatalAndReleasesLedger() {
        TestRegistry registry = new TestRegistry().add(AppEffectExecutorFactory.class,
                "broken", new AppEffectExecutorFactory() {
                    @Override public String scheme() { return "broken"; }
                    @Override public List<AppEffectExecutor> create(
                            String chainId, Map<String, String> config) {
                        throw new IllegalStateException("factory boom");
                    }
                });
        Map<String, String> brokenSettings = Map.of(
                "effects.enabled", "true",
                "effects.executor.enabled", "true",
                "effects.executor.identity", "registry-test",
                "effects.executors.broken.enabled", "true");
        Path base = tempDir.resolve("fatal");
        AppChainSubsystem failed = builtInSubsystem("effect-failure", base, registry, brokenSettings);

        assertThatThrownBy(failed::start)
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("effect executor factory 'broken'")
                .hasRootCauseMessage("factory boom");

        AppChainSubsystem replacement = builtInSubsystem(
                "effect-failure", base, registry, Map.of());
        try {
            replacement.start();
            assertThat(replacement.health().status()).isEqualTo(SubsystemHealth.Status.UP);
        } finally {
            replacement.stop();
        }
    }

    @Test
    void effectExecutorIdentityIsSnapshottedOnceForConfigBinding() {
        AtomicInteger idCalls = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() {
                if (idCalls.incrementAndGet() == 1) {
                    return "stable-effect-id";
                }
                throw new IllegalStateException("executor id must not be called again");
            }
            @Override public boolean supports(String effectType) { return true; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                return EffectExecution.confirmed(new byte[0]);
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        TestRegistry registry = new TestRegistry().add(AppEffectExecutorFactory.class,
                "stateful-effect-id", effectFactory("stateful-effect-id", executor));
        AppChainSubsystem subsystem = builtInSubsystem(
                "stateful-effect-id", tempDir.resolve("stateful-effect-id"), registry,
                effectSettings("stateful-effect-id"));

        try {
            subsystem.start();
            assertThat(subsystem.health().status()).isEqualTo(SubsystemHealth.Status.UP);
            assertThat(subsystem.effectStats().get("executors").toString())
                    .contains("stable-effect-id");
            assertThat(idCalls).hasValue(1);
        } finally {
            subsystem.stop();
        }
        awaitValue(closes, 1);
    }

    @Test
    void sameSubsystemRestartWaitsForEffectExecutorCloseCompletion() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AppEffectExecutorFactory factory = new AppEffectExecutorFactory() {
            @Override public String scheme() { return "blocking-close"; }
            @Override public List<AppEffectExecutor> create(
                    String chainId, Map<String, String> config) {
                int generation = creations.incrementAndGet();
                return List.of(new AppEffectExecutor() {
                    @Override public String id() { return "blocking-close-" + generation; }
                    @Override public boolean supports(String effectType) { return true; }
                    @Override public EffectExecution execute(
                            EffectExecutionContext context, PendingEffect effect) {
                        return EffectExecution.confirmed(new byte[0]);
                    }
                    @Override public void close() {
                        closes.incrementAndGet();
                        if (generation == 1) {
                            closeEntered.countDown();
                            awaitUninterruptibly(releaseClose);
                        }
                    }
                });
            }
        };
        TestRegistry registry = new TestRegistry().add(
                AppEffectExecutorFactory.class, "blocking-close", factory);
        Map<String, String> settings = new LinkedHashMap<>(effectSettings("blocking-close"));
        settings.put("effects.executor.tick-ms", "60000");
        AppChainSubsystem subsystem = builtInSubsystem(
                "effect-close-fence", tempDir.resolve("effect-close-fence"), registry,
                Map.copyOf(settings));

        try {
            subsystem.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), subsystem::stop);
            assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(subsystem::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still draining");

            releaseClose.countDown();
            startEventually(subsystem);
            assertThat(subsystem.health().status()).isEqualTo(SubsystemHealth.Status.UP);
        } finally {
            releaseClose.countDown();
            subsystem.stop();
        }
        assertThat(creations).hasValue(2);
        awaitValue(closes, 2);
    }

    @Test
    void failedEffectExecutorCleanupFailsSameSubsystemRestartClosed() {
        AtomicInteger closes = new AtomicInteger();
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "failing-close"; }
            @Override public boolean supports(String effectType) { return true; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                return EffectExecution.confirmed(new byte[0]);
            }
            @Override public void close() {
                closes.incrementAndGet();
                throw new IllegalStateException("plugin-secret-must-not-be-logged");
            }
        };
        TestRegistry registry = new TestRegistry().add(AppEffectExecutorFactory.class,
                "failing-close", effectFactory("failing-close", executor));
        Path base = tempDir.resolve("effect-failed-close");
        AppChainSubsystem subsystem = builtInSubsystem(
                "effect-failed-close", base, registry, effectSettings("failing-close"));

        subsystem.start();
        subsystem.stop();
        awaitValue(closes, 1);
        assertThatThrownBy(subsystem::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previous runtime cleanup failed")
                .hasRootCauseInstanceOf(IllegalStateException.class);

        // The failed plugin close poisons this subsystem generation, but it
        // does not retain RocksDB: an independently constructed replacement
        // can own the same chain path.
        AppChainSubsystem replacement = builtInSubsystem(
                "effect-failed-close", base, new TestRegistry(), Map.of());
        try {
            replacement.start();
            assertThat(replacement.health().status()).isEqualTo(SubsystemHealth.Status.UP);
        } finally {
            replacement.stop();
        }
    }

    @Test
    void configuredSinkFactoryFailureIsIsolated() {
        TestRegistry registry = new TestRegistry().add(FinalizedStreamSinkFactory.class,
                "broken", new FinalizedStreamSinkFactory() {
                    @Override public String scheme() { return "broken"; }
                    @Override public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        throw new IllegalStateException("sink boom");
                    }
                });
        AppChainSubsystem subsystem = builtInSubsystem("sink-failure", tempDir.resolve("sink"),
                registry, Map.of("sinks.broken.enabled", "true"));

        try {
            subsystem.start();
            assertThat(subsystem.health().status()).isEqualTo(SubsystemHealth.Status.UP);
            assertThat(subsystem.status())
                    .extractingByKey("sinkActivationFailures")
                    .asString()
                    .contains("broken")
                    .contains("IllegalStateException")
                    .doesNotContain("sink boom");
        } finally {
            subsystem.stop();
        }
    }

    @Test
    void sinkIdentityIsSnapshottedOnceBeforeAtomicPublication() {
        AtomicInteger idCalls = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() {
                if (idCalls.incrementAndGet() == 1) {
                    return "stable-sink-id";
                }
                throw new IllegalStateException("id must not be called again");
            }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { closes.incrementAndGet(); }
        };
        TestRegistry registry = new TestRegistry().add(FinalizedStreamSinkFactory.class,
                "stateful-id", new FinalizedStreamSinkFactory() {
                    @Override public String scheme() { return "stateful-id"; }
                    @Override public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return List.of(sink);
                    }
                });
        AppChainSubsystem subsystem = builtInSubsystem(
                "stateful-sink-id", tempDir.resolve("stateful-sink-id"), registry,
                Map.of("sinks.stateful-id.enabled", "true"));

        try {
            subsystem.start();
            assertThat(subsystem.health().status()).isEqualTo(SubsystemHealth.Status.UP);
            assertThat(subsystem.status().get("sinks").toString()).contains("stable-sink-id");
            assertThat(idCalls).hasValue(1);
        } finally {
            subsystem.stop();
        }
        awaitValue(closes, 1);
    }

    @Test
    void configuredSignerProductFailureIsStartupFatal() {
        TestRegistry registry = new TestRegistry().add(SignerProviderFactory.class,
                "broken-signer", new SignerProviderFactory() {
                    @Override public String scheme() { return "broken-signer"; }
                    @Override public com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider create(
                            String keyReference) {
                        return new com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider() {
                            @Override public byte[] sign(byte[] message) { return new byte[64]; }
                            @Override public byte[] publicKey() {
                                throw new IllegalStateException("signer product boom");
                            }
                        };
                    }
                });
        AppChainConfig config = baseConfig("signer-activation")
                .signingKeyHex("broken-signer:key")
                .build();

        assertThatThrownBy(() -> subsystem(config, tempDir.resolve("signer"), registry))
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("signer provider 'broken-signer'")
                .hasRootCauseMessage("signer product boom");
    }

    @Test
    void signerIdentityIsSnapshottedOnceAndDefensivelyCopied() {
        AtomicInteger publicKeyCalls = new AtomicInteger();
        byte[] expected = KeyGenUtil.getPublicKeyFromPrivateKey(KEY);
        TestRegistry registry = new TestRegistry().add(SignerProviderFactory.class,
                "stateful-signer", new SignerProviderFactory() {
                    @Override public String scheme() { return "stateful-signer"; }
                    @Override public com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider create(
                            String keyReference) {
                        return new com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider() {
                            @Override public byte[] sign(byte[] message) { return new byte[64]; }
                            @Override public byte[] publicKey() {
                                if (publicKeyCalls.incrementAndGet() == 1) {
                                    return expected;
                                }
                                throw new IllegalStateException(
                                        "public key must not be queried again");
                            }
                        };
                    }
                });

        var signer = SignerProviders.resolveFromRegistry(
                "stateful-signer:key", registry, LOG);
        byte[] first = signer.publicKey();
        first[0] ^= 0x7f;

        assertThat(signer.publicKey()).isEqualTo(expected);
        assertThat(signer.publicKeyHex()).isEqualTo(HexUtil.encodeHexString(expected));
        assertThat(publicKeyCalls).hasValue(1);
    }

    @Test
    void configuredMissingConsensusAndPrivilegedProvidersUseStartupFatalBoundary() {
        TestRegistry empty = new TestRegistry();

        assertThatThrownBy(() -> subsystem(
                baseConfig("missing-signer")
                        .signingKeyHex("missing-signer:key")
                        .build(),
                tempDir.resolve("missing-signer"), empty))
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("signer provider 'missing-signer'")
                .hasMessageContaining("is not selected");

        assertThatThrownBy(() -> subsystem(
                baseConfig("missing-machine")
                        .stateMachineId("missing-machine")
                        .build(),
                tempDir.resolve("missing-machine"), empty))
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("state machine 'missing-machine'")
                .hasMessageContaining("is not selected");

        assertThatThrownBy(() -> subsystem(
                baseConfig("missing-sequencer")
                        .pluginSettings(Map.of("sequencer.mode", "missing-sequencer"))
                        .build(),
                tempDir.resolve("missing-sequencer"), empty))
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("sequencer mode 'missing-sequencer'")
                .hasMessageContaining("is not selected");
    }

    @Test
    void configuredProviderProductIdentitiesMustMatchTheirSelection() {
        TestRegistry stateRegistry = new TestRegistry().add(AppStateMachineProvider.class,
                "selected-machine", new AppStateMachineProvider() {
                    @Override public String id() { return "selected-machine"; }
                    @Override public AppStateMachine create() {
                        return new AppStateMachine() {
                            @Override public String id() { return "different-machine"; }
                            @Override public void apply(AppBlock block, AppStateWriter writer) { }
                        };
                    }
                });
        assertThatThrownBy(() -> subsystem(
                baseConfig("mismatched-machine")
                        .stateMachineId("selected-machine")
                        .build(),
                tempDir.resolve("mismatched-machine"), stateRegistry))
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("state machine 'selected-machine'")
                .hasRootCauseMessage(
                        "AppStateMachineProvider 'selected-machine' returned product id 'different-machine'");

        TestRegistry sequencerRegistry = new TestRegistry().add(SequencerModeProvider.class,
                "selected-mode", new SequencerModeProvider() {
                    @Override public String id() { return "selected-mode"; }
                    @Override public SequencerMode create(SequencerContext context) {
                        return new SequencerMode() {
                            @Override public String id() { return "different-mode"; }
                            @Override public void init(SequencerContext ignored) { }
                            @Override public boolean shouldProposeNow(long height) { return false; }
                            @Override public ProposalEligibility checkProposal(
                                    byte[] proposerKey, long height) {
                                return ProposalEligibility.REJECT;
                            }
                        };
                    }
                });
        assertThatThrownBy(() -> subsystem(
                baseConfig("mismatched-mode")
                        .pluginSettings(Map.of("sequencer.mode", "selected-mode"))
                        .build(),
                tempDir.resolve("mismatched-mode"), sequencerRegistry))
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("sequencer mode 'selected-mode'")
                .hasRootCauseMessage(
                        "SequencerModeProvider 'selected-mode' returned product id 'different-mode'");

        TestRegistry observerRegistry = new TestRegistry().add(L1ObserverProvider.class,
                "selected-observer", new L1ObserverProvider() {
                    @Override public String type() { return "selected-observer"; }
                    @Override public L1Observer create(
                            String observerId, Map<String, String> settings) {
                        return new L1Observer() {
                            @Override public String observerId() { return "different-observer"; }
                            @Override public List<L1Observation> observe(
                                    long slot, byte[] blockHash,
                                    com.bloxbean.cardano.yaci.core.model.Block block) {
                                return List.of();
                            }
                        };
                    }
                });
        assertThatThrownBy(() -> L1ObservationService.fromRegistry(
                Map.of("observers.audit.type", "selected-observer"),
                64, observerRegistry, LOG))
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("L1 observer 'selected-observer'")
                .hasRootCauseMessage("L1ObserverProvider 'selected-observer' returned product id "
                        + "'different-observer' for configured observer 'audit'");
    }

    @Test
    void configuredSequencerProductInitFailureIsStartupFatal() {
        TestRegistry registry = new TestRegistry().add(SequencerModeProvider.class,
                "broken-mode", new SequencerModeProvider() {
                    @Override public String id() { return "broken-mode"; }
                    @Override public SequencerMode create(SequencerContext context) {
                        return new SequencerMode() {
                            @Override public String id() { return "broken-mode"; }
                            @Override public void init(SequencerContext ignored) {
                                throw new IllegalStateException("sequencer init boom");
                            }
                            @Override public boolean shouldProposeNow(long height) { return false; }
                            @Override public ProposalEligibility checkProposal(
                                    byte[] proposerKey, long height) {
                                return ProposalEligibility.REJECT;
                            }
                        };
                    }
                });
        AppChainConfig config = baseConfig("sequencer-activation")
                .pluginSettings(Map.of("sequencer.mode", "broken-mode"))
                .build();

        assertThatThrownBy(() -> subsystem(config, tempDir.resolve("sequencer"), registry))
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("sequencer mode 'broken-mode'")
                .hasRootCauseMessage("sequencer init boom");
    }

    @Test
    void configuredStateMachineInitFailureIsFatalAndReleasesLedger() {
        TestRegistry registry = new TestRegistry().add(AppStateMachineProvider.class,
                "broken-machine", new AppStateMachineProvider() {
                    @Override public String id() { return "broken-machine"; }
                    @Override public AppStateMachine create() {
                        return new AppStateMachine() {
                            @Override public String id() { return "broken-machine"; }
                            @Override public void init(
                                    com.bloxbean.cardano.yano.api.appchain.AppStateReader state,
                                    com.bloxbean.cardano.yano.api.appchain.AppChainInfo info) {
                                throw new IllegalStateException("state init boom");
                            }
                            @Override public void apply(AppBlock block, AppStateWriter writer) { }
                        };
                    }
                });
        Path base = tempDir.resolve("state-init");
        AppChainConfig config = baseConfig("state-init-activation")
                .stateMachineId("broken-machine")
                .build();
        AppChainSubsystem failed = new AppChainSubsystem(
                config, 42, new SimpleEventBus(), null,
                base.toString(), null, registry, LOG);

        assertThatThrownBy(failed::start)
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("state machine 'broken-machine'")
                .hasRootCauseMessage("state init boom");

        AppChainSubsystem replacement = subsystem(
                baseConfig("state-init-activation").build(), base, new TestRegistry());
        try {
            replacement.start();
            assertThat(replacement.health().status()).isEqualTo(SubsystemHealth.Status.UP);
        } finally {
            replacement.stop();
        }
    }

    @Test
    void configuredObserverFactoryFailureIsFatalAndReleasesLedger() {
        TestRegistry registry = new TestRegistry().add(L1ObserverProvider.class,
                "broken-observer", new L1ObserverProvider() {
                    @Override public String type() { return "broken-observer"; }
                    @Override public L1Observer create(
                            String observerId, Map<String, String> settings) {
                        throw new IllegalStateException("observer factory boom");
                    }
                });
        Path base = tempDir.resolve("observer-activation");
        AppChainConfig config = baseConfig("observer-activation")
                .l1StabilityDepth(1)
                .pluginSettings(Map.of("observers.audit.type", "broken-observer"))
                .build();
        AppChainSubsystem failed = new AppChainSubsystem(
                config, 42, new SimpleEventBus(), null,
                base.toString(), null, registry, LOG);

        assertThatThrownBy(failed::start)
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("L1 observer 'broken-observer'")
                .hasRootCauseMessage("observer factory boom");

        AppChainSubsystem replacement = subsystem(
                baseConfig("observer-activation").build(), base, new TestRegistry());
        try {
            replacement.start();
            assertThat(replacement.health().status()).isEqualTo(SubsystemHealth.Status.UP);
        } finally {
            replacement.stop();
        }
    }

    @Test
    void fatalSinkProductActivationClosesPartialProductAndRethrowsSameError() {
        AtomicInteger closes = new AtomicInteger();
        FatalPluginError fatal = new FatalPluginError("fatal sink id");
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { throw fatal; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { closes.incrementAndGet(); }
        };
        TestRegistry registry = new TestRegistry().add(FinalizedStreamSinkFactory.class,
                "fatal-id", new FinalizedStreamSinkFactory() {
                    @Override public String scheme() { return "fatal-id"; }
                    @Override public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return List.of(sink);
                    }
                });
        AppChainSubsystem subsystem = builtInSubsystem(
                "sink-fatal-id", tempDir.resolve("sink-fatal-id"), registry,
                Map.of("sinks.fatal-id.enabled", "true"));

        assertThatThrownBy(subsystem::start).isSameAs(fatal);
        assertThat(closes).hasValue(1);
        subsystem.stop();
        assertThat(closes).hasValue(1);
    }

    @Test
    void fatalSinkRollbackCloseWinsOverIsolatedActivationFailure() {
        AtomicInteger closes = new AtomicInteger();
        FatalPluginError fatal = new FatalPluginError("fatal sink rollback close");
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() {
                throw new IllegalStateException("malformed sink identity");
            }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() {
                closes.incrementAndGet();
                throw fatal;
            }
        };
        TestRegistry registry = new TestRegistry().add(FinalizedStreamSinkFactory.class,
                "fatal-close", new FinalizedStreamSinkFactory() {
                    @Override public String scheme() { return "fatal-close"; }
                    @Override public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return List.of(sink);
                    }
                });
        AppChainSubsystem subsystem = builtInSubsystem(
                "sink-fatal-close", tempDir.resolve("sink-fatal-close"), registry,
                Map.of("sinks.fatal-close.enabled", "true"));

        assertThatThrownBy(subsystem::start).isSameAs(fatal);
        assertThat(fatal.getSuppressed())
                .anyMatch(failure -> failure.getMessage().contains("malformed sink identity"));
        assertThat(closes).hasValue(1);
        subsystem.stop();
        assertThat(closes).hasValue(1);
    }

    @Test
    void fatalEffectProductActivationClosesPartialProductAndRethrowsSameError() {
        AtomicInteger closes = new AtomicInteger();
        FatalPluginError fatal = new FatalPluginError("fatal executor id");
        AppEffectExecutor executor = effectExecutor(() -> {
            throw fatal;
        }, closes);
        TestRegistry registry = new TestRegistry().add(AppEffectExecutorFactory.class,
                "fatal-id", effectFactory("fatal-id", executor));
        AppChainSubsystem subsystem = builtInSubsystem(
                "effect-fatal-id", tempDir.resolve("effect-fatal-id"), registry,
                effectSettings("fatal-id"));

        assertThatThrownBy(subsystem::start).isSameAs(fatal);
        assertThat(closes).hasValue(1);
        subsystem.stop();
        assertThat(closes).hasValue(1);
    }

    @Test
    void productIdsRejectControlsAndOversizeBeforePublicationWithReverseCleanup() {
        List<String> sinkCloses = new ArrayList<>();
        FinalizedStreamSink validSink = closingSink("valid-sink", "first", sinkCloses);
        FinalizedStreamSink invalidSink = closingSink("bad\nlabel", "invalid", sinkCloses);
        FinalizedStreamSink sinkTail = closingSink(
                "x".repeat(129), "tail", sinkCloses);
        TestRegistry sinkRegistry = new TestRegistry().add(
                FinalizedStreamSinkFactory.class, "bounded-sink",
                new FinalizedStreamSinkFactory() {
                    @Override public String scheme() { return "bounded-sink"; }
                    @Override public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return List.of(validSink, invalidSink, sinkTail);
                    }
                });
        AppChainSubsystem sinkSubsystem = builtInSubsystem(
                "bounded-sink", tempDir.resolve("bounded-sink"), sinkRegistry,
                Map.of("sinks.bounded-sink.enabled", "true"));
        sinkSubsystem.start();
        assertThat(sinkCloses).containsExactly("tail", "invalid", "first");
        assertThat(sinkSubsystem.health().details().toString())
                .doesNotContain("bad\nlabel");
        sinkSubsystem.stop();

        List<String> executorCloses = new ArrayList<>();
        AppEffectExecutor validExecutor = closingExecutor(
                "valid-executor", "first", executorCloses);
        AppEffectExecutor invalidExecutor = closingExecutor(
                "bad\u001b-label", "invalid", executorCloses);
        AppEffectExecutor executorTail = closingExecutor(
                "x".repeat(129), "tail", executorCloses);
        TestRegistry effectRegistry = new TestRegistry().add(
                AppEffectExecutorFactory.class, "bounded-effect",
                effectFactory("bounded-effect",
                        validExecutor, invalidExecutor, executorTail));
        AppChainSubsystem effectSubsystem = builtInSubsystem(
                "bounded-effect", tempDir.resolve("bounded-effect"), effectRegistry,
                effectSettings("bounded-effect"));

        assertThatThrownBy(effectSubsystem::start)
                .isInstanceOf(com.bloxbean.cardano.yano.api.plugin.PluginActivationException.class)
                .hasMessageNotContaining("bad\u001b-label");
        assertThat(executorCloses).containsExactly("tail", "invalid", "first");
        effectSubsystem.stop();
    }

    @Test
    void fatalEffectRuntimeActivationClosesOwnedProductAndRethrowsSameError() {
        AtomicInteger closes = new AtomicInteger();
        FatalPluginError fatal = new FatalPluginError("fatal runtime rollback close");
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "runtime-fatal"; }
            @Override public boolean supports(String effectType) { return true; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                return EffectExecution.confirmed(new byte[0]);
            }
            @Override public void close() {
                closes.incrementAndGet();
                throw fatal;
            }
        };
        TestRegistry registry = new TestRegistry().add(AppEffectExecutorFactory.class,
                "runtime-fatal", effectFactory("runtime-fatal", executor));
        Map<String, String> settings = new LinkedHashMap<>(effectSettings("runtime-fatal"));
        settings.put("effects.executor.identity", "x".repeat(513));
        AppChainSubsystem subsystem = builtInSubsystem(
                "effect-runtime-fatal", tempDir.resolve("effect-runtime-fatal"), registry,
                Map.copyOf(settings));

        assertThatThrownBy(subsystem::start).isSameAs(fatal);
        assertThat(closes).hasValue(1);
        subsystem.stop();
        assertThat(closes).hasValue(1);
    }

    @Test
    void partiallyCreatedSinkFactoryIsRolledBackExactlyOnce() {
        AtomicInteger firstCloses = new AtomicInteger();
        AtomicInteger tailCloses = new AtomicInteger();
        FinalizedStreamSink first = new FinalizedStreamSink() {
            @Override public String id() { return "partial-first"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { firstCloses.incrementAndGet(); }
        };
        FinalizedStreamSink tail = new FinalizedStreamSink() {
            @Override public String id() { return "partial-tail"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { tailCloses.incrementAndGet(); }
        };
        TestRegistry registry = new TestRegistry().add(FinalizedStreamSinkFactory.class,
                "partial", new FinalizedStreamSinkFactory() {
                    @Override public String scheme() { return "partial"; }
                    @Override public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return Arrays.asList(first, null, tail);
                    }
                });
        AppChainSubsystem subsystem = builtInSubsystem("sink-partial",
                tempDir.resolve("sink-partial"), registry,
                Map.of("sinks.partial.enabled", "true"));

        subsystem.start();
        assertThat(firstCloses).hasValue(1);
        assertThat(tailCloses).hasValue(1);
        subsystem.stop();
        assertThat(firstCloses).hasValue(1);
        assertThat(tailCloses).hasValue(1);
    }

    @Test
    void duplicateSinkInstanceIsRejectedAndClosedExactlyOnce() {
        AtomicInteger duplicateCloses = new AtomicInteger();
        AtomicInteger tailCloses = new AtomicInteger();
        FinalizedStreamSink duplicate = new FinalizedStreamSink() {
            @Override public String id() { return "duplicate"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { duplicateCloses.incrementAndGet(); }
        };
        FinalizedStreamSink tail = new FinalizedStreamSink() {
            @Override public String id() { return "duplicate-tail"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { tailCloses.incrementAndGet(); }
        };
        TestRegistry registry = new TestRegistry().add(FinalizedStreamSinkFactory.class,
                "duplicate", new FinalizedStreamSinkFactory() {
                    @Override public String scheme() { return "duplicate"; }
                    @Override public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return List.of(duplicate, duplicate, tail);
                    }
                });
        AppChainSubsystem subsystem = builtInSubsystem("sink-duplicate",
                tempDir.resolve("sink-duplicate"), registry,
                Map.of("sinks.duplicate.enabled", "true"));

        subsystem.start();
        assertThat(duplicateCloses).hasValue(1);
        assertThat(tailCloses).hasValue(1);
        subsystem.stop();
        assertThat(duplicateCloses).hasValue(1);
        assertThat(tailCloses).hasValue(1);
    }

    @Test
    void laterSinkCursorPreparationFailureLeavesNoInactiveCursor() {
        AtomicInteger closes = new AtomicInteger();
        String firstId = "cursor-first";
        String failingId = "cursor-failing-tail";
        FinalizedStreamSink first = new FinalizedStreamSink() {
            @Override public String id() { return firstId; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { closes.incrementAndGet(); }
        };
        FinalizedStreamSink failingTail = new FinalizedStreamSink() {
            @Override public String id() { return failingId; }
            @Override public String legacyCursorKey() {
                throw new IllegalStateException("legacy cursor failure");
            }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { closes.incrementAndGet(); }
        };
        TestRegistry registry = new TestRegistry().add(FinalizedStreamSinkFactory.class,
                "cursor-failure", new FinalizedStreamSinkFactory() {
                    @Override public String scheme() { return "cursor-failure"; }
                    @Override public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return List.of(first, failingTail);
                    }
                });
        String chainId = "sink-cursor-failure";
        Path base = tempDir.resolve("sink-cursor-failure");
        AppChainSubsystem subsystem = builtInSubsystem(chainId, base, registry,
                Map.of("sinks.cursor-failure.enabled", "true"));

        subsystem.start();
        assertThat(subsystem.health().status()).isEqualTo(SubsystemHealth.Status.UP);
        assertThat(closes).hasValue(2);
        subsystem.stop();

        try (AppLedgerStore reopened = new AppLedgerStore(
                base.resolve(chainId).toString(), LOG)) {
            assertThat(reopened.metaLong(SinkRunner.cursorKeyFor(firstId), -1L))
                    .isEqualTo(-1L);
            assertThat(reopened.metaLong(SinkRunner.cursorKeyFor(failingId), -1L))
                    .isEqualTo(-1L);
        }
        assertThat(closes).hasValue(2);
    }

    @Test
    void failedSinkCursorPreparationDoesNotPoisonLaterSchemeWithSameId() {
        AtomicInteger failingCloses = new AtomicInteger();
        AtomicInteger healthyCloses = new AtomicInteger();
        String sharedId = "shared-after-failure";
        FinalizedStreamSink failing = new FinalizedStreamSink() {
            @Override public String id() { return sharedId; }
            @Override public String legacyCursorKey() {
                throw new IllegalStateException("cursor preparation failed");
            }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { failingCloses.incrementAndGet(); }
        };
        FinalizedStreamSink healthy = new FinalizedStreamSink() {
            @Override public String id() { return sharedId; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { healthyCloses.incrementAndGet(); }
        };
        TestRegistry registry = new TestRegistry()
                .add(FinalizedStreamSinkFactory.class, "a-failing",
                        new FinalizedStreamSinkFactory() {
                            @Override public String scheme() { return "a-failing"; }
                            @Override public List<FinalizedStreamSink> create(
                                    String chainId, Map<String, String> config) {
                                return List.of(failing);
                            }
                        })
                .add(FinalizedStreamSinkFactory.class, "b-healthy",
                        new FinalizedStreamSinkFactory() {
                            @Override public String scheme() { return "b-healthy"; }
                            @Override public List<FinalizedStreamSink> create(
                                    String chainId, Map<String, String> config) {
                                return List.of(healthy);
                            }
                        });
        AppChainSubsystem subsystem = builtInSubsystem(
                "sink-id-recovery", tempDir.resolve("sink-id-recovery"), registry,
                Map.of("sinks.a-failing.enabled", "true",
                        "sinks.b-healthy.enabled", "true"));

        try {
            subsystem.start();
            assertThat(subsystem.status().get("sinks").toString()).contains(sharedId);
            assertThat(subsystem.status().get("sinkActivationFailures").toString())
                    .contains("a-failing")
                    .doesNotContain("b-healthy");
            assertThat(failingCloses).hasValue(1);
            assertThat(healthyCloses).hasValue(0);
        } finally {
            subsystem.stop();
        }
        assertThat(failingCloses).hasValue(1);
        awaitValue(healthyCloses, 1);
    }

    @Test
    void partiallyCreatedEffectFactoryClosesFreshTailExactlyOnce() {
        AtomicInteger firstCloses = new AtomicInteger();
        AtomicInteger tailCloses = new AtomicInteger();
        AppEffectExecutor first = effectExecutor(() -> "partial-first", firstCloses);
        AppEffectExecutor tail = effectExecutor(() -> "partial-tail", tailCloses);
        TestRegistry registry = new TestRegistry().add(AppEffectExecutorFactory.class,
                "partial", new AppEffectExecutorFactory() {
                    @Override public String scheme() { return "partial"; }
                    @Override public List<AppEffectExecutor> create(
                            String chainId, Map<String, String> config) {
                        return Arrays.asList(first, null, tail);
                    }
                });
        AppChainSubsystem subsystem = builtInSubsystem("effect-partial",
                tempDir.resolve("effect-partial"), registry, effectSettings("partial"));

        assertThatThrownBy(subsystem::start)
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage(
                        "AppEffectExecutorFactory.create returned a null executor");
        assertThat(firstCloses).hasValue(1);
        assertThat(tailCloses).hasValue(1);
        subsystem.stop();
        assertThat(firstCloses).hasValue(1);
        assertThat(tailCloses).hasValue(1);
    }

    @Test
    void duplicateEffectInstanceClosesFreshTailExactlyOnce() {
        AtomicInteger duplicateCloses = new AtomicInteger();
        AtomicInteger tailCloses = new AtomicInteger();
        AppEffectExecutor duplicate = effectExecutor(() -> "duplicate", duplicateCloses);
        AppEffectExecutor tail = effectExecutor(() -> "duplicate-tail", tailCloses);
        TestRegistry registry = new TestRegistry().add(AppEffectExecutorFactory.class,
                "duplicate", new AppEffectExecutorFactory() {
                    @Override public String scheme() { return "duplicate"; }
                    @Override public List<AppEffectExecutor> create(
                            String chainId, Map<String, String> config) {
                        return List.of(duplicate, duplicate, tail);
                    }
                });
        AppChainSubsystem subsystem = builtInSubsystem("effect-duplicate",
                tempDir.resolve("effect-duplicate"), registry, effectSettings("duplicate"));

        assertThatThrownBy(subsystem::start)
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage("AppEffectExecutorFactory 'duplicate' returned the same "
                        + "executor instance more than once");
        assertThat(duplicateCloses).hasValue(1);
        assertThat(tailCloses).hasValue(1);
        subsystem.stop();
        assertThat(duplicateCloses).hasValue(1);
        assertThat(tailCloses).hasValue(1);
    }

    @Test
    void duplicateEffectTypeOwnersFailBeforePublicationAndCloseEveryProduct() {
        List<String> closes = new ArrayList<>();
        AppEffectExecutor first = declarativeClosingExecutor(
                "alpha-executor", "shared.action", "alpha", closes);
        AppEffectExecutor second = declarativeClosingExecutor(
                "beta-executor", "shared.action", "beta", closes);
        TestRegistry registry = new TestRegistry()
                .add(AppEffectExecutorFactory.class, "alpha",
                        effectFactory("alpha", first))
                .add(AppEffectExecutorFactory.class, "beta",
                        effectFactory("beta", second));
        AppChainSubsystem subsystem = builtInSubsystem(
                "effect-owner-conflict", tempDir.resolve("effect-owner-conflict"), registry,
                Map.of(
                        "effects.enabled", "true",
                        "effects.executor.enabled", "true",
                        "effects.executor.identity", "registry-test",
                        "effects.executors.alpha.enabled", "true",
                        "effects.executors.beta.enabled", "true"));

        assertThatThrownBy(subsystem::start)
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage("Duplicate declarative effect ownership: "
                        + "type='shared.action' [bundle=legacy, scheme=alpha, "
                        + "executor=alpha-executor; bundle=legacy, scheme=beta, "
                        + "executor=beta-executor]");
        assertThat(closes).containsExactly("beta", "alpha");
        subsystem.stop();
        assertThat(closes).containsExactly("beta", "alpha");
    }

    @Test
    void assertionAndOrdinaryRollbackFailuresPreserveStrongestOutcomeWithoutLeakingLedger() {
        AtomicInteger closes = new AtomicInteger();
        TestRegistry registry = new TestRegistry().add(FinalizedStreamSinkFactory.class,
                "fatal-close", new FinalizedStreamSinkFactory() {
                    @Override public String scheme() { return "fatal-close"; }
                    @Override public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return List.of(
                                new FinalizedStreamSink() {
                                    @Override public String id() { return "asserting-close"; }
                                    @Override public boolean deliver(AppBlock block) { return true; }
                                    @Override public void close() {
                                        closes.incrementAndGet();
                                        throw new AssertionError("asserting sink close");
                                    }
                                },
                                new FinalizedStreamSink() {
                                    @Override public String id() { return "ordinary-close"; }
                                    @Override public boolean deliver(AppBlock block) { return true; }
                                    @Override public void close() {
                                        closes.incrementAndGet();
                                        throw new IllegalStateException("ordinary sink close");
                                    }
                                });
                    }
                });
        Path base = tempDir.resolve("invalid-effect");
        AppChainSubsystem failed = builtInSubsystem("invalid-effect", base, registry, Map.of(
                "sinks.fatal-close.enabled", "true",
                "effects.enabled", "true",
                "effects.executor.enabled", "true",
                "effects.executor.tick-ms", "not-a-number"));

        Throwable failure = catchThrowable(failed::start);
        assertThat(failure)
                .isInstanceOf(AssertionError.class)
                .hasMessage("asserting sink close");
        assertThat(failure.getSuppressed())
                .extracting(Throwable::getMessage)
                .anyMatch(message -> message.contains("Invalid effects.executor.* settings"))
                .contains("ordinary sink close");
        assertThat(closes).hasValue(2);
        assertThatThrownBy(failed::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previous runtime cleanup failed")
                .hasRootCauseInstanceOf(AssertionError.class);

        AppChainSubsystem replacement = builtInSubsystem(
                "invalid-effect", base, registry, Map.of());
        try {
            replacement.start();
            assertThat(replacement.health().status()).isEqualTo(SubsystemHealth.Status.UP);
        } finally {
            replacement.stop();
        }
    }

    @Test
    void ordinarySinkCloseFailureIsPreservedWithoutLeakingLedger() {
        TestRegistry registry = new TestRegistry().add(FinalizedStreamSinkFactory.class,
                "ordinary-close", new FinalizedStreamSinkFactory() {
                    @Override public String scheme() { return "ordinary-close"; }
                    @Override public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return List.of(new FinalizedStreamSink() {
                            @Override public String id() { return "ordinary-close"; }
                            @Override public boolean deliver(AppBlock block) { return true; }
                            @Override public void close() {
                                throw new IllegalStateException("ordinary sink close");
                            }
                        });
                    }
                });
        Path base = tempDir.resolve("ordinary-close");
        AppChainSubsystem failed = builtInSubsystem("ordinary-close", base, registry, Map.of(
                "sinks.ordinary-close.enabled", "true",
                "effects.enabled", "true",
                "effects.executor.enabled", "true",
                "effects.executor.tick-ms", "not-a-number"));

        Throwable failure = catchThrowable(failed::start);
        assertThat(failure)
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("Invalid effects.executor.* settings");
        assertThat(failure.getSuppressed())
                .anyMatch(suppressed -> suppressed.getMessage().contains("ordinary sink close"));

        AppChainSubsystem replacement = builtInSubsystem(
                "ordinary-close", base, new TestRegistry(), Map.of());
        try {
            replacement.start();
            assertThat(replacement.health().status()).isEqualTo(SubsystemHealth.Status.UP);
        } finally {
            replacement.stop();
        }
    }

    private AppChainSubsystem subsystem(String chainId, Path base,
                                        PluginProviderRegistry registry,
                                        Map<String, String> settings) {
        AppChainConfig config = AppChainConfig.builder(chainId)
                .signingKeyHex("registry.signer:key")
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1)
                .stateMachineId("Registry.Machine:V1")
                .l1StabilityDepth(1)
                .pluginSettings(settings)
                .build();
        return new AppChainSubsystem(config, 42, new SimpleEventBus(), null,
                base.toString(), null, registry, LOG);
    }

    private AppChainSubsystem subsystem(
            AppChainConfig config,
            Path base,
            PluginProviderRegistry registry
    ) {
        return new AppChainSubsystem(config, 42, null, null,
                base.toString(), null, registry, LOG);
    }

    private static AppChainConfig.Builder baseConfig(String chainId) {
        return AppChainConfig.builder(chainId)
                .signingKeyHex(KEY_HEX)
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1);
    }

    private AppChainSubsystem builtInSubsystem(String chainId, Path base,
                                               PluginProviderRegistry registry,
                                               Map<String, String> settings) {
        AppChainConfig config = AppChainConfig.builder(chainId)
                .signingKeyHex(KEY_HEX)
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1)
                .pluginSettings(settings)
                .build();
        return new AppChainSubsystem(config, 42, null, null,
                base.toString(), null, registry, LOG);
    }

    private static Map<String, String> configuredSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("sequencer.mode", "Registry-Mode");
        settings.put("observers.audit.type", "Registry.Observer:V1");
        settings.put("sinks.registry+sink.enabled", "true");
        settings.put("effects.enabled", "true");
        settings.put("effects.executor.enabled", "true");
        settings.put("effects.executor.tick-ms", "60000");
        settings.put("effects.executor.identity", "registry-test");
        settings.put("effects.executors.registry:effect.enabled", "true");
        return Map.copyOf(settings);
    }

    private static Map<String, String> effectSettings(String scheme) {
        return Map.of(
                "effects.enabled", "true",
                "effects.executor.enabled", "true",
                "effects.executor.identity", "registry-test",
                "effects.executors." + scheme + ".enabled", "true");
    }

    private static AppEffectExecutorFactory effectFactory(
            String scheme,
            AppEffectExecutor... executors
    ) {
        return new AppEffectExecutorFactory() {
            @Override public String scheme() { return scheme; }
            @Override public List<AppEffectExecutor> create(
                    String chainId, Map<String, String> config) {
                return List.of(executors);
            }
        };
    }

    private static FinalizedStreamSink closingSink(
            String id,
            String closeLabel,
            List<String> closes
    ) {
        return new FinalizedStreamSink() {
            @Override public String id() { return id; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { closes.add(closeLabel); }
        };
    }

    private static AppEffectExecutor closingExecutor(
            String id,
            String closeLabel,
            List<String> closes
    ) {
        return new AppEffectExecutor() {
            @Override public String id() { return id; }
            @Override public boolean supports(String effectType) { return true; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                return EffectExecution.confirmed(new byte[0]);
            }
            @Override public void close() { closes.add(closeLabel); }
        };
    }

    private static AppEffectExecutor declarativeClosingExecutor(
            String id,
            String type,
            String closeLabel,
            List<String> closes
    ) {
        return new AppEffectExecutor() {
            @Override public String id() { return id; }
            @Override public Set<String> effectTypes() { return Set.of(type); }
            @Override public boolean supports(String effectType) {
                throw new AssertionError("declarative owner must not use supports");
            }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                return EffectExecution.confirmed(new byte[0]);
            }
            @Override public void close() { closes.add(closeLabel); }
        };
    }

    private static AppEffectExecutor effectExecutor(
            java.util.function.Supplier<String> id,
            AtomicInteger closes
    ) {
        return new AppEffectExecutor() {
            @Override public String id() { return id.get(); }
            @Override public boolean supports(String effectType) { return true; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                return EffectExecution.confirmed(new byte[0]);
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
    }

    private static void startEventually(AppChainSubsystem subsystem) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        IllegalStateException lastDraining = null;
        while (System.nanoTime() < deadline) {
            try {
                subsystem.start();
                return;
            } catch (IllegalStateException failure) {
                if (!failure.getMessage().contains("still draining")) {
                    throw failure;
                }
                lastDraining = failure;
                Thread.sleep(10);
            }
        }
        throw new AssertionError("effect cleanup did not drain before restart deadline",
                lastDraining);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ProviderFixtures {
        final AtomicInteger signers = new AtomicInteger();
        final AtomicInteger stateMachines = new AtomicInteger();
        final AtomicInteger sequencerModes = new AtomicInteger();
        final AtomicInteger observers = new AtomicInteger();
        final AtomicInteger sinks = new AtomicInteger();
        final AtomicInteger executors = new AtomicInteger();
        final AtomicInteger closedSinks = new AtomicInteger();
        final AtomicInteger closedExecutors = new AtomicInteger();

        TestRegistry registry() {
            return new TestRegistry()
                    .add(SignerProviderFactory.class, "registry.signer", signerFactory())
                    .add(AppStateMachineProvider.class, "Registry.Machine:V1", stateMachineProvider())
                    .add(SequencerModeProvider.class, "Registry-Mode", sequencerModeProvider())
                    .add(L1ObserverProvider.class, "Registry.Observer:V1", observerProvider())
                    .add(FinalizedStreamSinkFactory.class, "registry+sink", sinkFactory())
                    .add(AppEffectExecutorFactory.class, "registry:effect", effectFactory());
        }

        private SignerProviderFactory signerFactory() {
            return new SignerProviderFactory() {
                @Override public String scheme() { return "registry.signer"; }
                @Override public com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider create(
                        String keyReference) {
                    signers.incrementAndGet();
                    return new AppMessageSigner(KEY_HEX);
                }
            };
        }

        private AppStateMachineProvider stateMachineProvider() {
            return new AppStateMachineProvider() {
                @Override public String id() { return "Registry.Machine:V1"; }
                @Override public AppStateMachine create() {
                    stateMachines.incrementAndGet();
                    return new AppStateMachine() {
                        @Override public String id() { return "Registry.Machine:V1"; }
                        @Override public void apply(AppBlock block, AppStateWriter writer) { }
                    };
                }
            };
        }

        private SequencerModeProvider sequencerModeProvider() {
            return new SequencerModeProvider() {
                @Override public String id() { return "Registry-Mode"; }
                @Override public SequencerMode create(SequencerContext context) {
                    sequencerModes.incrementAndGet();
                    return new SequencerMode() {
                        @Override public String id() { return "Registry-Mode"; }
                        @Override public void init(SequencerContext ignored) { }
                        @Override public boolean shouldProposeNow(long height) { return true; }
                        @Override public ProposalEligibility checkProposal(
                                byte[] proposerKey, long height) {
                            return ProposalEligibility.ACCEPT;
                        }
                    };
                }
            };
        }

        private L1ObserverProvider observerProvider() {
            return new L1ObserverProvider() {
                @Override public String type() { return "Registry.Observer:V1"; }
                @Override public L1Observer create(String observerId, Map<String, String> settings) {
                    observers.incrementAndGet();
                    return new L1Observer() {
                        @Override public String observerId() { return observerId; }
                        @Override public List<L1Observation> observe(
                                long slot, byte[] blockHash,
                                com.bloxbean.cardano.yaci.core.model.Block block) {
                            return List.of();
                        }
                    };
                }
            };
        }

        private FinalizedStreamSinkFactory sinkFactory() {
            return new FinalizedStreamSinkFactory() {
                @Override public String scheme() { return "registry+sink"; }
                @Override public List<FinalizedStreamSink> create(
                        String chainId, Map<String, String> config) {
                    sinks.incrementAndGet();
                    return List.of(new FinalizedStreamSink() {
                        @Override public String id() { return "sink-" + chainId; }
                        @Override public boolean deliver(AppBlock block) { return true; }
                        @Override public void close() { closedSinks.incrementAndGet(); }
                    });
                }
            };
        }

        private AppEffectExecutorFactory effectFactory() {
            return new AppEffectExecutorFactory() {
                @Override public String scheme() { return "registry:effect"; }
                @Override public List<AppEffectExecutor> create(
                        String chainId, Map<String, String> config) {
                    executors.incrementAndGet();
                    return List.of(new AppEffectExecutor() {
                        @Override public String id() { return "effect-" + chainId; }
                        @Override public boolean supports(String effectType) { return true; }
                        @Override public EffectExecution execute(
                                EffectExecutionContext context, PendingEffect effect) {
                            return EffectExecution.confirmed(new byte[0]);
                        }
                        @Override public void close() { closedExecutors.incrementAndGet(); }
                    });
                }
            };
        }
    }

    private static final class TestRegistry implements PluginProviderRegistry {
        private final Map<Class<?>, Map<String, Object>> providers = new LinkedHashMap<>();

        <P> TestRegistry add(Class<P> type, String name, P provider) {
            providers.computeIfAbsent(type, ignored -> new TreeMap<>()).put(name, provider);
            return this;
        }

        @Override
        public <P> Optional<P> find(Class<P> providerType, String selector) {
            Object provider = providers.getOrDefault(providerType, Map.of()).get(selector);
            return provider == null ? Optional.empty() : Optional.of(providerType.cast(provider));
        }

        @Override
        public <P> List<String> names(Class<P> providerType) {
            return List.copyOf(providers.getOrDefault(providerType, Map.of()).keySet());
        }
    }

    private static final class FatalPluginError extends Error {
        private FatalPluginError(String message) {
            super(message);
        }
    }

    /** A normal stop is bounded; an already-admitted callback may close just after it returns. */
    private static void awaitValue(AtomicInteger actual, int expected) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (actual.get() < expected) {
                Thread.sleep(10);
            }
            assertThat(actual).hasValue(expected);
        });
    }

    private static byte[] seed(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }
}
