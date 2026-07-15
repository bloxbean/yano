package com.bloxbean.cardano.yano.appchain.kafka.effects;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.api.appchain.sink.AppBlockJson;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaHeader;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import com.bloxbean.cardano.yano.appchain.kafka.KafkaSinkFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in real-broker coverage for both Kafka plugin contributions. The test
 * never performs implicit network access: it is skipped unless the bootstrap
 * system property is supplied by an integration-test environment.
 */
class KafkaPublishRealIntegrationTest {

    private static final int PARTITION_COUNT = 3;
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void publishesEffectsAndFinalizedBlocksThroughOneRealBroker() throws Exception {
        String bootstrap = System.getProperty("yano.kafka.integration.bootstrap", "").trim();
        Assumptions.assumeTrue(!bootstrap.isEmpty(),
                "set JAVA_TOOL_OPTIONS=-Dyano.kafka.integration.bootstrap=localhost:9092 to run");

        String topicPrefix = System.getProperty(
                "yano.kafka.integration.topic", "yano-kafka-integration-v1").trim();
        assertThat(topicPrefix)
                .as("integration topic prefix")
                .matches("[A-Za-z0-9._-]{1,180}");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String effectTopic = topicPrefix + "-effects-" + suffix;
        String sinkTopic = topicPrefix + "-blocks-" + suffix;

        try (AdminClient admin = AdminClient.create(adminProperties(bootstrap))) {
            try {
                admin.createTopics(List.of(
                                new NewTopic(effectTopic, PARTITION_COUNT, (short) 1),
                                new NewTopic(sinkTopic, PARTITION_COUNT, (short) 1)))
                        .all().get(CLIENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

                exerciseBothContributions(bootstrap, effectTopic, sinkTopic);
            } finally {
                deleteTopics(admin, effectTopic, sinkTopic);
            }
        }
    }

    private static void exerciseBothContributions(String bootstrap,
                                                   String effectTopic,
                                                   String sinkTopic) throws Exception {
        Map<String, String> effectSettings = effectSettings(bootstrap, effectTopic);
        Map<String, String> sinkSettings = Map.of(
                "bootstrap-servers", bootstrap,
                "topic", sinkTopic,
                "max-block-ms", "2000",
                "delivery-timeout-ms", "30000",
                "close-timeout-ms", "1000");

        AppEffectExecutor executor = new KafkaEffectExecutorFactory()
                .create(KafkaEffectTestSupport.CHAIN_ID, effectSettings).getFirst();
        FinalizedStreamSink sink = new KafkaSinkFactory()
                .create(KafkaEffectTestSupport.CHAIN_ID, sinkSettings).getFirst();
        try {
            byte[] firstBody = jsonBody("first");
            KafkaPublishCommandV1 firstCommand = command(
                    new byte[0], firstBody, new byte[]{7, 8, 9});
            PendingEffect firstEffect = effect(17, 3, firstCommand);

            int firstExpectedPartition = partition(firstEffect.idHash());
            byte[] secondKey = keyForDifferentPartition(firstExpectedPartition);
            byte[] secondBody = jsonBody("second");
            KafkaPublishCommandV1 secondCommand = command(
                    secondKey, secondBody, new byte[]{10, 11, 12});
            PendingEffect secondEffect = effect(18, 0, secondCommand);

            AppBlock firstBlock = block(21, 1_000L);
            AppBlock secondBlock = block(22, 2_000L);

            // Keep both independently-owned producers alive and interleave the
            // two contribution types to prove that sink and effect paths can
            // coexist in one plugin and broker without serializer interference.
            assertThat(sink.deliver(firstBlock)).isTrue();
            KafkaPublishReceiptV1 firstReceipt = confirmed(executor.execute(
                    KafkaEffectTestSupport.context(1), firstEffect));
            assertThat(sink.deliver(secondBlock)).isTrue();
            KafkaPublishReceiptV1 secondReceipt = confirmed(executor.execute(
                    KafkaEffectTestSupport.context(1), secondEffect));

            assertThat(firstReceipt.destinationFingerprint()).containsExactly(
                    KafkaDestinationFingerprint.compute("integration-v1", effectTopic).bytes());
            assertThat(secondReceipt.destinationFingerprint())
                    .containsExactly(firstReceipt.destinationFingerprint());
            assertThat(firstReceipt.partition()).isEqualTo(firstExpectedPartition);
            assertThat(secondReceipt.partition()).isEqualTo(partition(secondKey));
            assertThat(secondReceipt.partition()).isNotEqualTo(firstReceipt.partition());

            List<ConsumerRecord<byte[], byte[]>> records = consume(
                    bootstrap, List.of(effectTopic, sinkTopic), 4);
            assertEffectRecord(records, effectTopic, firstReceipt, firstEffect,
                    firstBody, firstEffect.idHash(), new byte[]{7, 8, 9});
            assertEffectRecord(records, effectTopic, secondReceipt, secondEffect,
                    secondBody, secondKey, new byte[]{10, 11, 12});
            assertSinkRecords(records, sinkTopic, firstBlock, secondBlock);
        } finally {
            try {
                sink.close();
            } finally {
                executor.close();
            }
        }
    }

    private static Map<String, String> effectSettings(String bootstrap, String topic) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("targets.integration.target-id", "integration-v1");
        settings.put("targets.integration.bootstrap-servers", bootstrap);
        settings.put("targets.integration.security-profile", "local-demo");
        settings.put("targets.integration.max-block-ms", "2000");
        settings.put("targets.integration.request-timeout-ms", "3000");
        settings.put("targets.integration.delivery-timeout-ms", "5000");
        settings.put("targets.integration.close-timeout-ms", "1000");
        settings.put("topics.events.target", "integration");
        settings.put("topics.events.name", topic);
        return settings;
    }

