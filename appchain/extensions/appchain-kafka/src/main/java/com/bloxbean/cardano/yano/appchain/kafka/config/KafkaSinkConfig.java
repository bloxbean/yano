package com.bloxbean.cardano.yano.appchain.kafka.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.apache.kafka.clients.producer.ProducerConfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Fail-closed finalized-stream Kafka transport profile. */
public final class KafkaSinkConfig {
    private static final String TARGET = "sink";
    private static final Set<String> KNOWN_SETTINGS = Set.of(
            "bootstrap-servers", "topic", "security-profile", "acks",
            "max-block-ms", "request-timeout-ms", "delivery-timeout-ms",
            "close-timeout-ms",
            "tls.truststore-path", "tls.truststore-password", "tls.truststore-type",
            "tls.keystore-path", "tls.keystore-password", "tls.keystore-type",
            "tls.key-password", "sasl.mechanism", "sasl.username", "sasl.password");
    private static final Set<String> TARGET_SETTINGS = Set.of(
            "bootstrap-servers", "security-profile", "acks", "max-block-ms",
            "request-timeout-ms", "delivery-timeout-ms", "close-timeout-ms",
            "tls.truststore-path", "tls.truststore-password", "tls.truststore-type",
            "tls.keystore-path", "tls.keystore-password", "tls.keystore-type",
            "tls.key-password", "sasl.mechanism", "sasl.username", "sasl.password");

    private final String topic;
    private final String securityProfile;
    private final Properties producerProperties;
    private final Duration acknowledgementTimeout;
    private final Duration closeTimeout;

    private KafkaSinkConfig(String topic,
                            String securityProfile,
                            Properties producerProperties,
                            Duration acknowledgementTimeout,
                            Duration closeTimeout) {
        this.topic = topic;
        this.securityProfile = securityProfile;
        this.producerProperties = copy(producerProperties);
        this.acknowledgementTimeout = acknowledgementTimeout;
        this.closeTimeout = closeTimeout;
    }

    public static Optional<KafkaSinkConfig> parse(Map<String, String> settings) {
        if (settings == null || settings.size() > KNOWN_SETTINGS.size()) {
            throw new IllegalArgumentException("Kafka sink config must be present and bounded");
        }
        for (String key : settings.keySet()) {
            if (key == null || !KNOWN_SETTINGS.contains(key)) {
                throw new IllegalArgumentException("Unknown Kafka sink setting");
            }
        }
        if (settings.isEmpty()) {
            return Optional.empty();
        }
        String bootstrap = settings.get("bootstrap-servers");
        String topic = settings.get("topic");
        String securityProfile = settings.get("security-profile");
        if (bootstrap == null || bootstrap.isBlank()
                || topic == null || topic.isBlank()
                || securityProfile == null || securityProfile.isBlank()) {
            throw new IllegalArgumentException(
                    "Kafka sink requires bootstrap-servers, topic and security-profile together");
        }

        Map<String, String> adapter = new LinkedHashMap<>();
        adapter.put("targets." + TARGET + ".target-id", "finalized-sink-v1");
        for (String key : TARGET_SETTINGS) {
            if (settings.containsKey(key)) {
                adapter.put("targets." + TARGET + "." + key, settings.get(key));
            }
        }
        adapter.put("topics." + TARGET + ".target", TARGET);
        adapter.put("topics." + TARGET + ".name", topic);

        KafkaEffectConfig parsed = KafkaEffectConfig.parse(adapter);
        KafkaEffectConfig.Target target = parsed.target(TARGET).orElseThrow();
        KafkaEffectConfig.Topic resolvedTopic = parsed.topic(TARGET).orElseThrow();
        Properties properties = target.producerProperties();
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "yano-kafka-finalized-sink");
        // Finalized JSON includes body hex and can be roughly twice the
        // canonical block bound. Broker/topic limits remain operator policy.
        int maximumRequestBytes = Math.toIntExact(AppChainConfig.MAX_BLOCK_BYTES * 2 + 65_536);
        properties.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG,
                Integer.toString(maximumRequestBytes));
        properties.put(ProducerConfig.BUFFER_MEMORY_CONFIG,
                Integer.toString(maximumRequestBytes + 4 * 1_024 * 1_024));
        return Optional.of(new KafkaSinkConfig(
                resolvedTopic.physicalName(), target.securityProfile().configValue(),
                properties, target.acknowledgementTimeout(), target.closeTimeout()));
    }

    public String topic() {
        return topic;
    }

    public String securityProfile() {
        return securityProfile;
    }

    public Properties producerProperties() {
        return copy(producerProperties);
    }

    public Duration acknowledgementTimeout() {
        return acknowledgementTimeout;
    }

    public Duration closeTimeout() {
        return closeTimeout;
    }

    @Override
    public String toString() {
        return "KafkaSinkConfig[topicConfigured=true, securityProfile="
                + securityProfile + "]";
    }

    private static Properties copy(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }
}
