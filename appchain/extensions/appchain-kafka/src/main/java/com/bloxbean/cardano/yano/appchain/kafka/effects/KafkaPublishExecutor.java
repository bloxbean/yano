package com.bloxbean.cardano.yano.appchain.kafka.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationalSnapshot;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationsTracker;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorContractException;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailHash;
import com.bloxbean.cardano.yano.appchain.integration.detail.KafkaPublishDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaHeader;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import com.bloxbean.cardano.yano.appchain.kafka.config.KafkaEffectConfig;
import com.bloxbean.cardano.yano.appchain.kafka.internal.KafkaEffectProducer;
import com.bloxbean.cardano.yano.appchain.kafka.internal.KafkaEffectProducerFactory;
import com.bloxbean.cardano.yano.appchain.kafka.internal.KafkaProducerAcknowledgement;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.ThrottlingQuotaExceededException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes the frozen {@code kafka.publish} command through configured target
 * and topic aliases. The effect-id hash is transmitted on every attempt as a
 * stable downstream deduplication token.
 *
 * <p>Kafka producer idempotence covers retries within one producer session;
 * it does not make restart/failover delivery exactly once. An acknowledgement
 * lost after broker commit can therefore cause a duplicate on a later attempt.
 * Consumers that require uniqueness deduplicate by {@code yano-effect-id}.</p>
 */
public final class KafkaPublishExecutor implements AppEffectExecutor {
    /** Frozen action routed by this executor. */
    public static final String TYPE = "kafka.publish";

    private static final String ID = "kafka-publish";
    private static final int MAX_RESERVED_VALUE_BYTES = 256;
    private static final int MAX_RESERVED_AGGREGATE_BYTES = 1024;
    private static final Duration MAX_CLOSE_DURATION = Duration.ofSeconds(30);

    private final KafkaEffectConfig config;
    private final KafkaEffectProducerFactory producerFactory;
    private final ConnectorDetailArchive detailArchive;
    private final Object lifecycleLock = new Object();
    private final Map<String, KafkaEffectProducer> producers = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final EffectExecutorOperationsTracker operations =
            new EffectExecutorOperationsTracker();

    /**
     * Creates an executor that owns every producer it opens and, when present,
     * the detail archive.
     *
     * @param config validated immutable connector policy
     * @param producerFactory fresh producer factory
     * @param detailArchive optional detail archive owned by this executor
     */
    public KafkaPublishExecutor(KafkaEffectConfig config,
                                KafkaEffectProducerFactory producerFactory,
                                ConnectorDetailArchive detailArchive) {
        this.config = Objects.requireNonNull(config, "config");
        this.producerFactory = Objects.requireNonNull(producerFactory, "producerFactory");
        this.detailArchive = detailArchive;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> effectTypes() {
        return Set.of(TYPE);
    }

    @Override
    public boolean supports(String effectType) {
        return TYPE.equals(effectType);
    }

    @Override
    public EffectExecution execute(EffectExecutionContext context, PendingEffect effect) {
        return operations.observe(() -> executeAttempt(context, effect));
    }

    @Override
    public EffectExecutorOperationalSnapshot operationalSnapshot() {
        return operations.snapshot();
    }

    private EffectExecution executeAttempt(EffectExecutionContext context, PendingEffect effect) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(effect, "effect");

        // Decode the full bounded canonical command before alias resolution,
        // client creation, or any provider call.
        KafkaPublishCommandV1 command;
        try {
            command = KafkaPublishCommandV1.decode(effect.payload());
        } catch (ConnectorContractException rejection) {
            return failed(rejection.code());
        } catch (RuntimeException rejection) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }

        if (closed.get()) {
            return failed(ConnectorErrorCode.SHUTDOWN);
        }
        if (!TYPE.equals(effect.type())) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }
        byte[] idHash = effect.idHash();
        if (idHash == null || idHash.length != 32) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }
        idHash = idHash.clone();
        if (!MessageDigest.isEqual(idHash, effect.effectId().hash())) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }
        if (!config.enabled()) {
            return failed(ConnectorErrorCode.TARGET_DISABLED);
        }
        Optional<KafkaEffectConfig.ResolvedTopic> resolution =
                config.resolve(command.target(), command.topic());
        if (resolution.isEmpty()) {
            ConnectorErrorCode code = config.target(command.target()).isEmpty()
                    ? ConnectorErrorCode.UNKNOWN_TARGET : ConnectorErrorCode.POLICY_DENIED;
            return failed(code);
        }

        KafkaEffectConfig.ResolvedTopic resolved = resolution.get();
        byte[] key = command.key().length == 0 ? idHash.clone() : command.key();
        Map<String, byte[]> headers;
        try {
            headers = headers(context, effect, command, idHash);
        } catch (RuntimeException invalidContext) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }

        byte[] destination = resolved.fingerprint().bytes();
        byte[] submittedRef = context.submittedRef();
        if (submittedRef == null) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }
        if (submittedRef.length > 0) {
            try {
                KafkaPublishReceiptV1 submitted = KafkaPublishReceiptV1.decode(submittedRef);
                if (!MessageDigest.isEqual(destination, submitted.destinationFingerprint())) {
                    return failed(ConnectorErrorCode.TARGET_CHANGED);
                }
                return confirmWithOptionalDetail(
                        submitted, idHash, key.length, command.body().length, true);
            } catch (ConnectorContractException invalidSubmittedRef) {
                // submittedRef is node-local operational state, not the
                // authenticated effect command. A corrupt or newer receipt
                // must never turn a valid business effect into a definitive
                // on-chain failure. Park/retry it for operator recovery and,
                // critically, never republish while the reference is present.
                return failed(ConnectorErrorCode.INTERNAL_ERROR);
            } catch (RuntimeException invalidSubmittedRef) {
                return failed(ConnectorErrorCode.INTERNAL_ERROR);
            }
        }

        KafkaProducerAcknowledgement acknowledgement;
        try {
            KafkaEffectProducer producer = producer(command.target(), resolved.target());
            if (closed.get()) {
                return failed(ConnectorErrorCode.SHUTDOWN);
            }
            acknowledgement = producer.publish(
                    resolved.topic().name(), key, command.body(), headers);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(ConnectorErrorCode.SHUTDOWN);
        } catch (RuntimeException providerFailure) {
            return failed(closed.get()
                    ? ConnectorErrorCode.SHUTDOWN : classify(providerFailure));
        }

        KafkaPublishReceiptV1 receipt = new KafkaPublishReceiptV1(destination,
                acknowledgement.partition(), acknowledgement.offset());
        return confirmWithOptionalDetail(
                receipt, idHash, key.length, command.body().length, false);
    }

    private EffectExecution confirmWithOptionalDetail(KafkaPublishReceiptV1 receipt,
                                                       byte[] idHash,
                                                       int serializedKeySize,
                                                       int serializedValueSize,
                                                       boolean submittedRefAlreadyPersisted) {
        byte[] externalRef = receipt.encode();
        if (detailArchive == null) {
            return EffectExecution.confirmed(externalRef);
        }
        try {
            ConnectorDetailDocumentV1 detail = ConnectorDetailDocumentV1.of(idHash,
                    new KafkaPublishDetailV1(receipt.destinationFingerprint(),
                            receipt.partition(), receipt.offset(),
                            serializedKeySize, serializedValueSize));
            ConnectorDetailHash detailHash = detailArchive.archive(detail);
            return EffectExecution.confirmed(externalRef, detailHash.bytes());
        } catch (IOException | RuntimeException archiveFailure) {
            if (submittedRefAlreadyPersisted) {
                return failed(closed.get()
                        ? ConnectorErrorCode.SHUTDOWN
                        : ConnectorErrorCode.DETAIL_ARCHIVE_FAILED);
            }
            // The broker acknowledgement is already known. Persist it as the
            // submitted reference so a later attempt retries only durable
            // detail archival and never republishes the Kafka record.
            return EffectExecution.submitted(externalRef);
        }
    }

    private KafkaEffectProducer producer(String targetAlias,
                                         KafkaEffectConfig.Target target) {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("executor is closed");
            }
            KafkaEffectProducer existing = producers.get(targetAlias);
            if (existing != null) {
                return existing;
            }
        }

        // Client construction may resolve metadata, initialize TLS providers,
        // or invoke a third-party callback. Never hold the lifecycle lock over
        // that work: close() must remain bounded even if construction stalls.
        KafkaEffectProducer created = Objects.requireNonNull(
                producerFactory.open(target), "producerFactory product");
        KafkaEffectProducer selected;
        boolean reject;
        synchronized (lifecycleLock) {
            reject = closed.get();
            KafkaEffectProducer existing = producers.get(targetAlias);
            if (reject) {
                selected = null;
            } else if (existing != null) {
                selected = existing;
            } else {
                producers.put(targetAlias, created);
                return created;
            }
        }
        safeClose(created);
        if (reject) {
            throw new IllegalStateException("executor is closed");
        }
        return selected;
    }

    private static void safeClose(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // A concurrently-created loser is never published or retained.
        }
    }

    private static Map<String, byte[]> headers(EffectExecutionContext context,
                                               PendingEffect effect,
                                               KafkaPublishCommandV1 command,
                                               byte[] idHash) {
        if (!effect.record().chainId().equals(context.chainId())
                || idHash.length != 32) {
            throw new IllegalArgumentException("invalid execution context");
        }
        Map<String, byte[]> values = new LinkedHashMap<>();
        for (KafkaHeader header : command.headers()) {
            values.put(header.name(), header.value());
        }
        putReserved(values, "yano-effect-id", ascii(HexFormat.of().formatHex(idHash)));
        putReserved(values, "yano-chain-id", utf8(effect.record().chainId()));
        putReserved(values, "yano-effect-type", ascii(TYPE));
        putReserved(values, "yano-payload-version", ascii("1"));
        putReserved(values, "yano-origin-height",
                ascii(Long.toString(effect.effectId().height())));
        putReserved(values, "yano-origin-ordinal",
                ascii(Integer.toString(effect.effectId().ordinal())));
        putReserved(values, "yano-content-type", ascii(command.contentType()));
        int reservedBytes = values.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("yano-"))
                .mapToInt(entry -> entry.getKey().length() + entry.getValue().length)
                .sum();
        if (reservedBytes > MAX_RESERVED_AGGREGATE_BYTES) {
            throw new IllegalArgumentException("reserved headers exceed bound");
        }
        return Collections.unmodifiableMap(values);
    }

    private static void putReserved(Map<String, byte[]> headers, String name, byte[] value) {
        if (value.length > MAX_RESERVED_VALUE_BYTES || headers.putIfAbsent(name, value) != null) {
            throw new IllegalArgumentException("invalid reserved header");
        }
    }

    private static byte[] ascii(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("non-ASCII reserved header");
        }
        return bytes;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ConnectorErrorCode classify(RuntimeException failure) {
        if (failure instanceof InterruptException) {
            Thread.currentThread().interrupt();
            return ConnectorErrorCode.SHUTDOWN;
        }
        if (failure instanceof AuthenticationException) {
            return ConnectorErrorCode.AUTH_UNAVAILABLE;
        }
        if (failure instanceof AuthorizationException) {
            return ConnectorErrorCode.POLICY_DENIED;
        }
        if (failure instanceof InvalidTopicException
                || failure instanceof InvalidConfigurationException
                || failure instanceof RecordTooLargeException
                || failure instanceof RecordBatchTooLargeException
                || failure instanceof SerializationException) {
            return ConnectorErrorCode.PROVIDER_REJECTED;
        }
        if (failure instanceof TimeoutException) {
            return ConnectorErrorCode.ACK_UNKNOWN;
        }
        if (failure instanceof ThrottlingQuotaExceededException) {
            return ConnectorErrorCode.RATE_LIMITED;
        }
        if (failure instanceof RetriableException) {
            return ConnectorErrorCode.SERVICE_UNAVAILABLE;
        }
        if (failure instanceof KafkaException) {
            return ConnectorErrorCode.INTERNAL_ERROR;
        }
        return ConnectorErrorCode.INTERNAL_ERROR;
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
            resources.addAll(producers.values());
            producers.clear();
        }
        if (detailArchive != null) {
            resources.add(detailArchive);
        }
        closeBounded(resources);
    }

    private static void closeBounded(List<AutoCloseable> resources) {
        ExecutorService closer = Executors.newCachedThreadPool(task ->
                Thread.ofPlatform().daemon().name("kafka-effect-close").unstarted(task));
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
                long remaining = MAX_CLOSE_DURATION.toNanos()
                        - (System.nanoTime() - started);
                if (remaining <= 0) {
                    break;
                }
                try {
                    future.get(remaining, TimeUnit.NANOSECONDS);
                } catch (ExecutionException ignored) {
                    // Resource exceptions are already contained by each task.
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
}
