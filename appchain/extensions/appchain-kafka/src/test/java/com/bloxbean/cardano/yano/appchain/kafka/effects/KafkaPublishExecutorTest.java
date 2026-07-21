package com.bloxbean.cardano.yano.appchain.kafka.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.KafkaPublishDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import com.bloxbean.cardano.yano.appchain.kafka.config.KafkaEffectConfig;
import com.bloxbean.cardano.yano.appchain.kafka.internal.KafkaProducerAcknowledgement;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.ThrottlingQuotaExceededException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.bloxbean.cardano.yano.appchain.kafka.effects.KafkaEffectTestSupport.CHAIN_ID;
import static com.bloxbean.cardano.yano.appchain.kafka.effects.KafkaEffectTestSupport.PHYSICAL_TOPIC;
import static com.bloxbean.cardano.yano.appchain.kafka.effects.KafkaEffectTestSupport.TARGET_ID;
import static org.assertj.core.api.Assertions.assertThat;

class KafkaPublishExecutorTest {

    @Test
    void malformedUnsupportedPolicyAndContextFailuresPerformNoProviderIo() {
        byte[] unsupported = KafkaEffectTestSupport.command().encode();
        unsupported[1] = 2;
        assertNoIoFailure(KafkaEffectTestSupport.config(),
                KafkaEffectTestSupport.effect(new byte[]{0}), KafkaEffectTestSupport.context(1),
                ConnectorErrorCode.INVALID_PAYLOAD);
        assertNoIoFailure(KafkaEffectTestSupport.config(),
                KafkaEffectTestSupport.effect(unsupported), KafkaEffectTestSupport.context(1),
                ConnectorErrorCode.UNSUPPORTED_VERSION);

        KafkaPublishCommandV1 unknownTarget = new KafkaPublishCommandV1(
                "missing", "events", new byte[0], "application/json", new byte[0], List.of());
        assertNoIoFailure(KafkaEffectTestSupport.config(),
                KafkaEffectTestSupport.effect(unknownTarget.encode()),
                KafkaEffectTestSupport.context(1), ConnectorErrorCode.UNKNOWN_TARGET);

        KafkaPublishCommandV1 unknownTopic = new KafkaPublishCommandV1(
                "primary", "missing", new byte[0], "application/json", new byte[0], List.of());
        assertNoIoFailure(KafkaEffectTestSupport.config(),
                KafkaEffectTestSupport.effect(unknownTopic.encode()),
                KafkaEffectTestSupport.context(1), ConnectorErrorCode.POLICY_DENIED);

        Map<String, String> disabledSettings = KafkaEffectTestSupport.settings();
        disabledSettings.put("enabled", "false");
        assertNoIoFailure(KafkaEffectConfig.parse(disabledSettings),
                KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command().encode()),
                KafkaEffectTestSupport.context(1), ConnectorErrorCode.TARGET_DISABLED);

