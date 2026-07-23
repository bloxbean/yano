package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventContext;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.events.AppChainAnchoredEvent;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Focused regression coverage for the generation-bound L1 callback pipeline. */
@Timeout(30)
class AppChainL1CallbackIsolationTest {

    private static final String MODE_ID = "controlled-l1-mode";
    private static final String OBSERVER_TYPE = "controlled-l1-observer";
    private static final String OBSERVER_ID = "controlled";
    private static final String ANCHOR_TX_HASH = "ab".repeat(32);
    private static final String PHASE_WARNING =
            "App-chain L1 {} failed (errorType={})";
    private static final byte[] SIGNING_KEY = fill(32, 73);
    private static final String SIGNING_KEY_HEX = HexUtil.encodeHexString(SIGNING_KEY);
    private static final String PUBLIC_KEY = HexUtil.encodeHexString(
            KeyGenUtil.getPublicKeyFromPrivateKey(SIGNING_KEY));

    @TempDir
    Path tempDir;

    @Test
    void observationPhaseAndRecoverableDiagnosticErrorsDoNotSkipOneTimeConfirmation()
            throws Exception {
        Controls controls = new Controls();
        Logger logger = mock(Logger.class);
        AssertionError phaseFailure = new AssertionError("observer phase failure");
        AssertionError diagnosticFailure = new AssertionError("logger backend failure");
        doThrow(diagnosticFailure).when(logger).warn(
                PHASE_WARNING, "observation", AssertionError.class.getName());

        try (StartedHarness harness = startHarness("recoverable", controls, logger)) {
            harness.publish(applied(100, emptyBlock()));
            controls.statusFailure.set(phaseFailure);

            harness.publish(applied(101, blockWithTx(ANCHOR_TX_HASH)));

            assertThat(anchorHeight(harness.subsystem)).isEqualTo(1);
            assertThat(harness.anchoredEvents()).hasSize(1);
            verify(logger).warn(PHASE_WARNING, "observation",
                    AssertionError.class.getName());

            // Replaying the inclusion event must not emit a second confirmation.
            harness.publish(applied(101, blockWithTx(ANCHOR_TX_HASH)));
            assertThat(harness.anchoredEvents()).hasSize(1);
        }
    }

    @Test
    void phaseTwoInvalidTransactionCannotConfirmAnchor() throws Exception {
        Controls controls = new Controls();
        Logger logger = mock(Logger.class);

        try (StartedHarness harness = startHarness("invalid-anchor-tx", controls, logger)) {
            harness.publish(applied(100, emptyBlock()));

            harness.publish(applied(101, blockWithInvalidTx(ANCHOR_TX_HASH)));
            assertThat(anchorHeight(harness.subsystem)).isZero();
            assertThat(harness.anchoredEvents()).isEmpty();

            harness.publish(applied(102, blockWithTx(ANCHOR_TX_HASH)));
            assertThat(anchorHeight(harness.subsystem)).isEqualTo(1);
            assertThat(harness.anchoredEvents()).hasSize(1);
        }
    }

    @Test
    void processFatalObservationPhaseRunsBeforeDiagnosticsAndLeavesAnchorForReplay()
            throws Exception {
        Controls controls = new Controls();
        Logger logger = mock(Logger.class);
        TestVirtualMachineError fatal = new TestVirtualMachineError();

        try (StartedHarness harness = startHarness("fatal-phase", controls, logger)) {
            harness.publish(applied(100, emptyBlock()));
            controls.statusFailure.set(fatal);

            assertThatThrownBy(() ->
                    harness.publish(applied(101, blockWithTx(ANCHOR_TX_HASH))))
                    .isSameAs(fatal);

            assertThat(anchorHeight(harness.subsystem)).isZero();
            assertThat(harness.anchoredEvents()).isEmpty();
            verify(logger, never()).warn(PHASE_WARNING, "observation",
                    TestVirtualMachineError.class.getName());

            harness.publish(applied(101, blockWithTx(ANCHOR_TX_HASH)));
            assertThat(anchorHeight(harness.subsystem)).isEqualTo(1);
            assertThat(harness.anchoredEvents()).hasSize(1);
        }
    }

