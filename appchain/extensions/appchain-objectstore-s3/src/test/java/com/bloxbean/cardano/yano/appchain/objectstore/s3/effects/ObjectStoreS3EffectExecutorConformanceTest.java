package com.bloxbean.cardano.yano.appchain.objectstore.s3.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectPutDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.BucketVersioning;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.EncryptionMode;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreClient;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreException;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutAcknowledgement;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutRequest;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObject;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObjectMetadata;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.VersionInventory;
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

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

class ObjectStoreS3EffectExecutorConformanceTest {

    @TestFactory
    Stream<DynamicNode> phaseOneConnectorContract() {
        return EffectExecutorConformance.tests(new ObjectStoreConformanceSpec());
    }

    private static final class ObjectStoreConformanceSpec implements ExecutorConformanceSpec {
        private static final Set<String> SECRETS = Set.of(
                "super-secret-canary", "http://127.0.0.1:9000");

        @Override public String actionType() { return ConnectorTypes.OBJECT_PUT; }

        @Override
        public byte[] validPayload() {
            return ObjectStoreEffectTestSupport.command().encode();
        }

        @Override
        public List<PayloadCase> invalidPayloads() {
            byte[] unsupported = validPayload();
            unsupported[1] = 2;
            byte[] trailing = Arrays.copyOf(validPayload(), validPayload().length + 1);
            return List.of(
                    new PayloadCase("empty", new byte[0], ConnectorErrorCode.INVALID_PAYLOAD),
                    new PayloadCase("malformed", new byte[]{0}, ConnectorErrorCode.INVALID_PAYLOAD),
                    new PayloadCase("trailing", trailing, ConnectorErrorCode.INVALID_PAYLOAD),
                    new PayloadCase("unsupported version", unsupported,
                            ConnectorErrorCode.UNSUPPORTED_VERSION));
        }

        @Override public Set<String> forbiddenSentinels() { return SECRETS; }
        @Override public IdempotencyModel idempotencyModel() {
            return IdempotencyModel.PROBE_SINGLE_MUTATION;
        }

        @Override
        public boolean supports(ExecutorScenario scenario) {
            return true;
        }

        @Override
        public ExecutorFixture open(ExecutorScenario scenario, byte[] payload) {
            return new ObjectStoreFixture(scenario, payload, new ProviderState());
        }

        @Override
        public ExecutorFixture restart(ExecutorFixture previous,
                                       ExecutorScenario scenario,
                                       byte[] payload) {
            ProviderState state = (ProviderState) previous.probe();
            state.revealPending();
            return new ObjectStoreFixture(scenario, payload, state);
        }

        @Override
        public FailedConstructionObservation failedConstruction() {
            ProviderState state = new ProviderState();
            ConformanceClient acquired = new ConformanceClient(
                    ExecutorScenario.SUCCESS, state, new byte[32]);
            Throwable failure = new IllegalArgumentException(
                    "objectstore-s3 effect executor construction failed");
            acquired.close();
            return new FailedConstructionObservation(failure, 1, state.closeCalls(),
                    ObjectStoreEffectTestSupport.config().safeDiagnostics(),
                    CapturedLogObservation.active(List.of()));
        }

        @Override
        public void verifyReceipt(byte[] canonicalExternalRef) {
            ObjectPutReceiptV1 receipt = ObjectPutReceiptV1.decode(canonicalExternalRef);
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
            ObjectPutCommandV1 command = ObjectPutCommandV1.decode(validPayload);
            ObjectPutReceiptV1 receipt = ObjectPutReceiptV1.decode(canonicalExternalRef);
            ObjectPutDetailV1 detail = Assertions.assertInstanceOf(
                    ObjectPutDetailV1.class, detailDocument.data());
            Assertions.assertArrayEquals(receipt.destinationFingerprint(),
                    detail.destinationFingerprint());
            Assertions.assertArrayEquals(command.digest(), receipt.verifiedSha256());
            Assertions.assertArrayEquals(receipt.verifiedSha256(), detail.verifiedSha256());
            Assertions.assertEquals(command.size(), receipt.size());
            Assertions.assertEquals(receipt.size(), detail.size());
            Assertions.assertEquals(ObjectRetentionMode.GOVERNANCE, detail.retentionMode());
        }

