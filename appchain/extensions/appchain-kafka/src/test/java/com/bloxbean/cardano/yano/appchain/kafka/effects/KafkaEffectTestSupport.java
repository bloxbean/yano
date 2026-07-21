package com.bloxbean.cardano.yano.appchain.kafka.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailHash;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaHeader;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.kafka.config.KafkaEffectConfig;
import com.bloxbean.cardano.yano.appchain.kafka.internal.KafkaEffectProducer;
import com.bloxbean.cardano.yano.appchain.kafka.internal.KafkaProducerAcknowledgement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class KafkaEffectTestSupport {
    static final String CHAIN_ID = "test-chain";
    static final String TARGET_ALIAS = "primary";
    static final String TARGET_ID = "primary-v1";
    static final String TOPIC_ALIAS = "events";
    static final String PHYSICAL_TOPIC = "evidence.events.v1";

    private KafkaEffectTestSupport() {
    }

    static Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("targets.primary.target-id", TARGET_ID);
        settings.put("targets.primary.bootstrap-servers", "localhost:9092");
        settings.put("targets.primary.security-profile", "local-demo");
        settings.put("topics.events.target", TARGET_ALIAS);
        settings.put("topics.events.name", PHYSICAL_TOPIC);
        return settings;
    }

    static KafkaEffectConfig config() {
        return KafkaEffectConfig.parse(settings());
    }

    static KafkaPublishCommandV1 command() {
        return command(new byte[0]);
    }

    static KafkaPublishCommandV1 command(byte[] key) {
        return new KafkaPublishCommandV1(TARGET_ALIAS, TOPIC_ALIAS, key,
                "application/json", "{\"ready\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                List.of(new KafkaHeader("trace-id", new byte[]{7, 8, 9})));
    }

    static PendingEffect effect(byte[] payload) {
        return effect(KafkaPublishExecutor.TYPE, payload, null);
    }

    static PendingEffect effect(String type, byte[] payload, byte[] idHash) {
        EffectRecord record = new EffectRecord(1, CHAIN_ID, 17, 3, type, payload,
                "demo", FinalityGate.APP_FINAL, ResultPolicy.CHAIN, 100, null);
        return idHash == null ? PendingEffect.of(record) : new PendingEffect(record, idHash);
    }

    static EffectExecutionContext context(int attempt) {
        return context(CHAIN_ID, attempt, new byte[0]);
    }

    static EffectExecutionContext context(String chainId, int attempt) {
        return context(chainId, attempt, new byte[0]);
    }

    static EffectExecutionContext context(int attempt, byte[] submittedRef) {
        return context(CHAIN_ID, attempt, submittedRef);
    }

    static EffectExecutionContext context(String chainId, int attempt, byte[] submittedRef) {
        byte[] reference = submittedRef != null ? submittedRef.clone() : null;
        return new EffectExecutionContext() {
            @Override public String chainId() { return chainId; }
            @Override public long tipHeight() { return 20; }
            @Override public long anchoredHeight() { return 19; }
            @Override public int attempt() { return attempt; }
            @Override public byte[] submittedRef() {
                return reference != null ? reference.clone() : null;
            }
            @Override public Map<String, String> settings() { return Map.of(); }
        };
    }

    @FunctionalInterface
    interface PublishBehavior {
        KafkaProducerAcknowledgement publish(int call,
                                             String topic,
                                             byte[] key,
                                             byte[] value,
                                             Map<String, byte[]> headers)
                throws InterruptedException;
    }

    static final class RecordingProducer implements KafkaEffectProducer {
        private final PublishBehavior behavior;
        private final List<PublishedRecord> records = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        RecordingProducer(PublishBehavior behavior) {
            this.behavior = behavior;
        }

        static RecordingProducer acknowledging() {
            return new RecordingProducer((call, topic, key, value, headers) ->
                    new KafkaProducerAcknowledgement(3, 42, key.length, value.length));
        }

        @Override
        public KafkaProducerAcknowledgement publish(String physicalTopic,
                                                     byte[] key,
                                                     byte[] value,
                                                     Map<String, byte[]> headers)
                throws InterruptedException {
            int call = calls.incrementAndGet();
            records.add(new PublishedRecord(physicalTopic, key, value, headers));
            return behavior.publish(call, physicalTopic, key.clone(), value.clone(), copy(headers));
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCalls.incrementAndGet();
            }
        }

        int calls() {
            return calls.get();
        }

        int closeCalls() {
            return closeCalls.get();
        }

        boolean closed() {
            return closed.get();
        }

        List<PublishedRecord> records() {
            synchronized (records) {
                return List.copyOf(records);
            }
        }
    }

    record PublishedRecord(String topic, byte[] key, byte[] value, Map<String, byte[]> headers) {
        PublishedRecord {
            key = key.clone();
            value = value.clone();
            headers = copy(headers);
        }

        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] value() { return value.clone(); }
        @Override public Map<String, byte[]> headers() { return copy(headers); }
    }

    static final class MemoryDetailArchive implements ConnectorDetailArchive {
        private final Map<String, byte[]> entries = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile IOException archiveFailure;
        private volatile RuntimeException runtimeFailure;

        @Override
        public ConnectorDetailHash archive(ConnectorDetailDocumentV1 document) throws IOException {
            if (closed.get()) {
                throw new IOException("archive closed");
            }
            if (archiveFailure != null) {
                throw archiveFailure;
            }
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            byte[] bytes = document.encode();
            ConnectorDetailHash hash = ConnectorDetailHash.compute(bytes);
            entries.putIfAbsent(hex(hash.bytes()), bytes.clone());
            return hash;
        }

        @Override
        public Optional<ConnectorDetailDocumentV1> retrieve(ConnectorDetailHash hash)
                throws IOException {
            if (closed.get()) {
                throw new IOException("archive closed");
            }
            byte[] bytes = entries.get(hex(hash.bytes()));
            return bytes == null ? Optional.empty()
                    : Optional.of(ConnectorDetailDocumentV1.decode(bytes));
        }

        Optional<byte[]> bytes(byte[] hash) {
            byte[] value = entries.get(hex(hash));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        void failWith(IOException failure) {
            archiveFailure = failure;
        }

        void failWith(RuntimeException failure) {
            runtimeFailure = failure;
        }

        void clearFailure() {
            archiveFailure = null;
            runtimeFailure = null;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static Map<String, byte[]> copy(Map<String, byte[]> source) {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        source.forEach((name, value) -> copy.put(name, value.clone()));
        return Collections.unmodifiableMap(copy);
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    static boolean contains(byte[] left, byte[] right) {
        return Arrays.equals(left, right);
    }
}