    @Test
    void processFatalDiagnosticFailureEscapesAndLeavesAnchorForReplay()
            throws Exception {
        Controls controls = new Controls();
        Logger logger = mock(Logger.class);
        TestVirtualMachineError fatalDiagnostic = new TestVirtualMachineError();
        AtomicBoolean failDiagnostic = new AtomicBoolean(true);
        doAnswer(ignored -> {
            if (failDiagnostic.getAndSet(false)) {
                throw fatalDiagnostic;
            }
            return null;
        }).when(logger).warn(PHASE_WARNING, "observation",
                AssertionError.class.getName());

        try (StartedHarness harness = startHarness("fatal-logger", controls, logger)) {
            harness.publish(applied(100, emptyBlock()));
            controls.statusFailure.set(new AssertionError("observer phase failure"));

            assertThatThrownBy(() ->
                    harness.publish(applied(101, blockWithTx(ANCHOR_TX_HASH))))
                    .isSameAs(fatalDiagnostic);

            assertThat(anchorHeight(harness.subsystem)).isZero();
            assertThat(harness.anchoredEvents()).isEmpty();

            harness.publish(applied(101, blockWithTx(ANCHOR_TX_HASH)));
            assertThat(anchorHeight(harness.subsystem)).isEqualTo(1);
            assertThat(harness.anchoredEvents()).hasSize(1);
        }
    }

