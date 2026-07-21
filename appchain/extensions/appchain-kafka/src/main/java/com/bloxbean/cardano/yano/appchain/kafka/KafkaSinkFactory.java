package com.bloxbean.cardano.yano.appchain.kafka;

import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * ServiceLoader factory for the Kafka bridge (ADR app-layer/006 E3.2). Enabled
 * by config:
 * <pre>
 *   yano.app-chain.sinks.kafka.bootstrap-servers = broker1:9092,broker2:9092
 *   yano.app-chain.sinks.kafka.topic             = my-appchain-blocks
 *   # optional: yano.app-chain.sinks.kafka.acks (default "all")
 * </pre>
 * Returns no sink (disabled) when bootstrap-servers/topic are absent.
 */
public final class KafkaSinkFactory implements FinalizedStreamSinkFactory {

    private static final Logger log = LoggerFactory.getLogger(KafkaSinkFactory.class);
    private static final Set<String> KNOWN_SETTINGS = Set.of(
            "bootstrap-servers", "topic", "acks", "max-block-ms",
            "delivery-timeout-ms", "close-timeout-ms");

    /** Creates the stateless ServiceLoader factory. */
    public KafkaSinkFactory() {
    }

    @Override
    public String scheme() {
        return "kafka";
    }

    @Override
    public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
        Objects.requireNonNull(chainId, "chainId");
        if (config == null) {
            throw new IllegalArgumentException("Kafka sink config is required");
        }
        for (String key : config.keySet()) {
            if (key == null || !KNOWN_SETTINGS.contains(key)) {
                throw new IllegalArgumentException("Unknown Kafka sink setting");
            }
        }
        String bootstrap = trimmed(config.get("bootstrap-servers"));
        String topic = trimmed(config.get("topic"));
        if (bootstrap == null || bootstrap.isBlank() || topic == null || topic.isBlank()) {
            return List.of();
        }
        requirePrintable(bootstrap, 2_048, "bootstrap-servers");
        if (!topic.matches("[A-Za-z0-9._-]{1,249}") || topic.equals(".") || topic.equals("..")) {
            throw new IllegalArgumentException("Invalid Kafka sink topic");
        }
        String acks = trimmed(config.getOrDefault("acks", "all"));
        if (!"all".equals(acks)) {
            throw new IllegalArgumentException("Kafka sink acks must be 'all'");
        }
        int maxBlockMs = boundedInt(config, "max-block-ms", 15_000, 1_000, 60_000);
        int deliveryTimeoutMs = boundedInt(
                config, "delivery-timeout-ms", 30_000, 30_000, 120_000);
        int closeTimeoutMs = boundedInt(config, "close-timeout-ms", 5_000, 1, 30_000);
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, acks);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ENABLE_METRICS_PUSH_CONFIG, "false");
        props.put(ProducerConfig.METRIC_REPORTER_CLASSES_CONFIG, List.of());
        // Preserve the pre-Kafka-4 sink behavior and keep delivery.timeout.ms
        // valid against request.timeout.ms + linger.ms without inheriting a
        // client-version default.
        props.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        // Bound send()/delivery so a broker outage can't block the sink thread
        // beyond the SinkRunner's expectations (KafkaStreamSink also caps get()).
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Integer.toString(maxBlockMs));
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Integer.toString(deliveryTimeoutMs));
        KafkaProducer<String, String> producer = new KafkaProducer<>(
                props, new StringSerializer(), new StringSerializer());
        log.info("Kafka app-chain sink producer initialized for chain '{}'", chainId);
        return List.of(new KafkaStreamSink(chainId, topic, producer,
                Duration.ofMillis(deliveryTimeoutMs), Duration.ofMillis(closeTimeoutMs)));
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static int boundedInt(Map<String, String> config,
                                  String key,
                                  int fallback,
                                  int minimum,
                                  int maximum) {
        String value = config.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException("Kafka sink setting is outside its safe range");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Kafka sink setting must be an integer");
        }
    }

    private static void requirePrintable(String value, int maximum, String name) {
        if (value.length() > maximum || value.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new IllegalArgumentException("Kafka sink " + name + " must be bounded printable ASCII");
        }
    }
}