        @Override public Duration closeTimeout() { return Duration.ofSeconds(2); }
    }

    private static final class ObjectStoreFixture implements ExecutorFixture {
        private final PendingEffect effect;
        private final ProviderState state;
        private final ConformanceClient client;
        private final ObjectStoreEffectTestSupport.MemoryDetailArchive archive =
                new ObjectStoreEffectTestSupport.MemoryDetailArchive();
        private final S3ObjectPutExecutor executor;

        private ObjectStoreFixture(ExecutorScenario scenario,
                                   byte[] payload,
                                   ProviderState state) {
            this.effect = ObjectStoreEffectTestSupport.effect(payload);
            this.state = state;
            state.initialize(scenario, effect);
            this.client = new ConformanceClient(scenario, state, effect.idHash());
            this.executor = new S3ObjectPutExecutor(ObjectStoreEffectTestSupport.config(),
                    ignored -> client, archive,
                    Clock.fixed(ObjectStoreEffectTestSupport.NOW, ZoneOffset.UTC));
        }

        @Override public AppEffectExecutor executor() { return executor; }
        @Override public PendingEffect effect() { return effect; }
        @Override public ExternalProbe probe() { return state; }

        @Override
        public EffectExecutionContext context(int attempt, byte[] submittedRef) {
            return ObjectStoreEffectTestSupport.context(attempt, submittedRef);
        }

        @Override
        public Map<String, Object> runtimeStats() {
            return Map.of(
                    "queueDepth", 0L,
                    "inFlight", 0L,
                    "resultBacklog", 0L,
                    "statusCounts", Map.of("DONE", 1L),
                    "executionTotals", Map.of("confirmed", 1L, "failed", 0L, "parked", 0L),
                    "resultBacklogByType", Map.of(ConnectorTypes.OBJECT_PUT, 0L),
                    "latencyByType", Map.of(ConnectorTypes.OBJECT_PUT,
                            Map.of("count", 1L, "totalMillis", 1L)),
                    "executors", List.of("objectstore-s3-object-put"));
        }

        @Override public Map<String, Object> runtimeStatus() {
            return Map.of("status", "DONE", "attempts", 1L);
        }
        @Override public CapturedLogObservation capturedLogs() {
            return CapturedLogObservation.active(List.of());
        }
        @Override public void closeLogCapture() { }
        @Override public Optional<byte[]> archivedDetail(byte[] hash) { return archive.bytes(hash); }
        @Override public boolean awaitBlocked(Duration timeout) throws InterruptedException {
            return client.blocked.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public void unblock() { client.release.countDown(); }

        @Override
        public void close() {
            executor.close();
            // Invalid payloads never cause lazy adoption; idempotent client close
            // accounts for both constructed and executor-owned fixtures.
            client.close();
        }
    }

    private static final class ConformanceClient implements ObjectStoreClient {
        private final ExecutorScenario scenario;
        private final ProviderState state;
        private final byte[] effectIdHash;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private ConformanceClient(ExecutorScenario scenario,
                                  ProviderState state,
                                  byte[] effectIdHash) {
            this.scenario = scenario;
            this.state = state;
            this.effectIdHash = effectIdHash.clone();
        }

        @Override
        public BucketVersioning bucketVersioning(String bucket) {
            call();
            return BucketVersioning.ENABLED;
        }

        @Override
        public VersionInventory listVersions(String bucket, String key, int maxEntries) {
            call();
            return new VersionInventory(state.history ? 1 : 0, state.history);
        }

        @Override
        public Optional<StoredObjectMetadata> head(String bucket, String key, String versionId) {
            call();
            if (scenario == ExecutorScenario.BLOCKED_CALL) {
                blocked.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ObjectStoreException(ConnectorErrorCode.SHUTDOWN);
                }
                if (closed.get()) {
                    throw new ObjectStoreException(ConnectorErrorCode.SHUTDOWN);
                }
            }
            if (scenario == ExecutorScenario.TRANSIENT_THEN_SUCCESS
                    && state.transientFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new ObjectStoreException(ConnectorErrorCode.SERVICE_UNAVAILABLE);
            }
            ProviderObject visible = state.visible;
            return visible == null ? Optional.empty() : Optional.of(visible.metadata);
        }

