package com.bloxbean.cardano.yano.appchain.kafka.config;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaSinkConfigTest {

    @Test
    void emptyIsDisabledButPartialOrImplicitPlaintextConfigFailsClosed() {
        assertThat(KafkaSinkConfig.parse(Map.of())).isEmpty();
        assertThatThrownBy(() -> KafkaSinkConfig.parse(Map.of(
                "bootstrap-servers", "localhost:9092", "topic", "blocks")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("security-profile");
        assertThatThrownBy(() -> KafkaSinkConfig.parse(Map.of(
                "topic", "blocks", "security-profile", "local-demo")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootstrap-servers");
    }

    @Test
    void localDemoIsExplicitBoundedAndUsesDurableProducerSettings() {
        KafkaSinkConfig config = KafkaSinkConfig.parse(local()).orElseThrow();

        assertThat(config.topic()).isEqualTo("finalized.blocks.v1");
        assertThat(config.securityProfile()).isEqualTo("local-demo");
        assertThat(config.acknowledgementTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(config.producerProperties())
                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT")
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
                .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "yano-kafka-finalized-sink");

        Map<String, String> remotePlaintext = new LinkedHashMap<>(local());
        remotePlaintext.put("bootstrap-servers", "broker.example.com:9092");
        assertThatThrownBy(() -> KafkaSinkConfig.parse(remotePlaintext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local/private bootstrap host");
    }

    @Test
    void tlsAndSaslTlsProfilesShareTheHardenedExecutorTransportContract() {
        Map<String, String> tls = secure("tls");
        tls.put("tls.truststore-path", "/tmp/kafka-sink-trust.p12");
        tls.put("tls.truststore-password", "trust-canary");
        KafkaSinkConfig tlsConfig = KafkaSinkConfig.parse(tls).orElseThrow();
        assertThat(tlsConfig.producerProperties())
                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
                .containsEntry(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https")
                .containsEntry(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG,
                        "/tmp/kafka-sink-trust.p12")
                .doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG);

        Map<String, String> sasl = secure("sasl-tls");
        sasl.put("sasl.mechanism", "SCRAM-SHA-512");
        sasl.put("sasl.username", "sink-user");
        sasl.put("sasl.password", "sink-secret-canary");
        KafkaSinkConfig saslConfig = KafkaSinkConfig.parse(sasl).orElseThrow();
        assertThat(saslConfig.producerProperties())
                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
                .containsEntry(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512");
        assertThat(saslConfig.toString())
                .contains("sasl-tls")
                .doesNotContain("broker.example.com", "sink-user", "sink-secret-canary");
    }

    private static Map<String, String> local() {
        return Map.of(
                "bootstrap-servers", "localhost:9092",
                "topic", "finalized.blocks.v1",
                "security-profile", "local-demo");
    }

    private static Map<String, String> secure(String profile) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("bootstrap-servers", "broker.example.com:9093");
        settings.put("topic", "finalized.blocks.v1");
        settings.put("security-profile", profile);
        return settings;
    }
}
