package com.bloxbean.cardano.yano.appchain.kafka;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.sink.AppBlockJson;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.concurrent.Future;

/**
 * {@link FinalizedStreamSink} that produces a finalized block's JSON to a Kafka
 * topic (ADR app-layer/006 E3.2). The block height is the record key, so all
 * records for a chain land on the same partition and stay strictly ordered;
 * the framework's per-sink cursor guarantees at-least-once. A synchronous send
 * (get on the ack) makes each delivery durable before the cursor advances.
 */
public final class KafkaStreamSink implements FinalizedStreamSink {

    private final String id;
    private final String topic;
    private final Producer<String, String> producer;

    public KafkaStreamSink(String chainId, String topic, Producer<String, String> producer) {
        this.id = "kafka:" + topic + ":" + chainId;
        this.topic = topic;
        this.producer = producer;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean deliver(AppBlock block) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic, Long.toString(block.height()), AppBlockJson.toJson(block));
            Future<RecordMetadata> ack = producer.send(record);
            ack.get(); // durable before the cursor advances (at-least-once)
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false; // SinkRunner retries the same block next tick
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