        @Override
        public StoredObject get(String bucket, String key, String versionId, long maxBytes) {
            call();
            if (ObjectStoreEffectTestSupport.SOURCE_BUCKET.equals(bucket)) {
                return ObjectStoreEffectTestSupport.source(
                        ObjectStoreEffectTestSupport.CONTENT.clone());
            }
            ProviderObject visible = state.visible;
            if (visible == null || !visible.metadata.versionId().equals(versionId)) {
                throw new ObjectStoreException(ConnectorErrorCode.SERVICE_UNAVAILABLE);
            }
            return new StoredObject(visible.metadata, visible.bytes.clone());
        }

        @Override
        public PutAcknowledgement putIfAbsent(PutRequest request, byte[] bytes) {
            call();
            if (state.visible != null || state.pending != null) {
                throw new ObjectStoreException(ConnectorErrorCode.DESTINATION_CONFLICT);
            }
            ProviderObject created = ProviderObject.from(request, bytes, "conformance-version");
            state.mutations.incrementAndGet();
            if (scenario == ExecutorScenario.UNKNOWN_ACK_THEN_RECONCILE) {
                state.pending = created;
                throw new ObjectStoreException(ConnectorErrorCode.ACK_UNKNOWN);
            }
            state.visible = created;
            state.history = true;
            return new PutAcknowledgement(created.metadata.versionId(), created.metadata.etag());
        }

        private void call() {
            if (closed.get()) {
                throw new ObjectStoreException(ConnectorErrorCode.SHUTDOWN);
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
        private volatile ProviderObject visible;
        private volatile ProviderObject pending;
        private volatile boolean history;
        private volatile boolean initialized;

        synchronized void initialize(ExecutorScenario scenario, PendingEffect effect) {
            if (initialized) {
                return;
            }
            initialized = true;
            if (scenario == ExecutorScenario.TRANSIENT_THEN_SUCCESS) {
                transientFailures.set(1);
            } else if (scenario == ExecutorScenario.EXISTING_MATCH) {
                StoredObject existing = ObjectStoreEffectTestSupport.existing(effect,
                        "existing-version", ObjectStoreEffectTestSupport.CONTENT,
                        ObjectStoreEffectTestSupport.NOW.plus(Duration.ofDays(7)).toEpochMilli());
                visible = new ProviderObject(existing.metadata(), existing.bytes());
                history = true;
            } else if (scenario == ExecutorScenario.EXISTING_CONFLICT) {
                StoredObject existing = ObjectStoreEffectTestSupport.existing(effect,
                        "conflict-version", ObjectStoreEffectTestSupport.CONTENT,
                        ObjectStoreEffectTestSupport.NOW.plus(Duration.ofDays(7)).toEpochMilli());
                Map<String, String> wrong = new LinkedHashMap<>(existing.metadata().userMetadata());
                wrong.put("yano-effect-id", "00".repeat(32));
                StoredObjectMetadata metadata = new StoredObjectMetadata("conflict-version", "etag",
                        existing.metadata().contentLength(), existing.metadata().contentType(), wrong,
                        EncryptionMode.NONE, null, ObjectRetentionMode.GOVERNANCE,
                        existing.metadata().retainUntilEpochMillis());
                visible = new ProviderObject(metadata, existing.bytes());
                history = true;
            }
        }

        synchronized void revealPending() {
            if (pending != null) {
                visible = pending;
                pending = null;
                history = true;
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
            return Map.of("provider", "fake-objectstore", "calls", calls(),
                    "mutations", logicalMutations());
        }
    }

    private record ProviderObject(StoredObjectMetadata metadata, byte[] bytes) {
        private ProviderObject {
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }

        static ProviderObject from(PutRequest request, byte[] bytes, String versionId) {
            StoredObjectMetadata metadata = new StoredObjectMetadata(versionId, "etag",
                    bytes.length, request.contentType(), request.userMetadata(),
                    request.encryptionMode(), request.kmsKeyId(), request.retentionMode(),
                    request.retainUntilEpochMillis());
            return new ProviderObject(metadata, bytes);
        }
    }
}