    private static KafkaPublishCommandV1 command(byte[] key,
                                                  byte[] body,
                                                  byte[] traceId) {
        // The physical topic is deliberately not placed in the command. The
        // payload carries only aliases; configuration resolves the destination.
        return new KafkaPublishCommandV1(
                "integration", "events", key, "application/json", body,
                List.of(new KafkaHeader("trace-id", traceId)));
    }

    private static PendingEffect effect(long height,
                                        int ordinal,
                                        KafkaPublishCommandV1 command) {
        EffectRecord record = new EffectRecord(
                EffectRecord.RECORD_VERSION,
                KafkaEffectTestSupport.CHAIN_ID,
                height,
                ordinal,
                KafkaPublishExecutor.TYPE,
                command.encode(),
                "integration",
                FinalityGate.APP_FINAL,
                ResultPolicy.CHAIN,
                1_000,
                null);
        return PendingEffect.of(record);
    }

    private static KafkaPublishReceiptV1 confirmed(EffectExecution outcome) {
        assertThat(outcome).isInstanceOf(EffectExecution.Confirmed.class);
        return KafkaPublishReceiptV1.decode(
                ((EffectExecution.Confirmed) outcome).externalRef());
    }

    private static List<ConsumerRecord<byte[], byte[]>> consume(String bootstrap,
                                                                List<String> topics,
                                                                int expectedCount) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "yano-kafka-integration-consumer");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "yano-kafka-integration-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (String topic : topics) {
                consumer.partitionsFor(topic, CLIENT_TIMEOUT).forEach(info ->
                        partitions.add(new TopicPartition(topic, info.partition())));
            }
            assertThat(partitions).hasSize(topics.size() * PARTITION_COUNT);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + CLIENT_TIMEOUT.toNanos();
            while (records.size() < expectedCount && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
            assertThat(records).hasSize(expectedCount);
            return List.copyOf(records);
        }
    }

    private static void assertEffectRecord(List<ConsumerRecord<byte[], byte[]>> records,
                                           String topic,
                                           KafkaPublishReceiptV1 receipt,
                                           PendingEffect effect,
                                           byte[] expectedBody,
                                           byte[] expectedKey,
                                           byte[] traceId) {
        ConsumerRecord<byte[], byte[]> record = records.stream()
                .filter(candidate -> candidate.topic().equals(topic)
                        && candidate.partition() == receipt.partition()
                        && candidate.offset() == receipt.offset())
                .findFirst()
                .orElseThrow(() -> new AssertionError("acknowledged Kafka record was not consumed"));

        assertThat(record.key()).containsExactly(expectedKey);
        assertThat(record.value()).containsExactly(expectedBody);
        Map<String, byte[]> headers = uniqueHeaders(record);
        assertThat(headers.keySet()).containsExactlyInAnyOrder(
                "trace-id",
                "yano-effect-id",
                "yano-chain-id",
                "yano-effect-type",
                "yano-payload-version",
                "yano-origin-height",
                "yano-origin-ordinal",
                "yano-content-type");
        assertHeader(headers, "trace-id", traceId);
        assertHeader(headers, "yano-effect-id", ascii(HexFormat.of().formatHex(effect.idHash())));
        assertHeader(headers, "yano-chain-id", utf8(KafkaEffectTestSupport.CHAIN_ID));
        assertHeader(headers, "yano-effect-type", ascii(KafkaPublishExecutor.TYPE));
        assertHeader(headers, "yano-payload-version", ascii("1"));
        assertHeader(headers, "yano-origin-height", ascii(Long.toString(effect.effectId().height())));
        assertHeader(headers, "yano-origin-ordinal", ascii(Integer.toString(effect.effectId().ordinal())));
        assertHeader(headers, "yano-content-type", ascii("application/json"));
    }

    private static void assertSinkRecords(List<ConsumerRecord<byte[], byte[]>> records,
                                          String topic,
                                          AppBlock first,
                                          AppBlock second) {
        List<ConsumerRecord<byte[], byte[]>> sinkRecords = records.stream()
                .filter(record -> record.topic().equals(topic))
                .toList();
        assertThat(sinkRecords).hasSize(2);

        ConsumerRecord<byte[], byte[]> firstRecord = recordWithValue(
                sinkRecords, utf8(AppBlockJson.toJson(first)));
        ConsumerRecord<byte[], byte[]> secondRecord = recordWithValue(
                sinkRecords, utf8(AppBlockJson.toJson(second)));
        assertThat(firstRecord.key()).containsExactly(utf8(KafkaEffectTestSupport.CHAIN_ID));
        assertThat(secondRecord.key()).containsExactly(utf8(KafkaEffectTestSupport.CHAIN_ID));
        assertThat(firstRecord.partition()).isEqualTo(secondRecord.partition());
        assertThat(firstRecord.offset()).isLessThan(secondRecord.offset());
    }

    private static ConsumerRecord<byte[], byte[]> recordWithValue(
            List<ConsumerRecord<byte[], byte[]>> records,
            byte[] expectedValue) {
        return records.stream()
                .filter(record -> Arrays.equals(expectedValue, record.value()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected Kafka record was not consumed"));
    }

    private static Map<String, byte[]> uniqueHeaders(ConsumerRecord<byte[], byte[]> record) {
        Map<String, byte[]> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            byte[] previous = headers.put(header.key(), header.value());
            assertThat(previous).as("duplicate header %s", header.key()).isNull();
        }
        return headers;
    }

    private static void assertHeader(Map<String, byte[]> headers, String name, byte[] expected) {
        assertThat(headers).containsKey(name);
        assertThat(headers.get(name)).containsExactly(expected);
    }

    private static int partition(byte[] key) {
        return Utils.toPositive(Utils.murmur2(key)) % PARTITION_COUNT;
    }

    private static byte[] keyForDifferentPartition(int excludedPartition) {
        for (int attempt = 0; attempt < 1_000; attempt++) {
            byte[] key = ascii("explicit-key-" + attempt);
            if (partition(key) != excludedPartition) {
                return key;
            }
        }
        throw new AssertionError("could not construct a key for another Kafka partition");
    }

    private static AppBlock block(long height, long timestamp) {
        byte[] stateRoot = new byte[32];
        stateRoot[0] = (byte) height;
        return new AppBlock(
                AppBlock.BLOCK_VERSION,
                KafkaEffectTestSupport.CHAIN_ID,
                height,
                new byte[32],
                0,
                new byte[0],
                timestamp,
                new byte[32],
                stateRoot,
                List.of(),
                new byte[32],
                FinalityCert.empty());
    }

    private static byte[] jsonBody(String id) {
        return utf8("{\"integrationId\":\"" + id + "\"}");
    }

    private static Properties adminProperties(String bootstrap) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "yano-kafka-integration-admin");
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
        return properties;
    }

    private static void deleteTopics(AdminClient admin, String... topics) {
        try {
            admin.deleteTopics(Set.of(topics)).all()
                    .get(CLIENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Best-effort cleanup: unique names keep a broker without topic
            // deletion support deterministic on subsequent executions.
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
