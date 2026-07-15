package com.bloxbean.cardano.yano.appchain.objectstore.s3.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorContractException;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailHash;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectPutDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectVersionFingerprint;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.config.ObjectStoreS3EffectConfig;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.BucketVersioning;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.EncryptionMode;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreClient;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreClientFactory;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreException;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutAcknowledgement;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutRequest;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObject;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObjectMetadata;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.VersionInventory;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes the frozen {@code object.put} command as a bounded verified source
 * read followed by one atomic destination create.
 *
 * <p>The executor never overwrites or resurrects a destination. Every retry
 * probes current state and bounded exact-key version history before it may
 * issue another conditional create. Existing bytes are confirmed only after
 * an exact-version GET and SHA-256 verification; ETag is never treated as a
 * content digest.</p>
 */
public final class S3ObjectPutExecutor implements AppEffectExecutor {
    /** Frozen action routed by this executor. */
    public static final String TYPE = ConnectorTypes.OBJECT_PUT;

    private static final String ID = "objectstore-s3-object-put";
    private static final int MAX_VERSION_INVENTORY_ENTRIES = 64;
    private static final String NO_RETENTION_CLASS = "none";

    private final ObjectStoreS3EffectConfig config;
    private final ObjectStoreClientFactory clientFactory;
    private final ConnectorDetailArchive detailArchive;
    private final Clock clock;
    private final Map<String, Semaphore> targetPermits;
    private final Duration closeDuration;
    private final Object lifecycleLock = new Object();
    private final Map<String, ObjectStoreClient> clients = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates an executor that owns every lazily opened client and archive.
     * @param config validated immutable connector configuration
     * @param clientFactory fresh provider-client factory
     * @param detailArchive optional durable detail archive owned by this executor
     */
    public S3ObjectPutExecutor(ObjectStoreS3EffectConfig config,
                               ObjectStoreClientFactory clientFactory,
                               ConnectorDetailArchive detailArchive) {
        this(config, clientFactory, detailArchive, Clock.systemUTC());
    }

    S3ObjectPutExecutor(ObjectStoreS3EffectConfig config,
                        ObjectStoreClientFactory clientFactory,
                        ConnectorDetailArchive detailArchive,
                        Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.detailArchive = detailArchive;
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<String, Semaphore> permits = new LinkedHashMap<>();
        config.targets().keySet().forEach(alias -> permits.put(alias, new Semaphore(1, true)));
        this.targetPermits = Map.copyOf(permits);
        this.closeDuration = config.targets().values().stream()
                .map(target -> target.timeouts().close())
                .max(Duration::compareTo).orElse(Duration.ofSeconds(5));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(String effectType) {
        return TYPE.equals(effectType);
    }

    @Override
    public EffectExecution execute(EffectExecutionContext context, PendingEffect effect) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(effect, "effect");

        ObjectPutCommandV1 command;
        try {
            command = ObjectPutCommandV1.decode(effect.payload());
        } catch (ConnectorContractException invalid) {
            return failed(invalid.code());
        } catch (RuntimeException invalid) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }

