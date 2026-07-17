package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventContext;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationalSnapshot;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.api.plugin.PluginCapability;
import com.bloxbean.cardano.yano.api.plugin.PluginContext;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.api.plugin.UtxoFilterContext;
import com.bloxbean.cardano.yano.catalog.BundleDependency;
import com.bloxbean.cardano.yano.catalog.BundleManifest;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import com.bloxbean.cardano.yano.catalog.SemVersion;
import com.bloxbean.cardano.yano.catalog.YanoApiRange;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class PluginTcclBoundaryTest {

    @Test
    void productReservationsUseWeakIdentityAndPruneUnreachableRuntimeProducts()
            throws Exception {
        PluginSpiFacades.ProductReservations reservations =
                new PluginSpiFacades.ProductReservations();
        List<WeakReference<Object>> products = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            products.add(reserveEphemeralProduct(reservations));
        }
        assertThat(reservations.reservationCountForTesting()).isEqualTo(256);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (products.stream().anyMatch(reference -> reference.get() != null)
                && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(10);
        }

        assertThat(products).allSatisfy(reference -> assertThat(reference.get()).isNull());
        assertThat(reservations.reservationCountForTesting()).isZero();
    }

    @Test
    void scopeRestoresPreviousTcclEvenWhenPluginReplacesItAndThrowsFatalError() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        try {
            Thread.currentThread().setContextClassLoader(caller);
            assertThatThrownBy(() -> PluginThreadContext.run(plugin, () -> {
                assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(plugin);
                Thread.currentThread().setContextClassLoader(rogue);
                throw new AssertionError("expected fatal failure");
            })).isInstanceOf(AssertionError.class).hasMessage("expected fatal failure");
            assertCaller(caller);

            Thread.currentThread().setContextClassLoader(plugin);
            PluginThreadContext.run(plugin,
                    () -> Thread.currentThread().setContextClassLoader(rogue));
            assertCaller(plugin);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void providerFactoriesAndEveryReturnedSpiCallbackUsePluginTcclAndRestoreCaller()
            throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        AssertingStateMachineProvider machines = new AssertingStateMachineProvider(probe);
        AssertingSequencerProvider sequencers = new AssertingSequencerProvider(probe);
        AssertingObserverProvider observers = new AssertingObserverProvider(probe);
        AssertingSignerFactory signers = new AssertingSignerFactory(probe);
        AssertingExecutorFactory executors = new AssertingExecutorFactory(probe);
        AssertingSinkFactory sinks = new AssertingSinkFactory(probe);
        List<CatalogPluginProviderRegistry.Entry> entries = List.of(
                entry("machine-bundle", ContributionKind.APP_STATE_MACHINE,
                        "machine", machines, probe),
                entry("sequencer-bundle", ContributionKind.SEQUENCER_MODE,
                        "sequencer", sequencers, probe),
                entry("observer-bundle", ContributionKind.L1_OBSERVER,
                        "observer", observers, probe),
                entry("signer-bundle", ContributionKind.SIGNER_PROVIDER,
                        "signer", signers, probe),
                entry("executor-bundle", ContributionKind.EFFECT_EXECUTOR,
                        "executor", executors, probe),
                entry("sink-bundle", ContributionKind.FINALIZED_SINK,
                        "sink", sinks, probe));
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                entries, entries.stream().map(CatalogPluginProviderRegistry.Entry::bundleId).toList(),
                List.of(), plugin);

        Thread.currentThread().setContextClassLoader(caller);
        try {
            AppStateMachineProvider machineProvider = registry.require(
                    AppStateMachineProvider.class, "machine");
            assertCaller(caller);
            assertThat(machineProvider.id()).isEqualTo("machine");
            AppStateMachine firstMachine = machineProvider.create();
            AppStateMachine secondMachine = machineProvider.create(null);
            assertThat(secondMachine).isNotSameAs(firstMachine);
            firstMachine.id();
            firstMachine.init(null, null);
            firstMachine.validate(null);
            firstMachine.apply(null, null);
            firstMachine.apply(null, null, null);
            firstMachine.onEffectResult(null, null, null);
            firstMachine.onEffectResult(null, null, null, null);
            assertThatThrownBy(() -> firstMachine.query("failure", new byte[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("expected query failure");
            assertThatThrownBy(() -> firstMachine.query(
                    "failure", new byte[0], (AppQueryContext) null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("expected query failure");
            assertCaller(caller);

            SequencerModeProvider sequencerProvider = registry.require(
                    SequencerModeProvider.class, "sequencer");
            sequencerProvider.id();
            SequencerMode sequencer = sequencerProvider.create(null);
            sequencer.id();
            sequencer.init(null);
            sequencer.shouldProposeNow(1);
            sequencer.checkProposal(new byte[0], 1);
            Map<String, Object> sequencerStatus = sequencer.status();
            assertThat(sequencerStatus).containsEntry("mode", "ready");
            assertThatThrownBy(() -> sequencerStatus.put("mutate", true))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertCaller(caller);

            L1ObserverProvider observerProvider = registry.require(
                    L1ObserverProvider.class, "observer");
            observerProvider.type();
            L1Observer observer = observerProvider.create("instance", Map.of());
            observer.observerId();
            List<L1Observation> observations = observer.observe(1, new byte[32], null);
            assertThat(observations).singleElement()
                    .satisfies(observation -> assertThat(observation.observerId())
                            .isEqualTo("instance"));
            assertThatThrownBy(() -> observations.add(observations.getFirst()))
                    .isInstanceOf(UnsupportedOperationException.class);
            Map<String, Object> observerStatus = observer.status();
            assertThat(observerStatus).containsEntry("observer", "ready");
            assertThatThrownBy(() -> observerStatus.put("mutate", true))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertCaller(caller);

            SignerProviderFactory signerFactory = registry.require(
                    SignerProviderFactory.class, "signer");
            signerFactory.scheme();
            SignerProvider signer = signerFactory.create("key");
            signer.sign(new byte[0]);
            signer.publicKey();
            signer.publicKeyHex();
            assertCaller(caller);

            AppEffectExecutorFactory executorFactory = registry.require(
                    AppEffectExecutorFactory.class, "executor");
            executorFactory.scheme();
            List<AppEffectExecutor> executorProducts = executorFactory.create("chain", Map.of());
            assertThat(executorProducts).hasSize(2);
            assertThat(executorProducts.get(1)).isSameAs(executorProducts.getFirst());
            AppEffectExecutor executor = executorProducts.getFirst();
            executor.id();
            assertThat(executor.effectTypes()).containsExactly("effect");
            assertThat(executor.operationalSnapshot().readiness())
                    .isEqualTo(EffectExecutorOperationalSnapshot.Readiness.READY);
            executor.supports("effect");
            executor.execute(null, null);
            executor.close();
            assertCaller(caller);

            FinalizedStreamSinkFactory sinkFactory = registry.require(
                    FinalizedStreamSinkFactory.class, "sink");
            sinkFactory.scheme();
            List<FinalizedStreamSink> sinkProducts = sinkFactory.create("chain", Map.of());
            assertThat(sinkProducts).hasSize(2);
            assertThat(sinkProducts.get(1)).isSameAs(sinkProducts.getFirst());
            FinalizedStreamSink sink = sinkProducts.getFirst();
            sink.id();
            sink.legacyCursorKey();
            assertThatThrownBy(() -> sink.deliver(null))
                    .isInstanceOf(IOException.class)
                    .hasMessage("expected delivery failure");
            assertCaller(caller);
            sink.close();

            registry.close();
            assertCaller(caller);
            assertThat(sinks.closeCalls).hasValue(1);
            assertThat(probe.callbacks.get()).isGreaterThan(35);
        } finally {
            registry.close();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void providerConstructionSelectorAndFailedCloseUseIndependentTcclScopes() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        AssertingStateMachine machine = new AssertingStateMachine(probe);
        AppStateMachineProvider valid = new AppStateMachineProvider() {
            @Override public String id() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return "valid";
            }
            @Override public AppStateMachine create() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return machine;
            }
        };
        CatalogPluginProviderRegistry.Entry validEntry =
                new CatalogPluginProviderRegistry.Entry(
                        "valid-bundle", ContributionKind.APP_STATE_MACHINE,
                        "valid", valid.getClass().getName(), null, () -> {
                    probe.check();
                    Thread.currentThread().setContextClassLoader(rogue);
                    return valid;
                });
        CatalogPluginProviderRegistry validRegistry =
                new CatalogPluginProviderRegistry(
                        List.of(validEntry), List.of("valid-bundle"), List.of(), plugin);

        AtomicInteger failedCloses = new AtomicInteger();
        class InvalidProvider implements AppStateMachineProvider, AutoCloseable {
            @Override public String id() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return "wrong";
            }
            @Override public AppStateMachine create() { return machine; }
            @Override public void close() {
                probe.check();
                failedCloses.incrementAndGet();
                Thread.currentThread().setContextClassLoader(rogue);
            }
        }
        InvalidProvider invalid = new InvalidProvider();
        CatalogPluginProviderRegistry.Entry invalidEntry =
                new CatalogPluginProviderRegistry.Entry(
                        "invalid-bundle", ContributionKind.APP_STATE_MACHINE,
                        "expected", invalid.getClass().getName(), null, () -> {
                    probe.check();
                    Thread.currentThread().setContextClassLoader(rogue);
                    return invalid;
                });
        CatalogPluginProviderRegistry invalidRegistry =
                new CatalogPluginProviderRegistry(
                        List.of(invalidEntry), List.of("invalid-bundle"), List.of(), plugin);

        Thread.currentThread().setContextClassLoader(caller);
        try {
            AppStateMachineProvider exposed = validRegistry.require(
                    AppStateMachineProvider.class, "valid");
            assertCaller(caller);
            assertThat(exposed.id()).isEqualTo("valid");
            assertThat(exposed.create().id()).isEqualTo("machine");
            assertCaller(caller);

            assertThatThrownBy(() -> invalidRegistry.require(
                    AppStateMachineProvider.class, "expected"))
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageContaining("construct provider");
            assertThat(failedCloses).hasValue(1);
            assertCaller(caller);
            invalidRegistry.close();
            assertThat(failedCloses).hasValue(1);
        } finally {
            validRegistry.close();
            invalidRegistry.close();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void observerResultsOwnConsensusCriticalByteArraysAfterCallback() {
        byte[] txHash = new byte[32];
        byte[] blockHash = new byte[32];
        byte[] claim = new byte[]{1, 2, 3};
        L1Observation retained = new L1Observation(
                "retained", txHash, 7, blockHash, claim);
        L1Observer rawObserver = new L1Observer() {
            @Override public String observerId() { return "retained"; }
            @Override public List<L1Observation> observe(
                    long slot, byte[] hash, Block block) {
                return List.of(retained);
            }
        };
        L1ObserverProvider rawProvider = new L1ObserverProvider() {
            @Override public String type() { return "retained"; }
            @Override public L1Observer create(String id, Map<String, String> settings) {
                return rawObserver;
            }
        };
        L1ObserverProvider provider = (L1ObserverProvider) PluginSpiFacades.provider(
                ContributionKind.L1_OBSERVER, rawProvider,
                getClass().getClassLoader(), "retained-bundle", "retained",
                rawProvider.getClass().getName());

        L1Observation snapshot = provider.create("retained", Map.of())
                .observe(7, new byte[32], null).getFirst();
        txHash[0] = 9;
        blockHash[0] = 9;
        claim[0] = 9;
        byte[] exposed = snapshot.claim();
        exposed[1] = 9;

        assertThat(snapshot.txHash()).containsOnly(0);
        assertThat(snapshot.blockHash()).containsOnly(0);
        assertThat(snapshot.claim()).containsExactly(1, 2, 3);
    }

    @Test
    void observerAggregateClaimBudgetStopsLazyTraversalAtFirstOverflow() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        byte[] blockHash = new byte[32];
        InfiniteProbeList<L1Observation> observations = new InfiniteProbeList<>(
                probe, rogue, ignored -> new L1Observation(
                        "observer-instance", new byte[32], 9, blockHash,
                        new byte[PluginSpiFacades.MAX_OBSERVATION_CLAIM_BYTES]));
        L1Observer rawObserver = new L1Observer() {
            @Override public String observerId() { probe.check(); return "observer-instance"; }
            @Override public List<L1Observation> observe(
                    long slot, byte[] callbackHash, Block block) {
                probe.check();
                return observations;
            }
        };
        L1ObserverProvider rawProvider = new L1ObserverProvider() {
            @Override public String type() { probe.check(); return "bounded-observer"; }
            @Override public L1Observer create(String id, Map<String, String> settings) {
                probe.check();
                return rawObserver;
            }
        };
        L1ObserverProvider provider = providerFacade(
                L1ObserverProvider.class, ContributionKind.L1_OBSERVER,
                rawProvider, plugin, "observer-bundle", "bounded-observer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            L1Observer observer = provider.create("observer-instance", Map.of());
            assertThatThrownBy(() -> observer.observe(9, blockHash, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("L1 observer aggregate claims exceed 4194304 bytes");
            assertThat(observations.hasNextCalls).hasValue(5);
            assertThat(observations.nextCalls).hasValue(5);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void observerSnapshotsRejectIdentitySlotAndBlockHashMismatches() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        byte[] blockHash = new byte[32];
        blockHash[0] = 1;
        AtomicReference<L1Observation> result = new AtomicReference<>();
        AtomicReference<byte[]> callbackInput = new AtomicReference<>();
        AtomicInteger mutateCallback = new AtomicInteger();
        L1Observer rawObserver = new L1Observer() {
            @Override public String observerId() { probe.check(); return "observer-instance"; }
            @Override public List<L1Observation> observe(
                    long slot, byte[] callbackHash, Block block) {
                probe.check();
                callbackInput.set(callbackHash);
                if (mutateCallback.get() != 0) {
                    callbackHash[0] = 2;
                    return List.of(new L1Observation(
                            "observer-instance", new byte[32], slot,
                            callbackHash, new byte[0]));
                }
                return List.of(result.get());
            }
        };
        L1ObserverProvider rawProvider = new L1ObserverProvider() {
            @Override public String type() { probe.check(); return "verified-observer"; }
            @Override public L1Observer create(String id, Map<String, String> settings) {
                probe.check();
                return rawObserver;
            }
        };
        L1ObserverProvider provider = providerFacade(
                L1ObserverProvider.class, ContributionKind.L1_OBSERVER,
                rawProvider, plugin, "observer-bundle", "verified-observer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            L1Observer observer = provider.create("observer-instance", Map.of());
            result.set(new L1Observation(
                    "wrong-observer", new byte[32], 9, blockHash, new byte[0]));
            assertThatThrownBy(() -> observer.observe(9, blockHash, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("L1 observation id does not match configured observer");

            result.set(new L1Observation(
                    "observer-instance", new byte[32], 10, blockHash, new byte[0]));
            assertThatThrownBy(() -> observer.observe(9, blockHash, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("L1 observation slot does not match callback slot");

            result.set(new L1Observation(
                    "observer-instance", new byte[32], 9, new byte[32], new byte[0]));
            assertThatThrownBy(() -> observer.observe(9, blockHash, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("L1 observation block hash does not match callback block hash");

            mutateCallback.set(1);
            assertThatThrownBy(() -> observer.observe(9, blockHash, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("L1 observation block hash does not match callback block hash");
            assertThat(callbackInput.get()).isNotSameAs(blockHash);
            assertThat(blockHash[0]).isEqualTo((byte) 1);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void signerFacadeOwnsBuffersValidatesSignatureAndSanitizesFailures() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        AtomicReference<byte[]> pluginInput = new AtomicReference<>();
        AtomicReference<byte[]> pluginOutput = new AtomicReference<>(filledBytes(64, (byte) 7));
        AtomicReference<RuntimeException> pluginFailure = new AtomicReference<>();
        SignerProvider rawSigner = new SignerProvider() {
            @Override public byte[] sign(byte[] message) {
                probe.check();
                RuntimeException failure = pluginFailure.get();
                if (failure != null) {
                    throw failure;
                }
                pluginInput.set(message);
                if (message.length > 0) {
                    message[0] = 99;
                }
                return pluginOutput.get();
            }
            @Override public byte[] publicKey() { probe.check(); return new byte[32]; }
        };
        SignerProviderFactory rawFactory = new SignerProviderFactory() {
            @Override public String scheme() { probe.check(); return "safe-signer"; }
            @Override public SignerProvider create(String keyReference) {
                probe.check();
                return rawSigner;
            }
        };
        SignerProviderFactory factory = providerFacade(
                SignerProviderFactory.class, ContributionKind.SIGNER_PROVIDER,
                rawFactory, plugin, "signer-bundle", "safe-signer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            SignerProvider signer = factory.create("key-reference");
            byte[] message = new byte[]{1, 2, 3};
            byte[] rawSignature = pluginOutput.get();
            byte[] signature = signer.sign(message);

            assertThat(pluginInput.get()).isNotSameAs(message);
            assertThat(message).containsExactly(1, 2, 3);
            assertThat(signature).isNotSameAs(rawSignature).containsOnly(7);
            rawSignature[0] = 8;
            signature[1] = 9;
            assertThat(signature[0]).isEqualTo((byte) 7);
            assertThat(rawSignature[1]).isEqualTo((byte) 7);
            assertCaller(caller);

            pluginOutput.set(new byte[63]);
            PluginActivationException lengthFailure = assertActivationFailure(
                    () -> signer.sign(new byte[0]), ContributionKind.SIGNER_PROVIDER,
                    "signer-bundle", "safe-signer", rawFactory);
            assertThat(lengthFailure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("64-byte Ed25519 signature");
            assertCaller(caller);

            String secret = "secret signer backend detail";
            IllegalStateException backendFailure = new IllegalStateException(secret);
            pluginFailure.set(backendFailure);
            assertSecretSafeActivationFailure(
                    () -> signer.sign(new byte[0]), backendFailure, secret,
                    ContributionKind.SIGNER_PROVIDER,
                    "signer-bundle", "safe-signer", rawFactory);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void sequencerFacadeDoesNotExposeTheConsensusBlockProposerBuffer() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        AtomicReference<byte[]> pluginInput = new AtomicReference<>();
        SequencerMode rawMode = new SequencerMode() {
            @Override public String id() { probe.check(); return "safe-sequencer"; }
            @Override public void init(SequencerContext context) { probe.check(); }
            @Override public boolean shouldProposeNow(long height) { probe.check(); return false; }
            @Override public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
                probe.check();
                pluginInput.set(proposerKey);
                proposerKey[0] = 99;
                return ProposalEligibility.ACCEPT;
            }
        };
        SequencerModeProvider rawProvider = new SequencerModeProvider() {
            @Override public String id() { probe.check(); return "safe-sequencer"; }
            @Override public SequencerMode create(SequencerContext context) {
                probe.check();
                return rawMode;
            }
        };
        SequencerModeProvider provider = providerFacade(
                SequencerModeProvider.class, ContributionKind.SEQUENCER_MODE,
                rawProvider, plugin, "sequencer-bundle", "safe-sequencer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            SequencerMode sequencer = provider.create(null);
            byte[] proposerKey = new byte[]{1, 2, 3};
            assertThat(sequencer.checkProposal(proposerKey, 7))
                    .isEqualTo(SequencerMode.ProposalEligibility.ACCEPT);
            assertThat(pluginInput.get()).isNotSameAs(proposerKey);
            assertThat(proposerKey).containsExactly(1, 2, 3);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void pluginStatusIsDeeplyNormalizedAndRejectsCyclesOrArbitraryBeans() {
        AtomicReference<Map<String, Object>> rawStatus = new AtomicReference<>();
        SequencerMode rawMode = new SequencerMode() {
            @Override public String id() { return "status-mode"; }
            @Override public void init(SequencerContext context) { }
            @Override public boolean shouldProposeNow(long height) { return false; }
            @Override public ProposalEligibility checkProposal(
                    byte[] proposerKey, long height) { return ProposalEligibility.REJECT; }
            @Override public Map<String, Object> status() { return rawStatus.get(); }
        };
        SequencerModeProvider rawProvider = new SequencerModeProvider() {
            @Override public String id() { return "status-mode"; }
            @Override public SequencerMode create(SequencerContext context) { return rawMode; }
        };
        SequencerModeProvider provider = (SequencerModeProvider) PluginSpiFacades.provider(
                ContributionKind.SEQUENCER_MODE, rawProvider,
                getClass().getClassLoader(), "status-bundle", "status-mode",
                rawProvider.getClass().getName());
        SequencerMode mode = provider.create(null);

        List<Object> rawList = new ArrayList<>();
        rawList.add("first");
        rawList.add(null);
        Map<String, Object> rawNested = new java.util.LinkedHashMap<>();
        rawNested.put("items", rawList);
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("nested", rawNested);
        rawStatus.set(root);

        Map<String, Object> snapshot = mode.status();
        rawList.add("late");
        rawNested.put("late", true);
        root.put("late", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedSnapshot =
                (Map<String, Object>) snapshot.get("nested");
        @SuppressWarnings("unchecked")
        List<Object> listSnapshot = (List<Object>) nestedSnapshot.get("items");
        assertThat(snapshot).containsOnlyKeys("nested");
        assertThat(nestedSnapshot).containsOnlyKeys("items");
        assertThat(listSnapshot).containsExactly("first", null);
        assertThatThrownBy(() -> nestedSnapshot.put("mutate", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> listSnapshot.add("mutate"))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<String, Object> cyclic = new java.util.LinkedHashMap<>();
        cyclic.put("self", cyclic);
        rawStatus.set(cyclic);
        assertThatThrownBy(mode::status)
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage("Plugin status must not contain container cycles");

        rawStatus.set(Map.of("bean", new Object()));
        assertThatThrownBy(mode::status)
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage(
                        "Plugin status values must be JSON primitives, maps, or lists");

        rawStatus.set(Map.of("number", new java.math.BigInteger("1".repeat(10_000))));
        assertThatThrownBy(mode::status)
                .isInstanceOf(PluginActivationException.class)
                .hasRootCauseMessage(
                        "Plugin status values must be JSON primitives, maps, or lists");
    }

    @Test
    void failedProviderCandidateSafelyWrapsSharedNonProcessFatalCloseFailure() {
        AssertionError sharedFatal = new AssertionError("shared provider fatal");
        AtomicInteger closeCalls = new AtomicInteger();
        class SameFatalProvider implements AppStateMachineProvider, AutoCloseable {
            @Override public String id() { throw sharedFatal; }
            @Override public AppStateMachine create() { throw new AssertionError("unused"); }
            @Override public void close() {
                closeCalls.incrementAndGet();
                throw sharedFatal;
            }
        }
        SameFatalProvider provider = new SameFatalProvider();
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        "fatal-bundle", ContributionKind.APP_STATE_MACHINE,
                        "expected", provider.getClass().getName(), null, () -> provider);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry), List.of("fatal-bundle"), List.of());

        assertThatThrownBy(() -> registry.require(
                AppStateMachineProvider.class, "expected"))
                .isInstanceOf(PluginActivationException.class)
                .hasCause(sharedFatal);
        assertThat(sharedFatal.getSuppressed()).isEmpty();
        assertThat(closeCalls).hasValue(1);

        registry.close();
        assertThat(closeCalls).hasValue(1);
    }

    @Test
    void stateMachineProductIdentityAssertionStaysInsideActivationBoundary() {
        String secret = "state-machine-identity-secret";
        AppStateMachine rawMachine = new AppStateMachine() {
            @Override public String id() { throw new AssertionError(secret); }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
        };
        AppStateMachineProvider rawProvider = new AppStateMachineProvider() {
            @Override public String id() { return "secret-safe-machine"; }
            @Override public AppStateMachine create() { return rawMachine; }
        };
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        "secret-safe-bundle", ContributionKind.APP_STATE_MACHINE,
                        "secret-safe-machine", rawProvider.getClass().getName(), null,
                        () -> rawProvider);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry), List.of("secret-safe-bundle"), List.of());
        try {
            AppStateMachine product = registry.require(
                    AppStateMachineProvider.class, "secret-safe-machine").create();

            assertThatThrownBy(product::id)
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageContaining("identify state-machine product")
                    .hasMessageNotContaining(secret)
                    .hasRootCauseMessage(secret);
        } finally {
            registry.close();
        }
    }

    @Test
    void factoryProductsAreExclusiveAcrossCreateInvocationsAndRejectedBatchesAreCleaned() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        AppStateMachine machine = new AssertingStateMachine(probe);
        AppStateMachineProvider rawMachines = new AppStateMachineProvider() {
            @Override public String id() { probe.check(); return "machine"; }
            @Override public AppStateMachine create() { probe.check(); return machine; }
            @Override public AppStateMachine create(AppStateMachineContext context) {
                probe.check();
                return machine;
            }
        };
        SequencerMode mode = new AssertingSequencerMode(probe);
        SequencerModeProvider rawSequencers = new SequencerModeProvider() {
            @Override public String id() { probe.check(); return "sequencer"; }
            @Override public SequencerMode create(SequencerContext context) {
                probe.check();
                return mode;
            }
        };
        L1Observer observer = new AssertingObserver(probe);
        L1ObserverProvider rawObservers = new L1ObserverProvider() {
            @Override public String type() { probe.check(); return "observer"; }
            @Override public L1Observer create(
                    String observerId,
                    Map<String, String> settings
            ) {
                probe.check();
                return observer;
            }
        };
        SignerProvider signer = new AssertingSigner(probe);
        SignerProviderFactory rawSigners = new SignerProviderFactory() {
            @Override public String scheme() { probe.check(); return "signer"; }
            @Override public SignerProvider create(String keyReference) {
                probe.check();
                return signer;
            }
        };

        List<String> executorCloseOrder = new ArrayList<>();
        RuntimeException executorCleanupFailure =
                new IllegalStateException("expected executor cleanup failure");
        TrackingExecutor ownedExecutor = new TrackingExecutor(
                "owned-executor", probe, executorCloseOrder, null);
        TrackingExecutor freshExecutorOne = new TrackingExecutor(
                "fresh-executor-1", probe, executorCloseOrder, null);
        TrackingExecutor freshExecutorTwo = new TrackingExecutor(
                "fresh-executor-2", probe, executorCloseOrder, executorCleanupFailure);
        AtomicInteger executorCreates = new AtomicInteger();
        AppEffectExecutorFactory rawExecutors = new AppEffectExecutorFactory() {
            @Override public String scheme() { probe.check(); return "executor"; }
            @Override public List<AppEffectExecutor> create(
                    String chainId,
                    Map<String, String> config
            ) {
                probe.check();
                if (executorCreates.incrementAndGet() == 1) {
                    return List.of(ownedExecutor, ownedExecutor);
                }
                return List.of(
                        freshExecutorOne, ownedExecutor,
                        freshExecutorTwo, freshExecutorOne);
            }
        };

        List<String> sinkCloseOrder = new ArrayList<>();
        TrackingSink ownedSink = new TrackingSink("owned-sink", probe, sinkCloseOrder);
        TrackingSink freshSinkOne = new TrackingSink("fresh-sink-1", probe, sinkCloseOrder);
        TrackingSink freshSinkTwo = new TrackingSink("fresh-sink-2", probe, sinkCloseOrder);
        AtomicInteger sinkCreates = new AtomicInteger();
        FinalizedStreamSinkFactory rawSinks = new FinalizedStreamSinkFactory() {
            @Override public String scheme() { probe.check(); return "sink"; }
            @Override public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                probe.check();
                if (sinkCreates.incrementAndGet() == 1) {
                    return List.of(ownedSink, ownedSink);
                }
                return List.of(freshSinkOne, ownedSink, freshSinkTwo, freshSinkOne);
            }
        };

        AppStateMachineProvider machines = providerFacade(
                AppStateMachineProvider.class, ContributionKind.APP_STATE_MACHINE,
                rawMachines, plugin, "machine-bundle", "machine");
        SequencerModeProvider sequencers = providerFacade(
                SequencerModeProvider.class, ContributionKind.SEQUENCER_MODE,
                rawSequencers, plugin, "sequencer-bundle", "sequencer");
        L1ObserverProvider observers = providerFacade(
                L1ObserverProvider.class, ContributionKind.L1_OBSERVER,
                rawObservers, plugin, "observer-bundle", "observer");
        SignerProviderFactory signers = providerFacade(
                SignerProviderFactory.class, ContributionKind.SIGNER_PROVIDER,
                rawSigners, plugin, "signer-bundle", "signer");
        AppEffectExecutorFactory executors = providerFacade(
                AppEffectExecutorFactory.class, ContributionKind.EFFECT_EXECUTOR,
                rawExecutors, plugin, "executor-bundle", "executor");
        FinalizedStreamSinkFactory sinks = providerFacade(
                FinalizedStreamSinkFactory.class, ContributionKind.FINALIZED_SINK,
                rawSinks, plugin, "sink-bundle", "sink");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            machines.create(null);
            assertReuseFailure(
                    () -> machines.create(null), ContributionKind.APP_STATE_MACHINE,
                    "machine-bundle", "machine", rawMachines);

            sequencers.create(null);
            assertReuseFailure(
                    () -> sequencers.create(null), ContributionKind.SEQUENCER_MODE,
                    "sequencer-bundle", "sequencer", rawSequencers);

            observers.create("chain-a-observer", Map.of());
            assertReuseFailure(
                    () -> observers.create("chain-b-observer", Map.of()),
                    ContributionKind.L1_OBSERVER,
                    "observer-bundle", "observer", rawObservers);

            signers.create("chain-a-key");
            assertReuseFailure(
                    () -> signers.create("chain-b-key"), ContributionKind.SIGNER_PROVIDER,
                    "signer-bundle", "signer", rawSigners);

            List<AppEffectExecutor> firstExecutors = executors.create("chain-a", Map.of());
            assertThat(firstExecutors).hasSize(2);
            assertThat(firstExecutors.get(1)).isSameAs(firstExecutors.getFirst());
            PluginActivationException executorFailure = assertReuseFailure(
                    () -> executors.create("chain-b", Map.of()),
                    ContributionKind.EFFECT_EXECUTOR,
                    "executor-bundle", "executor", rawExecutors);
            assertThat(ownedExecutor.closeCalls).hasValue(0);
            assertThat(freshExecutorOne.closeCalls).hasValue(1);
            assertThat(freshExecutorTwo.closeCalls).hasValue(1);
            assertThat(executorCloseOrder)
                    .containsExactly("fresh-executor-2", "fresh-executor-1");
            assertThat(executorFailure.getCause().getSuppressed())
                    .containsExactly(executorCleanupFailure);

            List<FinalizedStreamSink> firstSinks = sinks.create("chain-a", Map.of());
            assertThat(firstSinks).hasSize(2);
            assertThat(firstSinks.get(1)).isSameAs(firstSinks.getFirst());
            assertReuseFailure(
                    () -> sinks.create("chain-b", Map.of()), ContributionKind.FINALIZED_SINK,
                    "sink-bundle", "sink", rawSinks);
            assertThat(ownedSink.closeCalls).hasValue(0);
            assertThat(freshSinkOne.closeCalls).hasValue(1);
            assertThat(freshSinkTwo.closeCalls).hasValue(1);
            assertThat(sinkCloseOrder).containsExactly("fresh-sink-2", "fresh-sink-1");

            firstExecutors.getFirst().close();
            firstSinks.getFirst().close();
            assertThat(ownedExecutor.closeCalls).hasValue(1);
            assertThat(ownedSink.closeCalls).hasValue(1);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void iteratorFailureTerminallyReservesAndReverseClosesOnlyCapturedFreshProducts() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        List<String> closeOrder = new ArrayList<>();
        AssertionError fatalCleanup = new AssertionError("expected traversal cleanup fatal");
        TrackingSink owned = new TrackingSink("owned", probe, closeOrder);
        TrackingSink freshOne = new TrackingSink("fresh-1", probe, closeOrder);
        TrackingSink freshTwo = new TrackingSink(
                "fresh-2", probe, closeOrder, fatalCleanup);
        RuntimeException traversalFailure =
                new IllegalStateException("expected iterator failure");
        AtomicInteger creates = new AtomicInteger();
        FinalizedStreamSinkFactory rawFactory = new FinalizedStreamSinkFactory() {
            @Override public String scheme() { probe.check(); return "failing-list"; }
            @Override public List<FinalizedStreamSink> create(
                    String chainId, Map<String, String> config) {
                probe.check();
                return switch (creates.incrementAndGet()) {
                    case 1 -> List.of(owned);
                    case 2 -> new FailingTraversalList<>(
                            probe, List.of(freshOne, owned, freshTwo), traversalFailure);
                    default -> List.of(freshOne);
                };
            }
        };
        FinalizedStreamSinkFactory factory = providerFacade(
                FinalizedStreamSinkFactory.class, ContributionKind.FINALIZED_SINK,
                rawFactory, plugin, "sink-bundle", "failing-list");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            FinalizedStreamSink owner = factory.create("chain-a", Map.of()).getFirst();
            Throwable failure = catchThrowable(
                    () -> factory.create("chain-b", Map.of()));
            assertThat(failure)
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageNotContaining(fatalCleanup.getMessage())
                    .hasCause(fatalCleanup);
            assertThat(fatalCleanup.getSuppressed()).containsExactly(traversalFailure);
            assertThat(closeOrder).containsExactly("fresh-2", "fresh-1");
            assertThat(owned.closeCalls).hasValue(0);
            assertThat(freshOne.closeCalls).hasValue(1);
            assertThat(freshTwo.closeCalls).hasValue(1);

            assertReuseFailure(() -> factory.create("chain-c", Map.of()),
                    ContributionKind.FINALIZED_SINK,
                    "sink-bundle", "failing-list", rawFactory);
            assertThat(freshOne.closeCalls).hasValue(1);
            assertThat(owned.closeCalls).hasValue(0);
            owner.close();
            assertThat(owned.closeCalls).hasValue(1);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void factoryProductListsAreSnapshottedOnceUnderPluginTccl() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        List<String> executorCloseOrder = new ArrayList<>();
        TrackingExecutor executorOne = new TrackingExecutor(
                "executor-1", probe, executorCloseOrder, null);
        TrackingExecutor executorTwo = new TrackingExecutor(
                "executor-2", probe, executorCloseOrder, null);
        TrackingExecutor unexpectedExecutor = new TrackingExecutor(
                "unexpected-executor", probe, executorCloseOrder, null);
        ChangingTraversalList<AppEffectExecutor> executorProducts =
                new ChangingTraversalList<>(probe,
                        List.of(executorOne, executorTwo), List.of(unexpectedExecutor));
        AppEffectExecutorFactory rawExecutors = new AppEffectExecutorFactory() {
            @Override public String scheme() { probe.check(); return "changing-executor"; }
            @Override public List<AppEffectExecutor> create(
                    String chainId, Map<String, String> config) {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return executorProducts;
            }
        };

        List<String> sinkCloseOrder = new ArrayList<>();
        TrackingSink sinkOne = new TrackingSink("sink-1", probe, sinkCloseOrder);
        TrackingSink sinkTwo = new TrackingSink("sink-2", probe, sinkCloseOrder);
        TrackingSink unexpectedSink = new TrackingSink(
                "unexpected-sink", probe, sinkCloseOrder);
        ChangingTraversalList<FinalizedStreamSink> sinkProducts =
                new ChangingTraversalList<>(probe,
                        List.of(sinkOne, sinkTwo), List.of(unexpectedSink));
        FinalizedStreamSinkFactory rawSinks = new FinalizedStreamSinkFactory() {
            @Override public String scheme() { probe.check(); return "changing-sink"; }
            @Override public List<FinalizedStreamSink> create(
                    String chainId, Map<String, String> config) {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return sinkProducts;
            }
        };

        AppEffectExecutorFactory executors = providerFacade(
                AppEffectExecutorFactory.class, ContributionKind.EFFECT_EXECUTOR,
                rawExecutors, plugin, "executor-bundle", "changing-executor");
        FinalizedStreamSinkFactory sinks = providerFacade(
                FinalizedStreamSinkFactory.class, ContributionKind.FINALIZED_SINK,
                rawSinks, plugin, "sink-bundle", "changing-sink");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            List<AppEffectExecutor> activatedExecutors =
                    executors.create("chain", Map.of());
            assertThat(activatedExecutors).hasSize(2);
            assertThat(activatedExecutors)
                    .extracting(AppEffectExecutor::id)
                    .containsExactly("executor-1", "executor-2");
            assertThat(executorProducts.traversals()).isEqualTo(1);

            List<FinalizedStreamSink> activatedSinks = sinks.create("chain", Map.of());
            assertThat(activatedSinks).hasSize(2);
            assertThat(activatedSinks)
                    .extracting(FinalizedStreamSink::id)
                    .containsExactly("sink-1", "sink-2");
            assertThat(sinkProducts.traversals()).isEqualTo(1);
            assertCaller(caller);

            activatedExecutors.forEach(AppEffectExecutor::close);
            activatedSinks.forEach(FinalizedStreamSink::close);
            assertThat(executorCloseOrder).containsExactly("executor-1", "executor-2");
            assertThat(sinkCloseOrder).containsExactly("sink-1", "sink-2");
            assertThat(unexpectedExecutor.closeCalls).hasValue(0);
            assertThat(unexpectedSink.closeCalls).hasValue(0);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void rejectedCompanionClosesRestorePluginTcclBetweenCallbacks() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        List<String> closeOrder = new ArrayList<>();
        AtomicInteger firstCloses = new AtomicInteger();
        AtomicInteger corruptingCloses = new AtomicInteger();

        TrackingSink owned = new TrackingSink("owned", probe, closeOrder);
        FinalizedStreamSink firstFresh = new FinalizedStreamSink() {
            @Override public String id() { probe.check(); return "first-fresh"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() {
                probe.check();
                firstCloses.incrementAndGet();
                closeOrder.add("first-fresh");
            }
        };
        FinalizedStreamSink corruptingFresh = new FinalizedStreamSink() {
            @Override public String id() { probe.check(); return "corrupting-fresh"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() {
                probe.check();
                corruptingCloses.incrementAndGet();
                closeOrder.add("corrupting-fresh");
                Thread.currentThread().setContextClassLoader(rogue);
            }
        };
        AtomicInteger creates = new AtomicInteger();
        FinalizedStreamSinkFactory rawFactory = new FinalizedStreamSinkFactory() {
            @Override public String scheme() { probe.check(); return "rogue-close"; }
            @Override public List<FinalizedStreamSink> create(
                    String chainId, Map<String, String> config) {
                probe.check();
                return creates.incrementAndGet() == 1
                        ? List.of(owned)
                        : List.of(firstFresh, owned, corruptingFresh);
            }
        };
        FinalizedStreamSinkFactory factory = providerFacade(
                FinalizedStreamSinkFactory.class, ContributionKind.FINALIZED_SINK,
                rawFactory, plugin, "sink-bundle", "rogue-close");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            FinalizedStreamSink firstOwner = factory.create("chain-a", Map.of()).getFirst();
            assertReuseFailure(() -> factory.create("chain-b", Map.of()),
                    ContributionKind.FINALIZED_SINK,
                    "sink-bundle", "rogue-close", rawFactory);

            assertThat(closeOrder).containsExactly("corrupting-fresh", "first-fresh");
            assertThat(corruptingCloses).hasValue(1);
            assertThat(firstCloses).hasValue(1);
            assertThat(owned.closeCalls).hasValue(0);
            assertCaller(caller);

            firstOwner.close();
            assertThat(owned.closeCalls).hasValue(1);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void startupIdentityAndStatusFailuresRetainPluginActivationIdentity() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        SequencerMode failingMode = new FailingStartupSequencerMode(probe);
        SequencerModeProvider rawSequencers = new SequencerModeProvider() {
            @Override public String id() { probe.check(); return "sequencer"; }
            @Override public SequencerMode create(SequencerContext context) {
                probe.check();
                return failingMode;
            }
        };
        L1Observer failingObserver = new FailingStartupObserver(probe);
        L1ObserverProvider rawObservers = new L1ObserverProvider() {
            @Override public String type() { probe.check(); return "observer"; }
            @Override public L1Observer create(
                    String observerId,
                    Map<String, String> settings
            ) {
                probe.check();
                return failingObserver;
            }
        };
        SequencerModeProvider sequencers = providerFacade(
                SequencerModeProvider.class, ContributionKind.SEQUENCER_MODE,
                rawSequencers, plugin, "sequencer-bundle", "sequencer");
        L1ObserverProvider observers = providerFacade(
                L1ObserverProvider.class, ContributionKind.L1_OBSERVER,
                rawObservers, plugin, "observer-bundle", "observer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            SequencerMode sequencer = sequencers.create(null);
            assertActivationFailure(
                    sequencer::id, ContributionKind.SEQUENCER_MODE,
                    "sequencer-bundle", "sequencer", rawSequencers);
            assertActivationFailure(
                    sequencer::status, ContributionKind.SEQUENCER_MODE,
                    "sequencer-bundle", "sequencer", rawSequencers);

            L1Observer observer = observers.create("observer-instance", Map.of());
            assertActivationFailure(
                    observer::observerId, ContributionKind.L1_OBSERVER,
                    "observer-bundle", "observer", rawObservers);
            assertActivationFailure(
                    observer::status, ContributionKind.L1_OBSERVER,
                    "observer-bundle", "observer", rawObservers);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void activationBoundariesWrapEveryNonProcessFatalWithoutPromotingPluginMessages() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        String assertionSecret = "secret signer assertion";
        AssertionError assertion = new AssertionError(assertionSecret);
        SignerProviderFactory rawSigners = new SignerProviderFactory() {
            @Override public String scheme() { probe.check(); return "signer"; }
            @Override public SignerProvider create(String keyReference) {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                throw assertion;
            }
        };
        SignerProviderFactory signers = providerFacade(
                SignerProviderFactory.class, ContributionKind.SIGNER_PROVIDER,
                rawSigners, plugin, "signer-bundle", "signer");

        String linkageSecret = "secret sequencer linkage detail";
        LinkageError linkage = new LinkageError(linkageSecret);
        SequencerMode rawMode = new SequencerMode() {
            @Override public String id() { probe.check(); return "sequencer"; }
            @Override public void init(SequencerContext context) { probe.check(); }
            @Override public boolean shouldProposeNow(long height) { return false; }
            @Override public ProposalEligibility checkProposal(byte[] key, long height) {
                return ProposalEligibility.ACCEPT;
            }
            @Override public Map<String, Object> status() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                throw linkage;
            }
        };
        SequencerModeProvider rawSequencers = new SequencerModeProvider() {
            @Override public String id() { probe.check(); return "sequencer"; }
            @Override public SequencerMode create(SequencerContext context) {
                probe.check();
                return rawMode;
            }
        };
        SequencerModeProvider sequencers = providerFacade(
                SequencerModeProvider.class, ContributionKind.SEQUENCER_MODE,
                rawSequencers, plugin, "sequencer-bundle", "sequencer");

        String checkedSecret = "secret interrupted observer detail";
        InterruptedException checked = new InterruptedException(checkedSecret);
        L1Observer rawObserver = new L1Observer() {
            @Override public String observerId() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return sneakyThrow(checked);
            }
            @Override public List<L1Observation> observe(
                    long slot, byte[] blockHash, Block block) {
                return List.of();
            }
        };
        L1ObserverProvider rawObservers = new L1ObserverProvider() {
            @Override public String type() { probe.check(); return "observer"; }
            @Override public L1Observer create(
                    String observerId, Map<String, String> settings) {
                probe.check();
                return rawObserver;
            }
        };
        L1ObserverProvider observers = providerFacade(
                L1ObserverProvider.class, ContributionKind.L1_OBSERVER,
                rawObservers, plugin, "observer-bundle", "observer");

        String spoofedSecret = "secret spoofed activation diagnostic";
        PluginActivationException spoofed = new PluginActivationException(
                spoofedSecret, new IllegalStateException("nested plugin detail"));
        AppEffectExecutor rawExecutor = new AppEffectExecutor() {
            @Override public String id() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                throw spoofed;
            }
            @Override public boolean supports(String effectType) { return true; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                return EffectExecution.confirmed(new byte[0]);
            }
        };
        AppEffectExecutorFactory rawExecutors = executorFactory(
                "executor", probe, List.of(rawExecutor));
        AppEffectExecutorFactory executors = providerFacade(
                AppEffectExecutorFactory.class, ContributionKind.EFFECT_EXECUTOR,
                rawExecutors, plugin, "executor-bundle", "executor");

        TestVirtualMachineError fatal = new TestVirtualMachineError(
                "process-fatal sink identity");
        FinalizedStreamSink rawSink = new FinalizedStreamSink() {
            @Override public String id() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                throw fatal;
            }
            @Override public boolean deliver(AppBlock block) { return true; }
        };
        FinalizedStreamSinkFactory rawSinks = sinkFactory(
                "sink", probe, List.of(rawSink));
        FinalizedStreamSinkFactory sinks = providerFacade(
                FinalizedStreamSinkFactory.class, ContributionKind.FINALIZED_SINK,
                rawSinks, plugin, "sink-bundle", "sink");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            assertSecretSafeActivationFailure(
                    () -> signers.create("key"), assertion, assertionSecret,
                    ContributionKind.SIGNER_PROVIDER,
                    "signer-bundle", "signer", rawSigners);
            assertCaller(caller);

            SequencerMode sequencer = sequencers.create(null);
            assertSecretSafeActivationFailure(
                    sequencer::status, linkage, linkageSecret,
                    ContributionKind.SEQUENCER_MODE,
                    "sequencer-bundle", "sequencer", rawSequencers);
            assertCaller(caller);

            L1Observer observer = observers.create("instance", Map.of());
            assertSecretSafeActivationFailure(
                    observer::observerId, checked, checkedSecret,
                    ContributionKind.L1_OBSERVER,
                    "observer-bundle", "observer", rawObservers);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertCaller(caller);
            Thread.interrupted();

            AppEffectExecutor executor = executors.create("chain", Map.of()).getFirst();
            assertSecretSafeActivationFailure(
                    executor::id, spoofed, spoofedSecret,
                    ContributionKind.EFFECT_EXECUTOR,
                    "executor-bundle", "executor", rawExecutors);
            assertCaller(caller);

            FinalizedStreamSink sink = sinks.create("chain", Map.of()).getFirst();
            assertThatThrownBy(sink::id).isSameAs(fatal);
            assertCaller(caller);
        } finally {
            Thread.interrupted();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void lazyObserverAndStatusCollectionsGetFreshTcclScopesAfterRogueCallbacks() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        L1Observation observation = new L1Observation(
                "observer-instance", new byte[32], 1, new byte[32], new byte[0]);

        SequencerMode mode = new SequencerMode() {
            @Override public String id() { probe.check(); return "sequencer"; }
            @Override public void init(SequencerContext context) { probe.check(); }
            @Override public boolean shouldProposeNow(long height) { return false; }
            @Override public ProposalEligibility checkProposal(byte[] key, long height) {
                return ProposalEligibility.ACCEPT;
            }
            @Override public Map<String, Object> status() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return new ProbeMap<>(probe, Map.of("mode", "ready"));
            }
        };
        SequencerModeProvider sequencers = providerFacade(
                SequencerModeProvider.class, ContributionKind.SEQUENCER_MODE,
                new SequencerModeProvider() {
                    @Override public String id() { probe.check(); return "sequencer"; }
                    @Override public SequencerMode create(SequencerContext context) {
                        probe.check();
                        return mode;
                    }
                }, plugin, "sequencer-bundle", "sequencer");

        L1Observer observer = new L1Observer() {
            @Override public String observerId() {
                probe.check();
                return "observer-instance";
            }
            @Override public List<L1Observation> observe(
                    long slot, byte[] blockHash, Block block) {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return new ProbeList<>(probe, observation);
            }
            @Override public Map<String, Object> status() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return new ProbeMap<>(probe, Map.of("observer", "ready"));
            }
        };
        L1ObserverProvider observers = providerFacade(
                L1ObserverProvider.class, ContributionKind.L1_OBSERVER,
                new L1ObserverProvider() {
                    @Override public String type() { probe.check(); return "observer"; }
                    @Override public L1Observer create(
                            String observerId, Map<String, String> settings) {
                        probe.check();
                        return observer;
                    }
                }, plugin, "observer-bundle", "observer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            assertThat(sequencers.create(null).status()).containsEntry("mode", "ready");
            L1Observer exposed = observers.create("observer-instance", Map.of());
            assertThat(exposed.observe(1, new byte[32], null)).singleElement()
                    .satisfies(snapshot -> {
                        assertThat(snapshot.observerId()).isEqualTo(observation.observerId());
                        assertThat(snapshot.slot()).isEqualTo(observation.slot());
                        assertThat(snapshot.txHash()).isEqualTo(observation.txHash());
                        assertThat(snapshot.blockHash()).isEqualTo(observation.blockHash());
                        assertThat(snapshot.claim()).isEqualTo(observation.claim());
                    });
            assertThat(exposed.status()).containsEntry("observer", "ready");
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void statusAndObservationSnapshotsRejectInfinitePluginCollectionsUnderTccl() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        InfiniteProbeMap sequencerStatus = new InfiniteProbeMap(probe, rogue);
        InfiniteProbeMap observerStatus = new InfiniteProbeMap(probe, rogue);
        L1Observation observation = new L1Observation(
                "observer-instance", new byte[32], 1, new byte[32], new byte[0]);
        InfiniteProbeList<L1Observation> observations = new InfiniteProbeList<>(
                probe, rogue, ignored -> observation);

        SequencerMode rawMode = new SequencerMode() {
            @Override public String id() { probe.check(); return "bounded-status"; }
            @Override public void init(SequencerContext context) { probe.check(); }
            @Override public boolean shouldProposeNow(long height) { return false; }
            @Override public ProposalEligibility checkProposal(byte[] key, long height) {
                return ProposalEligibility.ACCEPT;
            }
            @Override public Map<String, Object> status() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return sequencerStatus;
            }
        };
        SequencerModeProvider rawSequencers = new SequencerModeProvider() {
            @Override public String id() { probe.check(); return "bounded-status"; }
            @Override public SequencerMode create(SequencerContext context) {
                probe.check();
                return rawMode;
            }
        };
        SequencerModeProvider sequencers = providerFacade(
                SequencerModeProvider.class, ContributionKind.SEQUENCER_MODE,
                rawSequencers, plugin, "sequencer-bundle", "bounded-status");

        L1Observer rawObserver = new L1Observer() {
            @Override public String observerId() {
                probe.check();
                return "observer-instance";
            }
            @Override public List<L1Observation> observe(
                    long slot, byte[] blockHash, Block block) {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return observations;
            }
            @Override public Map<String, Object> status() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return observerStatus;
            }
        };
        L1ObserverProvider rawObservers = new L1ObserverProvider() {
            @Override public String type() { probe.check(); return "bounded-observer"; }
            @Override public L1Observer create(
                    String observerId, Map<String, String> settings) {
                probe.check();
                return rawObserver;
            }
        };
        L1ObserverProvider observers = providerFacade(
                L1ObserverProvider.class, ContributionKind.L1_OBSERVER,
                rawObservers, plugin, "observer-bundle", "bounded-observer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            PluginActivationException sequencerFailure = assertActivationFailure(
                    () -> sequencers.create(null).status(),
                    ContributionKind.SEQUENCER_MODE, "sequencer-bundle",
                    "bounded-status", rawSequencers);
            assertThat(sequencerFailure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Plugin status must contain at most 256 entries");
            assertThat(sequencerStatus.hasNextCalls).hasValue(257);
            assertThat(sequencerStatus.nextCalls).hasValue(256);
            assertCaller(caller);

            L1Observer observer = observers.create("observer-instance", Map.of());
            assertThatThrownBy(() -> observer.observe(1, new byte[32], null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("L1 observer result must contain at most 4096 observations");
            assertThat(observations.hasNextCalls).hasValue(4_097);
            assertThat(observations.nextCalls).hasValue(4_096);
            assertCaller(caller);

            PluginActivationException observerFailure = assertActivationFailure(
                    observer::status, ContributionKind.L1_OBSERVER,
                    "observer-bundle", "bounded-observer", rawObservers);
            assertThat(observerFailure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Plugin status must contain at most 256 entries");
            assertThat(observerStatus.hasNextCalls).hasValue(257);
            assertThat(observerStatus.nextCalls).hasValue(256);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void factorySnapshotOverflowReverseClosesEveryCapturedProductUnderTccl() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        List<String> executorCloseOrder = new ArrayList<>();
        List<TrackingExecutor> capturedExecutors = new ArrayList<>();
        InfiniteProbeList<AppEffectExecutor> executorProducts = new InfiniteProbeList<>(
                probe, rogue, index -> {
                    TrackingExecutor executor = new TrackingExecutor(
                            "executor-" + index, probe, executorCloseOrder, null);
                    capturedExecutors.add(executor);
                    return executor;
                });
        AppEffectExecutorFactory rawExecutors = new AppEffectExecutorFactory() {
            @Override public String scheme() { probe.check(); return "bounded-executor"; }
            @Override public List<AppEffectExecutor> create(
                    String chainId, Map<String, String> config) {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return executorProducts;
            }
        };
        AppEffectExecutorFactory executors = providerFacade(
                AppEffectExecutorFactory.class, ContributionKind.EFFECT_EXECUTOR,
                rawExecutors, plugin, "executor-bundle", "bounded-executor");

        List<String> sinkCloseOrder = new ArrayList<>();
        List<TrackingSink> capturedSinks = new ArrayList<>();
        InfiniteProbeList<FinalizedStreamSink> sinkProducts = new InfiniteProbeList<>(
                probe, rogue, index -> {
                    TrackingSink sink = new TrackingSink(
                            "sink-" + index, probe, sinkCloseOrder);
                    capturedSinks.add(sink);
                    return sink;
                });
        FinalizedStreamSinkFactory rawSinks = new FinalizedStreamSinkFactory() {
            @Override public String scheme() { probe.check(); return "bounded-sink"; }
            @Override public List<FinalizedStreamSink> create(
                    String chainId, Map<String, String> config) {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return sinkProducts;
            }
        };
        FinalizedStreamSinkFactory sinks = providerFacade(
                FinalizedStreamSinkFactory.class, ContributionKind.FINALIZED_SINK,
                rawSinks, plugin, "sink-bundle", "bounded-sink");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            PluginActivationException executorFailure = assertActivationFailure(
                    () -> executors.create("chain", Map.of()),
                    ContributionKind.EFFECT_EXECUTOR, "executor-bundle",
                    "bounded-executor", rawExecutors);
            assertThat(executorFailure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Plugin factory result must contain at most 256 products");
            assertThat(executorProducts.hasNextCalls).hasValue(257);
            assertThat(executorProducts.nextCalls).hasValue(256);
            assertThat(capturedExecutors).hasSize(256)
                    .allSatisfy(executor -> assertThat(executor.closeCalls).hasValue(1));
            assertThat(executorCloseOrder).hasSize(256)
                    .startsWith("executor-255")
                    .endsWith("executor-0");
            assertCaller(caller);

            PluginActivationException sinkFailure = assertActivationFailure(
                    () -> sinks.create("chain", Map.of()),
                    ContributionKind.FINALIZED_SINK, "sink-bundle",
                    "bounded-sink", rawSinks);
            assertThat(sinkFailure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Plugin factory result must contain at most 256 products");
            assertThat(sinkProducts.hasNextCalls).hasValue(257);
            assertThat(sinkProducts.nextCalls).hasValue(256);
            assertThat(capturedSinks).hasSize(256)
                    .allSatisfy(sink -> assertThat(sink.closeCalls).hasValue(1));
            assertThat(sinkCloseOrder).hasSize(256)
                    .startsWith("sink-255")
                    .endsWith("sink-0");
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void manifestedNodePluginLazyDependenciesGetIndependentTcclScopes() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader pluginLoader = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(pluginLoader);
        String bundleId = "com.example.lazy-node-plugin";
        String dependencyId = "com.example.dependency";

        NodePlugin plugin = new NodePlugin() {
            @Override public String id() { probe.check(); return bundleId; }
            @Override public String version() { probe.check(); return "1.0.0"; }
            @Override public Set<String> dependsOn() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return new AbstractSet<>() {
                    @Override public Iterator<String> iterator() {
                        probe.check();
                        Iterator<String> delegate = Set.of(dependencyId).iterator();
                        return new Iterator<>() {
                            @Override public boolean hasNext() {
                                probe.check();
                                return delegate.hasNext();
                            }
                            @Override public String next() {
                                probe.check();
                                return delegate.next();
                            }
                        };
                    }
                    @Override public int size() { probe.check(); return 1; }
                };
            }
            @Override public void init(PluginContext context) { probe.check(); }
            @Override public void start() { probe.check(); }
            @Override public void stop() { probe.check(); }
            @Override public void close() { probe.check(); }
        };
        BundleManifest manifest = new BundleManifest(
                BundleManifest.CURRENT_SCHEMA_VERSION,
                bundleId,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1, 1),
                List.of(new BundleDependency(dependencyId, null, null)),
                List.of());
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        bundleId, ContributionKind.NODE_PLUGIN, bundleId,
                        plugin.getClass().getName(), manifest, () -> plugin);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry), List.of(bundleId), List.of(), pluginLoader);

        Thread.currentThread().setContextClassLoader(caller);
        try {
            NodePlugin exposed = registry.nodePluginInstances().getFirst();
            assertThat(exposed).isNotSameAs(plugin);
            assertThat(exposed.id()).isEqualTo(bundleId);
            assertThat(exposed.version()).isEqualTo("1.0.0");
            assertThat(exposed.dependsOn()).containsExactly(dependencyId);
            assertCaller(caller);
        } finally {
            registry.close();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void manifestedNodePluginDependencyTraversalIsBoundedInsidePluginTccl() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader pluginLoader = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(pluginLoader);
        String bundleId = "com.example.unbounded-node-plugin";
        AtomicInteger hasNextCalls = new AtomicInteger();
        AtomicInteger nextCalls = new AtomicInteger();

        NodePlugin plugin = new NodePlugin() {
            @Override public String id() { probe.check(); return bundleId; }
            @Override public String version() { probe.check(); return "1.0.0"; }
            @Override public Set<String> dependsOn() {
                probe.check();
                return new AbstractSet<>() {
                    @Override public Iterator<String> iterator() {
                        probe.check();
                        return new Iterator<>() {
                            @Override public boolean hasNext() {
                                probe.check();
                                hasNextCalls.incrementAndGet();
                                return true;
                            }

                            @Override public String next() {
                                probe.check();
                                nextCalls.incrementAndGet();
                                return "com.example.dependency";
                            }
                        };
                    }

                    @Override public int size() {
                        throw new AssertionError("dependency size must not be consulted");
                    }
                };
            }
            @Override public void init(PluginContext context) { probe.check(); }
            @Override public void start() { probe.check(); }
            @Override public void stop() { probe.check(); }
            @Override public void close() { probe.check(); }
        };
        BundleManifest manifest = new BundleManifest(
                BundleManifest.CURRENT_SCHEMA_VERSION,
                bundleId,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1, 1),
                List.of(),
                List.of());
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        bundleId, ContributionKind.NODE_PLUGIN, bundleId,
                        plugin.getClass().getName(), manifest, () -> plugin);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry), List.of(bundleId), List.of(), pluginLoader);

        Thread.currentThread().setContextClassLoader(caller);
        try {
            assertThatThrownBy(registry::nodePluginInstances)
                    .isInstanceOf(PluginActivationException.class)
                    .hasRootCauseMessage(
                            "NodePlugin dependencies must contain at most 256 entries");
            assertThat(hasNextCalls).hasValue(257);
            assertThat(nextCalls).hasValue(256);
            assertCaller(caller);
        } finally {
            registry.close();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void catalogReservationsRejectSharedProductsAcrossDifferentFactories() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        List<String> executorCloseOrder = new ArrayList<>();
        TrackingExecutor ownedExecutor = new TrackingExecutor(
                "shared-executor", probe, executorCloseOrder, null);
        TrackingExecutor freshExecutor = new TrackingExecutor(
                "fresh-executor", probe, executorCloseOrder, null);
        AppEffectExecutorFactory rawExecutorA = executorFactory(
                "executor-a", probe, List.of(ownedExecutor));
        AppEffectExecutorFactory rawExecutorB = executorFactory(
                "executor-b", probe, List.of(freshExecutor, ownedExecutor));

        List<String> sinkCloseOrder = new ArrayList<>();
        TrackingSink ownedSink = new TrackingSink("shared-sink", probe, sinkCloseOrder);
        TrackingSink freshSink = new TrackingSink("fresh-sink", probe, sinkCloseOrder);
        FinalizedStreamSinkFactory rawSinkA = sinkFactory(
                "sink-a", probe, List.of(ownedSink));
        FinalizedStreamSinkFactory rawSinkB = sinkFactory(
                "sink-b", probe, List.of(freshSink, ownedSink));

        List<CatalogPluginProviderRegistry.Entry> entries = List.of(
                entry("executor-a-bundle", ContributionKind.EFFECT_EXECUTOR,
                        "executor-a", rawExecutorA, probe),
                entry("executor-b-bundle", ContributionKind.EFFECT_EXECUTOR,
                        "executor-b", rawExecutorB, probe),
                entry("sink-a-bundle", ContributionKind.FINALIZED_SINK,
                        "sink-a", rawSinkA, probe),
                entry("sink-b-bundle", ContributionKind.FINALIZED_SINK,
                        "sink-b", rawSinkB, probe));
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                entries, entries.stream().map(
                        CatalogPluginProviderRegistry.Entry::bundleId).toList(),
                List.of(), plugin);

        Thread.currentThread().setContextClassLoader(caller);
        try {
            AppEffectExecutorFactory executorA = registry.require(
                    AppEffectExecutorFactory.class, "executor-a");
            AppEffectExecutorFactory executorB = registry.require(
                    AppEffectExecutorFactory.class, "executor-b");
            AppEffectExecutor firstExecutor = executorA.create(
                    "chain-a", Map.of()).getFirst();
            assertReuseFailure(
                    () -> executorB.create("chain-b", Map.of()),
                    ContributionKind.EFFECT_EXECUTOR,
                    "executor-b-bundle", "executor-b", rawExecutorB);
            assertThat(ownedExecutor.closeCalls).hasValue(0);
            assertThat(freshExecutor.closeCalls).hasValue(1);
            assertThat(executorCloseOrder).containsExactly("fresh-executor");

            FinalizedStreamSinkFactory sinkA = registry.require(
                    FinalizedStreamSinkFactory.class, "sink-a");
            FinalizedStreamSinkFactory sinkB = registry.require(
                    FinalizedStreamSinkFactory.class, "sink-b");
            FinalizedStreamSink firstSink = sinkA.create("chain-a", Map.of()).getFirst();
            assertReuseFailure(
                    () -> sinkB.create("chain-b", Map.of()),
                    ContributionKind.FINALIZED_SINK,
                    "sink-b-bundle", "sink-b", rawSinkB);
            assertThat(ownedSink.closeCalls).hasValue(0);
            assertThat(freshSink.closeCalls).hasValue(1);
            assertThat(sinkCloseOrder).containsExactly("fresh-sink");

            firstExecutor.close();
            firstSink.close();
            assertThat(ownedExecutor.closeCalls).hasValue(1);
            assertThat(ownedSink.closeCalls).hasValue(1);
            assertCaller(caller);
        } finally {
            registry.close();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void rejectedBatchCompanionsAreReservedBeforeBlockingCleanup() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();

        TrackingSink owned = new TrackingSink("owned", probe, new ArrayList<>());
        FinalizedStreamSink fresh = new FinalizedStreamSink() {
            @Override public String id() { probe.check(); return "fresh"; }
            @Override public boolean deliver(AppBlock block) { probe.check(); return true; }
            @Override public void close() {
                probe.check();
                closeCalls.incrementAndGet();
                closeStarted.countDown();
                try {
                    if (!releaseClose.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out awaiting close release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
        };
        AtomicInteger firstCreates = new AtomicInteger();
        FinalizedStreamSinkFactory rawFirst = new FinalizedStreamSinkFactory() {
            @Override public String scheme() { probe.check(); return "first"; }
            @Override public List<FinalizedStreamSink> create(
                    String chainId, Map<String, String> config) {
                probe.check();
                return firstCreates.incrementAndGet() == 1
                        ? List.of(owned) : List.of(owned, fresh);
            }
        };
        FinalizedStreamSinkFactory rawSecond = sinkFactory(
                "second", probe, List.of(fresh));
        PluginSpiFacades.ProductReservations reservations =
                new PluginSpiFacades.ProductReservations();
        FinalizedStreamSinkFactory first = (FinalizedStreamSinkFactory)
                PluginSpiFacades.provider(ContributionKind.FINALIZED_SINK, rawFirst,
                        plugin, "first-bundle", "first", rawFirst.getClass().getName(),
                        reservations);
        FinalizedStreamSinkFactory second = (FinalizedStreamSinkFactory)
                PluginSpiFacades.provider(ContributionKind.FINALIZED_SINK, rawSecond,
                        plugin, "second-bundle", "second", rawSecond.getClass().getName(),
                        reservations);

        Thread.currentThread().setContextClassLoader(caller);
        var cleanupExecutor = Executors.newSingleThreadExecutor();
        try {
            FinalizedStreamSink firstOwner = first.create("chain-a", Map.of()).getFirst();
            var rejected = cleanupExecutor.submit(
                    () -> catchThrowable(() -> first.create("chain-b", Map.of())));
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertReuseFailure(() -> second.create("chain-c", Map.of()),
                    ContributionKind.FINALIZED_SINK,
                    "second-bundle", "second", rawSecond);
            assertThat(closeCalls).hasValue(1);
            assertThat(owned.closeCalls).hasValue(0);

            releaseClose.countDown();
            assertThat(rejected.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(PluginActivationException.class);
            assertThat(closeCalls).hasValue(1);
            firstOwner.close();
            assertThat(owned.closeCalls).hasValue(1);
            assertCaller(caller);
        } finally {
            releaseClose.countDown();
            cleanupExecutor.shutdownNow();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void nonProcessCleanupErrorsAreWrappedAfterRemainingFreshProductsClose() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        List<String> closeOrder = new ArrayList<>();
        AssertionError fatal = new AssertionError("expected fatal cleanup failure");
        TrackingExecutor owned = new TrackingExecutor("owned", probe, closeOrder, null);
        TrackingExecutor freshOne = new TrackingExecutor("fresh-1", probe, closeOrder, null);
        TrackingExecutor freshTwo = new TrackingExecutor("fresh-2", probe, closeOrder, fatal);
        AtomicInteger creates = new AtomicInteger();
        AppEffectExecutorFactory rawFactory = new AppEffectExecutorFactory() {
            @Override public String scheme() { probe.check(); return "executor"; }
            @Override public List<AppEffectExecutor> create(
                    String chainId,
                    Map<String, String> config
            ) {
                probe.check();
                if (creates.incrementAndGet() == 1) {
                    return List.of(owned);
                }
                return List.of(freshOne, owned, freshTwo);
            }
        };
        AppEffectExecutorFactory factory = providerFacade(
                AppEffectExecutorFactory.class, ContributionKind.EFFECT_EXECUTOR,
                rawFactory, plugin, "executor-bundle", "executor");

        List<String> sinkCloseOrder = new ArrayList<>();
        AssertionError sinkFatal = new AssertionError("expected fatal sink cleanup failure");
        TrackingSink ownedSink = new TrackingSink("owned-sink", probe, sinkCloseOrder);
        TrackingSink freshSinkOne = new TrackingSink("fresh-sink-1", probe, sinkCloseOrder);
        TrackingSink freshSinkTwo = new TrackingSink(
                "fresh-sink-2", probe, sinkCloseOrder, sinkFatal);
        AtomicInteger sinkCreates = new AtomicInteger();
        FinalizedStreamSinkFactory rawSinkFactory = new FinalizedStreamSinkFactory() {
            @Override public String scheme() { probe.check(); return "sink"; }
            @Override public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                probe.check();
                if (sinkCreates.incrementAndGet() == 1) {
                    return List.of(ownedSink);
                }
                return List.of(freshSinkOne, ownedSink, freshSinkTwo);
            }
        };
        FinalizedStreamSinkFactory sinkFactory = providerFacade(
                FinalizedStreamSinkFactory.class, ContributionKind.FINALIZED_SINK,
                rawSinkFactory, plugin, "sink-bundle", "sink");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            AppEffectExecutor first = factory.create("chain-a", Map.of()).getFirst();
            Throwable failure = catchThrowable(() -> factory.create("chain-b", Map.of()));
            assertThat(failure)
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageNotContaining(fatal.getMessage())
                    .hasCause(fatal);
            assertThat(closeOrder).containsExactly("fresh-2", "fresh-1");
            assertThat(owned.closeCalls).hasValue(0);
            assertThat(freshOne.closeCalls).hasValue(1);
            assertThat(freshTwo.closeCalls).hasValue(1);
            assertErrorReuseCollision(fatal);

            FinalizedStreamSink firstSink = sinkFactory.create("chain-a", Map.of()).getFirst();
            Throwable sinkFailure = catchThrowable(
                    () -> sinkFactory.create("chain-b", Map.of()));
            assertThat(sinkFailure)
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageNotContaining(sinkFatal.getMessage())
                    .hasCause(sinkFatal);
            assertThat(sinkCloseOrder).containsExactly("fresh-sink-2", "fresh-sink-1");
            assertThat(ownedSink.closeCalls).hasValue(0);
            assertThat(freshSinkOne.closeCalls).hasValue(1);
            assertThat(freshSinkTwo.closeCalls).hasValue(1);
            assertErrorReuseCollision(sinkFatal);
            assertCaller(caller);
            first.close();
            firstSink.close();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void nodePluginMetadataLifecycleRestartAndCloseUsePluginTcclAndRestoreCaller() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader pluginLoader = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(pluginLoader);
        AssertingNodePlugin plugin = new AssertingNodePlugin(probe);

        Thread.currentThread().setContextClassLoader(caller);
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            PluginManager manager = new PluginManager(
                    new NoopEventBus(), scheduler, PluginsOptions.defaults(),
                    pluginLoader, List.of(plugin));
            manager.discoverAndInit();
            assertCaller(caller);
            manager.startAll();
            assertCaller(caller);
            manager.stopAll();
            assertCaller(caller);
            manager.startAll();
            manager.stopAll();
            manager.close();
            assertCaller(caller);

            assertThat(plugin.initCalls).hasValue(1);
            assertThat(plugin.startCalls).hasValue(2);
            assertThat(plugin.stopCalls).hasValue(2);
            assertThat(plugin.closeCalls).hasValue(1);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void pluginContextManagedCallbacksUsePluginTcclWithoutOwningSharedResources()
            throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader platform = new MarkerClassLoader(original);
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader pluginLoader = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(pluginLoader);
        ContextProbe platformProbe = new ContextProbe(platform);
        CapturingEventBus eventBus = new CapturingEventBus();
        eventBus.setPlatformListener(platformProbe::check);
        ScheduledExecutorService sharedScheduler =
                Executors.newSingleThreadScheduledExecutor();
        ContextContributionPlugin plugin = new ContextContributionPlugin(probe, rogue);
        Thread.currentThread().setContextClassLoader(platform);
        PluginManager manager = new PluginManager(
                eventBus, sharedScheduler, PluginsOptions.defaults(),
                pluginLoader, List.of(plugin));

        Thread.currentThread().setContextClassLoader(caller);
        try {
            manager.discoverAndInit();
            PluginContext context = plugin.context;

            // Callback resources are deliberately start-cycle scoped. This
            // prevents work queued before stop from being admitted after a
            // later generation resumes.
            assertThatThrownBy(() -> context.eventBus().subscribe(
                    TestEvent.class, ignored -> { }, SubscriptionOptions.builder().build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only during start/running");
            assertThatThrownBy(() -> context.scheduler().execute(() -> { }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only during start/running");

            manager.startAll();
            assertCaller(caller);
            assertThat(eventBus.completionClassLoader).isSameAs(platform);

            AtomicInteger listenerCalls = new AtomicInteger();
            AtomicInteger filterCalls = new AtomicInteger();
            context.eventBus().subscribe(TestEvent.class, event -> {
                probe.check();
                listenerCalls.incrementAndGet();
                Thread.currentThread().setContextClassLoader(rogue);
            }, SubscriptionOptions.builder().filter((TestEvent event, EventMetadata metadata) -> {
                probe.check();
                filterCalls.incrementAndGet();
                Thread.currentThread().setContextClassLoader(rogue);
                return true;
            }).build());

            assertThat(filterCalls).hasValue(0);
            assertThat(listenerCalls).hasValue(0);

            context.eventBus().publish(new TestEvent(), EventMetadata.builder().build(),
                    PublishOptions.builder().build());
            assertCaller(caller);
            assertThat(eventBus.completionClassLoader).isSameAs(platform);
            assertThat(filterCalls).hasValue(1);
            assertThat(listenerCalls).hasValue(1);
            assertThat(platformProbe.callbacks).hasValue(2);

            // A fatal failure after a platform listener corrupts its TCCL
            // must restore platform -> plugin -> caller in that exact nesting.
            eventBus.setPlatformListener(() -> {
                platformProbe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                throw new AssertionError("expected platform dispatch failure");
            });
            assertThatThrownBy(() -> PluginThreadContext.run(pluginLoader,
                    () -> context.eventBus().publish(
                            new TestEvent(), EventMetadata.builder().build(),
                            PublishOptions.builder().build())))
                    .isInstanceOf(AssertionError.class)
                    .hasMessage("expected platform dispatch failure");
            assertCaller(caller);
            assertThat(platformProbe.callbacks).hasValue(3);
            assertThat(filterCalls).hasValue(1);
            assertThat(listenerCalls).hasValue(1);

            assertThatThrownBy(context.eventBus()::close)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("shared EventBus");
            assertThat(eventBus.closeCalls).hasValue(0);

            ClassLoader workerContext = sharedScheduler.submit(
                    () -> Thread.currentThread().getContextClassLoader()).get();
            context.scheduler().submit(() -> {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
            }).get();
            int scheduledValue = context.scheduler().schedule(() -> {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return 42;
            }, 0, TimeUnit.MILLISECONDS).get();
            assertThat(scheduledValue).isEqualTo(42);
            assertThat(sharedScheduler.submit(
                    () -> Thread.currentThread().getContextClassLoader()).get())
                    .isSameAs(workerContext);
            assertThatThrownBy(context.scheduler()::shutdown)
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(context.scheduler()::shutdownNow)
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(context.scheduler()::close)
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(sharedScheduler.isShutdown()).isFalse();

            StorageFilter filter = manager.getStorageFilters().getFirst();
            assertThat(PluginContextFacades.storageFilterDelegate(filter))
                    .isSameAs(plugin.filter);
            assertThat(filter.priority()).isEqualTo(17);
            assertThat(filter.acceptUtxoOutput(null, null, null)).isTrue();
            assertCaller(caller);

            manager.close();
            assertCaller(caller);
        } finally {
            try {
                manager.close();
            } finally {
                eventBus.close();
                sharedScheduler.shutdownNow();
                Thread.currentThread().setContextClassLoader(original);
            }
        }
    }

    private static CatalogPluginProviderRegistry.Entry entry(
            String bundleId,
            ContributionKind kind,
            String name,
            Object provider,
            ContextProbe probe) {
        return new CatalogPluginProviderRegistry.Entry(
                bundleId, kind, name, provider.getClass().getName(), null, () -> {
                    probe.check();
                    return provider;
                });
    }

    private static <T> T providerFacade(
            Class<T> providerType,
            ContributionKind kind,
            T delegate,
            ClassLoader loader,
            String bundleId,
            String selector
    ) {
        return providerType.cast(PluginSpiFacades.provider(
                kind, delegate, loader, bundleId, selector, delegate.getClass().getName()));
    }

    private static AppEffectExecutorFactory executorFactory(
            String scheme,
            ContextProbe probe,
            List<AppEffectExecutor> products
    ) {
        return new AppEffectExecutorFactory() {
            @Override public String scheme() { probe.check(); return scheme; }
            @Override public List<AppEffectExecutor> create(
                    String chainId,
                    Map<String, String> config
            ) {
                probe.check();
                return products;
            }
        };
    }

    private static FinalizedStreamSinkFactory sinkFactory(
            String scheme,
            ContextProbe probe,
            List<FinalizedStreamSink> products
    ) {
        return new FinalizedStreamSinkFactory() {
            @Override public String scheme() { probe.check(); return scheme; }
            @Override public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                probe.check();
                return products;
            }
        };
    }

    private static PluginActivationException assertReuseFailure(
            ThrowingCallable callback,
            ContributionKind kind,
            String bundleId,
            String selector,
            Object rawProvider
    ) {
        PluginActivationException failure = assertActivationFailure(
                callback, kind, bundleId, selector, rawProvider);
        assertThat(failure.getCause())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already owned by a previous create invocation");
        return failure;
    }

    private static PluginActivationException assertActivationFailure(
            ThrowingCallable callback,
            ContributionKind kind,
            String bundleId,
            String selector,
            Object rawProvider
    ) {
        Throwable failure = catchThrowable(callback);
        assertThat(failure).isInstanceOf(PluginActivationException.class);
        PluginActivationException activation = (PluginActivationException) failure;
        assertThat(activation.bundleId()).isEqualTo(bundleId);
        assertThat(activation.contributionKind()).isEqualTo(kind.manifestKey());
        assertThat(activation.selector()).isEqualTo(selector);
        assertThat(activation.providerClass()).isEqualTo(rawProvider.getClass().getName());
        return activation;
    }

    private static void assertSecretSafeActivationFailure(
            ThrowingCallable callback,
            Throwable cause,
            String secret,
            ContributionKind kind,
            String bundleId,
            String selector,
            Object rawProvider
    ) {
        PluginActivationException activation = assertActivationFailure(
                callback, kind, bundleId, selector, rawProvider);
        assertThat(activation)
                .hasMessageNotContaining(secret)
                .hasCause(cause);
    }

    @SuppressWarnings("unchecked")
    private static <T, X extends Throwable> T sneakyThrow(Throwable failure) throws X {
        throw (X) failure;
    }

    private static void assertErrorReuseCollision(Error error) {
        assertThat(error.getSuppressed()).hasSize(1);
        assertThat(error.getSuppressed()[0])
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already owned by a previous create invocation");
    }

    private static void assertCaller(ClassLoader caller) {
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
    }

    private static byte[] filledBytes(int length, byte value) {
        byte[] result = new byte[length];
        java.util.Arrays.fill(result, value);
        return result;
    }

    private static WeakReference<Object> reserveEphemeralProduct(
            PluginSpiFacades.ProductReservations reservations
    ) {
        Object product = new Object();
        WeakReference<Object> reference = new WeakReference<>(product);
        reservations.facadeForNewInvocation(product, ignored -> new Object());
        return reference;
    }

    private static final class MarkerClassLoader extends ClassLoader {
        private MarkerClassLoader(ClassLoader parent) {
            super(parent);
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }

    private static final class ContextProbe {
        private final ClassLoader expected;
        private final AtomicInteger callbacks = new AtomicInteger();

        private ContextProbe(ClassLoader expected) {
            this.expected = expected;
        }

        private void check() {
            callbacks.incrementAndGet();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(expected);
        }
    }

    private static final class ProbeList<T> extends AbstractList<T> {
        private final ContextProbe probe;
        private final List<T> values;

        @SafeVarargs
        private ProbeList(ContextProbe probe, T... values) {
            this.probe = probe;
            this.values = List.of(values);
        }

        @Override public T get(int index) { probe.check(); return values.get(index); }
        @Override public int size() { probe.check(); return values.size(); }
    }

    private static final class ChangingTraversalList<T> extends AbstractList<T> {
        private final ContextProbe probe;
        private final List<T> firstTraversal;
        private final List<T> laterTraversals;
        private final AtomicInteger traversals = new AtomicInteger();

        private ChangingTraversalList(
                ContextProbe probe,
                List<T> firstTraversal,
                List<T> laterTraversals
        ) {
            this.probe = probe;
            this.firstTraversal = firstTraversal;
            this.laterTraversals = laterTraversals;
        }

        @Override
        public Iterator<T> iterator() {
            probe.check();
            List<T> values = traversals.incrementAndGet() == 1
                    ? firstTraversal : laterTraversals;
            Iterator<T> delegate = values.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() {
                    probe.check();
                    return delegate.hasNext();
                }

                @Override public T next() {
                    probe.check();
                    return delegate.next();
                }
            };
        }

        @Override public T get(int index) { throw new UnsupportedOperationException(); }
        @Override public int size() { throw new UnsupportedOperationException(); }

        private int traversals() {
            return traversals.get();
        }
    }

    private static final class FailingTraversalList<T> extends AbstractList<T> {
        private final ContextProbe probe;
        private final List<T> values;
        private final RuntimeException failure;

        private FailingTraversalList(
                ContextProbe probe,
                List<T> values,
                RuntimeException failure
        ) {
            this.probe = probe;
            this.values = values;
            this.failure = failure;
        }

        @Override
        public Iterator<T> iterator() {
            probe.check();
            Iterator<T> delegate = values.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    probe.check();
                    if (!delegate.hasNext()) {
                        throw failure;
                    }
                    return true;
                }

                @Override
                public T next() {
                    probe.check();
                    return delegate.next();
                }
            };
        }

        @Override public T get(int index) { throw new UnsupportedOperationException(); }
        @Override public int size() { throw new UnsupportedOperationException(); }
    }

    private static final class ProbeMap<K, V> extends AbstractMap<K, V> {
        private final ContextProbe probe;
        private final Map<K, V> values;

        private ProbeMap(ContextProbe probe, Map<K, V> values) {
            this.probe = probe;
            this.values = values;
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            probe.check();
            return values.entrySet();
        }
    }

    private static final class InfiniteProbeList<T> extends AbstractList<T> {
        private final ContextProbe probe;
        private final ClassLoader rogue;
        private final IntFunction<T> values;
        private final AtomicInteger hasNextCalls = new AtomicInteger();
        private final AtomicInteger nextCalls = new AtomicInteger();

        private InfiniteProbeList(
                ContextProbe probe,
                ClassLoader rogue,
                IntFunction<T> values
        ) {
            this.probe = probe;
            this.rogue = rogue;
            this.values = values;
        }

        @Override
        public Iterator<T> iterator() {
            checkAndPoison();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    checkAndPoison();
                    hasNextCalls.incrementAndGet();
                    return true;
                }

                @Override
                public T next() {
                    probe.check();
                    T value = values.apply(nextCalls.getAndIncrement());
                    Thread.currentThread().setContextClassLoader(rogue);
                    return value;
                }
            };
        }

        @Override public T get(int index) { throw new UnsupportedOperationException(); }
        @Override public int size() { throw new UnsupportedOperationException(); }

        private void checkAndPoison() {
            probe.check();
            Thread.currentThread().setContextClassLoader(rogue);
        }
    }

    private static final class InfiniteProbeMap extends AbstractMap<String, Object> {
        private final ContextProbe probe;
        private final ClassLoader rogue;
        private final AtomicInteger hasNextCalls = new AtomicInteger();
        private final AtomicInteger nextCalls = new AtomicInteger();

        private InfiniteProbeMap(ContextProbe probe, ClassLoader rogue) {
            this.probe = probe;
            this.rogue = rogue;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            checkAndPoison();
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    checkAndPoison();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            checkAndPoison();
                            hasNextCalls.incrementAndGet();
                            return true;
                        }

                        @Override
                        public Entry<String, Object> next() {
                            probe.check();
                            int value = nextCalls.getAndIncrement();
                            Thread.currentThread().setContextClassLoader(rogue);
                            return new Entry<>() {
                                @Override public String getKey() {
                                    checkAndPoison();
                                    return "duplicate-key";
                                }

                                @Override public Object getValue() {
                                    checkAndPoison();
                                    return value;
                                }

                                @Override public Object setValue(Object replacement) {
                                    throw new UnsupportedOperationException();
                                }
                            };
                        }
                    };
                }

                @Override public int size() { throw new UnsupportedOperationException(); }
            };
        }

        private void checkAndPoison() {
            probe.check();
            Thread.currentThread().setContextClassLoader(rogue);
        }
    }

    private record TestEvent() implements Event {
    }

    private static final class CapturingEventBus implements EventBus {
        private Class<? extends Event> type;
        private EventListener<? extends Event> listener;
        private SubscriptionOptions subscriptionOptions;
        private Runnable platformListener = () -> { };
        private ClassLoader completionClassLoader;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private void setPlatformListener(Runnable platformListener) {
            this.platformListener = platformListener;
        }

        @Override
        public <E extends Event> SubscriptionHandle subscribe(
                Class<E> type,
                EventListener<E> listener,
                SubscriptionOptions options) {
            this.type = type;
            this.listener = listener;
            this.subscriptionOptions = options;
            return new SubscriptionHandle() {
                private boolean active = true;

                @Override public void close() { active = false; }
                @Override public boolean isActive() { return active; }
            };
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public <E extends Event> void publish(
                E event,
                EventMetadata metadata,
                PublishOptions options) {
            platformListener.run();
            if (type == null || !type.isInstance(event)) {
                completionClassLoader = Thread.currentThread().getContextClassLoader();
                return;
            }
            if (subscriptionOptions != null && subscriptionOptions.filter() != null
                    && !subscriptionOptions.filter().test(event, metadata)) {
                return;
            }
            try {
                EventListener rawListener = listener;
                rawListener.onEvent(new EventContext<E>() {
                    @Override public E event() { return event; }
                    @Override public EventMetadata metadata() { return metadata; }
                });
                completionClassLoader = Thread.currentThread().getContextClassLoader();
            } catch (Exception e) {
                throw new IllegalStateException("Test event delivery failed", e);
            }
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class ContextContributionPlugin implements NodePlugin {
        private final ContextProbe probe;
        private final ClassLoader rogue;
        private final StorageFilter filter;
        private PluginContext context;

        private ContextContributionPlugin(ContextProbe probe, ClassLoader rogue) {
            this.probe = probe;
            this.rogue = rogue;
            this.filter = new StorageFilter() {
                @Override
                public boolean acceptUtxoOutput(
                        UtxoFilterContext ctx,
                        Block block,
                        TransactionBody txBody) {
                    probe.check();
                    Thread.currentThread().setContextClassLoader(rogue);
                    return true;
                }

                @Override
                public int priority() {
                    probe.check();
                    Thread.currentThread().setContextClassLoader(rogue);
                    return 17;
                }
            };
        }

        @Override public String id() { probe.check(); return "com.example.context-callbacks"; }
        @Override public String version() { probe.check(); return "1.0.0"; }
        @Override public void init(PluginContext context) {
            probe.check();
            this.context = context;
            context.registerStorageFilter(filter);
        }
        @Override public void start() {
            probe.check();
            context.eventBus().publish(new TestEvent(), EventMetadata.builder().build(),
                    PublishOptions.builder().build());
            probe.check();
        }
        @Override public void stop() { probe.check(); }
        @Override public void close() { probe.check(); }
    }

    private static final class AssertingStateMachineProvider implements AppStateMachineProvider {
        private final ContextProbe probe;

        private AssertingStateMachineProvider(ContextProbe probe) {
            this.probe = probe;
        }

        @Override public String id() { probe.check(); return "machine"; }
        @Override public AppStateMachine create() {
            probe.check();
            return new AssertingStateMachine(probe);
        }
        @Override public AppStateMachine create(AppStateMachineContext context) {
            probe.check();
            return new AssertingStateMachine(probe);
        }
    }

    private static final class AssertingStateMachine implements AppStateMachine {
        private final ContextProbe probe;

        private AssertingStateMachine(ContextProbe probe) { this.probe = probe; }

        @Override public String id() { probe.check(); return "machine"; }
        @Override public void init(AppStateReader state, AppChainInfo info) { probe.check(); }
        @Override public AdmissionResult validate(AppMessage message) {
            probe.check();
            return AdmissionResult.accept();
        }
        @Override public void apply(AppBlock block, AppStateWriter writer) { probe.check(); }
        @Override
        public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
            probe.check();
        }
        @Override
        public void onEffectResult(AppBlock block, EffectResult result, AppStateWriter writer) {
            probe.check();
        }
        @Override
        public void onEffectResult(
                AppBlock block,
                EffectResult result,
                AppStateWriter writer,
                AppEffectEmitter effects
        ) {
            probe.check();
        }
        @Override public byte[] query(String path, byte[] params) {
            probe.check();
            throw new IllegalStateException("expected query failure");
        }
        @Override public byte[] query(
                String path, byte[] params, AppQueryContext context
        ) {
            probe.check();
            return query(path, params);
        }
    }

    private static final class AssertingSequencerProvider implements SequencerModeProvider {
        private final ContextProbe probe;
        private final SequencerMode mode;

        private AssertingSequencerProvider(ContextProbe probe) {
            this.probe = probe;
            this.mode = new AssertingSequencerMode(probe);
        }

        @Override public String id() { probe.check(); return "sequencer"; }
        @Override public SequencerMode create(SequencerContext context) {
            probe.check();
            return mode;
        }
    }

    private static final class AssertingSequencerMode implements SequencerMode {
        private final ContextProbe probe;

        private AssertingSequencerMode(ContextProbe probe) { this.probe = probe; }

        @Override public String id() { probe.check(); return "sequencer"; }
        @Override public void init(SequencerContext context) { probe.check(); }
        @Override public boolean shouldProposeNow(long height) { probe.check(); return true; }
        @Override public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            probe.check();
            return ProposalEligibility.ACCEPT;
        }
        @Override public Map<String, Object> status() {
            probe.check();
            return new ProbeMap<>(probe, Map.of("mode", "ready"));
        }
    }

    private static final class FailingStartupSequencerMode implements SequencerMode {
        private final ContextProbe probe;

        private FailingStartupSequencerMode(ContextProbe probe) {
            this.probe = probe;
        }

        @Override public String id() {
            probe.check();
            throw new IllegalStateException("expected sequencer id failure");
        }
        @Override public void init(SequencerContext context) { probe.check(); }
        @Override public boolean shouldProposeNow(long height) { return false; }
        @Override public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            return ProposalEligibility.REJECT;
        }
        @Override public Map<String, Object> status() {
            probe.check();
            throw new IllegalStateException("expected sequencer status failure");
        }
    }

    private static final class AssertingObserverProvider implements L1ObserverProvider {
        private final ContextProbe probe;
        private final L1Observer observer;

        private AssertingObserverProvider(ContextProbe probe) {
            this.probe = probe;
            this.observer = new AssertingObserver(probe);
        }

        @Override public String type() { probe.check(); return "observer"; }
        @Override public L1Observer create(String observerId, Map<String, String> settings) {
            probe.check();
            return observer;
        }
    }

    private static final class AssertingObserver implements L1Observer {
        private final ContextProbe probe;

        private AssertingObserver(ContextProbe probe) { this.probe = probe; }

        @Override public String observerId() { probe.check(); return "instance"; }
        @Override public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
            probe.check();
            return new ProbeList<>(probe,
                    new L1Observation("instance", new byte[32], slot,
                            new byte[32], new byte[0]));
        }
        @Override public Map<String, Object> status() {
            probe.check();
            return new ProbeMap<>(probe, Map.of("observer", "ready"));
        }
    }

    private static final class FailingStartupObserver implements L1Observer {
        private final ContextProbe probe;

        private FailingStartupObserver(ContextProbe probe) {
            this.probe = probe;
        }

        @Override public String observerId() {
            probe.check();
            throw new IllegalStateException("expected observer id failure");
        }
        @Override public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
            return List.of();
        }
        @Override public Map<String, Object> status() {
            probe.check();
            throw new IllegalStateException("expected observer status failure");
        }
    }

    private static final class AssertingSignerFactory implements SignerProviderFactory {
        private final ContextProbe probe;
        private final SignerProvider signer;

        private AssertingSignerFactory(ContextProbe probe) {
            this.probe = probe;
            this.signer = new AssertingSigner(probe);
        }

        @Override public String scheme() { probe.check(); return "signer"; }
        @Override public SignerProvider create(String keyReference) {
            probe.check();
            return signer;
        }
    }

    private static final class AssertingSigner implements SignerProvider {
        private final ContextProbe probe;

        private AssertingSigner(ContextProbe probe) { this.probe = probe; }

        @Override public byte[] sign(byte[] message) { probe.check(); return new byte[64]; }
        @Override public byte[] publicKey() { probe.check(); return new byte[32]; }
        @Override public String publicKeyHex() { probe.check(); return "00".repeat(32); }
    }

    private static final class AssertingExecutorFactory implements AppEffectExecutorFactory {
        private final ContextProbe probe;
        private final AppEffectExecutor executor;

        private AssertingExecutorFactory(ContextProbe probe) {
            this.probe = probe;
            this.executor = new AssertingExecutor(probe);
        }

        @Override public String scheme() { probe.check(); return "executor"; }
        @Override public List<AppEffectExecutor> create(
                String chainId, Map<String, String> config) {
            probe.check();
            return new ProbeList<>(probe, executor, executor);
        }
    }

    private static final class AssertingExecutor implements AppEffectExecutor {
        private final ContextProbe probe;

        private AssertingExecutor(ContextProbe probe) { this.probe = probe; }

        @Override public String id() { probe.check(); return "executor"; }
        @Override public Set<String> effectTypes() { probe.check(); return Set.of("effect"); }
        @Override public EffectExecutorOperationalSnapshot operationalSnapshot() {
            probe.check();
            return new EffectExecutorOperationalSnapshot(
                    EffectExecutorOperationalSnapshot.Readiness.READY,
                    1, 1, 0, 0, 0,
                    EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_ONE_MINUTE,
                    EffectExecutorOperationalSnapshot.AgeBucket.NEVER,
                    EffectExecutorOperationalSnapshot.FailureCode.NONE);
        }
        @Override public boolean supports(String effectType) { probe.check(); return true; }
        @Override public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) {
            probe.check();
            return EffectExecution.confirmed(new byte[0]);
        }
        @Override public void close() { probe.check(); }
    }

    private static final class TrackingExecutor implements AppEffectExecutor {
        private final String id;
        private final ContextProbe probe;
        private final List<String> closeOrder;
        private final Throwable closeFailure;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private TrackingExecutor(
                String id,
                ContextProbe probe,
                List<String> closeOrder,
                Throwable closeFailure
        ) {
            this.id = id;
            this.probe = probe;
            this.closeOrder = closeOrder;
            this.closeFailure = closeFailure;
        }

        @Override public String id() { probe.check(); return id; }
        @Override public boolean supports(String effectType) { return true; }
        @Override public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) {
            return EffectExecution.confirmed(new byte[0]);
        }
        @Override public void close() {
            probe.check();
            closeCalls.incrementAndGet();
            closeOrder.add(id);
            if (closeFailure instanceof Error error) {
                throw error;
            }
            if (closeFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
        }
    }

    private static final class AssertingSinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        private final ContextProbe probe;
        private final FinalizedStreamSink sink;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private AssertingSinkFactory(ContextProbe probe) {
            this.probe = probe;
            this.sink = new AssertingSink(probe);
        }

        @Override public String scheme() { probe.check(); return "sink"; }
        @Override public List<FinalizedStreamSink> create(
                String chainId, Map<String, String> config) {
            probe.check();
            return new ProbeList<>(probe, sink, sink);
        }
        @Override public void close() { probe.check(); closeCalls.incrementAndGet(); }
    }

    private static final class AssertingSink implements FinalizedStreamSink {
        private final ContextProbe probe;

        private AssertingSink(ContextProbe probe) { this.probe = probe; }

        @Override public String id() { probe.check(); return "sink"; }
        @Override public boolean deliver(AppBlock block) throws Exception {
            probe.check();
            throw new IOException("expected delivery failure");
        }
        @Override public String legacyCursorKey() { probe.check(); return "old-sink"; }
        @Override public void close() { probe.check(); }
    }

    private static final class TrackingSink implements FinalizedStreamSink {
        private final String id;
        private final ContextProbe probe;
        private final List<String> closeOrder;
        private final Throwable closeFailure;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private TrackingSink(String id, ContextProbe probe, List<String> closeOrder) {
            this(id, probe, closeOrder, null);
        }

        private TrackingSink(
                String id,
                ContextProbe probe,
                List<String> closeOrder,
                Throwable closeFailure
        ) {
            this.id = id;
            this.probe = probe;
            this.closeOrder = closeOrder;
            this.closeFailure = closeFailure;
        }

        @Override public String id() { probe.check(); return id; }
        @Override public boolean deliver(AppBlock block) { return true; }
        @Override public void close() {
            probe.check();
            closeCalls.incrementAndGet();
            closeOrder.add(id);
            if (closeFailure instanceof Error error) {
                throw error;
            }
            if (closeFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
        }
    }

    private static final class AssertingNodePlugin implements NodePlugin {
        private final ContextProbe probe;
        private final AtomicInteger initCalls = new AtomicInteger();
        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private AssertingNodePlugin(ContextProbe probe) { this.probe = probe; }

        @Override public String id() { probe.check(); return "com.example.tccl"; }
        @Override public String version() { probe.check(); return "1.0.0"; }
        @Override public Set<String> dependsOn() { probe.check(); return Set.of(); }
        @Override public Set<PluginCapability> capabilities() {
            probe.check();
            return Set.of(PluginCapability.EVENT_CONSUMER);
        }
        @Override public void init(PluginContext ctx) { probe.check(); initCalls.incrementAndGet(); }
        @Override public void start() { probe.check(); startCalls.incrementAndGet(); }
        @Override public void stop() { probe.check(); stopCalls.incrementAndGet(); }
        @Override public void close() { probe.check(); closeCalls.incrementAndGet(); }
    }
}
