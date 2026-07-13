package com.bloxbean.cardano.yano.appchain.kafka;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E3.2: the Kafka sink produces finalized-block JSON with the height as
 * the key (partition-stable ordering), verified with a MockProducer — no broker.
 */
class KafkaStreamSinkTest {

    @Test
    void deliver_producesBlockJson_keyedByHeight() {
        MockProducer<String, String> producer =
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaStreamSink sink = new KafkaStreamSink("chain-1", "blocks-topic", producer);

        AppBlock block = sampleBlock(7);
        assertThat(sink.deliver(block)).isTrue();

        List<ProducerRecord<String, String>> sent = producer.history();
        assertThat(sent).hasSize(1);
        ProducerRecord<String, String> record = sent.get(0);
        assertThat(record.topic()).isEqualTo("blocks-topic");
        assertThat(record.key()).isEqualTo("7");                       // height key → ordered partition
        assertThat(record.value()).contains("\"height\":7").contains("\"chainId\":\"chain-1\"");
        assertThat(sink.id()).isEqualTo("kafka:blocks-topic:chain-1");
    }

    @Test
    void factory_disabledWithoutConfig_enabledWithBootstrapAndTopic() {
        KafkaSinkFactory factory = new KafkaSinkFactory();
        assertThat(factory.scheme()).isEqualTo("kafka");
        assertThat(factory.create("chain-1", Map.of())).isEmpty();
        assertThat(factory.create("chain-1", Map.of("topic", "t"))).isEmpty();
        // With both present it builds a real producer (connects lazily); just
        // assert one sink is created, then close it.
        var sinks = factory.create("chain-1",
                Map.of("bootstrap-servers", "localhost:9092", "topic", "t"));
        assertThat(sinks).hasSize(1);
        sinks.forEach(s -> {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        });
    }

    @Test
    void deliver_returnsFalseOnProducerError() {
        MockProducer<String, String> producer =
                new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        producer.sendException = new RuntimeException("broker down");
        KafkaStreamSink sink = new KafkaStreamSink("chain-1", "blocks-topic", producer);
        assertThat(sink.deliver(sampleBlock(1))).isFalse(); // SinkRunner will retry
    }

    private static AppBlock sampleBlock(long height) {
        return new AppBlock(2, "chain-1", height, new byte[32], 0, new byte[0], 1000L,
                new byte[32], new byte[32], List.of(), new byte[32],
                new FinalityCert(FinalityCert.SCHEME_ED25519, List.of()));
    }
}
