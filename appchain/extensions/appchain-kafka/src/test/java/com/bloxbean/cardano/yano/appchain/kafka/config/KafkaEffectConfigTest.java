package com.bloxbean.cardano.yano.appchain.kafka.config;

import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaDestinationFingerprint;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaEffectConfigTest {
    private static final String SECRET = "kafka-password-canary";

    @Test
    void emptyConfigurationIsInactiveAndExplicitActivationRequiresACompleteProfile() {
        KafkaEffectConfig empty = KafkaEffectConfig.parse(Map.of());

        assertThat(empty.enabled()).isFalse();
        assertThat(empty.targets()).isEmpty();
        assertThat(empty.topic("events")).isEmpty();
        assertThat(empty.detailArchivePath()).isEmpty();

        assertThatThrownBy(() -> KafkaEffectConfig.parse(Map.of("enabled", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("enabled Kafka executor requires at least one target and topic");

        Map<String, String> disabledSettings = baseSettings();
        disabledSettings.put("enabled", "false");
        KafkaEffectConfig disabled = KafkaEffectConfig.parse(disabledSettings);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.targets()).containsOnlyKeys("primary");
        assertThat(disabled.topic("events")).isPresent();
    }

    @Test
    void localDemoProfileFreezesSafeProducerSettingsAndAliasResolution() {
        KafkaEffectConfig config = KafkaEffectConfig.parse(baseSettings());
        KafkaEffectConfig.Target target = config.target("primary").orElseThrow();
        KafkaEffectConfig.Topic topic = config.topic("events").orElseThrow();
        Properties properties = target.producerProperties();

        assertThat(config.enabled()).isTrue();
        assertThat(target.alias()).isEqualTo("primary");
        assertThat(target.targetId()).isEqualTo("primary-v1");
        assertThat(target.securityProfile()).isEqualTo(KafkaEffectConfig.SecurityProfile.LOCAL_DEMO);
        assertThat(target.acknowledgementTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(target.closeTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties)
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
                .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "yano-kafka-effect-primary-v1")
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
                .containsEntry(ProducerConfig.ENABLE_METRICS_PUSH_CONFIG, "false")
                .containsEntry(ProducerConfig.METRIC_REPORTER_CLASSES_CONFIG, List.of())
                .containsEntry(ProducerConfig.RETRIES_CONFIG, Integer.toString(Integer.MAX_VALUE))
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000")
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000")
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000")
                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
        assertThat(properties)
                .doesNotContainKeys(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);

        assertThat(topic.alias()).isEqualTo("events");
        assertThat(topic.targetAlias()).isEqualTo("primary");
        assertThat(topic.physicalName()).isEqualTo("evidence.events.v1");
        assertThat(topic.destinationFingerprint().bytes()).containsExactly(
                KafkaDestinationFingerprint.compute("primary-v1", "evidence.events.v1").bytes());
        assertThat(config.resolve("primary", "events")).isPresent();
        assertThat(config.resolve("missing", "events")).isEmpty();
        assertThat(config.resolve("primary", "missing")).isEmpty();
    }

    @Test
    void parsedMapsAndClientPropertiesAreDefensiveAndDiagnosticsAreRedacted() {
        Map<String, String> settings = baseSettings();
        settings.put("detail-archive-path", "/tmp/yano-kafka-detail-archive");
        KafkaEffectConfig config = KafkaEffectConfig.parse(settings);
        KafkaEffectConfig.Target target = config.target("primary").orElseThrow();

        Properties first = target.producerProperties();
        first.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, SECRET);
        first.put("untrusted.setting", SECRET);
        Properties second = target.producerProperties();

        assertThat(second.getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
                .isEqualTo("localhost:9092");
        assertThat(second).doesNotContainKey("untrusted.setting");
        assertThatThrownBy(() -> config.targets().put("other", target))
                .isInstanceOf(UnsupportedOperationException.class);

        String rendered = config.toString() + config.safeDiagnostics();
        assertThat(rendered)
                .contains("primary", "events", "local-demo")
                .doesNotContain("localhost:9092")
                .doesNotContain("evidence.events.v1")
                .doesNotContain("/tmp/yano-kafka-detail-archive")
                .doesNotContain(SECRET);
        assertThat(config.safeDiagnostics())
                .containsEntry("enabled", true)
                .containsEntry("detailArchiveConfigured", true);
    }

    @Test
    void tlsMtlsAndSaslTlsProfilesApplyOnlyTheirAllowlistedSecurityMaterial() {
        KafkaEffectConfig.Target tls = KafkaEffectConfig.parse(tlsSettings()).target("primary")
                .orElseThrow();
        Properties tlsProperties = tls.producerProperties();
        assertThat(tlsProperties)
                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
                .containsEntry(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https")
                .containsEntry(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, "/tmp/kafka-trust.p12")
                .containsEntry(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, SECRET)
                .containsEntry(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PKCS12")
                .doesNotContainKey(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG)
                .doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG);

        Map<String, String> mtlsSettings = secureBase("mtls");
        mtlsSettings.put("targets.primary.tls.truststore-path", "/tmp/kafka-trust.jks");
        mtlsSettings.put("targets.primary.tls.truststore-password", SECRET);
        mtlsSettings.put("targets.primary.tls.truststore-type", "jks");
        mtlsSettings.put("targets.primary.tls.keystore-path", "/tmp/kafka-key.p12");
        mtlsSettings.put("targets.primary.tls.keystore-password", SECRET);
        mtlsSettings.put("targets.primary.tls.key-password", "private-key-canary");
        Properties mtls = KafkaEffectConfig.parse(mtlsSettings).target("primary").orElseThrow()
                .producerProperties();
        assertThat(mtls)
                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
                .containsEntry(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, "/tmp/kafka-key.p12")
                .containsEntry(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PKCS12")
                .containsEntry(SslConfigs.SSL_KEY_PASSWORD_CONFIG, "private-key-canary")
                .doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG);

        Map<String, String> saslSettings = secureBase("sasl-tls");
        saslSettings.put("targets.primary.sasl.mechanism", "scram-sha-512");
        saslSettings.put("targets.primary.sasl.username", "service-user");
        saslSettings.put("targets.primary.sasl.password", "p\\ass\"word");
        Properties sasl = KafkaEffectConfig.parse(saslSettings).target("primary").orElseThrow()
                .producerProperties();
        assertThat(sasl)
                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
                .containsEntry(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512")
                .doesNotContainKey(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG);
        assertThat(sasl.getProperty(SaslConfigs.SASL_JAAS_CONFIG))
                .contains("ScramLoginModule", "username=\"service-user\"")
                .contains("password=\"p\\\\ass\\\"word\"");
    }

    @Test
    void localDemoAcceptsOnlyExplicitLocalOrPrivateHosts() {
        for (String host : new String[]{
                "localhost:9092", "127.0.0.1:9092", "10.2.3.4:9092", "172.31.255.1:9092",
                "192.168.10.4:9092", "[::1]:9092", "[fc00::1]:9092", "[fe80::1]:9092"}) {
            Map<String, String> settings = baseSettings();
            settings.put("targets.primary.bootstrap-servers", host);
            assertThat(KafkaEffectConfig.parse(settings).enabled()).as(host).isTrue();
        }

        for (String host : new String[]{
                "broker:9092", "node.local:9092", "node.internal:9092",
                "example.com:9092", "8.8.8.8:9092", "172.32.0.1:9092",
                "192.169.1.1:9092", "[2001:db8::1]:9092", "010.0.0.1:9092"}) {
            Map<String, String> settings = baseSettings();
            settings.put("targets.primary.bootstrap-servers", host);
            assertThatThrownBy(() -> KafkaEffectConfig.parse(settings)).as(host)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("local-demo Kafka profile requires a local/private bootstrap host");
        }

        Map<String, String> publicTls = tlsSettings();
        publicTls.put("targets.primary.bootstrap-servers", "broker.example.com:9093");
        assertThat(KafkaEffectConfig.parse(publicTls).enabled()).isTrue();
    }

    @Test
    void timeoutAndBooleanValuesUseExactBoundedSyntax() {
        Map<String, String> settings = baseSettings();
        settings.put("targets.primary.max-block-ms", "100");
        settings.put("targets.primary.request-timeout-ms", "1000");
        settings.put("targets.primary.delivery-timeout-ms", "1000");
        settings.put("targets.primary.close-timeout-ms", "30000");
        KafkaEffectConfig.Target target = KafkaEffectConfig.parse(settings).target("primary")
                .orElseThrow();
        assertThat(target.acknowledgementTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(target.closeTimeout()).isEqualTo(Duration.ofSeconds(30));

        assertRejectedSetting("targets.primary.max-block-ms", "99");
        assertRejectedSetting("targets.primary.request-timeout-ms", "999");
        assertRejectedSetting("targets.primary.delivery-timeout-ms", "60001");
        assertRejectedSetting("targets.primary.close-timeout-ms", "01");

        Map<String, String> inverted = baseSettings();
        inverted.put("targets.primary.request-timeout-ms", "5000");
        inverted.put("targets.primary.delivery-timeout-ms", "4999");
        assertThatThrownBy(() -> KafkaEffectConfig.parse(inverted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kafka integer setting out of range: delivery-timeout-ms");

        assertThatThrownBy(() -> KafkaEffectConfig.parse(Map.of("enabled", "TRUE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kafka boolean setting must be true or false: enabled");
    }

    @Test
    void unknownAmbiguousAndUnsafeSettingsFailClosed() {
        assertRejectedSetting("targets.primary.unknown", "value");
        assertRejectedSetting("arbitrary.kafka.property", "value");
        assertRejectedSetting("targets.primary.acks", "1");
        assertRejectedSetting("topics.events.name", "../events");
        assertRejectedSetting("topics.events.name", ".");
        assertRejectedSetting("targets.primary.bootstrap-servers", "localhost:0");
        assertRejectedSetting("targets.primary.bootstrap-servers", " localhost:9092");

        Map<String, String> nullValue = baseSettings();
        nullValue.put("targets.primary.target-id", null);
        assertThatThrownBy(() -> KafkaEffectConfig.parse(nullValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid or oversized Kafka executor value");

        Map<String, String> control = baseSettings();
        control.put("targets.primary.bootstrap-servers", "localhost:9092\nsecret");
        assertThatThrownBy(() -> KafkaEffectConfig.parse(control))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid or oversized Kafka executor value");
    }

    @Test
    void securityProfilesRejectIncompleteOrCrossProfileCredentials() {
        Map<String, String> localWithSecret = baseSettings();
        localWithSecret.put("targets.primary.sasl.password", SECRET);
        assertThatThrownBy(() -> KafkaEffectConfig.parse(localWithSecret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("local-demo profile does not accept TLS or SASL settings");

        Map<String, String> tlsMissingPassword = secureBase("tls");
        tlsMissingPassword.put("targets.primary.tls.truststore-path", "/tmp/trust.p12");
        assertThatThrownBy(() -> KafkaEffectConfig.parse(tlsMissingPassword))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("custom truststore requires path and password together");

        Map<String, String> mtlsMissingKey = secureBase("mtls");
        assertThatThrownBy(() -> KafkaEffectConfig.parse(mtlsMissingKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("missing required Kafka executor setting: tls.keystore-path");

        Map<String, String> saslMissingPassword = secureBase("sasl-tls");
        saslMissingPassword.put("targets.primary.sasl.mechanism", "PLAIN");
        saslMissingPassword.put("targets.primary.sasl.username", "service-user");
        assertThatThrownBy(() -> KafkaEffectConfig.parse(saslMissingPassword))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("missing required Kafka executor setting: sasl.password");

        Map<String, String> unsupported = secureBase("sasl-tls");
        unsupported.put("targets.primary.sasl.mechanism", "OAUTHBEARER");
        unsupported.put("targets.primary.sasl.username", "service-user");
        unsupported.put("targets.primary.sasl.password", SECRET);
        assertThatThrownBy(() -> KafkaEffectConfig.parse(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported Kafka SASL mechanism");
    }

    @Test
    void pathsTargetIdentityTopicBindingsAndCollectionBoundsAreStrict() {
        Map<String, String> relativeArchive = baseSettings();
        relativeArchive.put("detail-archive-path", "relative/archive");
        assertThatThrownBy(() -> KafkaEffectConfig.parse(relativeArchive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an absolute normalized non-root path");

        Map<String, String> rootArchive = baseSettings();
        rootArchive.put("detail-archive-path", Path.of("/").toString());
        assertThatThrownBy(() -> KafkaEffectConfig.parse(rootArchive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an absolute normalized non-root path");

        Map<String, String> unknownTarget = baseSettings();
        unknownTarget.put("topics.events.target", "secondary");
        assertThatThrownBy(() -> KafkaEffectConfig.parse(unknownTarget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kafka topic references an unknown target");

        Map<String, String> duplicateId = baseSettings();
        duplicateId.put("targets.secondary.target-id", "primary-v1");
        duplicateId.put("targets.secondary.bootstrap-servers", "localhost:9093");
        duplicateId.put("targets.secondary.security-profile", "local-demo");
        assertThatThrownBy(() -> KafkaEffectConfig.parse(duplicateId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate Kafka target-id");

        Map<String, String> tooManyTargets = baseSettings();
        for (int index = 0; index < 17; index++) {
            String alias = "target-" + index;
            tooManyTargets.put("targets." + alias + ".target-id", "identity-" + index);
            tooManyTargets.put("targets." + alias + ".bootstrap-servers", "localhost:9092");
            tooManyTargets.put("targets." + alias + ".security-profile", "local-demo");
        }
        assertThatThrownBy(() -> KafkaEffectConfig.parse(tooManyTargets))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("too many Kafka targets or topics");
    }

    private static void assertRejectedSetting(String key, String value) {
        Map<String, String> settings = baseSettings();
        settings.put(key, value);
        assertThatThrownBy(() -> KafkaEffectConfig.parse(settings))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> baseSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("targets.primary.target-id", "primary-v1");
        settings.put("targets.primary.bootstrap-servers", "localhost:9092");
        settings.put("targets.primary.security-profile", "local-demo");
        settings.put("topics.events.target", "primary");
        settings.put("topics.events.name", "evidence.events.v1");
        return settings;
    }

    private static Map<String, String> secureBase(String profile) {
        Map<String, String> settings = baseSettings();
        settings.put("targets.primary.bootstrap-servers", "broker.example.com:9093");
        settings.put("targets.primary.security-profile", profile);
        return settings;
    }

    private static Map<String, String> tlsSettings() {
        Map<String, String> settings = secureBase("tls");
        settings.put("targets.primary.tls.truststore-path", "/tmp/kafka-trust.p12");
        settings.put("targets.primary.tls.truststore-password", SECRET);
        settings.put("targets.primary.tls.truststore-type", "PKCS12");
        return settings;
    }
}
