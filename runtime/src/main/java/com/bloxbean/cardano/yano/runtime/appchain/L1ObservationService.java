package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.SequencedL1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverConsensusIdentity;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;
import org.rocksdb.WriteBatch;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * L1 observations (ADR app-layer/008.4 §3.1): EVERY member runs the
 * configured observers over its own streamed L1 blocks and keeps a bounded
 * window of self-computed observations; the current proposer additionally
 * injects them as {@code ~l1/<observer-id>} messages. Follower verification
 * (consensus-critical) is a lookup in this window with the same verdict
 * semantics as the I1.3 l1-ref check: match → OK, differs or
 * absent-but-in-window → MISMATCH, newer than our L1 view → AHEAD,
 * older than the window → UNKNOWN (the certified chain vouches).
 */
final class L1ObservationService {

    /** Bound plugin-controlled exception metadata before it reaches operator logs. */
    private static final int MAX_CALLBACK_FAILURE_TYPE_CHARS = 256;

    /** Window size in L1 blocks (mirrors the recent-L1-points window). */
    private final int windowBlocks;
    private final List<L1Observer> observers;
    private final L1ObservationJournal journal;
    private final Logger log;
    private volatile boolean healthy;
    private volatile long callbackFailureSlot;

    /** slot → (observation key → claim). Bounded to {@link #windowBlocks}. */
    private final ConcurrentSkipListMap<Long, Map<String, byte[]>> window =
            new ConcurrentSkipListMap<>();
    /** slot → L1 block hash observed at that slot (pointer check). */
    private final ConcurrentSkipListMap<Long, byte[]> blockHashes = new ConcurrentSkipListMap<>();
    private volatile long newestSlot;
    /**
     * Observed facts awaiting STABILITY before injection (rollback safety:
     * a fact must be l1.stability-depth confirmations old before it may be
     * sequenced — the app chain never rolls back, so nothing reorg-able may
     * ever finalize). Drained by {@link #drainInjectable} as the stable ref
     * advances; pruned on rollback.
     */
    private final ConcurrentSkipListMap<Long, List<L1Observation>> pendingInjection =
            new ConcurrentSkipListMap<>();

    L1ObservationService(List<L1Observer> observers, int windowBlocks, Logger log) {
        this(observers, windowBlocks, null, log);
    }

    L1ObservationService(List<L1Observer> observers,
                         int windowBlocks,
                         L1ObservationJournal journal,
                         Logger log) {
        this.observers = List.copyOf(observers);
        this.windowBlocks = Math.max(windowBlocks, 64);
        this.journal = journal;
        this.log = log;
        this.callbackFailureSlot = journal == null ? -1 : journal.callbackFailureSlot();
        this.healthy = callbackFailureSlot < 0 && (journal == null || journal.healthy());
    }

    L1ObservationService withJournal(L1ObservationJournal durableJournal) {
        if (journal != null) {
            throw new IllegalStateException("L1 observation journal is already attached");
        }
        return new L1ObservationService(observers, windowBlocks,
                Objects.requireNonNull(durableJournal, "durableJournal"), log);
    }

    /**
     * Build the configured observers from {@code observers.<id>.*} plugin
     * settings; returns null when none are configured. This library-mode
     * overload has no extension providers; runtime assembly supplies the
     * catalog-selected registry through {@link #fromRegistry}.
     */
    static L1ObservationService fromConfig(Map<String, String> pluginSettings,
                                           int windowBlocks,
                                           ClassLoader pluginClassLoader,
                                           Logger log) {
        return fromRegistry(pluginSettings, windowBlocks,
                PluginProviderRegistry.empty(), log);
    }

    static L1ObservationService fromRegistry(Map<String, String> pluginSettings,
                                             int windowBlocks,
                                             PluginProviderRegistry providers,
                                             Logger log) {
        return fromRegistry(pluginSettings, windowBlocks, providers, null, log);
    }

