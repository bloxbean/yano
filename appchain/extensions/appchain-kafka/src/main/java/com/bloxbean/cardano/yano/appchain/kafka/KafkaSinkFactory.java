package com.bloxbean.cardano.yano.appchain.kafka;

import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.appchain.kafka.config.KafkaSinkConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ServiceLoader factory for the Kafka bridge (ADR app-layer/006 E3.2). Enabled
 * by config:
 * <pre>
 *   yano.app-chain.sinks.kafka.bootstrap-servers = broker1:9092,broker2:9092
 *   yano.app-chain.sinks.kafka.topic             = my-appchain-blocks
 *   yano.app-chain.sinks.kafka.security-profile  = tls
 * </pre>
 * Returns no sink only when the entire sink configuration is absent. Partial
 * or implicitly plaintext configuration fails startup.
 */
public final class KafkaSinkFactory implements FinalizedStreamSinkFactory {

    private static final Logger log = LoggerFactory.getLogger(KafkaSinkFactory.class);
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
        KafkaSinkConfig parsed = KafkaSinkConfig.parse(config).orElse(null);
        if (parsed == null) {
            return List.of();
        }
        KafkaProducer<String, String> producer = new KafkaProducer<>(
                parsed.producerProperties(), new StringSerializer(), new StringSerializer());
        log.info("Kafka app-chain sink producer initialized for chain '{}' with profile '{}'",
                chainId, parsed.securityProfile());
        return List.of(new KafkaStreamSink(chainId, parsed.topic(), producer,
                parsed.acknowledgementTimeout(), parsed.closeTimeout()));
    }
}
