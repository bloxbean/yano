package com.bloxbean.cardano.yano.appchain.kafka.internal;

import com.bloxbean.cardano.yano.appchain.kafka.config.KafkaEffectConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Creates fresh Kafka clients from strict target configuration. No producer is
 * cached or shared across factory invocations, chains, or executors.
 *
 * @hidden internal connector boundary
 */
public final class KafkaProducerClients {
    private KafkaProducerClients() {
    }

    /**
     * Opens one producer client owned by the caller.
     *
     * @param target the validated target configuration
     * @return a fresh bounded producer
     */
    public static KafkaEffectProducer open(KafkaEffectConfig.Target target) {
        Objects.requireNonNull(target, "target");
        Producer<byte[], byte[]> producer = new KafkaProducer<>(
                target.producerProperties(),
                new ByteArraySerializer(),
                new ByteArraySerializer());
        return new DefaultKafkaEffectProducer(
                producer, target.acknowledgementTimeout(), target.closeTimeout());
    }

    private static final class DefaultKafkaEffectProducer implements KafkaEffectProducer {
        private static final Pattern PHYSICAL_TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,249}");
        private static final Pattern HEADER_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
        private static final int MAX_KEY_BYTES = 256;
        private static final int MAX_VALUE_BYTES = 8_192;
        private static final int MAX_HEADERS = 32;
        private static final int MAX_HEADER_VALUE_BYTES = 1_024;
        private static final int MAX_AGGREGATE_HEADER_BYTES = 4_096;

        private final Producer<byte[], byte[]> producer;
        private final Duration acknowledgementTimeout;
        private final Duration closeTimeout;
        private final AtomicBoolean closed = new AtomicBoolean();

        private DefaultKafkaEffectProducer(Producer<byte[], byte[]> producer,
                                           Duration acknowledgementTimeout,
                                           Duration closeTimeout) {
            this.producer = producer;
            this.acknowledgementTimeout = acknowledgementTimeout;
            this.closeTimeout = closeTimeout;
        }

        @Override
        public KafkaProducerAcknowledgement publish(String physicalTopic,
                                                     byte[] key,
                                                     byte[] value,
                                                     Map<String, byte[]> headers)
                throws InterruptedException {
            if (closed.get()) {
                throw new IllegalStateException("Kafka producer is closed");
            }
            String topic = requireTopic(physicalTopic);
            byte[] keySnapshot = boundedCopy(key, MAX_KEY_BYTES, "key");
            byte[] valueSnapshot = boundedCopy(value, MAX_VALUE_BYTES, "value");
            RecordHeaders headerSnapshot = headers(headers);
            ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                    topic, null, null, keySnapshot, valueSnapshot, headerSnapshot);
            try {
                RecordMetadata metadata = producer.send(record).get(
                        acknowledgementTimeout.toMillis(), TimeUnit.MILLISECONDS);
                int keySize = metadata.serializedKeySize() >= 0
                        ? metadata.serializedKeySize() : keySnapshot.length;
                int valueSize = metadata.serializedValueSize() >= 0
                        ? metadata.serializedValueSize() : valueSnapshot.length;
                return new KafkaProducerAcknowledgement(
                        metadata.partition(), metadata.offset(), keySize, valueSize);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (InterruptException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (java.util.concurrent.TimeoutException timedOut) {
                throw new TimeoutException("Kafka acknowledgement timeout");
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof InterruptException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
                if (cause instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                if (cause instanceof Error fatal) {
                    throw fatal;
                }
                throw new KafkaException("Kafka publish failed");
            } catch (CancellationException cancelled) {
                throw new KafkaException("Kafka publish cancelled");
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            boolean interrupted = Thread.currentThread().isInterrupted();
            try {
                producer.close(interrupted ? Duration.ZERO : closeTimeout);
            } catch (InterruptException closeInterrupted) {
                Thread.currentThread().interrupt();
                throw closeInterrupted;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static String requireTopic(String value) {
            if (value == null || !PHYSICAL_TOPIC.matcher(value).matches()
                    || value.equals(".") || value.equals("..")) {
                throw new IllegalArgumentException("invalid Kafka physical topic");
            }
            return value;
        }

        private static byte[] boundedCopy(byte[] value, int maximum, String field) {
            if (value == null || value.length > maximum) {
                throw new IllegalArgumentException("invalid Kafka record " + field);
            }
            return value.clone();
        }

        private static RecordHeaders headers(Map<String, byte[]> values) {
            if (values == null || values.size() > MAX_HEADERS) {
                throw new IllegalArgumentException("invalid Kafka record headers");
            }
            List<Map.Entry<String, byte[]>> ordered = new ArrayList<>(values.entrySet());
            ordered.sort(Comparator.comparing(Map.Entry::getKey,
                    Comparator.nullsFirst(Comparator.naturalOrder())));
            RecordHeaders result = new RecordHeaders();
            int aggregateBytes = 0;
            String previous = null;
            for (Map.Entry<String, byte[]> entry : ordered) {
                String name = entry.getKey();
                byte[] bytes = entry.getValue();
                if (name == null || !HEADER_NAME.matcher(name).matches()
                        || name.equals(previous) || bytes == null
                        || bytes.length > MAX_HEADER_VALUE_BYTES) {
                    throw new IllegalArgumentException("invalid Kafka record header");
                }
                aggregateBytes += name.length() + bytes.length;
                if (aggregateBytes > MAX_AGGREGATE_HEADER_BYTES) {
                    throw new IllegalArgumentException("Kafka record headers exceed the bound");
                }
                result.add(name, bytes.clone());
                previous = name;
            }
            return result;
        }
    }
}