        assertNoIoFailure(KafkaEffectTestSupport.config(),
                KafkaEffectTestSupport.effect("kafka.publish.extra",
                        KafkaEffectTestSupport.command().encode(), null),
                KafkaEffectTestSupport.context(1), ConnectorErrorCode.INVALID_PAYLOAD);
        assertNoIoFailure(KafkaEffectTestSupport.config(),
                KafkaEffectTestSupport.effect(KafkaPublishExecutor.TYPE,
                        KafkaEffectTestSupport.command().encode(), new byte[31]),
                KafkaEffectTestSupport.context(1), ConnectorErrorCode.INVALID_PAYLOAD);
        byte[] forgedIdHash = new byte[32];
        java.util.Arrays.fill(forgedIdHash, (byte) 0xa5);
        assertNoIoFailure(KafkaEffectTestSupport.config(),
                KafkaEffectTestSupport.effect(KafkaPublishExecutor.TYPE,
                        KafkaEffectTestSupport.command().encode(), forgedIdHash),
                KafkaEffectTestSupport.context(1), ConnectorErrorCode.INVALID_PAYLOAD);
        assertNoIoFailure(KafkaEffectTestSupport.config(),
                KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command().encode()),
                KafkaEffectTestSupport.context("other-chain", 1), ConnectorErrorCode.INTERNAL_ERROR);
    }

    @Test
    void submittedReceiptIsStrictlyValidatedAndNeverRepublished() {
        byte[] expectedDestination = KafkaDestinationFingerprint.compute(TARGET_ID, PHYSICAL_TOPIC)
                .bytes();
        byte[] validReceipt = new KafkaPublishReceiptV1(expectedDestination, 4, 91).encode();
        AtomicInteger opens = new AtomicInteger();
        KafkaPublishExecutor executor = new KafkaPublishExecutor(
                KafkaEffectTestSupport.config(), ignored -> {
                    opens.incrementAndGet();
                    return KafkaEffectTestSupport.RecordingProducer.acknowledging();
                }, null);
        PendingEffect effect = KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command().encode());
        try {
            EffectExecution valid = executor.execute(
                    KafkaEffectTestSupport.context(2, validReceipt), effect);
            assertThat(valid).isInstanceOf(EffectExecution.Confirmed.class);
            assertThat(((EffectExecution.Confirmed) valid).externalRef()).containsExactly(validReceipt);

            EffectExecution malformed = executor.execute(
                    KafkaEffectTestSupport.context(2, new byte[]{0}), effect);
            assertFailure(malformed, ConnectorErrorCode.INTERNAL_ERROR);

            byte[] newerReceipt = validReceipt.clone();
            newerReceipt[1] = 2;
            EffectExecution unsupported = executor.execute(
                    KafkaEffectTestSupport.context(2, newerReceipt), effect);
            assertFailure(unsupported, ConnectorErrorCode.INTERNAL_ERROR);

            byte[] otherDestination = KafkaDestinationFingerprint.compute(
                    "other-v1", PHYSICAL_TOPIC).bytes();
            byte[] mismatched = new KafkaPublishReceiptV1(otherDestination, 4, 91).encode();
            EffectExecution changed = executor.execute(
                    KafkaEffectTestSupport.context(2, mismatched), effect);
            assertFailure(changed, ConnectorErrorCode.TARGET_CHANGED);

            EffectExecution missing = executor.execute(
                    KafkaEffectTestSupport.context(CHAIN_ID, 2, null), effect);
            assertFailure(missing, ConnectorErrorCode.INTERNAL_ERROR);
            assertThat(opens).hasValue(0);
        } finally {
            executor.close();
        }
    }

    @Test
    void emptyKeyUsesEffectIdentityAndReservedHeadersAreBoundedAndDeterministic() {
        KafkaEffectTestSupport.RecordingProducer producer =
                KafkaEffectTestSupport.RecordingProducer.acknowledging();
        KafkaPublishExecutor executor = new KafkaPublishExecutor(
                KafkaEffectTestSupport.config(), ignored -> producer, null);
        PendingEffect effect = KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command().encode());

        EffectExecution outcome = executor.execute(KafkaEffectTestSupport.context(1), effect);

        assertThat(outcome).isInstanceOf(EffectExecution.Confirmed.class);
        EffectExecution.Confirmed confirmed = (EffectExecution.Confirmed) outcome;
        KafkaPublishReceiptV1 receipt = KafkaPublishReceiptV1.decode(confirmed.externalRef());
        assertThat(receipt.destinationFingerprint()).containsExactly(
                KafkaDestinationFingerprint.compute(TARGET_ID, PHYSICAL_TOPIC).bytes());
        assertThat(receipt.partition()).isEqualTo(3);
        assertThat(receipt.offset()).isEqualTo(42);
        assertThat(confirmed.detailHash()).isNull();

        KafkaEffectTestSupport.PublishedRecord sent = producer.records().getFirst();
        assertThat(sent.topic()).isEqualTo(PHYSICAL_TOPIC);
        assertThat(sent.key()).containsExactly(effect.idHash());
        assertThat(sent.value()).containsExactly(KafkaEffectTestSupport.command().body());
        assertThat(sent.headers()).containsOnlyKeys(
                "trace-id", "yano-effect-id", "yano-chain-id", "yano-effect-type",
                "yano-payload-version", "yano-origin-height", "yano-origin-ordinal",
                "yano-content-type");
        assertThat(sent.headers().get("trace-id")).containsExactly(7, 8, 9);
        assertThat(text(sent.headers(), "yano-effect-id"))
                .isEqualTo(HexFormat.of().formatHex(effect.idHash()));
        assertThat(text(sent.headers(), "yano-chain-id")).isEqualTo(CHAIN_ID);
        assertThat(text(sent.headers(), "yano-effect-type")).isEqualTo("kafka.publish");
        assertThat(text(sent.headers(), "yano-payload-version")).isEqualTo("1");
        assertThat(text(sent.headers(), "yano-origin-height")).isEqualTo("17");
        assertThat(text(sent.headers(), "yano-origin-ordinal")).isEqualTo("3");
        assertThat(text(sent.headers(), "yano-content-type")).isEqualTo("application/json");
        executor.close();
    }

    @Test
    void explicitKeyIsPreservedInsteadOfBeingReplacedByEffectIdentity() {
        byte[] explicitKey = new byte[]{1, 2, 3, 4};
        KafkaEffectTestSupport.RecordingProducer producer =
                KafkaEffectTestSupport.RecordingProducer.acknowledging();
        KafkaPublishExecutor executor = new KafkaPublishExecutor(
                KafkaEffectTestSupport.config(), ignored -> producer, null);

        EffectExecution outcome = executor.execute(KafkaEffectTestSupport.context(1),
                KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command(explicitKey).encode()));

        assertThat(outcome).isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(producer.records().getFirst().key()).containsExactly(explicitKey);
        executor.close();
    }

    @Test
    void durableDetailHashBindsEffectReceiptDestinationAndAcknowledgement() throws Exception {
        KafkaEffectTestSupport.RecordingProducer producer =
                KafkaEffectTestSupport.RecordingProducer.acknowledging();
        KafkaEffectTestSupport.MemoryDetailArchive archive =
                new KafkaEffectTestSupport.MemoryDetailArchive();
        KafkaPublishExecutor executor = new KafkaPublishExecutor(
                KafkaEffectTestSupport.config(), ignored -> producer, archive);
        PendingEffect effect = KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command().encode());

        EffectExecution.Confirmed confirmed = (EffectExecution.Confirmed) executor.execute(
                KafkaEffectTestSupport.context(1), effect);
        KafkaPublishReceiptV1 receipt = KafkaPublishReceiptV1.decode(confirmed.externalRef());
        byte[] archived = archive.bytes(confirmed.detailHash()).orElseThrow();
        ConnectorDetailDocumentV1 document = ConnectorDetailDocumentV1.decode(archived);
        KafkaPublishDetailV1 detail = (KafkaPublishDetailV1) document.data();

        assertThat(document.effectIdHash()).containsExactly(effect.idHash());
        assertThat(detail.destinationFingerprint()).containsExactly(receipt.destinationFingerprint());
        assertThat(detail.partition()).isEqualTo(receipt.partition());
        assertThat(detail.offset()).isEqualTo(receipt.offset());
        assertThat(detail.serializedKeySize()).isEqualTo(effect.idHash().length);
        assertThat(detail.serializedValueSize()).isEqualTo(KafkaEffectTestSupport.command().body().length);
        executor.close();
    }

    @Test
    void detailArchiveFailurePersistsReceiptAndRetryArchivesWithoutRepublishing() {
        KafkaEffectTestSupport.MemoryDetailArchive ioArchive =
                new KafkaEffectTestSupport.MemoryDetailArchive();
        ioArchive.failWith(new IOException("archive-secret-canary"));
        assertArchiveRecovery(ioArchive);

        KafkaEffectTestSupport.MemoryDetailArchive runtimeArchive =
                new KafkaEffectTestSupport.MemoryDetailArchive();
        runtimeArchive.failWith(new IllegalStateException("runtime-secret-canary"));
        assertArchiveRecovery(runtimeArchive);
    }

    @TestFactory
    Stream<DynamicTest> providerFailuresUseFrozenCodeAndRetryability() {
        return Stream.of(
                error("authentication", () -> new AuthenticationException("auth-secret"),
                        ConnectorErrorCode.AUTH_UNAVAILABLE),
                error("authorization", () -> new AuthorizationException("policy-secret"),
                        ConnectorErrorCode.POLICY_DENIED),
                error("invalid topic", () -> new InvalidTopicException("topic-secret"),
                        ConnectorErrorCode.PROVIDER_REJECTED),
                error("invalid config", () -> new InvalidConfigurationException("config-secret"),
                        ConnectorErrorCode.PROVIDER_REJECTED),
                error("oversize record", () -> new RecordTooLargeException("size-secret"),
                        ConnectorErrorCode.PROVIDER_REJECTED),
                error("serialization", () -> new SerializationException("codec-secret"),
                        ConnectorErrorCode.PROVIDER_REJECTED),
                error("unknown acknowledgement", () -> new TimeoutException("timeout-secret"),
                        ConnectorErrorCode.ACK_UNKNOWN),
                error("broker quota", () -> new ThrottlingQuotaExceededException("quota-secret"),
                        ConnectorErrorCode.RATE_LIMITED),
                error("transient network", () -> new NetworkException("network-secret"),
                        ConnectorErrorCode.SERVICE_UNAVAILABLE),
                error("other kafka", () -> new KafkaException("kafka-secret"),
                        ConnectorErrorCode.INTERNAL_ERROR),
                error("unexpected runtime", () -> new IllegalStateException("runtime-secret"),
                        ConnectorErrorCode.INTERNAL_ERROR))
                .map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> {
                    KafkaEffectTestSupport.RecordingProducer producer =
                            new KafkaEffectTestSupport.RecordingProducer(
                                    (call, topic, key, value, headers) -> {
                                        throw testCase.failure().get();
                                    });
                    KafkaPublishExecutor executor = new KafkaPublishExecutor(
                            KafkaEffectTestSupport.config(), ignored -> producer, null);

                    EffectExecution outcome = executor.execute(KafkaEffectTestSupport.context(1),
                            KafkaEffectTestSupport.effect(
                                    KafkaEffectTestSupport.command().encode()));

                    assertFailure(outcome, testCase.code());
                    assertThat(((EffectExecution.Failed) outcome).reason())
                            .doesNotContain("secret");
                    assertThat(producer.calls()).isEqualTo(1);
                    executor.close();
                }));
    }

    @Test
    void checkedAndKafkaInterruptsRestoreInterruptAndReturnShutdown() {
        assertInterruptFailure(new KafkaEffectTestSupport.RecordingProducer(
                (call, topic, key, value, headers) -> {
                    throw new InterruptedException("checked-secret");
                }));
        assertInterruptFailure(new KafkaEffectTestSupport.RecordingProducer(
                (call, topic, key, value, headers) -> {
                    throw new InterruptException("kafka-secret");
                }));
    }

    @Test
    void transientRetryReusesOneClientAndStableIdentity() {
        KafkaEffectTestSupport.RecordingProducer producer =
                new KafkaEffectTestSupport.RecordingProducer((call, topic, key, value, headers) -> {
                    if (call == 1) {
                        throw new NetworkException("temporary");
                    }
                    return new KafkaProducerAcknowledgement(1, 9, key.length, value.length);
                });
        AtomicInteger opens = new AtomicInteger();
        KafkaPublishExecutor executor = new KafkaPublishExecutor(
                KafkaEffectTestSupport.config(), ignored -> {
                    opens.incrementAndGet();
                    return producer;
                }, null);
        PendingEffect effect = KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command().encode());

        assertFailure(executor.execute(KafkaEffectTestSupport.context(1), effect),
                ConnectorErrorCode.SERVICE_UNAVAILABLE);
        assertThat(executor.execute(KafkaEffectTestSupport.context(2), effect))
                .isInstanceOf(EffectExecution.Confirmed.class);

        assertThat(opens).hasValue(1);
        assertThat(producer.records()).hasSize(2);
        assertThat(producer.records()).allSatisfy(record ->
                assertThat(record.key()).containsExactly(effect.idHash()));
        executor.close();
    }

    @Test
    void concurrentFirstUseClosesEveryLosingClientAndRetainsExactlyOne() throws Exception {
        int workers = 12;
        CountDownLatch allOpened = new CountDownLatch(workers);
        List<KafkaEffectTestSupport.RecordingProducer> created =
                java.util.Collections.synchronizedList(new ArrayList<>());
        KafkaPublishExecutor executor = new KafkaPublishExecutor(
                KafkaEffectTestSupport.config(), ignored -> {
                    KafkaEffectTestSupport.RecordingProducer producer =
                            KafkaEffectTestSupport.RecordingProducer.acknowledging();
                    created.add(producer);
                    allOpened.countDown();
                    awaitUninterruptibly(allOpened);
                    return producer;
                }, null);
        PendingEffect effect = KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command().encode());
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<EffectExecution>> outcomes = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                outcomes.add(pool.submit(() ->
                        executor.execute(KafkaEffectTestSupport.context(1), effect)));
            }
            for (Future<EffectExecution> outcome : outcomes) {
                assertThat(outcome.get(3, TimeUnit.SECONDS))
                        .isInstanceOf(EffectExecution.Confirmed.class);
            }

            assertThat(created).hasSize(workers);
            assertThat(created.stream().mapToInt(
                    KafkaEffectTestSupport.RecordingProducer::calls).sum()).isEqualTo(workers);
            assertThat(created.stream().filter(producer -> producer.calls() == workers)).hasSize(1);
            assertThat(created.stream().mapToInt(
                    KafkaEffectTestSupport.RecordingProducer::closeCalls).sum())
                    .isEqualTo(workers - 1);

            executor.close();
            executor.close();
            assertThat(created.stream().mapToInt(
                    KafkaEffectTestSupport.RecordingProducer::closeCalls).sum()).isEqualTo(workers);
        } finally {
            pool.shutdownNow();
            executor.close();
        }
    }

    @Test
    void closeDoesNotBlockBehindClientConstructionAndLateClientIsClosed() throws Exception {
        CountDownLatch constructing = new CountDownLatch(1);
        CountDownLatch releaseConstruction = new CountDownLatch(1);
        KafkaEffectTestSupport.RecordingProducer producer =
                KafkaEffectTestSupport.RecordingProducer.acknowledging();
        KafkaPublishExecutor executor = new KafkaPublishExecutor(
                KafkaEffectTestSupport.config(), ignored -> {
                    constructing.countDown();
                    awaitUninterruptibly(releaseConstruction);
                    return producer;
                }, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<EffectExecution> execution = pool.submit(() -> executor.execute(
                    KafkaEffectTestSupport.context(1),
                    KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command().encode())));
            assertThat(constructing.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> close = pool.submit(executor::close);
            close.get(1, TimeUnit.SECONDS);
            releaseConstruction.countDown();

            EffectExecution outcome = execution.get(2, TimeUnit.SECONDS);
            assertFailure(outcome, ConnectorErrorCode.SHUTDOWN);
            assertThat(producer.calls()).isZero();
            assertThat(producer.closeCalls()).isEqualTo(1);
            executor.close();
            assertThat(producer.closeCalls()).isEqualTo(1);
        } finally {
            releaseConstruction.countDown();
            pool.shutdownNow();
            executor.close();
        }
    }

    @Test
    void factoryDeclinesEmptyConfigAndCreatesFreshExecutorProducts() throws Exception {
        List<KafkaEffectTestSupport.RecordingProducer> producers = new ArrayList<>();
        KafkaEffectExecutorFactory factory = new KafkaEffectExecutorFactory(ignored -> {
            KafkaEffectTestSupport.RecordingProducer producer =
                    KafkaEffectTestSupport.RecordingProducer.acknowledging();
            producers.add(producer);
            return producer;
        });

        assertThat(factory.scheme()).isEqualTo("kafka");
        assertThat(factory.create(CHAIN_ID, Map.of())).isEmpty();
        AppEffectExecutor first = factory.create(CHAIN_ID, KafkaEffectTestSupport.settings()).getFirst();
        AppEffectExecutor second = factory.create(CHAIN_ID, KafkaEffectTestSupport.settings()).getFirst();
        assertThat(first).isNotSameAs(second);
        assertThat(first.effectTypes()).containsExactly(KafkaPublishExecutor.TYPE);

        PendingEffect effect = KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command().encode());
        assertThat(first.execute(KafkaEffectTestSupport.context(1), effect))
                .isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(second.execute(KafkaEffectTestSupport.context(1), effect))
                .isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(first.operationalSnapshot().readiness().name()).isEqualTo("READY");
        assertThat(first.operationalSnapshot().attempts()).isEqualTo(1);
        assertThat(first.operationalSnapshot().successes()).isEqualTo(1);
        assertThat(producers).hasSize(2);
        first.close();
        second.close();
        assertThat(producers).allSatisfy(producer -> assertThat(producer.closeCalls()).isEqualTo(1));
    }

    private static void assertNoIoFailure(KafkaEffectConfig config,
                                          PendingEffect effect,
                                          EffectExecutionContext context,
                                          ConnectorErrorCode expected) {
        AtomicInteger opens = new AtomicInteger();
        KafkaPublishExecutor executor = new KafkaPublishExecutor(config, ignored -> {
            opens.incrementAndGet();
            return KafkaEffectTestSupport.RecordingProducer.acknowledging();
        }, null);
        try {
            assertFailure(executor.execute(context, effect), expected);
            assertThat(opens).hasValue(0);
        } finally {
            executor.close();
        }
    }

    private static void assertArchiveRecovery(KafkaEffectTestSupport.MemoryDetailArchive archive) {
        KafkaEffectTestSupport.RecordingProducer producer =
                KafkaEffectTestSupport.RecordingProducer.acknowledging();
        KafkaPublishExecutor executor = new KafkaPublishExecutor(
                KafkaEffectTestSupport.config(), ignored -> producer, archive);
        try {
            PendingEffect effect = KafkaEffectTestSupport.effect(
                    KafkaEffectTestSupport.command().encode());
            EffectExecution first = executor.execute(KafkaEffectTestSupport.context(1), effect);
            assertThat(first).isInstanceOf(EffectExecution.Submitted.class);
            byte[] receipt = ((EffectExecution.Submitted) first).externalRef();
            assertThat(producer.calls()).isEqualTo(1);

            EffectExecution stillUnavailable = executor.execute(
                    KafkaEffectTestSupport.context(2, receipt), effect);
            assertFailure(stillUnavailable, ConnectorErrorCode.DETAIL_ARCHIVE_FAILED);
            assertThat(producer.calls()).isEqualTo(1);

            archive.clearFailure();
            EffectExecution second = executor.execute(
                    KafkaEffectTestSupport.context(3, receipt), effect);
            assertThat(second).isInstanceOf(EffectExecution.Confirmed.class);
            EffectExecution.Confirmed confirmed = (EffectExecution.Confirmed) second;
            assertThat(confirmed.externalRef()).containsExactly(receipt);
            assertThat(archive.bytes(confirmed.detailHash())).isPresent();
            assertThat(producer.calls()).isEqualTo(1);
        } finally {
            executor.close();
        }
    }

    private static void assertInterruptFailure(KafkaEffectTestSupport.RecordingProducer producer) {
        KafkaPublishExecutor executor = new KafkaPublishExecutor(
                KafkaEffectTestSupport.config(), ignored -> producer, null);
        try {
            EffectExecution outcome = executor.execute(KafkaEffectTestSupport.context(1),
                    KafkaEffectTestSupport.effect(KafkaEffectTestSupport.command().encode()));
            assertFailure(outcome, ConnectorErrorCode.SHUTDOWN);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            executor.close();
        }
    }

    private static void assertFailure(EffectExecution outcome, ConnectorErrorCode expected) {
        assertThat(outcome).isInstanceOfSatisfying(EffectExecution.Failed.class, failed -> {
            assertThat(failed.reason()).isEqualTo(expected.wireCode());
            assertThat(failed.retryable()).isEqualTo(expected.disposition().retryable());
        });
    }

    private static String text(Map<String, byte[]> headers, String name) {
        return new String(headers.get(name), StandardCharsets.UTF_8);
    }

    private static ErrorCase error(String name,
                                   Supplier<RuntimeException> failure,
                                   ConnectorErrorCode code) {
        return new ErrorCase(name, failure, code);
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

    private record ErrorCase(String name,
                             Supplier<RuntimeException> failure,
                             ConnectorErrorCode code) {
    }
}
