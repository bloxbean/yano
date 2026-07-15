package com.bloxbean.cardano.yano.appchain.objectstore.s3.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailHash;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.config.ObjectStoreS3EffectConfig;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.BucketVersioning;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.EncryptionMode;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreClient;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreException;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutAcknowledgement;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutRequest;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObject;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObjectMetadata;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.VersionInventory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class ObjectStoreEffectTestSupport {
    static final String CHAIN_ID = "test-chain";
    static final String TARGET_ALIAS = "archive";
    static final String TARGET_ID = "archive-v1";
    static final String SOURCE_BUCKET = "evidence-staging";
    static final String DESTINATION_BUCKET = "evidence-archive";
    static final String SOURCE_KEY = "incoming/passport.json";
    static final String DESTINATION_KEY = "passports/passport.json";
    static final String COMPOSED_SOURCE_KEY = "staged/" + SOURCE_KEY;
    static final String COMPOSED_DESTINATION_KEY = "verified/" + DESTINATION_KEY;
    static final byte[] CONTENT = "{\"passport\":\"P-100\"}"
            .getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-07-16T01:02:03.456Z");

    private ObjectStoreEffectTestSupport() {
    }

    static Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>();
        String prefix = "targets.archive.";
        settings.put(prefix + "target-id", TARGET_ID);
        settings.put(prefix + "endpoint", "http://127.0.0.1:9000");
        settings.put(prefix + "region", "us-east-1");
        settings.put(prefix + "security-profile", "local-demo");
        settings.put(prefix + "path-style", "true");
        settings.put(prefix + "credentials-provider", "static");
        settings.put(prefix + "credentials.access-key-id", "test-access-key");
        settings.put(prefix + "credentials.secret-access-key", "super-secret-canary");
        settings.put(prefix + "source-bucket", SOURCE_BUCKET);
        settings.put(prefix + "source-prefix", "staged");
        settings.put(prefix + "destination-bucket", DESTINATION_BUCKET);
        settings.put(prefix + "destination-prefix", "verified");
        settings.put(prefix + "encryption-policy-id", "plain-demo-v1");
        settings.put(prefix + "encryption-mode", "none");
        settings.put(prefix + "retention-policy-id", "retention-v1");
        settings.put(prefix + "retention-classes.worm.mode", "governance");
        settings.put(prefix + "retention-classes.worm.days", "7");
        settings.put(prefix + "require-versioning", "true");
        settings.put(prefix + "max-object-bytes", "16777216");
        return settings;
    }

    static ObjectStoreS3EffectConfig config() {
        return ObjectStoreS3EffectConfig.parse(settings());
    }

    static ObjectPutCommandV1 command() {
        return command("worm", sha256(CONTENT), CONTENT.length);
    }

    static ObjectPutCommandV1 command(String retentionClass, byte[] digest, long size) {
        return new ObjectPutCommandV1(TARGET_ALIAS, SOURCE_KEY, DESTINATION_KEY,
                DigestAlgorithm.SHA_256, digest, size, "application/json", retentionClass);
    }

    static PendingEffect effect(byte[] payload) {
        return effect(S3ObjectPutExecutor.TYPE, payload, null);
    }

    static PendingEffect effect(String type, byte[] payload, byte[] idHash) {
        EffectRecord record = new EffectRecord(1, CHAIN_ID, 17, 3, type, payload,
                "demo", FinalityGate.APP_FINAL, ResultPolicy.CHAIN, 100, null);
        return idHash == null ? PendingEffect.of(record) : new PendingEffect(record, idHash);
    }

    static EffectExecutionContext context(int attempt) {
        return context(CHAIN_ID, attempt, new byte[0]);
    }

    static EffectExecutionContext context(int attempt, byte[] submittedRef) {
        return context(CHAIN_ID, attempt, submittedRef);
    }

    static EffectExecutionContext context(String chainId, int attempt, byte[] submittedRef) {
        byte[] reference = submittedRef != null ? submittedRef.clone() : null;
        return new EffectExecutionContext() {
            @Override public String chainId() { return chainId; }
            @Override public long tipHeight() { return 20; }
            @Override public long anchoredHeight() { return 19; }
            @Override public int attempt() { return attempt; }
            @Override public byte[] submittedRef() {
                return reference != null ? reference.clone() : null;
            }
            @Override public Map<String, String> settings() { return Map.of(); }
        };
    }

    static StoredObject source(byte[] bytes) {
        return new StoredObject(new StoredObjectMetadata(null, null, bytes.length,
                "application/octet-stream", Map.of(), EncryptionMode.NONE,
                null, ObjectRetentionMode.NONE, null), bytes);
    }

    static StoredObject existing(PendingEffect effect, String versionId, byte[] bytes,
                                 long retainUntilEpochMillis) {
        Map<String, String> metadata = expectedMetadata(effect, bytes.length, sha256(bytes), "worm");
        metadata = new LinkedHashMap<>(metadata);
        metadata.put("yano-retain-until", Long.toString(retainUntilEpochMillis));
        StoredObjectMetadata observed = new StoredObjectMetadata(versionId, "etag-1", bytes.length,
                "application/json", metadata, EncryptionMode.NONE,
                null, ObjectRetentionMode.GOVERNANCE, retainUntilEpochMillis);
        return new StoredObject(observed, bytes);
    }

    static Map<String, String> expectedMetadata(PendingEffect effect,
                                                long size,
                                                byte[] digest,
                                                String retentionClass) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("yano-schema", "1");
        metadata.put("yano-action", S3ObjectPutExecutor.TYPE);
        metadata.put("yano-effect-id", HexFormat.of().formatHex(effect.idHash()));
        metadata.put("yano-sha256", HexFormat.of().formatHex(digest));
        metadata.put("yano-size", Long.toString(size));
        metadata.put("yano-content-type", "application/json");
        metadata.put("yano-target-id", TARGET_ID);
        metadata.put("yano-encryption-policy", "plain-demo-v1");
        metadata.put("yano-retention-policy", "retention-v1");
        metadata.put("yano-retention-class", retentionClass);
        metadata.put("yano-retain-until", retentionClass.equals("none")
                ? "none" : Long.toString(NOW.plus(java.time.Duration.ofDays(7)).toEpochMilli()));
        return Map.copyOf(metadata);
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    enum PutBehavior {
        ACKNOWLEDGE,
        UNKNOWN_COMMIT,
        UNKNOWN_NO_COMMIT,
        CONFLICT_COMMIT,
        FAIL
    }

    static final class FakeClient implements ObjectStoreClient {
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger mutations = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile StoredObject source = ObjectStoreEffectTestSupport.source(CONTENT);
        private volatile StoredObject destination;
        private volatile boolean history;
        private volatile BucketVersioning versioning = BucketVersioning.ENABLED;
        private volatile PutBehavior putBehavior = PutBehavior.ACKNOWLEDGE;
        private volatile ConnectorErrorCode failureCode = ConnectorErrorCode.PROVIDER_REJECTED;
        private volatile boolean blockHead;
        private volatile int transientHeadFailures;
        private volatile PutRequest lastPutRequest;
        private volatile byte[] lastPutBytes;
        private volatile byte[] lastPutInput;
        private volatile boolean sourceWipedBeforeReconciliation;
        private volatile byte[] observationKey;

        @Override
        public BucketVersioning bucketVersioning(String bucket) {
            call("versioning");
            return versioning;
        }

        @Override
        public VersionInventory listVersions(String bucket, String exactKey, int maxEntries) {
            call("versions");
            if (maxEntries != 64 || !DESTINATION_BUCKET.equals(bucket)
                    || !COMPOSED_DESTINATION_KEY.equals(exactKey)) {
                throw new AssertionError("executor did not use bounded exact-key inventory");
            }
            return new VersionInventory(history ? 1 : 0, history);
        }

        @Override
        public Optional<StoredObjectMetadata> head(String bucket, String key, String versionId) {
            call("head");
            byte[] putInput = lastPutInput;
            if (putInput != null) {
                sourceWipedBeforeReconciliation = true;
                for (byte value : putInput) {
                    sourceWipedBeforeReconciliation &= value == 0;
                }
            }
            if (blockHead) {
                blocked.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ObjectStoreException(ConnectorErrorCode.SHUTDOWN);
                }
            }
            if (transientHeadFailures-- > 0) {
                throw new ObjectStoreException(ConnectorErrorCode.SERVICE_UNAVAILABLE);
            }
            StoredObject value = destination;
            return value == null ? Optional.empty() : Optional.of(value.metadata());
        }

        @Override
        public StoredObject get(String bucket, String key, String versionId, long maxBytes) {
            call("get");
            StoredObject value;
            if (SOURCE_BUCKET.equals(bucket)) {
                if (!COMPOSED_SOURCE_KEY.equals(key) || versionId != null) {
                    throw new AssertionError("unexpected source request");
                }
                value = source;
            } else {
                if (!DESTINATION_BUCKET.equals(bucket)
                        || !COMPOSED_DESTINATION_KEY.equals(key)) {
                    throw new AssertionError("unexpected destination request");
                }
                value = destination;
                if (value == null || !value.metadata().versionId().equals(versionId)) {
                    throw new ObjectStoreException(ConnectorErrorCode.SERVICE_UNAVAILABLE);
                }
            }
            if (value == null) {
                throw new ObjectStoreException(ConnectorErrorCode.SOURCE_UNAVAILABLE);
            }
            if (value.bytes().length > maxBytes) {
                throw new ObjectStoreException(SOURCE_BUCKET.equals(bucket)
                        ? ConnectorErrorCode.SOURCE_MISMATCH
                        : ConnectorErrorCode.DESTINATION_CONFLICT);
            }
            return new StoredObject(value.metadata(), value.bytes());
        }

        @Override
        public PutAcknowledgement putIfAbsent(PutRequest request, byte[] bytes) {
            call("put");
            lastPutRequest = request;
            lastPutBytes = bytes.clone();
            lastPutInput = bytes;
            return switch (putBehavior) {
                case ACKNOWLEDGE -> commit(request, bytes, "version-1");
                case UNKNOWN_COMMIT -> {
                    commit(request, bytes, "version-unknown");
                    throw new ObjectStoreException(ConnectorErrorCode.ACK_UNKNOWN);
                }
                case UNKNOWN_NO_COMMIT ->
                        throw new ObjectStoreException(ConnectorErrorCode.ACK_UNKNOWN);
                case CONFLICT_COMMIT -> {
                    commit(request, bytes, "version-race");
                    throw new ObjectStoreException(ConnectorErrorCode.DESTINATION_CONFLICT);
                }
                case FAIL -> throw new ObjectStoreException(failureCode);
            };
        }

        private PutAcknowledgement commit(PutRequest request, byte[] bytes, String versionId) {
            if (destination != null) {
                throw new ObjectStoreException(ConnectorErrorCode.DESTINATION_CONFLICT);
            }
            mutations.incrementAndGet();
            history = true;
            StoredObjectMetadata metadata = new StoredObjectMetadata(versionId, "etag-created",
                    bytes.length, request.contentType(), request.userMetadata(),
                    request.encryptionMode(), request.kmsKeyId(), request.retentionMode(),
                    request.retainUntilEpochMillis());
            // Simulate provider persistence outside the executor-owned buffer;
            // the executor wipes its synchronous request array on return.
            destination = new StoredObject(metadata, bytes.clone());
            return new PutAcknowledgement(versionId, "etag-created");
        }

        private void call(String name) {
            if (closed.get()) {
                throw new ObjectStoreException(ConnectorErrorCode.SHUTDOWN);
            }
            calls.add(name);
        }

        void observeWith(byte[] effectIdHash) {
            observationKey = effectIdHash.clone();
        }

        List<String> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        int mutations() { return mutations.get(); }
        int closeCalls() { return closeCalls.get(); }
        StoredObject destination() { return destination; }
        PutRequest lastPutRequest() { return lastPutRequest; }
        byte[] lastPutBytes() { return lastPutBytes == null ? null : lastPutBytes.clone(); }
        boolean sourceWipedBeforeReconciliation() {
            return sourceWipedBeforeReconciliation;
        }
        void source(StoredObject value) { source = value; }
        void destination(StoredObject value) { destination = value; history = value != null; }
        void history(boolean value) { history = value; }
        void versioning(BucketVersioning value) { versioning = value; }
        void putBehavior(PutBehavior value) { putBehavior = value; }
        void failureCode(ConnectorErrorCode value) { failureCode = value; }
        void transientHeadFailures(int value) { transientHeadFailures = value; }
        void blockHead() { blockHead = true; }
        boolean awaitBlocked() throws InterruptedException { return blocked.await(2, TimeUnit.SECONDS); }
        void release() { release.countDown(); }
        byte[] observationKey() { return observationKey == null ? null : observationKey.clone(); }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCalls.incrementAndGet();
                release.countDown();
            }
        }
    }

    static final class MemoryDetailArchive implements ConnectorDetailArchive {
        private final Map<String, byte[]> entries = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile IOException failure;

        @Override
        public ConnectorDetailHash archive(ConnectorDetailDocumentV1 document) throws IOException {
            if (closed.get()) {
                throw new IOException("archive closed");
            }
            if (failure != null) {
                throw failure;
            }
            byte[] bytes = document.encode();
            ConnectorDetailHash hash = ConnectorDetailHash.compute(bytes);
            entries.putIfAbsent(HexFormat.of().formatHex(hash.bytes()), bytes.clone());
            return hash;
        }

        @Override
        public Optional<ConnectorDetailDocumentV1> retrieve(ConnectorDetailHash hash)
                throws IOException {
            byte[] bytes = entries.get(HexFormat.of().formatHex(hash.bytes()));
            return bytes == null ? Optional.empty()
                    : Optional.of(ConnectorDetailDocumentV1.decode(bytes));
        }

        Optional<byte[]> bytes(byte[] hash) {
            byte[] value = entries.get(HexFormat.of().formatHex(hash));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        void fail(IOException value) { failure = value; }
        void recover() { failure = null; }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