    static byte[] consensusProfileDigest(Map<String, String> pluginSettings,
                                         PluginProviderRegistry providers,
                                         byte[] l1NetworkGenesisId) {
        Map<String, Map<String, String>> byId = observerSettings(pluginSettings);
        byte[] networkIdentity = Objects.requireNonNull(
                l1NetworkGenesisId, "l1NetworkGenesisId").clone();
        if (networkIdentity.length != 32) {
            throw new IllegalArgumentException("L1 network genesis identity must be 32 bytes");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write("yano-observer-profile-v2\0".getBytes(StandardCharsets.US_ASCII));
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.write(networkIdentity);
                for (Map.Entry<String, Map<String, String>> entry : byId.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    String observerId = entry.getKey();
                    Map<String, String> settings = entry.getValue();
                    String type = settings.getOrDefault("type", "").trim();
                    L1ObserverConsensusIdentity identity = consensusIdentity(
                            observerId, type, settings, providers);
                    writeProfileString(out, observerId);
                    writeProfileString(out, type);
                    out.writeInt(identity.abiVersion());
                    writeProfileString(out, identity.claimSchema());
                    out.writeInt(identity.orderingVersion());
                    byte[] canonical = identity.canonicalIdentityBytes();
                    out.writeInt(canonical.length);
                    out.write(canonical);
                }
            }
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static void writeProfileString(DataOutputStream out, String value)
            throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static L1ObserverConsensusIdentity consensusIdentity(
            String observerId, String type, Map<String, String> settings,
            PluginProviderRegistry providers) {
        return switch (type) {
            case MetadataLabelObserver.TYPE -> builtInIdentity(
                    "metadata-label-claim-v1", "label", settings);
            case AddressDepositObserver.TYPE -> builtInIdentity(
                    "address-deposit-claim-v1", "address", settings);
            default -> providers.find(L1ObserverProvider.class, type)
                    .map(provider -> Objects.requireNonNull(provider.consensusIdentity(
                            observerId, Map.copyOf(settings)),
                            "L1ObserverProvider.consensusIdentity returned null"))
                    .orElseGet(() -> providers.find(L1EpochObserverProvider.class, type)
                            .map(provider -> Objects.requireNonNull(provider.consensusIdentity(
                                    observerId, Map.copyOf(settings)),
                                    "L1EpochObserverProvider.consensusIdentity returned null"))
                            .orElseThrow(() -> new PluginActivationException(
                                    "Configured L1 observer type '" + type
                                            + "' has no selected provider", null)));
        };
    }

    private static L1ObserverConsensusIdentity builtInIdentity(
            String claimSchema, String settingName, Map<String, String> settings) {
        String value = settings.get(settingName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Built-in observer setting '" + settingName + "' is required");
        }
        return new L1ObserverConsensusIdentity(1, claimSchema, 1,
                (settingName + "=" + value.trim()).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Map<String, String>> observerSettings(
            Map<String, String> pluginSettings) {
        Map<String, Map<String, String>> byId = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : pluginSettings.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("observers.")) {
                continue;
            }
            String rest = key.substring("observers.".length());
            int dot = rest.indexOf('.');
            if (dot > 0) {
                byId.computeIfAbsent(rest.substring(0, dot), ignored -> new HashMap<>())
                        .put(rest.substring(dot + 1), entry.getValue());
            }
        }
        return byId;
    }

    static L1ObservationService fromRegistry(Map<String, String> pluginSettings,
                                             int windowBlocks,
                                             PluginProviderRegistry providers,
                                             L1ObservationJournal journal,
                                             Logger log) {
        Map<String, Map<String, String>> byId = observerSettings(pluginSettings);
        if (byId.isEmpty())
            return null;

        List<L1Observer> observers = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : byId.entrySet()) {
            String observerId = entry.getKey();
            Map<String, String> settings = entry.getValue();
            String type = settings.getOrDefault("type", "");
            if (L1EpochObservationCoordinator.isEpochObserverType(type, providers)) {
                continue;
            }
            observers.add(switch (type) {
                case MetadataLabelObserver.TYPE -> new MetadataLabelObserver(observerId, settings);
                case AddressDepositObserver.TYPE -> new AddressDepositObserver(observerId, settings);
                default -> loadProvided(type, observerId, settings, providers);
            });
        }
        return observers.isEmpty() ? null
                : new L1ObservationService(observers, windowBlocks, journal, log);
    }

    private static L1Observer loadProvided(String type, String observerId,
                                           Map<String, String> settings,
                                           PluginProviderRegistry providers) {
        L1ObserverProvider provider = providers.find(L1ObserverProvider.class, type).orElse(null);
        if (provider != null) {
            try {
                L1Observer observer = Objects.requireNonNull(
                        provider.create(observerId, settings),
                        "L1ObserverProvider.create returned null");
                String productId = Objects.requireNonNull(
                        observer.observerId(), "L1Observer.observerId returned null");
                if (!observerId.equals(productId)) {
                    throw new IllegalStateException("L1ObserverProvider '" + type
                            + "' returned product id '" + productId
                            + "' for configured observer '" + observerId + "'");
                }
                return stableObserverIdentity(observer, observerId);
            } catch (PluginActivationException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new PluginActivationException(
                        "Configured plugin L1 observer '" + type + "' failed to activate",
                        failure);
            }
        }
        throw new PluginActivationException("Configured plugin L1 observer type '" + type
                + "' for observers." + observerId
                + " is not selected (built-ins: metadata-label, address-deposit; "
                + "selected custom types: "
                + providers.names(L1ObserverProvider.class) + ")", null);
    }

