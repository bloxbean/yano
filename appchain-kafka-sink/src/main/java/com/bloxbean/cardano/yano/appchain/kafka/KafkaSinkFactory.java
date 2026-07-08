package com.bloxbean.cardano.yano.appchain.kafka;

import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Properties;

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

    @Override
    public String scheme() {
        return "kafka";
    }

    @Override
    public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
        String bootstrap = config.get("bootstrap-servers");
        String topic = config.get("topic");
        if (bootstrap == null || bootstrap.isBlank() || topic == null || topic.isBlank()) {
            return List.of();
        }
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, config.getOrDefault("acks", "all"));
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        log.info("Kafka app-chain sink: chain '{}' -> topic '{}' on {}", chainId, topic, bootstrap);
        return List.of(new KafkaStreamSink(chainId, topic, new KafkaProducer<>(props)));
    }
}
