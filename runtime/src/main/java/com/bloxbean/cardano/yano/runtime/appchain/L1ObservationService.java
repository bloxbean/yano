package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.runtime.plugins.LegacyServiceLoaderProviderRegistry;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Logger log;

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
        this.observers = List.copyOf(observers);
        this.windowBlocks = Math.max(windowBlocks, 64);
        this.log = log;
    }

    /**
     * Build the configured observers from {@code observers.<id>.*} plugin
     * settings; returns null when none are configured. Built-in types plus
     * ServiceLoader {@link L1ObserverProvider}s from the plugin classloader.
     */
    static L1ObservationService fromConfig(Map<String, String> pluginSettings,
                                           int windowBlocks,
                                           ClassLoader pluginClassLoader,
                                           Logger log) {
        return fromRegistry(pluginSettings, windowBlocks,
                new LegacyServiceLoaderProviderRegistry(pluginClassLoader), log);
    }

    static L1ObservationService fromRegistry(Map<String, String> pluginSettings,
                                             int windowBlocks,
                                             PluginProviderRegistry providers,
                                             Logger log) {
        Map<String, Map<String, String>> byId = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : pluginSettings.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("observers."))
                continue;
            String rest = key.substring("observers.".length());
            int dot = rest.indexOf('.');
            if (dot <= 0)
                continue;
            byId.computeIfAbsent(rest.substring(0, dot), id -> new HashMap<>())
                    .put(rest.substring(dot + 1), entry.getValue());
        }
        if (byId.isEmpty())
            return null;

        List<L1Observer> observers = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : byId.entrySet()) {
            String observerId = entry.getKey();
            Map<String, String> settings = entry.getValue();
            String type = settings.getOrDefault("type", "");
            observers.add(switch (type) {
                case MetadataLabelObserver.TYPE -> new MetadataLabelObserver(observerId, settings);
                case AddressDepositObserver.TYPE -> new AddressDepositObserver(observerId, settings);
                default -> loadProvided(type, observerId, settings, providers);
            });
        }
        return new L1ObservationService(observers, windowBlocks, log);
    }

    private static L1Observer loadProvided(String type, String observerId,
                                           Map<String, String> settings,
                                           PluginProviderRegistry providers) {
        L1ObserverProvider provider = providers.find(L1ObserverProvider.class, type).orElse(null);
        if (provider != null) {
            try {
                L1Observer observer = java.util.Objects.requireNonNull(
                        provider.create(observerId, settings),
                        "L1ObserverProvider.create returned null");
                String productId = java.util.Objects.requireNonNull(
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
        byte[] stableBlockHash = java.util.Objects.requireNonNull(
                blockHash, "blockHash").clone();
        if (stableBlockHash.length != 32) {
            throw new IllegalArgumentException("L1 block hash must be 32 bytes");
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
            }
        }
        window.put(slot, claims);
        blockHashes.put(slot, stableBlockHash.clone());
        newestSlot = slot;
        if (!all.isEmpty()) {
            pendingInjection.put(slot, all);
        }
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
        List<L1Observation> ready = new ArrayList<>();
        var eligible = pendingInjection.headMap(stableSlot, true);
        for (var iterator = eligible.entrySet().iterator(); iterator.hasNext(); ) {
            ready.addAll(iterator.next().getValue());
            iterator.remove();
        }
        return ready;
    }

    /** L1 rollback: forget observations above the rollback point. */
    void onL1Rollback(long rollbackToSlot) {
        window.tailMap(rollbackToSlot, false).clear();
        blockHashes.tailMap(rollbackToSlot, false).clear();
        pendingInjection.tailMap(rollbackToSlot, false).clear();
        newestSlot = Math.min(newestSlot, rollbackToSlot);
    }

    /**
     * Follower-side verdict on a proposed {@code ~l1/*} message against this
     * node's OWN window (fail-closed: undecodable bodies and topic/body
     * disagreements are MISMATCH).
     */
    AppChainEngine.L1RefVerdict verify(AppMessage message) {
        L1Observation observation = L1Observation.decode(message.getBody());
        if (observation == null || !observation.topic().equals(message.getTopic())) {
            return AppChainEngine.L1RefVerdict.MISMATCH;
        }
        if (observation.slot() > newestSlot) {
            return AppChainEngine.L1RefVerdict.AHEAD;
        }
        Map<String, byte[]> atSlot = window.get(observation.slot());
        if (atSlot == null) {
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

    Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        for (L1Observer observer : observers) {
            status.put(observer.observerId(), observer.status());
        }
        status.put("windowSlots", window.isEmpty()
                ? "empty" : window.firstKey() + ".." + window.lastKey());
        return status;
    }
}
