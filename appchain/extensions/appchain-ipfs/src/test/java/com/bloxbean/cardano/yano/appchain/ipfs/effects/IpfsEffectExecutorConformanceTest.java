package com.bloxbean.cardano.yano.appchain.ipfs.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.IpfsPinDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsPinClient;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsProviderException;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.PinState;
import com.bloxbean.cardano.yano.appchain.testkit.effects.CapturedLogObservation;
import com.bloxbean.cardano.yano.appchain.testkit.effects.EffectExecutorConformance;
import com.bloxbean.cardano.yano.appchain.testkit.effects.ExecutorConformanceSpec;
import com.bloxbean.cardano.yano.appchain.testkit.effects.ExecutorFixture;
import com.bloxbean.cardano.yano.appchain.testkit.effects.ExecutorScenario;
import com.bloxbean.cardano.yano.appchain.testkit.effects.ExternalProbe;
import com.bloxbean.cardano.yano.appchain.testkit.effects.FailedConstructionObservation;
import com.bloxbean.cardano.yano.appchain.testkit.effects.IdempotencyModel;
import com.bloxbean.cardano.yano.appchain.testkit.effects.PayloadCase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class IpfsEffectExecutorConformanceTest {

    @TestFactory
    Stream<DynamicNode> phaseOneConnectorContract() {
        return EffectExecutorConformance.tests(new IpfsConformanceSpec());
    }

    private static final class IpfsConformanceSpec implements ExecutorConformanceSpec {
        private static final Set<String> SECRETS = Set.of(
                "http://127.0.0.1:5001", "secret-ipfs-bearer-canary");

        @Override public String actionType() { return ConnectorTypes.IPFS_PIN; }

        @Override
        public byte[] validPayload() {
            return IpfsEffectTestSupport.command().encode();
        }

        @Override
        public List<PayloadCase> invalidPayloads() {
            byte[] unsupported = validPayload();
            unsupported[1] = 2;
            byte[] trailing = Arrays.copyOf(validPayload(), validPayload().length + 1);
            return List.of(
                    new PayloadCase("empty", new byte[0], ConnectorErrorCode.INVALID_PAYLOAD),
                    new PayloadCase("malformed", new byte[]{0},
                            ConnectorErrorCode.INVALID_PAYLOAD),
                    new PayloadCase("trailing", trailing,
                            ConnectorErrorCode.INVALID_PAYLOAD),
                    new PayloadCase("unsupported version", unsupported,
                            ConnectorErrorCode.UNSUPPORTED_VERSION));
        }

        @Override public Set<String> forbiddenSentinels() { return SECRETS; }
        @Override public IdempotencyModel idempotencyModel() {
            return IdempotencyModel.IDEMPOTENT_SET;
        }

        @Override
        public boolean supports(ExecutorScenario scenario) {
            return scenario != ExecutorScenario.EXISTING_CONFLICT;
        }

        @Override
        public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
            return new IpfsFixture(scenario, payload, new ProviderState());
        }

        @Override
        public ExecutorFixture restart(ExecutorFixture previous,
                                       ExecutorScenario scenario,
                                       byte[] payload) {
            ProviderState state = (ProviderState) previous.probe();
            state.revealPending();
            return new IpfsFixture(scenario, payload, state);
        }

        @Override
        public FailedConstructionObservation failedConstruction() {
            ProviderState state = new ProviderState();
            ConformanceClient acquired = new ConformanceClient(
                    ExecutorScenario.SUCCESS, state);
            Throwable failure = new IllegalArgumentException(
                    "ipfs effect executor construction failed");
            acquired.close();
            return new FailedConstructionObservation(failure, 1, state.closeCalls(),
                    IpfsEffectTestSupport.config().safeDiagnostics(),
                    CapturedLogObservation.active(List.of()));
        }

        @Override
        public void verifyReceipt(byte[] canonicalExternalRef) {
            IpfsPinReceiptV1 receipt = IpfsPinReceiptV1.decode(canonicalExternalRef);
            Assertions.assertArrayEquals(canonicalExternalRef, receipt.encode());
        }

        @Override
        public void verifySubmittedRef(byte[] canonicalSubmittedRef) {
            verifyReceipt(canonicalSubmittedRef);
        }

        @Override
        public void verifyReceiptDetailConsistency(byte[] validPayload,
                                                   byte[] canonicalExternalRef,
                                                   ConnectorDetailDocumentV1 detailDocument) {
            IpfsPinCommandV1 command = IpfsPinCommandV1.decode(validPayload);
            IpfsPinReceiptV1 receipt = IpfsPinReceiptV1.decode(canonicalExternalRef);
            IpfsPinDetailV1 detail = Assertions.assertInstanceOf(
                    IpfsPinDetailV1.class, detailDocument.data());
            Assertions.assertArrayEquals(receipt.targetFingerprint(),
                    detail.targetFingerprint());
            Assertions.assertEquals(command.cid(), detail.cid());
            Assertions.assertTrue(detail.recursive());
            Assertions.assertNull(detail.providerReference());
        }

        @Override public Duration closeTimeout() { return Duration.ofSeconds(2); }
    }

    private static final class IpfsFixture implements ExecutorFixture {
        private final PendingEffect effect;
        private final ProviderState state;
        private final ConformanceClient client;
        private final IpfsEffectTestSupport.MemoryDetailArchive archive =
                new IpfsEffectTestSupport.MemoryDetailArchive();
        private final IpfsPinExecutor executor;

        private IpfsFixture(ExecutorScenario scenario,
                            byte[] payload,
                            ProviderState state) {
            this.effect = IpfsEffectTestSupport.effect(payload);
            this.state = state;
            state.initialize(scenario);
            this.client = new ConformanceClient(scenario, state);
            this.executor = new IpfsPinExecutor(IpfsEffectTestSupport.config(),
                    ignored -> () -> client, archive);
        }

        @Override public AppEffectExecutor executor() { return executor; }
        @Override public PendingEffect effect() { return effect; }
        @Override public ExternalProbe probe() { return state; }

        @Override
        public EffectExecutionContext context(int attempt, byte[] submittedRef) {
            return IpfsEffectTestSupport.context(attempt, submittedRef);
        }

        @Override
        public Map<String, Object> runtimeStats() {
            return Map.of(
                    "queueDepth", 0L,
                    "inFlight", 0L,
                    "resultBacklog", 0L,
                    "statusCounts", Map.of("DONE", 1L),
                    "executionTotals", Map.of("confirmed", 1L, "failed", 0L,
                            "parked", 0L),
                    "resultBacklogByType", Map.of(ConnectorTypes.IPFS_PIN, 0L),
                    "latencyByType", Map.of(ConnectorTypes.IPFS_PIN,
                            Map.of("count", 1L, "totalMillis", 1L)),
                    "executors", List.of("ipfs-pin"));
        }

        @Override public Map<String, Object> runtimeStatus() {
            return Map.of("status", "DONE", "attempts", 1L);
        }
        @Override public CapturedLogObservation capturedLogs() {
            return CapturedLogObservation.active(List.of());
        }
        @Override public void closeLogCapture() { }
        @Override public Optional<byte[]> archivedDetail(byte[] hash) {
            return archive.bytes(hash);
        }
        @Override public boolean awaitBlocked(Duration timeout) throws InterruptedException {
            return client.blocked.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public void unblock() { client.release.countDown(); }

        @Override
        public void close() {
            executor.close();
            // Invalid payload and lifecycle-only fixtures may never lazily
            // transfer ownership to the executor.
            client.close();
        }
    }

    private static final class ConformanceClient implements IpfsPinClient {
        private final ExecutorScenario scenario;
        private final ProviderState state;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private ConformanceClient(ExecutorScenario scenario, ProviderState state) {
            this.scenario = scenario;
            this.state = state;
        }

        @Override
        public PinState probe(com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid cid,
                              byte[] effectIdHash) {
            call(effectIdHash);
            if (scenario == ExecutorScenario.BLOCKED_CALL) {
                blocked.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IpfsProviderException(ConnectorErrorCode.SHUTDOWN);
                }
                if (closed.get()) {
                    throw new IpfsProviderException(ConnectorErrorCode.SHUTDOWN);
                }
            }
            if (scenario == ExecutorScenario.TRANSIENT_THEN_SUCCESS
                    && state.transientFailures.getAndUpdate(
                    value -> Math.max(0, value - 1)) > 0) {
                throw new IpfsProviderException(ConnectorErrorCode.SERVICE_UNAVAILABLE);
            }
            return state.visible;
        }

        @Override
        public void add(com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid cid,
                        boolean recursive,
                        byte[] effectIdHash) {
            call(effectIdHash);
            PinState created = recursive ? PinState.RECURSIVE : PinState.DIRECT;
            state.mutations.incrementAndGet();
            if (scenario == ExecutorScenario.UNKNOWN_ACK_THEN_RECONCILE) {
                state.pending = created;
                throw new IpfsProviderException(ConnectorErrorCode.ACK_UNKNOWN);
            }
            state.visible = created;
        }

        private void call(byte[] effectIdHash) {
            if (closed.get()) {
                throw new IpfsProviderException(ConnectorErrorCode.SHUTDOWN);
            }
            state.recordCall(effectIdHash);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.closeCalls.incrementAndGet();
                release.countDown();
            }
        }
    }

    private static final class ProviderState implements ExternalProbe {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger mutations = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger transientFailures = new AtomicInteger();
        private final List<byte[]> keys = Collections.synchronizedList(new ArrayList<>());
        private volatile PinState visible = PinState.ABSENT;
        private volatile PinState pending;
        private volatile boolean initialized;

        synchronized void initialize(ExecutorScenario scenario) {
            if (initialized) {
                return;
            }
            initialized = true;
            if (scenario == ExecutorScenario.TRANSIENT_THEN_SUCCESS) {
                transientFailures.set(1);
            } else if (scenario == ExecutorScenario.EXISTING_MATCH) {
                visible = PinState.RECURSIVE;
            }
        }

        synchronized void revealPending() {
            if (pending != null) {
                visible = pending;
                pending = null;
            }
        }

        void recordCall(byte[] key) {
            keys.add(key.clone());
            calls.incrementAndGet();
        }

        @Override public int calls() { return calls.get(); }
        @Override public int logicalMutations() { return mutations.get(); }
        @Override public int closeCalls() { return closeCalls.get(); }
        @Override public List<byte[]> observedIdempotencyKeys() {
            synchronized (keys) {
                return keys.stream().map(byte[]::clone).toList();
            }
        }
        @Override public Map<String, Object> diagnostics() {
            return Map.of("provider", "fake-ipfs", "calls", calls(),
                    "mutations", logicalMutations());
        }
    }
}
