package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaDemoClientRealIntegrationTest {
    private static final String ENABLED = "yano.kafka.audit.integration.enabled";
    private static final String BOOTSTRAP = "yano.kafka.audit.integration.bootstrap";
    private static final String TOPIC = "yano.kafka.audit.integration.topic";
    private static final String EFFECT_ID = "ab".repeat(32);

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void auditsExactRetryWindowAndRejectsRecordOrPartitionDrift() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty(ENABLED, "false")),
                "real Kafka audit integration is opt-in");
        String bootstrap = System.getProperty(BOOTSTRAP, "").trim();
        Assumptions.assumeTrue(!bootstrap.isEmpty(), "real Kafka bootstrap is required");
        String prefix = System.getProperty(TOPIC, "yano-audit-integration-v1").trim();
        Assumptions.assumeTrue(prefix.matches("[A-Za-z0-9._-]{1,180}"),
                "real Kafka topic prefix must be bounded");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String exactTopic = prefix + "-exact-" + suffix;
        String driftTopic = prefix + "-drift-" + suffix;

        try (AdminClient admin = AdminClient.create(adminProperties(bootstrap))) {
            try {
                DemoConfig.KafkaSettings exact = settings(bootstrap, exactTopic);
                try (KafkaDemoClient client = new KafkaDemoClient(exact);
                     KafkaProducer<byte[], byte[]> producer = producer(bootstrap)) {
                    client.ensureTopic();
                    publish(producer, exactTopic);
                    publish(producer, exactTopic);

                    KafkaDemoClient.KafkaAudit audit = client.audit(2, EFFECT_ID);
                    assertThat(audit.partition()).isZero();
                    assertThat(audit.beginningOffset()).isZero();
                    assertThat(audit.endOffset()).isEqualTo(2);
                    assertThat(audit.records()).extracting(
                                    KafkaDemoClient.AuditRecord::offset)
                            .containsExactly(0L, 1L);
                    assertThat(audit.records()).extracting(
                                    KafkaDemoClient.AuditRecord::recordDigest)
                            .containsOnly(audit.records().getFirst().recordDigest());

                    publish(producer, exactTopic);
                    assertMismatch(() -> client.audit(2, EFFECT_ID));
                }

                admin.createTopics(List.of(new NewTopic(driftTopic, 2, (short) 1)))
                        .all().get(20, TimeUnit.SECONDS);
                try (KafkaDemoClient drift = new KafkaDemoClient(
                        settings(bootstrap, driftTopic))) {
                    assertMismatch(drift::validateTopic);
                }
            } finally {
                deleteTopics(admin, exactTopic, driftTopic);
            }
        }
    }

    private static DemoConfig.KafkaSettings settings(String bootstrap, String topic) {
        return new DemoConfig.KafkaSettings(bootstrap, "primary", "primary-v1",
                "evidence-ready", topic);
    }

    private static Properties adminProperties(String bootstrap) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "20000");
        return properties;
    }

    private static void deleteTopics(AdminClient admin, String... topics) {
        try {
            admin.deleteTopics(List.of(topics)).all().get(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // The broker is disposable and test cleanup must not mask the assertion failure.
        }
    }

    private static KafkaProducer<byte[], byte[]> producer(String bootstrap) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "20000");
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");
        return new KafkaProducer<>(properties);
    }

    private static void publish(KafkaProducer<byte[], byte[]> producer,
                                String topic) throws Exception {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                topic, 0, new byte[]{1}, new byte[]{2});
        record.headers().add("yano-effect-id",
                EFFECT_ID.getBytes(StandardCharsets.US_ASCII));
        record.headers().add("yano-chain-id",
                "evidence-chain".getBytes(StandardCharsets.US_ASCII));
        record.headers().add("yano-effect-type",
                "kafka.publish".getBytes(StandardCharsets.US_ASCII));
        record.headers().add("yano-payload-version",
                "1".getBytes(StandardCharsets.US_ASCII));
        record.headers().add("yano-origin-height",
                "7".getBytes(StandardCharsets.US_ASCII));
        record.headers().add("yano-origin-ordinal",
                "1".getBytes(StandardCharsets.US_ASCII));
        record.headers().add("yano-content-type",
                "application/cbor".getBytes(StandardCharsets.US_ASCII));
        producer.send(record).get(20, TimeUnit.SECONDS);
    }

    private static void assertMismatch(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.EXTERNAL_STATE_MISMATCH);
    }
}
