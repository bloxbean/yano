package com.bloxbean.cardano.yano.appchain.ipfs.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailHash;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.ipfs.config.IpfsEffectConfig;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsPinClient;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.PinState;

import java.io.IOException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class IpfsEffectTestSupport {
    static final String CHAIN_ID = "test-chain";
    static final String TARGET_ALIAS = "evidence";
    static final String TARGET_ID = "kubo-local-v1";
    static final String REPLICATION_POLICY = "local-one";
    static final CanonicalCid CID = rawCid(7);

    private IpfsEffectTestSupport() {
    }

    static Map<String, String> settings() {
        return settings(true);
    }

    static Map<String, String> settings(boolean recursive) {
        Map<String, String> settings = new LinkedHashMap<>();
        String prefix = "targets." + TARGET_ALIAS + ".";
        settings.put(prefix + "target-id", TARGET_ID);
        settings.put(prefix + "api-url", "http://127.0.0.1:5001");
        settings.put(prefix + "security-profile", "local-demo");
        settings.put(prefix + "allowed-codecs", "raw");
        settings.put(prefix + "recursive", Boolean.toString(recursive));
        settings.put(prefix + "replication-policy", REPLICATION_POLICY);
        settings.put(prefix + "connect-timeout-ms", "250");
        settings.put(prefix + "request-timeout-ms", "1000");
        settings.put(prefix + "close-timeout-ms", "250");
        return settings;
    }

    static IpfsEffectConfig config() {
        return IpfsEffectConfig.parse(settings());
    }

    static IpfsEffectConfig config(boolean recursive) {
        return IpfsEffectConfig.parse(settings(recursive));
    }

    static IpfsPinCommandV1 command() {
        return command(CID, true, REPLICATION_POLICY);
    }

    static IpfsPinCommandV1 command(CanonicalCid cid,
                                    boolean recursive,
                                    String replicationPolicy) {
        return new IpfsPinCommandV1(TARGET_ALIAS, cid, recursive, replicationPolicy);
    }

    static PendingEffect effect(byte[] payload) {
        return effect(IpfsPinExecutor.TYPE, payload, null);
    }

    static PendingEffect effect(String type, byte[] payload, byte[] idHash) {
        EffectRecord record = new EffectRecord(1, CHAIN_ID, 17, 3, type, payload,
                "demo", FinalityGate.APP_FINAL, ResultPolicy.CHAIN, 100, null);
        return idHash == null ? PendingEffect.of(record) : new PendingEffect(record, idHash);
    }

    static EffectExecutionContext context(int attempt) {
        return context(CHAIN_ID, attempt, new byte[0]);
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

    static CanonicalCid rawCid(int seed) {
        byte[] value = new byte[36];
        value[0] = 1;
        value[1] = 0x55;
        value[2] = 0x12;
        value[3] = 0x20;
        for (int index = 4; index < value.length; index++) {
            value[index] = (byte) (seed + index);
        }
        return CanonicalCid.fromBytes(value);
    }

    static class RecordingClient implements IpfsPinClient {
        private final AtomicReference<PinState> state;
        private final List<byte[]> observedKeys = Collections.synchronizedList(
                new java.util.ArrayList<>());
        private final AtomicInteger probes = new AtomicInteger();
        private final AtomicInteger adds = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch probeEntered = new CountDownLatch(1);
        private final CountDownLatch probeRelease = new CountDownLatch(1);
        private volatile RuntimeException nextProbeFailure;
        private volatile RuntimeException nextAddFailure;
        private volatile boolean commitFailedAdd;
        private volatile boolean blockProbe;

        RecordingClient(PinState initial) {
            state = new AtomicReference<>(initial);
        }

        @Override
        public PinState probe(CanonicalCid cid, byte[] effectIdHash) {
            record(effectIdHash);
            probes.incrementAndGet();
            if (blockProbe) {
                probeEntered.countDown();
                try {
                    probeRelease.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (closed.get()) {
                throw new com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsProviderException(
                        com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode.SHUTDOWN);
            }
            RuntimeException failure = nextProbeFailure;
            nextProbeFailure = null;
            if (failure != null) {
                throw failure;
            }
            return state.get();
        }

        @Override
        public void add(CanonicalCid cid, boolean recursive, byte[] effectIdHash) {
            record(effectIdHash);
            adds.incrementAndGet();
            RuntimeException failure = nextAddFailure;
            nextAddFailure = null;
            if (failure != null) {
                if (commitFailedAdd) {
                    state.set(recursive ? PinState.RECURSIVE : PinState.DIRECT);
                }
                throw failure;
            }
            state.set(recursive ? PinState.RECURSIVE : PinState.DIRECT);
        }

        void failNextProbe(RuntimeException failure) {
            nextProbeFailure = failure;
        }

        void failNextAdd(RuntimeException failure, boolean commit) {
            nextAddFailure = failure;
            commitFailedAdd = commit;
        }

        void blockProbe() {
            blockProbe = true;
        }

        boolean awaitProbe(long timeout, TimeUnit unit) throws InterruptedException {
            return probeEntered.await(timeout, unit);
        }

        PinState state() {
            return state.get();
        }

        int probes() {
            return probes.get();
        }

        int adds() {
            return adds.get();
        }

        int calls() {
            return probes() + adds();
        }

        int closeCalls() {
            return closeCalls.get();
        }

        List<byte[]> observedKeys() {
            synchronized (observedKeys) {
                return observedKeys.stream().map(byte[]::clone).toList();
            }
        }

        private void record(byte[] effectIdHash) {
            observedKeys.add(effectIdHash.clone());
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCalls.incrementAndGet();
                probeRelease.countDown();
            }
        }
    }

    static final class MemoryDetailArchive implements ConnectorDetailArchive {
        private final Map<String, byte[]> entries = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile IOException failure;

        @Override
        public ConnectorDetailHash archive(ConnectorDetailDocumentV1 document) throws IOException {
            if (closed.get()) {
                throw new IOException("archive closed");
            }
            if (failure != null) {
                throw failure;
            }
            byte[] bytes = document.encode();
            ConnectorDetailHash hash = ConnectorDetailHash.compute(bytes);
            entries.putIfAbsent(HexFormat.of().formatHex(hash.bytes()), bytes.clone());
            return hash;
        }

        @Override
        public Optional<ConnectorDetailDocumentV1> retrieve(ConnectorDetailHash hash)
                throws IOException {
            byte[] bytes = entries.get(HexFormat.of().formatHex(hash.bytes()));
            return bytes == null ? Optional.empty()
                    : Optional.of(ConnectorDetailDocumentV1.decode(bytes));
        }

        Optional<byte[]> bytes(byte[] hash) {
            byte[] value = entries.get(HexFormat.of().formatHex(hash));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        void failWith(IOException archiveFailure) {
            failure = archiveFailure;
        }

        void clearFailure() {
            failure = null;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
