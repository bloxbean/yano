package com.bloxbean.cardano.yano.appchain.kafka;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.sink.AppBlockJson;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link FinalizedStreamSink} that produces a finalized block's JSON to a Kafka
 * topic (ADR app-layer/006 E3.2). The stable chain id is the record key, so all
 * records for a chain land on the same partition and stay strictly ordered;
 * the framework's per-sink cursor guarantees at-least-once. A synchronous send
 * (get on the ack) makes each delivery durable before the cursor advances.
 */
public final class KafkaStreamSink implements FinalizedStreamSink {

    private static final Duration DEFAULT_ACK_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final String id;
    private final String topic;
    private final Producer<String, String> producer;
    private final Duration ackTimeout;
    private final Duration closeTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a sink that owns the supplied producer until {@link #close()}.
     *
     * @param chainId stable app-chain identifier and Kafka record key
     * @param topic configured physical Kafka topic
     * @param producer producer owned by this sink
     */
    public KafkaStreamSink(String chainId, String topic, Producer<String, String> producer) {
        this(chainId, topic, producer, DEFAULT_ACK_TIMEOUT, DEFAULT_CLOSE_TIMEOUT);
    }

    KafkaStreamSink(String chainId,
                    String topic,
                    Producer<String, String> producer,
                    Duration ackTimeout,
                    Duration closeTimeout) {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(topic, "topic");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.ackTimeout = requirePositive(ackTimeout, "ackTimeout");
        this.closeTimeout = requirePositive(closeTimeout, "closeTimeout");
        this.id = "kafka:" + topic + ":" + chainId;
        this.topic = topic;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean deliver(AppBlock block) {
        if (closed.get()) {
            return false;
        }
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic, block.chainId(), AppBlockJson.toJson(block));
            Future<RecordMetadata> ack = producer.send(record);
            // Bounded wait — durable before the cursor advances (at-least-once)
            // but never blocks the sink thread indefinitely on a broker outage.
            ack.get(ackTimeout.toNanos(), TimeUnit.NANOSECONDS);
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
        if (closed.compareAndSet(false, true)) {
            producer.close(closeTimeout);
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