    @Test
    void admittedOldGenerationCallbackKeepsCapturedServicesAndFencesRestart()
            throws Exception {
        Controls controls = new Controls();
        Logger logger = mock(Logger.class);
        ExecutorService publisher = Executors.newSingleThreadExecutor();
        StartedHarness harness = startHarness("generation", controls, logger);
        try {
            harness.publish(applied(100, emptyBlock()));
            controls.blockingSlot.set(101);
            Future<?> callback = publisher.submit(() ->
                    harness.publish(applied(101, blockWithTx(ANCHOR_TX_HASH))));
            assertThat(controls.observationEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // stop unpublishes the fields and closes both subscription handles,
            // but the already-admitted callback still owns its captured services.
            harness.subsystem.stop();
            assertThat(harness.subsystem.status()).containsEntry("running", false);
            assertThatThrownBy(harness.subsystem::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still draining");

            controls.releaseObservation.countDown();
            callback.get(5, TimeUnit.SECONDS);
            assertThat(harness.anchoredEvents()).hasSize(1);

            startAfterDrain(harness.subsystem);
            assertThat(controls.observerInstances).hasValue(2);
            assertThat(anchorHeight(harness.subsystem)).isEqualTo(1);
        } finally {
            controls.releaseObservation.countDown();
            harness.close();
            publisher.shutdownNow();
        }
    }

    @Test
    void delayedRetiredCallbackCannotEnterReplacementGeneration()
            throws Exception {
        Controls controls = new Controls();
        Logger logger = mock(Logger.class);
        ExecutorService publisher = Executors.newSingleThreadExecutor();
        StartedHarness harness = startHarness("delayed-retired", controls, logger);
        try {
            // Pause after the event bus has accepted the OLD subscription but
            // before its listener can acquire a subsystem generation lease.
            harness.eventBus.pauseNextAppliedDispatch();
            Future<?> delayed = publisher.submit(() ->
                    harness.publish(applied(501, blockWithTx(ANCHOR_TX_HASH))));
            assertThat(harness.eventBus.appliedDispatchPassedActiveCheck
                    .await(5, TimeUnit.SECONDS)).isTrue();

            harness.subsystem.stop();
            startAfterDrain(harness.subsystem);
            assertThat(controls.observerInstances).hasValue(2);

            // The retired callback now reaches the listener while the new
            // generation is RUNNING. Its captured token must reject admission
            // before reference tracking, old services, or the new engine/pool.
            harness.eventBus.releasePausedAppliedDispatch.countDown();
            delayed.get(5, TimeUnit.SECONDS);

            assertThat(controls.observerCalls).hasValue(0);
            assertThat(harness.anchoredEvents()).isEmpty();
            Map<?, ?> observers = (Map<?, ?>) harness.subsystem.status().get("observers");
            assertThat(observers.get("windowSlots")).isEqualTo("empty");
        } finally {
            harness.eventBus.releasePausedAppliedDispatch.countDown();
            harness.close();
            publisher.shutdownNow();
        }
    }

    private StartedHarness startHarness(String testId, Controls controls, Logger logger)
            throws Exception {
        DirectEventBus eventBus = new DirectEventBus();
        AppChainConfig config = AppChainConfig.builder("l1-callback-" + testId)
                .signingKeyHex(SIGNING_KEY_HEX)
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .threshold(1)
                .blockIntervalMs(25)
                .l1StabilityDepth(1)
                .anchor(new AppChainConfig.AnchorConfig(
                        true, SIGNING_KEY_HEX, 1, 60, 7014))
                .pluginSettings(Map.of(
                        "sequencer.mode", MODE_ID,
                        "observers." + OBSERVER_ID + ".type", OBSERVER_TYPE))
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(
                config, 42, eventBus, null, tempDir.resolve(testId).toString(),
                null, new ControlledRegistry(controls), logger);
        subsystem.wireL1(ignored -> ANCHOR_TX_HASH,
                () -> new FixedUtxoState(List.of(anchorUtxo())));
        try {
            subsystem.start();
            subsystem.submit("test", new byte[]{1});
            awaitTip(subsystem, 1);
            assertThat(subsystem.forceAnchor()).isTrue();
            return new StartedHarness(subsystem, eventBus);
        } catch (Exception | Error failure) {
            subsystem.stop();
            throw failure;
        }
    }

    private static void awaitTip(AppChainSubsystem subsystem, long expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (subsystem.tipHeight() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("App-chain tip did not reach " + expected);
    }

    private static void startAfterDrain(AppChainSubsystem subsystem)
            throws InterruptedException {
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
        throw new AssertionError("Old L1 callback generation did not drain", lastDraining);
    }

    private static long anchorHeight(AppChainSubsystem subsystem) {
        Object rawAnchor = subsystem.status().get("anchor");
        assertThat(rawAnchor).isInstanceOf(Map.class);
        Object value = ((Map<?, ?>) rawAnchor).get("lastAnchoredHeight");
        assertThat(value).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    private static BlockAppliedEvent applied(long slot, Block block) {
        return new BlockAppliedEvent(null, slot, slot,
                HexUtil.encodeHexString(fill(32, Math.toIntExact(slot))), block);
    }

    private static Block emptyBlock() {
        return Block.builder().transactionBodies(List.of())
                .invalidTransactions(List.of()).build();
    }

    private static Block blockWithTx(String txHash) {
        return Block.builder()
                .transactionBodies(List.of(TransactionBody.builder().txHash(txHash).build()))
                .invalidTransactions(List.of())
                .build();
    }

    private static Block blockWithInvalidTx(String txHash) {
        return Block.builder()
                .transactionBodies(List.of(TransactionBody.builder().txHash(txHash).build()))
                .invalidTransactions(List.of(0))
                .build();
    }

    private static Utxo anchorUtxo() {
        return new Utxo(new Outpoint("cd".repeat(32), 0), "addr_test",
                BigInteger.valueOf(50_000_000), List.of(), null, null,
                null, null, false, 0, 0, null);
    }

    private static byte[] fill(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
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

    private static final class Controls {
        private final AtomicReference<Throwable> statusFailure = new AtomicReference<>();
        private final AtomicLong blockingSlot = new AtomicLong(-1);
        private final CountDownLatch observationEntered = new CountDownLatch(1);
        private final CountDownLatch releaseObservation = new CountDownLatch(1);
        private final AtomicInteger observerInstances = new AtomicInteger();
        private final AtomicInteger observerCalls = new AtomicInteger();
    }

    private static final class ControlledRegistry implements PluginProviderRegistry {
        private final Controls controls;
        private final SequencerModeProvider modeProvider;
        private final L1ObserverProvider observerProvider;

        private ControlledRegistry(Controls controls) {
            this.controls = controls;
            this.modeProvider = new SequencerModeProvider() {
                @Override public String id() { return MODE_ID; }
                @Override public SequencerMode create(SequencerContext context) {
                    return new ControlledMode(controls);
                }
            };
            this.observerProvider = new L1ObserverProvider() {
                @Override public String type() { return OBSERVER_TYPE; }
                @Override
                public L1Observer create(String observerId, Map<String, String> settings) {
                    controls.observerInstances.incrementAndGet();
                    return new ControlledObserver(observerId, controls);
                }
            };
        }

        @Override
        public <P> Optional<P> find(Class<P> providerType, String selector) {
            if (providerType == SequencerModeProvider.class && MODE_ID.equals(selector)) {
                return Optional.of(providerType.cast(modeProvider));
            }
            if (providerType == L1ObserverProvider.class && OBSERVER_TYPE.equals(selector)) {
                return Optional.of(providerType.cast(observerProvider));
            }
            return Optional.empty();
        }

        @Override
        public <P> List<String> names(Class<P> providerType) {
            if (providerType == SequencerModeProvider.class) {
                return List.of(MODE_ID);
            }
            if (providerType == L1ObserverProvider.class) {
                return List.of(OBSERVER_TYPE);
            }
            return List.of();
        }
    }

    private static final class ControlledMode implements SequencerMode {
        private final Controls controls;
        private String selfKey;

        private ControlledMode(Controls controls) {
            this.controls = controls;
        }

        @Override public String id() { return MODE_ID; }

        @Override
        public void init(SequencerContext context) {
            selfKey = context.selfKeyHex();
        }

        @Override public boolean shouldProposeNow(long height) { return true; }

        @Override
        public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            return ProposalEligibility.ACCEPT;
        }

        @Override
        public Map<String, Object> status() {
            Throwable failure = controls.statusFailure.getAndSet(null);
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
            return Map.of("currentProposer", selfKey);
        }
    }

    private static final class ControlledObserver implements L1Observer {
        private final String observerId;
        private final Controls controls;

        private ControlledObserver(String observerId, Controls controls) {
            this.observerId = observerId;
            this.controls = controls;
        }

        @Override public String observerId() { return observerId; }

        @Override
        public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
            controls.observerCalls.incrementAndGet();
            if (controls.blockingSlot.get() == slot) {
                controls.observationEntered.countDown();
                awaitUninterruptibly(controls.releaseObservation);
            }
            return List.of(new L1Observation(observerId, fill(32, Math.toIntExact(slot)),
                    slot, blockHash.clone(), new byte[]{1}));
        }
    }

    private record FixedUtxoState(List<Utxo> utxos) implements UtxoState {
        @Override
        public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
            return utxos;
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(
                String credential, int page, int pageSize) {
            return utxos;
        }

        @Override public Optional<Utxo> getUtxo(Outpoint outpoint) { return Optional.empty(); }

        @Override public boolean isEnabled() { return true; }
    }

    private static final class DirectEventBus implements EventBus {
        private final Map<Class<?>, CopyOnWriteArrayList<DirectSubscription<?>>> subscribers =
                new ConcurrentHashMap<>();
        private final List<Event> published = new CopyOnWriteArrayList<>();
        private final AtomicBoolean pauseNextAppliedDispatch = new AtomicBoolean();
        private final CountDownLatch appliedDispatchPassedActiveCheck = new CountDownLatch(1);
        private final CountDownLatch releasePausedAppliedDispatch = new CountDownLatch(1);

        private void pauseNextAppliedDispatch() {
            pauseNextAppliedDispatch.set(true);
        }

        @Override
        public <E extends Event> SubscriptionHandle subscribe(
                Class<E> eventType,
                EventListener<E> listener,
                SubscriptionOptions options
        ) {
            CopyOnWriteArrayList<DirectSubscription<?>> typeSubscribers =
                    subscribers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>());
            DirectSubscription<E> subscription = new DirectSubscription<>(listener);
            typeSubscribers.add(subscription);
            return new SubscriptionHandle() {
                @Override
                public void close() {
                    subscription.active.set(false);
                    typeSubscribers.remove(subscription);
                }

                @Override public boolean isActive() { return subscription.active.get(); }
            };
        }

        @Override
        public <E extends Event> void publish(
                E event,
                EventMetadata metadata,
                PublishOptions options
        ) {
            published.add(event);
            List<DirectSubscription<?>> current = subscribers.get(event.getClass());
            if (current == null) {
                return;
            }
            for (DirectSubscription<?> raw : current) {
                dispatch(event, metadata, raw);
            }
        }

        private <E extends Event> void dispatch(
                E event,
                EventMetadata metadata,
                DirectSubscription<?> raw
        ) {
            @SuppressWarnings("unchecked")
            DirectSubscription<E> subscription = (DirectSubscription<E>) raw;
            if (!subscription.active.get()) {
                return;
            }
            if (event instanceof BlockAppliedEvent
                    && pauseNextAppliedDispatch.compareAndSet(true, false)) {
                appliedDispatchPassedActiveCheck.countDown();
                awaitUninterruptibly(releasePausedAppliedDispatch);
            }
            try {
                subscription.listener.onEvent(new EventContext<>() {
                    @Override public E event() { return event; }
                    @Override public EventMetadata metadata() { return metadata; }
                });
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Checked event-listener failure", failure);
            }
        }

        @Override
        public void close() {
            subscribers.values().forEach(list ->
                    list.forEach(subscription -> subscription.active.set(false)));
            subscribers.clear();
        }
    }

    private static final class DirectSubscription<E extends Event> {
        private final EventListener<E> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private DirectSubscription(EventListener<E> listener) {
            this.listener = listener;
        }
    }

    private record StartedHarness(
            AppChainSubsystem subsystem,
            DirectEventBus eventBus
    ) implements AutoCloseable {
        private void publish(Event event) {
            eventBus.publish(event, EventMetadata.builder().build(),
                    PublishOptions.builder().build());
        }

        private List<AppChainAnchoredEvent> anchoredEvents() {
            return eventBus.published.stream()
                    .filter(AppChainAnchoredEvent.class::isInstance)
                    .map(AppChainAnchoredEvent.class::cast)
                    .toList();
        }

        @Override public void close() { subsystem.stop(); }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
    }
}