        if (closed.get()) {
            return failed(ConnectorErrorCode.SHUTDOWN);
        }
        if (!TYPE.equals(effect.type())) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }
        byte[] effectIdHash = effect.idHash();
        if (effectIdHash == null || effectIdHash.length != 32) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }
        effectIdHash = effectIdHash.clone();
        if (!MessageDigest.isEqual(effectIdHash, effect.effectId().hash())) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }
        if (!effect.record().chainId().equals(context.chainId())) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }
        if (!config.enabled()) {
            return failed(ConnectorErrorCode.TARGET_DISABLED);
        }

        Optional<ObjectStoreS3EffectConfig.Target> configuredTarget = config.target(command.target());
        if (configuredTarget.isEmpty()) {
            return failed(ConnectorErrorCode.UNKNOWN_TARGET);
        }
        ObjectStoreS3EffectConfig.Target target = configuredTarget.orElseThrow();
        if (command.size() > target.maxObjectBytes()) {
            return failed(ConnectorErrorCode.POLICY_DENIED);
        }

        Optional<ObjectStoreS3EffectConfig.ResolvedObject> resolution;
        try {
            resolution = config.resolve(command, clock.instant());
        } catch (RuntimeException invalidPolicy) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }
        if (resolution.isEmpty()) {
            return failed(ConnectorErrorCode.POLICY_DENIED);
        }
        ObjectStoreS3EffectConfig.ResolvedObject resolved = resolution.orElseThrow();
        DesiredObject desired;
        try {
            desired = DesiredObject.from(command, resolved, effectIdHash);
        } catch (RuntimeException invalidPolicy) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }

        byte[] submittedRef = context.submittedRef();
        if (submittedRef == null) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }

        Semaphore permit = targetPermits.get(command.target());
        if (permit == null) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }
        if (!permit.tryAcquire()) {
            // Ordinary same-target local contention is a precondition delay,
            // not a provider failure and must not consume attempt budget.
            return new EffectExecution.Retry(Duration.ofMillis(100));
        }
        try {
            ObjectStoreClient client;
            try {
                client = client(command.target(), target);
            } catch (ObjectStoreException providerFailure) {
                return failed(providerFailure.code());
            } catch (RuntimeException providerFailure) {
                return failed(closed.get()
                        ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.INTERNAL_ERROR);
            }
            if (closed.get()) {
                return failed(ConnectorErrorCode.SHUTDOWN);
            }

            if (submittedRef.length > 0) {
                return resumeSubmitted(client, command, resolved, desired, submittedRef);
            }
            return createOrReconcile(client, command, resolved, desired);
        } finally {
            permit.release();
        }
    }

    private EffectExecution resumeSubmitted(ObjectStoreClient client,
                                            ObjectPutCommandV1 command,
                                            ObjectStoreS3EffectConfig.ResolvedObject resolved,
                                            DesiredObject desired,
                                            byte[] submittedRef) {
        ObjectPutReceiptV1 receipt;
        try {
            receipt = ObjectPutReceiptV1.decode(submittedRef);
            if (!MessageDigest.isEqual(receipt.destinationFingerprint(),
                    resolved.destinationFingerprint().bytes())) {
                return failed(ConnectorErrorCode.TARGET_CHANGED);
            }
            if (!MessageDigest.isEqual(receipt.verifiedSha256(), command.digest())
                    || receipt.size() != command.size()) {
                return failed(ConnectorErrorCode.INTERNAL_ERROR);
            }
        } catch (RuntimeException invalidReference) {
            // submittedRef is node-local operational state. Corruption must
            // consume bounded retry/parking policy, never authorize a mutation.
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }

        try {
            Optional<VerifiedDestination> verified = inspectDestination(
                    client, resolved, desired, false);
            if (verified.isEmpty()) {
                return failed(ConnectorErrorCode.ACK_UNKNOWN);
            }
            VerifiedDestination destination = verified.orElseThrow();
            if (!MessageDigest.isEqual(receipt.objectVersionFingerprint(),
                    ObjectVersionFingerprint.compute(destination.metadata().versionId()).bytes())) {
                return failed(ConnectorErrorCode.INTERNAL_ERROR);
            }
            return confirmWithOptionalDetail(
                    receipt, desired.effectIdHash(), destination.metadata(), true);
        } catch (DestinationConflict conflict) {
            return failed(ConnectorErrorCode.DESTINATION_CONFLICT);
        } catch (ObjectStoreException providerFailure) {
            return failed(providerFailure.code());
        } catch (RuntimeException providerFailure) {
            return failed(closed.get()
                    ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.INTERNAL_ERROR);
        }
    }

    private EffectExecution createOrReconcile(ObjectStoreClient client,
                                              ObjectPutCommandV1 command,
                                              ObjectStoreS3EffectConfig.ResolvedObject resolved,
                                              DesiredObject desired) {
        try {
            Optional<VerifiedDestination> existing = inspectDestination(
                    client, resolved, desired, false);
            if (existing.isPresent()) {
                return confirm(existing.orElseThrow(), resolved, desired, false);
            }
        } catch (DestinationConflict conflict) {
            return failed(ConnectorErrorCode.DESTINATION_CONFLICT);
        } catch (ObjectStoreException providerFailure) {
            return failed(providerFailure.code());
        } catch (RuntimeException providerFailure) {
            return failed(closed.get()
                    ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.INTERNAL_ERROR);
        }

        byte[] sourceBytes;
        try {
            StoredObject source = client.get(resolved.target().sourceBucket(),
                    resolved.sourceObjectKey(), null, command.size());
            sourceBytes = source.takeBytes();
        } catch (ObjectStoreException providerFailure) {
            return failed(providerFailure.code());
        } catch (RuntimeException providerFailure) {
            return failed(closed.get()
                    ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.INTERNAL_ERROR);
        }
        try {
            if (sourceBytes.length != command.size()
                    || !MessageDigest.isEqual(sha256(sourceBytes), command.digest())) {
                return failed(ConnectorErrorCode.SOURCE_MISMATCH);
            }

            try {
                // Check immediately before the only mutating call. A suspended,
                // disabled, or unknown state is target drift and can never fall
                // back to an unversioned write.
                if (client.bucketVersioning(resolved.target().destinationBucket())
                        != BucketVersioning.ENABLED) {
                    return failed(ConnectorErrorCode.TARGET_CHANGED);
                }
            } catch (ObjectStoreException providerFailure) {
                return failed(providerFailure.code());
            } catch (RuntimeException providerFailure) {
                return failed(closed.get()
                        ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.INTERNAL_ERROR);
            }

            // Source verification can be slow. Repeat the destination/current
            // version-history probe after it so stale absence never directly
            // authorizes a PUT. The compatibility profile additionally denies
            // destination deletes to the executor principal.
            try {
                Optional<VerifiedDestination> appeared = inspectDestination(
                        client, resolved, desired, false);
                if (appeared.isPresent()) {
                    return confirm(appeared.orElseThrow(), resolved, desired, false);
                }
            } catch (DestinationConflict conflict) {
                return failed(ConnectorErrorCode.DESTINATION_CONFLICT);
            } catch (ObjectStoreException providerFailure) {
                return failed(providerFailure.code());
            } catch (RuntimeException providerFailure) {
                return failed(closed.get()
                        ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.INTERNAL_ERROR);
            }

            // Retention is derived from the actual mutation boundary, not
            // effect-attempt start. Existing-object reconciliation above never
            // recomputes or compares a provider's authoritative deadline.
            ObjectStoreS3EffectConfig.ResolvedObject mutationResolution;
            DesiredObject mutationDesired;
            try {
                mutationResolution = config.resolve(command, clock.instant()).orElseThrow();
                if (!sameDestination(resolved, mutationResolution)) {
                    return failed(ConnectorErrorCode.TARGET_CHANGED);
                }
                mutationDesired = DesiredObject.from(command, mutationResolution,
                        desired.effectIdHash());
            } catch (RuntimeException invalidPolicy) {
                return failed(ConnectorErrorCode.INTERNAL_ERROR);
            }

            PutAcknowledgement acknowledgement;
            try {
                acknowledgement = client.putIfAbsent(mutationDesired.putRequest(), sourceBytes);
            } catch (ObjectStoreException providerFailure) {
                if (providerFailure.code() == ConnectorErrorCode.ACK_UNKNOWN
                        || providerFailure.code() == ConnectorErrorCode.DESTINATION_CONFLICT) {
                    return reconcileAfterPutAfterWipe(client, mutationResolution,
                            mutationDesired, null,
                            providerFailure.code() == ConnectorErrorCode.ACK_UNKNOWN,
                            sourceBytes);
                }
                return failed(providerFailure.code());
            } catch (RuntimeException providerFailure) {
                // A non-normalized adapter failure after invocation cannot prove
                // whether the write happened. Reconcile rather than retrying PUT.
                return reconcileAfterPutAfterWipe(
                        client, mutationResolution, mutationDesired, null, true, sourceBytes);
            }

            // The synchronous SDK call has returned and internal retries are
            // disabled. Release the source body before reconciliation fetches
            // a second full-sized destination body.
            Arrays.fill(sourceBytes, (byte) 0);
            return reconcileAfterPut(client, mutationResolution, mutationDesired,
                    acknowledgement.versionId(), true);
        } finally {
            Arrays.fill(sourceBytes, (byte) 0);
        }
    }

    private EffectExecution reconcileAfterPutAfterWipe(
            ObjectStoreClient client,
            ObjectStoreS3EffectConfig.ResolvedObject resolved,
            DesiredObject desired,
            String acknowledgedVersionId,
            boolean unknownIfAbsent,
            byte[] sourceBytes) {
        Arrays.fill(sourceBytes, (byte) 0);
        return reconcileAfterPut(client, resolved, desired, acknowledgedVersionId,
                unknownIfAbsent);
    }

    private static boolean sameDestination(ObjectStoreS3EffectConfig.ResolvedObject first,
                                           ObjectStoreS3EffectConfig.ResolvedObject second) {
        return first.target() == second.target()
                && first.sourceObjectKey().equals(second.sourceObjectKey())
                && first.destinationObjectKey().equals(second.destinationObjectKey())
                && MessageDigest.isEqual(first.destinationFingerprint().bytes(),
                second.destinationFingerprint().bytes());
    }

    private EffectExecution reconcileAfterPut(ObjectStoreClient client,
                                               ObjectStoreS3EffectConfig.ResolvedObject resolved,
                                               DesiredObject desired,
                                               String acknowledgedVersionId,
                                               boolean unknownIfAbsent) {
        try {
            Optional<VerifiedDestination> verified = inspectDestination(
                    client, resolved, desired, acknowledgedVersionId != null);
            if (verified.isEmpty()) {
                return failed(unknownIfAbsent
                        ? ConnectorErrorCode.ACK_UNKNOWN
                        : ConnectorErrorCode.DESTINATION_CONFLICT);
            }
            VerifiedDestination destination = verified.orElseThrow();
            if (acknowledgedVersionId != null
                    && !acknowledgedVersionId.equals(destination.metadata().versionId())) {
                return failed(ConnectorErrorCode.DESTINATION_CONFLICT);
            }
            return confirm(destination, resolved, desired, false);
        } catch (DestinationConflict conflict) {
            return failed(ConnectorErrorCode.DESTINATION_CONFLICT);
        } catch (ObjectStoreException unavailableDuringReconciliation) {
            return failed(ConnectorErrorCode.ACK_UNKNOWN);
        } catch (RuntimeException unavailableDuringReconciliation) {
            return failed(closed.get()
                    ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.ACK_UNKNOWN);
        }
    }

    private Optional<VerifiedDestination> inspectDestination(
            ObjectStoreClient client,
            ObjectStoreS3EffectConfig.ResolvedObject resolved,
            DesiredObject desired,
            boolean requireRequestedRetentionDeadline) {
        Optional<StoredObjectMetadata> current = client.head(
                resolved.target().destinationBucket(), resolved.destinationObjectKey(), null);
        if (current.isEmpty()) {
            VersionInventory versions = client.listVersions(
                    resolved.target().destinationBucket(), resolved.destinationObjectKey(),
                    MAX_VERSION_INVENTORY_ENTRIES);
            if (versions.anyVersionOrDeleteMarker()) {
                throw new DestinationConflict();
            }
            return Optional.empty();
        }

        StoredObjectMetadata head = current.orElseThrow();
        validateMetadata(head, desired, requireRequestedRetentionDeadline);
        StoredObject fetched = client.get(resolved.target().destinationBucket(),
                resolved.destinationObjectKey(), head.versionId(), desired.size());
        byte[] destinationBytes = fetched.takeBytes();
        try {
            validateMetadata(fetched.metadata(), desired, requireRequestedRetentionDeadline);
            if (!head.versionId().equals(fetched.metadata().versionId())
                    || !MessageDigest.isEqual(sha256(destinationBytes), desired.sha256())) {
                throw new DestinationConflict();
            }
            return Optional.of(new VerifiedDestination(fetched.metadata()));
        } finally {
            Arrays.fill(destinationBytes, (byte) 0);
        }
    }

    private static void validateMetadata(StoredObjectMetadata metadata,
                                         DesiredObject desired,
                                         boolean requireRequestedRetentionDeadline) {
        Map<String, String> observedMetadata = new LinkedHashMap<>(metadata.userMetadata());
        String observedRetainUntil = observedMetadata.remove("yano-retain-until");
        Map<String, String> expectedMetadata = new LinkedHashMap<>(desired.userMetadata());
        String requestedRetainUntil = expectedMetadata.remove("yano-retain-until");
        if (metadata.versionId() == null || "null".equals(metadata.versionId())
                || metadata.contentLength() != desired.size()
                || !metadata.contentType().equals(desired.contentType())
                || !observedMetadata.equals(expectedMetadata)
                || metadata.encryptionMode() != desired.encryptionMode()
                || !Objects.equals(metadata.kmsKeyId(), desired.kmsKeyId())
                || metadata.retentionMode() != desired.retentionMode()
                || desired.retentionMode().retainUntilRequired()
                && (metadata.retainUntilEpochMillis() == null
                || metadata.retainUntilEpochMillis() < 0)
                || !desired.retentionMode().retainUntilRequired()
                && metadata.retainUntilEpochMillis() != null
                || !retentionDeadlineMatches(metadata, observedRetainUntil)
                || requireRequestedRetentionDeadline
                && !Objects.equals(observedRetainUntil, requestedRetainUntil)) {
            throw new DestinationConflict();
        }
    }

    private static boolean retentionDeadlineMatches(StoredObjectMetadata metadata,
                                                     String metadataValue) {
        if (!metadata.retentionMode().retainUntilRequired()) {
            return "none".equals(metadataValue);
        }
        if (metadataValue == null || metadata.retainUntilEpochMillis() == null) {
            return false;
        }
        try {
            long parsed = Long.parseLong(metadataValue);
            return parsed >= 0
                    && Long.toString(parsed).equals(metadataValue)
                    && parsed == metadata.retainUntilEpochMillis();
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private EffectExecution confirm(VerifiedDestination destination,
                                    ObjectStoreS3EffectConfig.ResolvedObject resolved,
                                    DesiredObject desired,
                                    boolean submittedRefAlreadyPersisted) {
        ObjectPutReceiptV1 receipt = new ObjectPutReceiptV1(
                resolved.destinationFingerprint(),
                ObjectVersionFingerprint.compute(destination.metadata().versionId()),
                desired.sha256(), desired.size());
        return confirmWithOptionalDetail(receipt, desired.effectIdHash(),
                destination.metadata(), submittedRefAlreadyPersisted);
    }

    private EffectExecution confirmWithOptionalDetail(ObjectPutReceiptV1 receipt,
                                                       byte[] effectIdHash,
                                                       StoredObjectMetadata metadata,
                                                       boolean submittedRefAlreadyPersisted) {
        byte[] externalRef = receipt.encode();
        if (detailArchive == null) {
            return EffectExecution.confirmed(externalRef);
        }
        try {
            ConnectorDetailDocumentV1 detail = ConnectorDetailDocumentV1.of(effectIdHash,
                    new ObjectPutDetailV1(receipt.destinationFingerprint(),
                            metadata.versionId(), metadata.etag(), receipt.verifiedSha256(),
                            receipt.size(), metadata.retentionMode(),
                            metadata.retainUntilEpochMillis()));
            ConnectorDetailHash detailHash = detailArchive.archive(detail);
            return EffectExecution.confirmed(externalRef, detailHash.bytes());
        } catch (IOException | RuntimeException archiveFailure) {
            if (submittedRefAlreadyPersisted) {
                return failed(closed.get()
                        ? ConnectorErrorCode.SHUTDOWN
                        : ConnectorErrorCode.DETAIL_ARCHIVE_FAILED);
            }
            // Provider success is already verified. Persist the compact
            // receipt so later attempts refetch/archive only and never mutate.
            return EffectExecution.submitted(externalRef);
        }
    }

    private ObjectStoreClient client(String targetAlias,
                                     ObjectStoreS3EffectConfig.Target target) {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("executor is closed");
            }
            ObjectStoreClient existing = clients.get(targetAlias);
            if (existing != null) {
                return existing;
            }
        }

        // Client construction can initialize credential providers, TLS, or
        // service discovery. Never hold the lifecycle lock over third-party work.
        ObjectStoreClient created = Objects.requireNonNull(
                clientFactory.open(target), "clientFactory product");
        ObjectStoreClient selected;
        boolean reject;
        synchronized (lifecycleLock) {
            reject = closed.get();
            ObjectStoreClient existing = clients.get(targetAlias);
            if (reject) {
                selected = null;
            } else if (existing != null) {
                selected = existing;
            } else {
                clients.put(targetAlias, created);
                return created;
            }
        }
        closeOneBounded(created, closeDuration);
        if (reject) {
            throw new IllegalStateException("executor is closed");
        }
        return selected;
    }

    private static void closeOneBounded(AutoCloseable resource, Duration duration) {
        closeBounded(List.of(resource), duration);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static EffectExecution failed(ConnectorErrorCode code) {
        return EffectExecution.failed(code.wireCode(), code.disposition().retryable());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<AutoCloseable> resources = new ArrayList<>();
        synchronized (lifecycleLock) {
            resources.addAll(clients.values());
            clients.clear();
        }
        if (detailArchive != null) {
            resources.add(detailArchive);
        }
        closeBounded(resources, closeDuration);
    }

    private static void closeBounded(List<AutoCloseable> resources, Duration duration) {
        ExecutorService closer = Executors.newCachedThreadPool(task ->
                Thread.ofPlatform().daemon().name("objectstore-s3-close").unstarted(task));
        List<Future<?>> futures = new ArrayList<>(resources.size());
        for (AutoCloseable resource : resources) {
            futures.add(closer.submit(() -> {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // Close is best effort after ownership is atomically fenced.
                }
            }));
        }
        long started = System.nanoTime();
        try {
            for (Future<?> future : futures) {
                long remaining = duration.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) {
                    break;
                }
                try {
                    future.get(remaining, TimeUnit.NANOSECONDS);
                } catch (ExecutionException ignored) {
                    // Resource exception is already contained by its task.
                } catch (java.util.concurrent.TimeoutException timeout) {
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            futures.forEach(future -> future.cancel(true));
            closer.shutdownNow();
        }
    }

    private record VerifiedDestination(StoredObjectMetadata metadata) {
        private VerifiedDestination {
            metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }

    private record DesiredObject(byte[] effectIdHash,
                                 byte[] sha256,
                                 long size,
                                 String contentType,
                                 Map<String, String> userMetadata,
                                 EncryptionMode encryptionMode,
                                 String kmsKeyId,
                                 ObjectRetentionMode retentionMode,
                                 PutRequest putRequest) {
        private DesiredObject {
            effectIdHash = effectIdHash.clone();
            sha256 = sha256.clone();
            userMetadata = Map.copyOf(userMetadata);
        }

        @Override public byte[] effectIdHash() { return effectIdHash.clone(); }
        @Override public byte[] sha256() { return sha256.clone(); }

        private static DesiredObject from(ObjectPutCommandV1 command,
                                          ObjectStoreS3EffectConfig.ResolvedObject resolved,
                                          byte[] effectIdHash) {
            if (command.digestAlgorithm() != DigestAlgorithm.SHA_256) {
                throw new IllegalArgumentException("unsupported digest algorithm");
            }
            ObjectStoreS3EffectConfig.Target target = resolved.target();
            EncryptionMode encryptionMode = EncryptionMode.valueOf(
                    target.encryption().mode().name());
            String kmsKeyId = target.encryption().kmsKeyId().orElse(null);
            ObjectRetentionMode retentionMode = resolved.retention()
                    .map(value -> value.retentionClass().mode())
                    .orElse(ObjectRetentionMode.NONE);
            Long retainUntil = resolved.retention()
                    .map(value -> value.retainUntil().toEpochMilli()).orElse(null);
            String retentionClass = resolved.retention()
                    .map(value -> value.retentionClass().alias()).orElse(NO_RETENTION_CLASS);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("yano-schema", "1");
            metadata.put("yano-action", TYPE);
            metadata.put("yano-effect-id", HexFormat.of().formatHex(effectIdHash));
            metadata.put("yano-sha256", HexFormat.of().formatHex(command.digest()));
            metadata.put("yano-size", Long.toString(command.size()));
            metadata.put("yano-content-type", command.contentType());
            metadata.put("yano-target-id", target.targetId());
            metadata.put("yano-encryption-policy", target.encryptionPolicyId());
            metadata.put("yano-retention-policy", target.retentionPolicyId());
            metadata.put("yano-retention-class", retentionClass);
            metadata.put("yano-retain-until",
                    retainUntil == null ? "none" : Long.toString(retainUntil));
            PutRequest request = new PutRequest(target.destinationBucket(),
                    resolved.destinationObjectKey(), command.contentType(), command.size(),
                    command.digest(), metadata, encryptionMode,
                    kmsKeyId,
                    retentionMode, retainUntil);
            return new DesiredObject(effectIdHash, command.digest(), command.size(),
                    command.contentType(), metadata, encryptionMode, kmsKeyId,
                    retentionMode, request);
        }
    }

    /** Internal control-flow marker; it never escapes as an error message. */
    private static final class DestinationConflict extends RuntimeException {
        private DestinationConflict() {
            super(null, null, false, false);
        }
    }
}
