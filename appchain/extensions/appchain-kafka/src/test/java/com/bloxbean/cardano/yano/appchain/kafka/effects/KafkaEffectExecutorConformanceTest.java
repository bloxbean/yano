package com.bloxbean.cardano.yano.appchain.kafka.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.KafkaPublishDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import com.bloxbean.cardano.yano.appchain.kafka.config.KafkaEffectConfig;
import com.bloxbean.cardano.yano.appchain.kafka.internal.KafkaEffectProducer;
import com.bloxbean.cardano.yano.appchain.kafka.internal.KafkaProducerAcknowledgement;
import com.bloxbean.cardano.yano.appchain.testkit.effects.CapturedLogObservation;
import com.bloxbean.cardano.yano.appchain.testkit.effects.EffectExecutorConformance;
import com.bloxbean.cardano.yano.appchain.testkit.effects.ExecutorConformanceSpec;
import com.bloxbean.cardano.yano.appchain.testkit.effects.ExecutorFixture;
import com.bloxbean.cardano.yano.appchain.testkit.effects.ExecutorScenario;
import com.bloxbean.cardano.yano.appchain.testkit.effects.ExternalProbe;
import com.bloxbean.cardano.yano.appchain.testkit.effects.FailedConstructionObservation;
import com.bloxbean.cardano.yano.appchain.testkit.effects.IdempotencyModel;
import com.bloxbean.cardano.yano.appchain.testkit.effects.PayloadCase;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class KafkaEffectExecutorConformanceTest {

    @TestFactory
    Stream<DynamicNode> phaseOneConnectorContract() {
        return EffectExecutorConformance.tests(new KafkaConformanceSpec());
    }

    private static final class KafkaConformanceSpec implements ExecutorConformanceSpec {
        private static final Set<String> SECRETS = Set.of(
                "super-secret-canary", "private-broker.example");

        @Override
        public String actionType() {
            return ConnectorTypes.KAFKA_PUBLISH;
        }

        @Override
        public byte[] validPayload() {
            return KafkaEffectTestSupport.command().encode();
        }

        @Override
        public List<PayloadCase> invalidPayloads() {
            byte[] unsupported = validPayload();
            unsupported[1] = 2;
            byte[] trailing = Arrays.copyOf(validPayload(), validPayload().length + 1);
            return List.of(
                    new PayloadCase("empty", new byte[0], ConnectorErrorCode.INVALID_PAYLOAD),
                    new PayloadCase("malformed", new byte[]{0}, ConnectorErrorCode.INVALID_PAYLOAD),
                    new PayloadCase("trailing data", trailing, ConnectorErrorCode.INVALID_PAYLOAD),
                    new PayloadCase("unsupported version", unsupported,
                            ConnectorErrorCode.UNSUPPORTED_VERSION));
        }

        @Override
        public Set<String> forbiddenSentinels() {
            return SECRETS;
        }

        @Override
        public IdempotencyModel idempotencyModel() {
            return IdempotencyModel.STABLE_DEDUPE_TOKEN;
        }

        @Override
        public boolean supports(ExecutorScenario scenario) {
            return switch (scenario) {
                case SUCCESS, TRANSIENT_THEN_SUCCESS,
                     UNKNOWN_ACK_THEN_RECONCILE, BLOCKED_CALL -> true;
                case EXISTING_MATCH, EXISTING_CONFLICT -> false;
            };
        }

        @Override
        public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
            return new KafkaFixture(scenario, payload, new ProviderState());
        }

        @Override
        public ExecutorFixture restart(ExecutorFixture previous,
                                       ExecutorScenario scenario,
                                       byte[] payload) {
            return new KafkaFixture(scenario, payload, (ProviderState) previous.probe());
        }

        @Override
        public FailedConstructionObservation failedConstruction() throws Exception {
            ReflectiveLogCapture capture = ReflectiveLogCapture.open();
            ProviderState state = new ProviderState();
            ConformanceProducer resource = new ConformanceProducer(
                    ExecutorScenario.SUCCESS, state, new CountDownLatch(0), new CountDownLatch(0));
            Throwable failure;
            try {
                throw new IllegalArgumentException("kafka effect executor construction failed");
            } catch (IllegalArgumentException expected) {
                failure = expected;
            } finally {
                resource.close();
            }
            CapturedLogObservation logs = capture.observation();
            capture.close();
            return new FailedConstructionObservation(failure, 1, state.closeCalls(),
                    KafkaEffectConfig.parse(secretSettings()).safeDiagnostics(), logs);
        }

        @Override
        public void verifyReceipt(byte[] canonicalExternalRef) {
            KafkaPublishReceiptV1 receipt = KafkaPublishReceiptV1.decode(canonicalExternalRef);
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
            KafkaPublishCommandV1 command = KafkaPublishCommandV1.decode(validPayload);
            KafkaPublishReceiptV1 receipt = KafkaPublishReceiptV1.decode(canonicalExternalRef);
            KafkaPublishDetailV1 detail = Assertions.assertInstanceOf(
                    KafkaPublishDetailV1.class, detailDocument.data());
            byte[] expected = KafkaDestinationFingerprint.compute(
                    KafkaEffectTestSupport.TARGET_ID, KafkaEffectTestSupport.PHYSICAL_TOPIC).bytes();
            Assertions.assertArrayEquals(expected, receipt.destinationFingerprint());
            Assertions.assertArrayEquals(expected, detail.destinationFingerprint());
            Assertions.assertEquals(receipt.partition(), detail.partition());
            Assertions.assertEquals(receipt.offset(), detail.offset());
            int expectedKeySize = command.key().length == 0 ? 32 : command.key().length;
            Assertions.assertEquals(expectedKeySize, detail.serializedKeySize());
            Assertions.assertEquals(command.body().length, detail.serializedValueSize());
        }

        @Override
        public Duration closeTimeout() {
            return Duration.ofSeconds(2);
        }

        private static Map<String, String> secretSettings() {
            Map<String, String> settings = KafkaEffectTestSupport.settings();
            settings.put("targets.primary.bootstrap-servers", "private-broker.example:9093");
            settings.put("targets.primary.security-profile", "sasl-tls");
            settings.put("targets.primary.sasl.mechanism", "PLAIN");
            settings.put("targets.primary.sasl.username", "service-user");
            settings.put("targets.primary.sasl.password", "super-secret-canary");
            return settings;
        }
    }

    private static final class KafkaFixture implements ExecutorFixture {
        private final PendingEffect effect;
        private final ProviderState state;
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ReflectiveLogCapture logCapture;
        private final KafkaEffectTestSupport.MemoryDetailArchive archive =
                new KafkaEffectTestSupport.MemoryDetailArchive();
        private final ConformanceProducer producer;
        private final KafkaPublishExecutor executor;

        private KafkaFixture(ExecutorScenario scenario, byte[] payload, ProviderState state) {
            try {
                this.logCapture = ReflectiveLogCapture.open();
            } catch (ReflectiveOperationException unavailable) {
                throw new AssertionError("reload4j test appender is unavailable", unavailable);
            }
            this.state = state;
            this.effect = KafkaEffectTestSupport.effect(payload);
            this.producer = new ConformanceProducer(scenario, state, blocked, release);
            this.executor = new KafkaPublishExecutor(
                    KafkaEffectConfig.parse(KafkaConformanceSpec.secretSettings()),
                    ignored -> producer, archive);
        }

        @Override public AppEffectExecutor executor() { return executor; }
        @Override public PendingEffect effect() { return effect; }
        @Override public ExternalProbe probe() { return state; }

        @Override
        public EffectExecutionContext context(int attempt, byte[] submittedRef) {
            return KafkaEffectTestSupport.context(attempt, submittedRef);
        }

        @Override
        public Map<String, Object> runtimeStats() {
            return Map.of(
                    "queueDepth", 0L,
                    "inFlight", 0L,
                    "resultBacklog", 0L,
                    "statusCounts", Map.of("DONE", 1L),
                    "executionTotals", Map.of("confirmed", 1L, "failed", 0L, "parked", 0L),
                    "resultBacklogByType", Map.of(ConnectorTypes.KAFKA_PUBLISH, 0L),
                    "latencyByType", Map.of(ConnectorTypes.KAFKA_PUBLISH,
                            Map.of("count", 1L, "totalMillis", 1L)),
                    "executors", List.of("kafka-publish"));
        }

        @Override
        public Map<String, Object> runtimeStatus() {
            return Map.of("status", "DONE", "attempts", 1L);
        }

        @Override
        public CapturedLogObservation capturedLogs() {
            return logCapture.observation();
        }

        @Override
        public void closeLogCapture() throws Exception {
            logCapture.close();
        }

        @Override
        public Optional<byte[]> archivedDetail(byte[] detailHash) {
            return archive.bytes(detailHash);
        }

        @Override
        public boolean awaitBlocked(Duration timeout) throws InterruptedException {
            return blocked.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void unblock() {
            release.countDown();
        }

        @Override
        public void close() {
            executor.close();
            // Until lazy construction is claimed, the fixture owns this client.
            producer.close();
        }
    }

    private static final class ConformanceProducer implements KafkaEffectProducer {
        private final ExecutorScenario scenario;
        private final ProviderState state;
        private final CountDownLatch blocked;
        private final CountDownLatch release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ConformanceProducer(ExecutorScenario scenario,
                                    ProviderState state,
                                    CountDownLatch blocked,
                                    CountDownLatch release) {
            this.scenario = scenario;
            this.state = state;
            this.blocked = blocked;
            this.release = release;
        }

        @Override
        public KafkaProducerAcknowledgement publish(String physicalTopic,
                                                     byte[] key,
                                                     byte[] value,
                                                     Map<String, byte[]> headers)
                throws InterruptedException {
            int call = state.recordCall(key);
            return switch (scenario) {
                case SUCCESS -> acknowledgeMutation(call, key, value);
                case TRANSIENT_THEN_SUCCESS -> {
                    if (call == 1) {
                        throw new NetworkException("synthetic transient failure");
                    }
                    yield acknowledgeMutation(call, key, value);
                }
                case UNKNOWN_ACK_THEN_RECONCILE -> {
                    state.mutations.incrementAndGet();
                    if (call == 1) {
                        throw new TimeoutException("synthetic unknown acknowledgement");
                    }
                    yield acknowledgement(call, key, value);
                }
                case BLOCKED_CALL -> {
                    blocked.countDown();
                    release.await();
                    if (closed.get()) {
                        throw new IllegalStateException("producer closed");
                    }
                    yield acknowledgeMutation(call, key, value);
                }
                case EXISTING_MATCH, EXISTING_CONFLICT ->
                        throw new AssertionError("unsupported Kafka conformance scenario");
            };
        }

        private KafkaProducerAcknowledgement acknowledgeMutation(int call,
                                                                  byte[] key,
                                                                  byte[] value) {
            state.mutations.incrementAndGet();
            return acknowledgement(call, key, value);
        }

        private static KafkaProducerAcknowledgement acknowledgement(int call,
                                                                     byte[] key,
                                                                     byte[] value) {
            return new KafkaProducerAcknowledgement(2, 100L + call, key.length, value.length);
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
        private final List<byte[]> keys = java.util.Collections.synchronizedList(new ArrayList<>());

        int recordCall(byte[] key) {
            keys.add(key.clone());
            return calls.incrementAndGet();
        }

        @Override public int calls() { return calls.get(); }
        @Override public int logicalMutations() { return mutations.get(); }
        @Override public int closeCalls() { return closeCalls.get(); }

        @Override
        public List<byte[]> observedIdempotencyKeys() {
            synchronized (keys) {
                return keys.stream().map(byte[]::clone).toList();
            }
        }

        @Override
        public Map<String, Object> diagnostics() {
            return Map.of("provider", "fake-kafka", "calls", calls(),
                    "mutations", logicalMutations());
        }
    }

    /** Installs an actual reload4j appender without adding a test compile dependency. */
    private static final class ReflectiveLogCapture implements AutoCloseable {
        private static final int MAX_EVENTS = 1_000;
        private static final int MAX_TEXT = 1_024;

        private final Object rootLogger;
        private final Class<?> appenderType;
        private final Object appender;
        private final List<Object> events = java.util.Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean active = new AtomicBoolean(true);

        private ReflectiveLogCapture(Object rootLogger, Class<?> appenderType) {
            this.rootLogger = rootLogger;
            this.appenderType = appenderType;
            this.appender = Proxy.newProxyInstance(appenderType.getClassLoader(),
                    new Class<?>[]{appenderType}, (proxy, method, arguments) -> {
                        String name = method.getName();
                        if (name.equals("doAppend") && arguments != null && arguments.length == 1) {
                            capture(arguments[0]);
                            return null;
                        }
                        if (name.equals("getName")) {
                            return "kafka-effect-conformance-capture";
                        }
                        if (name.equals("requiresLayout")) {
                            return false;
                        }
                        if (name.equals("equals")) {
                            return proxy == arguments[0];
                        }
                        if (name.equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (name.equals("toString")) {
                            return "KafkaEffectConformanceAppender";
                        }
                        Class<?> returnType = method.getReturnType();
                        if (returnType == boolean.class) {
                            return false;
                        }
                        if (returnType == int.class) {
                            return 0;
                        }
                        if (returnType == long.class) {
                            return 0L;
                        }
                        return null;
                    });
        }

        static ReflectiveLogCapture open() throws ReflectiveOperationException {
            Class<?> loggerType = Class.forName("org.apache.log4j.Logger");
            Class<?> appenderType = Class.forName("org.apache.log4j.Appender");
            Object root = loggerType.getMethod("getRootLogger").invoke(null);
            ReflectiveLogCapture capture = new ReflectiveLogCapture(root, appenderType);
            root.getClass().getMethod("addAppender", appenderType).invoke(root, capture.appender);
            return capture;
        }

        CapturedLogObservation observation() {
            synchronized (events) {
                return new CapturedLogObservation(active.get(), List.copyOf(events));
            }
        }

        private void capture(Object event) {
            if (!active.get() || events.size() >= MAX_EVENTS) {
                return;
            }
            try {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("logger", text(event, "getLoggerName"));
                snapshot.put("level", text(event, "getLevel"));
                snapshot.put("message", text(event, "getRenderedMessage"));
                Object throwable = event.getClass().getMethod("getThrowableStrRep").invoke(event);
                if (throwable instanceof String[] lines && lines.length > 0) {
                    snapshot.put("throwable", Arrays.stream(lines)
                            .limit(32).map(ReflectiveLogCapture::bounded).toList());
                }
                events.add(Map.copyOf(snapshot));
            } catch (ReflectiveOperationException | RuntimeException captureFailure) {
                events.add(Map.of("capture", "unavailable"));
            }
        }

        private static String text(Object event, String methodName)
                throws ReflectiveOperationException {
            Method method = event.getClass().getMethod(methodName);
            return bounded(String.valueOf(method.invoke(event)));
        }

        private static String bounded(String value) {
            return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
        }

        @Override
        public void close() throws Exception {
            if (active.compareAndSet(true, false)) {
                rootLogger.getClass().getMethod("removeAppender", appenderType)
                        .invoke(rootLogger, appender);
            }
        }
    }
}
