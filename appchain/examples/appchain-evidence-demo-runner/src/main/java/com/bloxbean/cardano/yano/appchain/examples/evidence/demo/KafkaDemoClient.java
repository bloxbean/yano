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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Kafka topic initializer, scenario event verifier, and bounded retry-window auditor. */
final class KafkaDemoClient implements AutoCloseable {
    private static final Duration API_TIMEOUT = Duration.ofSeconds(20);
    static final int MAX_AUDIT_RECORDS = 16;
    private static final int MAX_AUDIT_HEADERS = 32;
    private static final int MAX_AUDIT_HEADER_BYTES = 4_096;
    private static final int MAX_AUDIT_KEY_BYTES = 1_024;
    private static final int MAX_AUDIT_VALUE_BYTES = 65_536;
    private static final Pattern EFFECT_ID = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CHAIN_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
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
            requireExactPartitionZero(description == null ? List.of()
                    : description.partitions().stream()
                    .map(partition -> partition.partition()).toList());
        } catch (DemoException failure) {
            throw failure;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
    }

    /**
     * Reads a tiny exact snapshot used only by the destructive failover E2E.
     * The fresh demo topic must remain partition 0 with offsets [0,n); any
     * retention, topology, header, offset, or concurrent-append drift fails.
     */
    KafkaAudit audit(int expectedRecords, String expectedEffectId) {
        if (expectedRecords < 0 || expectedRecords > MAX_AUDIT_RECORDS
                || expectedEffectId == null || !EFFECT_ID.matcher(expectedEffectId).matches()) {
            throw new DemoException(DemoError.INVALID_ARGUMENT);
        }
        validateTopic();
        TopicPartition partition = new TopicPartition(settings.physicalTopic(), 0);
        KafkaConsumer<byte[], byte[]> consumer = consumer();
        try {
            consumer.assign(List.of(partition));
            long beginning = onlyOffset(consumer.beginningOffsets(
                    List.of(partition), API_TIMEOUT), partition);
            long end = onlyOffset(consumer.endOffsets(
                    List.of(partition), API_TIMEOUT), partition);
            requireExactWindow(beginning, end, expectedRecords);
            consumer.seek(partition, beginning);

            List<AuditRecord> records = new ArrayList<>(expectedRecords);
            long deadline = System.nanoTime() + API_TIMEOUT.toNanos();
            while (records.size() < expectedRecords && System.nanoTime() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<byte[], byte[]> record : polled.records(partition)) {
                    long expectedOffset = records.size();
                    AuditRecord audited = auditRecord(
                            record, expectedOffset, expectedEffectId);
                    if (!records.isEmpty()) {
                        requireSameRetryContent(records.getFirst(), audited);
                    }
                    records.add(audited);
                    if (records.size() > expectedRecords) {
                        throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                    }
                }
            }
            if (records.size() != expectedRecords) {
                throw new DemoException(DemoError.SERVICE_TIMEOUT);
            }

            long finalBeginning = onlyOffset(consumer.beginningOffsets(
                    List.of(partition), API_TIMEOUT), partition);
            long finalEnd = onlyOffset(consumer.endOffsets(
                    List.of(partition), API_TIMEOUT), partition);
            requireExactWindow(finalBeginning, finalEnd, expectedRecords);
            validateTopic();
            return new KafkaAudit(settings.physicalTopic(), 0,
                    finalBeginning, finalEnd, List.copyOf(records));
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        } finally {
            consumer.close(Duration.ofSeconds(2));
        }
    }

    static void requireExactPartitionZero(List<Integer> partitions) {
        if (!List.of(0).equals(partitions)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    private static long onlyOffset(Map<TopicPartition, Long> offsets,
                                   TopicPartition partition) {
        if (offsets.size() != 1 || !offsets.containsKey(partition)
                || offsets.get(partition) == null || offsets.get(partition) < 0) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        return offsets.get(partition);
    }

    private static void requireExactWindow(long beginning, long end, int expectedRecords) {
        if (beginning != 0 || end != expectedRecords) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    static AuditRecord auditRecord(ConsumerRecord<byte[], byte[]> record,
                                   long expectedOffset,
                                   String expectedEffectId) {
        if (record == null || expectedEffectId == null
                || !EFFECT_ID.matcher(expectedEffectId).matches()
                || record.partition() != 0 || record.offset() != expectedOffset
                || record.key() == null || record.key().length > MAX_AUDIT_KEY_BYTES
                || record.value() == null || record.value().length > MAX_AUDIT_VALUE_BYTES) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        MessageDigest fingerprint = sha256();
        fingerprint.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(record.partition()).array());
        updateFingerprint(fingerprint, record.key());
        updateFingerprint(fingerprint, record.value());

        Map<String, byte[]> values = new LinkedHashMap<>();
        int count = 0;
        int totalBytes = 0;
        for (Header header : record.headers()) {
            count++;
            byte[] value = header.value();
            if (count > MAX_AUDIT_HEADERS || header.key() == null
                    || header.key().isEmpty() || header.key().length() > 128
                    || header.key().chars().anyMatch(character -> character < 0x21
                    || character > 0x7e)
                    || value == null || value.length > 1_024) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
            totalBytes = Math.addExact(totalBytes, header.key().length() + value.length);
            if (totalBytes > MAX_AUDIT_HEADER_BYTES) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
            if (!RESERVED_HEADERS.contains(header.key())
                    || values.putIfAbsent(header.key(), value) != null) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
            updateFingerprint(fingerprint,
                    header.key().getBytes(StandardCharsets.US_ASCII));
            updateFingerprint(fingerprint, value);
        }
        if (!values.keySet().equals(RESERVED_HEADERS)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        requireAscii(values, "yano-effect-id", expectedEffectId);
        requireAscii(values, "yano-effect-type", "kafka.publish");
        requireAscii(values, "yano-payload-version", "1");
        requireAscii(values, "yano-content-type", "application/cbor");
        String chainId = printableAscii(values, "yano-chain-id", 128);
        if (!CHAIN_ID.matcher(chainId).matches()) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        requireCanonicalUnsigned(values, "yano-origin-height", Long.MAX_VALUE);
        requireCanonicalUnsigned(values, "yano-origin-ordinal", Integer.MAX_VALUE);
        return new AuditRecord(expectedOffset, expectedEffectId,
                HexFormat.of().formatHex(fingerprint.digest()));
    }

    static void requireSameRetryContent(AuditRecord first, AuditRecord retry) {
        if (first == null || retry == null
                || !first.recordDigest().equals(retry.recordDigest())) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static void updateFingerprint(MessageDigest fingerprint, byte[] value) {
        fingerprint.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        fingerprint.update(value);
    }

    private static String printableAscii(Map<String, byte[]> values,
                                         String name,
                                         int maximumBytes) {
        byte[] value = values.get(name);
        if (value == null || value.length < 1 || value.length > maximumBytes) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        for (byte character : value) {
            int unsigned = Byte.toUnsignedInt(character);
            if (unsigned < 0x21 || unsigned > 0x7e) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
        }
        return new String(value, StandardCharsets.US_ASCII);
    }

    private static void requireCanonicalUnsigned(Map<String, byte[]> values,
                                                 String name,
                                                 long maximum) {
        String value = printableAscii(values, name, 19);
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        try {
            if (Long.parseLong(value) > maximum) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
        } catch (NumberFormatException invalid) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
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

    record AuditRecord(long offset, String effectId, String recordDigest) {
    }

    record KafkaAudit(String topic, int partition, long beginningOffset,
                      long endOffset, List<AuditRecord> records) {
        String toJson() {
            StringBuilder json = new StringBuilder(256 + records.size() * 100);
            json.append("{\"schemaVersion\":1,\"topic\":\"")
                    .append(topic)
                    .append("\",\"partition\":").append(partition)
                    .append(",\"beginningOffset\":").append(beginningOffset)
                    .append(",\"endOffset\":").append(endOffset)
                    .append(",\"recordCount\":").append(records.size())
                    .append(",\"records\":[");
            for (int index = 0; index < records.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                AuditRecord record = records.get(index);
                json.append("{\"offset\":").append(record.offset())
                        .append(",\"effectId\":\"").append(record.effectId())
                        .append("\",\"recordDigest\":\"")
                        .append(record.recordDigest()).append("\"}");
            }
            return json.append("]}").toString();
        }
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
