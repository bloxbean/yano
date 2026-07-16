package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.appchain.examples.evidence.event.EvidenceAvailableEventV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Kafka topic initializer and exact acknowledged-event verifier. */
final class KafkaDemoClient implements AutoCloseable {
    private static final Duration API_TIMEOUT = Duration.ofSeconds(20);
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "yano-effect-id", "yano-chain-id", "yano-effect-type",
            "yano-payload-version", "yano-origin-height", "yano-origin-ordinal",
            "yano-content-type");

    private final DemoConfig.KafkaSettings settings;
    private final AdminClient admin;

    KafkaDemoClient(DemoConfig.KafkaSettings settings) {
        this.settings = settings;
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers());
        properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "yano-evidence-demo-admin");
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "20000");
        admin = AdminClient.create(properties);
    }

    /** Creates one demo topic if absent and validates its usable partition set. */
    void ensureTopic() {
        try {
            admin.createTopics(List.of(new NewTopic(settings.physicalTopic(), 1, (short) 1)))
                    .all().get(API_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException exists) {
            if (!(rootCause(exists) instanceof TopicExistsException)) {
                throw new DemoException(DemoError.INITIALIZATION_FAILED);
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DemoException(DemoError.INITIALIZATION_FAILED);
        }
        validateTopic();
    }

    void probeBroker() {
        try {
            if (admin.describeCluster().nodes()
                    .get(API_TIMEOUT.toSeconds(), TimeUnit.SECONDS).isEmpty()) {
                throw new DemoException(DemoError.SERVICE_TIMEOUT);
            }
        } catch (DemoException failure) {
            throw failure;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
    }

    void validateTopic() {
        try {
            TopicDescription description = admin.describeTopics(List.of(settings.physicalTopic()))
                    .allTopicNames().get(API_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                    .get(settings.physicalTopic());
            if (description == null || description.partitions().isEmpty()) {
                throw new DemoException(DemoError.INITIALIZATION_FAILED);
            }
        } catch (DemoException failure) {
            throw failure;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
    }

    /** Opens and assigns the consumer before the notification command is submitted. */
    EventWindow openEventWindow() {
        return new EventWindow(consumer(), settings.physicalTopic());
    }

    VerifiedKafkaEvent verify(EventWindow window,
                              KafkaPublishReceiptV1 receipt,
                              EvidenceRecordV1 state,
                              EffectRecord effect,
                              Duration timeout) {
        ConsumerRecord<byte[], byte[]> record = window.readExact(
                receipt.partition(), receipt.offset(), timeout);
        EvidenceAvailableEventV1 expected = EvidenceAvailableEventV1.fromRecord(state);
        KafkaPublishCommandV1 command = new KafkaPublishCommandV1(
                state.kafkaTarget(), state.kafkaTopic(), expected.kafkaKey(),
                expected.contentType(), expected.encode(), List.of());
        if (!Arrays.equals(record.key(), expected.kafkaKey())
                || !Arrays.equals(record.value(), expected.encode())
                || !Arrays.equals(effect.payload(), command.encode())) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        try {
            EvidenceAvailableEventV1 decoded = EvidenceAvailableEventV1.decode(record.value());
            if (!decoded.equals(expected)) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        verifyHeaders(record, effect);
        return new VerifiedKafkaEvent(record.partition(), record.offset());
    }

    private KafkaConsumer<byte[], byte[]> consumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers());
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "yano-evidence-demo-consumer");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "yano-evidence-demo-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "20000");
        properties.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, "65536");
        properties.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, "65536");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        return new KafkaConsumer<>(properties);
    }

    private static void verifyHeaders(ConsumerRecord<byte[], byte[]> record, EffectRecord effect) {
        Map<String, byte[]> values = new HashMap<>();
        for (Header header : record.headers()) {
            if (!RESERVED_HEADERS.contains(header.key())
                    || values.putIfAbsent(header.key(), header.value()) != null) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
        }
        if (!values.keySet().equals(RESERVED_HEADERS)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        requireAscii(values, "yano-effect-id", effect.effectId().hashHex());
        requireUtf8(values, "yano-chain-id", effect.chainId());
        requireAscii(values, "yano-effect-type", "kafka.publish");
        requireAscii(values, "yano-payload-version", "1");
        requireAscii(values, "yano-origin-height", Long.toString(effect.height()));
        requireAscii(values, "yano-origin-ordinal", Integer.toString(effect.ordinal()));
        requireAscii(values, "yano-content-type", "application/cbor");
    }

    private static void requireAscii(Map<String, byte[]> values, String name, String expected) {
        if (!Arrays.equals(values.get(name), expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    private static void requireUtf8(Map<String, byte[]> values, String name, String expected) {
        if (!Arrays.equals(values.get(name), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current.getCause() != null && depth < 16; depth++) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        admin.close(API_TIMEOUT);
    }

    record VerifiedKafkaEvent(int partition, long offset) {
    }

    static final class EventWindow implements AutoCloseable {
        private final KafkaConsumer<byte[], byte[]> consumer;
        private final String topic;

        private EventWindow(KafkaConsumer<byte[], byte[]> consumer, String topic) {
            this.consumer = consumer;
            this.topic = topic;
            try {
                List<TopicPartition> partitions = new ArrayList<>();
                consumer.partitionsFor(topic, API_TIMEOUT)
                        .forEach(info -> partitions.add(new TopicPartition(topic, info.partition())));
                if (partitions.isEmpty()) {
                    throw new DemoException(DemoError.SERVICE_TIMEOUT);
                }
                consumer.assign(partitions);
                consumer.seekToEnd(partitions);
                // Force position resolution now: the consumer is genuinely
                // established before the continuation is submitted.
                partitions.forEach(consumer::position);
            } catch (DemoException failure) {
                consumer.close(Duration.ZERO);
                throw failure;
            } catch (RuntimeException failure) {
                consumer.close(Duration.ZERO);
                throw new DemoException(DemoError.SERVICE_TIMEOUT);
            }
        }

        ConsumerRecord<byte[], byte[]> readExact(int partition, long offset, Duration timeout) {
            TopicPartition target = new TopicPartition(topic, partition);
            consumer.assign(List.of(target));
            consumer.seek(target, offset);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<byte[], byte[]> record : records.records(target)) {
                    if (record.offset() == offset) {
                        return record;
                    }
                    if (record.offset() > offset) {
                        throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                    }
                }
            }
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }

        @Override
        public void close() {
            consumer.close(Duration.ofSeconds(2));
        }
    }
}