    private static L1Observer stableObserverIdentity(L1Observer delegate, String observerId) {
        return new L1Observer() {
            @Override
            public String observerId() {
                return observerId;
            }

            @Override
            public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
                return delegate.observe(slot, blockHash, block);
            }

            @Override
            public Map<String, Object> status() {
                return delegate.status();
            }
        };
    }

    /**
     * Run the observers over one applied L1 block and record the results in
     * the verification window (all members) and the pending-injection queue
     * (drained once stable — see {@link #drainInjectable}).
     */
    void onL1Block(long slot, byte[] blockHash, Block block) {
        byte[] stableBlockHash = Objects.requireNonNull(
                blockHash, "blockHash").clone();
        if (stableBlockHash.length != 32) {
            throw new IllegalArgumentException("L1 block hash must be 32 bytes");
        }
        if (callbackFailureSlot >= 0 && callbackFailureSlot != slot) {
            throw new IllegalStateException("L1_OBSERVER_REPLAY_REQUIRED_AT_SLOT_"
                    + callbackFailureSlot);
        }
        List<L1Observation> all = new ArrayList<>();
        Map<String, byte[]> claims = new ConcurrentHashMap<>();
        for (L1Observer observer : observers) {
            try {
                for (L1Observation observation : observer.observe(
                        slot, stableBlockHash.clone(), block)) {
                    claims.put(observation.key(), observation.claim());
                    all.add(observation);
                }
            } catch (Throwable e) {
                // Fail-closed on the FOLLOWER side is the safety net; the
                // observer contract is determinism, so log loudly. Do not log
                // the plugin exception message/cause: it may contain settings
                // such as endpoint credentials.
                LifecycleFailures.rethrowIfProcessFatal(e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                healthy = false;
                callbackFailureSlot = slot;
                if (journal != null) {
                    journal.markCallbackFailure(slot);
                }
                try {
                    // Do not re-enter observerId() while handling a callback
                    // failure: it is plugin code too and could mask the
                    // original failure or expose mutable identity data.
                    log.warn("L1 observer failed on slot {} (errorType={})",
                            slot, callbackFailureType(e));
                } catch (Throwable loggingFailure) {
                    LifecycleFailures.rethrowIfProcessFatal(loggingFailure);
                    if (loggingFailure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
                throw new IllegalStateException("L1_OBSERVER_CALLBACK_FAILED", e);
            }
        }
        if (journal != null) {
            try {
                if (!all.isEmpty()) {
                    journal.observe(all);
                }
                journal.clearCallbackFailure(slot);
            } catch (RuntimeException failure) {
                healthy = false;
                callbackFailureSlot = slot;
                journal.markCallbackFailure(slot);
                throw failure;
            }
        } else if (!all.isEmpty()) {
            pendingInjection.put(slot, all);
        }
        callbackFailureSlot = -1;
        healthy = journal == null || journal.healthy();
        window.put(slot, claims);
        blockHashes.put(slot, stableBlockHash.clone());
        newestSlot = slot;
        while (window.size() > windowBlocks) {
            window.pollFirstEntry();
        }
        while (blockHashes.size() > windowBlocks) {
            blockHashes.pollFirstEntry();
        }
        while (pendingInjection.size() > windowBlocks) {
            pendingInjection.pollFirstEntry();
        }
    }

    private static String callbackFailureType(Throwable failure) {
        String type = failure.getClass().getName();
        return type.length() <= MAX_CALLBACK_FAILURE_TYPE_CHARS
                ? type : type.substring(0, MAX_CALLBACK_FAILURE_TYPE_CHARS);
    }

    /**
     * Remove and return the observed facts that have become STABLE (slot ≤
     * the current stable L1 ref) — safe to sequence: a fact this deep can no
     * longer be rolled back under the chain's own stability assumption.
     * Called on every member at the same L1 block (deterministic drain);
     * only the currently-scheduled proposer injects the result.
     */
    List<L1Observation> drainInjectable(long stableSlot) {
        return drainInjectable(stableSlot, AppChainConfig.MAX_BLOCK_MESSAGES,
                AppChainConfig.MAX_BLOCK_BYTES);
    }

    List<L1Observation> drainInjectable(long stableSlot, int maxCount, long maxBytes) {
        if (journal != null) {
            return journal.pending(stableSlot, maxCount, maxBytes);
        }
        List<L1Observation> ready = new ArrayList<>();
        var eligible = pendingInjection.headMap(stableSlot, true);
        for (var iterator = eligible.entrySet().iterator(); iterator.hasNext(); ) {
            ready.addAll(iterator.next().getValue());
            iterator.remove();
        }
        return ready;
    }

    AppChainEngine.L1RefVerdict verifyPrefix(List<L1Observation> actual,
                                             long stableSlot,
                                             int maxCount,
                                             long maxBytes) {
        if (!healthy) {
            return AppChainEngine.L1RefVerdict.MISMATCH;
        }
        if (newestSlot < stableSlot) {
            return AppChainEngine.L1RefVerdict.AHEAD;
        }
        List<L1Observation> expected = drainInjectable(stableSlot, maxCount, maxBytes);
        if (actual.size() != expected.size()) {
            return AppChainEngine.L1RefVerdict.MISMATCH;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!Arrays.equals(actual.get(index).encode(), expected.get(index).encode())) {
                return AppChainEngine.L1RefVerdict.MISMATCH;
            }
        }
        return AppChainEngine.L1RefVerdict.OK;
    }

    /** L1 rollback: forget observations above the rollback point. */
    void onL1Rollback(long rollbackToSlot) {
        window.tailMap(rollbackToSlot, false).clear();
        blockHashes.tailMap(rollbackToSlot, false).clear();
        pendingInjection.tailMap(rollbackToSlot, false).clear();
        if (journal != null) {
            try {
                journal.rollback(rollbackToSlot);
            } catch (RuntimeException failure) {
                healthy = false;
                throw failure;
            }
        }
        newestSlot = Math.min(newestSlot, rollbackToSlot);
    }

    /**
     * Follower-side verdict on a proposed {@code ~l1/*} message against this
     * node's OWN window (fail-closed: undecodable bodies and topic/body
     * disagreements are MISMATCH).
     */
    AppChainEngine.L1RefVerdict verify(SequencedL1Observation sequenced) {
        L1Observation observation = sequenced.observation();
        if (observation.slot() > newestSlot) {
            return AppChainEngine.L1RefVerdict.AHEAD;
        }
        Map<String, byte[]> atSlot = window.get(observation.slot());
        if (atSlot == null) {
            if (journal != null && journal.contains(observation)) {
                return AppChainEngine.L1RefVerdict.OK;
            }
            // Below the window (restart/catch-up): the certified chain vouches
            return window.isEmpty() || observation.slot() < window.firstKey()
                    ? AppChainEngine.L1RefVerdict.UNKNOWN
                    : AppChainEngine.L1RefVerdict.MISMATCH;
        }
        byte[] observedBlockHash = blockHashes.get(observation.slot());
        if (observedBlockHash != null && !Arrays.equals(observedBlockHash, observation.blockHash())) {
            return AppChainEngine.L1RefVerdict.MISMATCH;
        }
        byte[] ownClaim = atSlot.get(observation.key());
        if (ownClaim == null || !Arrays.equals(ownClaim, observation.claim())) {
            return AppChainEngine.L1RefVerdict.MISMATCH;
        }
        return AppChainEngine.L1RefVerdict.OK;
    }

    boolean hasObservers() {
        return !observers.isEmpty();
    }

    boolean healthy() {
        return healthy;
    }

    long newestSlot() {
        return newestSlot;
    }

    boolean acknowledge(L1Observation observation) {
        if (journal == null) {
            return false;
        }
        try {
            return journal.acknowledge(observation);
        } catch (RuntimeException failure) {
            healthy = false;
            throw failure;
        }
    }

    void quarantineUnencodable(L1Observation observation) {
        healthy = false;
        if (journal != null) {
            journal.quarantineObservation(observation, "OBSERVATION_UNENCODABLE");
        }
    }

    void stageFinalized(AppBlock block, WriteBatch batch) {
        if (journal != null) {
            journal.stageFinalized(block, batch);
        }
    }

    void markInFlight(AppBlock block) {
        if (journal != null) {
            journal.markInFlight(block);
        }
    }

    void markPrepared(AppBlock block) {
        if (journal != null) {
            journal.markPrepared(block);
        }
    }

    Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        for (L1Observer observer : observers) {
            status.put(observer.observerId(), observer.status());
        }
        status.put("windowSlots", window.isEmpty()
                ? "empty" : window.firstKey() + ".." + window.lastKey());
        status.put("healthy", healthy);
        if (journal != null) {
            status.put("journal", journal.status());
        }
        return status;
    }
}
