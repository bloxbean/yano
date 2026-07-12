package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The execution plane (ADR app-layer/010 F5): discovers finalized effects
 * from the consensus-tier outbox via a persisted intake cursor, gates them on
 * finality policy, and drives {@link AppEffectExecutor} attempts with
 * per-effect retry state — no head-of-line blocking (skipped rows never
 * consume the dispatch budget; foreign partitions are filtered at intake),
 * attempt cap into the PARKED lane, and a backfill quarantine so a
 * late-enabled executor never blind-fires history.
 * <p>
 * Deliberately NOT deterministic and NOT consensus: everything here is
 * node-local bookkeeping in the {@code app_fx_runtime} CF. Discovery reads
 * only atomically-committed records (never a re-run of apply), so replay and
 * restart can never re-fire a chain-terminal effect; the at-least-once window
 * (crash between the external call and the local terminal) is closed by
 * executor idempotency on {@code idHash}, not by the runtime.
 * <p>
 * Threading: one scheduler tick thread, a bounded worker pool, plus REST
 * threads (stats/requeue). Status+queue transition pairs are serialized on
 * {@code transitionLock}; {@code closed} gates every ledger access during
 * shutdown so the RocksDB handle is never touched after {@code close()}
 * returns.
 */
final class EffectRuntime implements AutoCloseable {

    /** How close to its expiry height a CHAIN effect may still be dispatched. */
    private static final long EXPIRY_SAFETY_BLOCKS = 2;

    /** Everything the runtime needs from configuration (effects.executor.*). */
    record Settings(boolean enabled,
                    Set<String> types,
                    long tickMs,
                    int maxParallel,
                    int maxAttempts,
                    long backoffInitialMs,
                    long backoffMaxMs,
                    long anchorMarginBlocks,
                    int maxBatch) {

        static Settings fromSettings(Map<String, String> settings) {
            boolean enabled = Boolean.parseBoolean(
                    settings.getOrDefault("effects.executor.enabled", "false"));
            Set<String> types = Set.of();
            String typesValue = settings.getOrDefault("effects.executor.types", "").trim();
            if (!typesValue.isEmpty()) {
                types = Set.copyOf(new LinkedHashSet<>(List.of(typesValue.split("\\s*,\\s*"))));
            }
            return new Settings(enabled, types,
                    longOf(settings, "effects.executor.tick-ms", 2000),
                    (int) longOf(settings, "effects.executor.max-parallel", 4),
                    (int) longOf(settings, "effects.executor.max-attempts", 8),
                    longOf(settings, "effects.executor.backoff-initial-ms", 2000),
                    longOf(settings, "effects.executor.backoff-max-ms", 300_000),
                    longOf(settings, "effects.gate.anchor-margin-blocks", 0),
                    (int) longOf(settings, "effects.executor.max-batch", 256));
        }

        private static long longOf(Map<String, String> settings, String key, long defaultValue) {
            String value = settings.get(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                // Name the offending key — this surfaces during subsystem start
                throw new IllegalArgumentException(
                        "Invalid numeric value for " + key + ": '" + value + "'");
            }
        }

        /** Queue rows examined per tick — far above the dispatch cap so skipped rows never starve it. */
        int scanLimit() {
            return Math.max(4096, maxBatch * 16);
        }

        /** New executions submitted per tick. */
        int dispatchCap() {
            return Math.max(1, maxParallel * 2);
        }
    }

    private final AppLedgerStore ledger;
    private final String chainId;
    private final Settings settings;
    private final List<AppEffectExecutor> executors;
    private final Map<String, Map<String, String>> executorConfigs;
    private final ExecutorService pool;
    private final Logger log;

    /** Effects currently in flight on the worker pool (in-memory only). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** Serializes status+queue transition PAIRS (worker terminals vs REST requeue). */
    private final Object transitionLock = new Object();
    private final AtomicLong executedCount = new AtomicLong();
    private final AtomicLong parkedCount = new AtomicLong();
    private volatile boolean closed;
    private volatile String lastError;

    EffectRuntime(AppLedgerStore ledger, String chainId, Settings settings,
                  List<AppEffectExecutor> executors,
                  Map<String, Map<String, String>> executorConfigs, Logger log) {
        this.ledger = ledger;
        this.chainId = chainId;
        this.settings = settings;
        this.executors = List.copyOf(executors);
        this.executorConfigs = Map.copyOf(executorConfigs);
        this.log = log;
        this.pool = Executors.newFixedThreadPool(Math.max(1, settings.maxParallel()), r -> {
            Thread t = new Thread(r, "app-chain-fx-worker-" + chainId);
            t.setDaemon(true);
            return t;
        });
        initializeCursor();
    }

    Settings settings() {
        return settings;
    }

    /**
     * First enablement (no cursor): start at the current tip and QUARANTINE
     * historical open effects — unexecuted obligations need an explicit
     * operator requeue, never a blind backfill (ADR-010 F10). Key-only scan:
     * no record decode, no payloads in memory.
     */
    private void initializeCursor() {
        if (ledger.fxIntakeCursor(-1) >= 0) {
            return;
        }
        long tip = ledger.tipHeight();
        List<long[]> open = ledger.fxOpenRecordKeysUpTo(tip);
        for (long[] key : open) {
            ledger.fxRuntimePutStatus(key[0], (int) key[1], FxStatusRecord.quarantined());
        }
        ledger.fxPutIntakeCursor(tip);
        if (!open.isEmpty()) {
            log.warn("App-chain '{}' effect executor enabled with {} historical open effect(s) — "
                    + "QUARANTINED; review and requeue via the effects admin surface",
                    chainId, open.size());
        }
    }

    /** One scheduler tick: intake new blocks, then gate + dispatch eligible effects. */
    void tick() {
        if (closed) {
            return;
        }
        try {
            intake();
            dispatch();
        } catch (Exception e) {
            if (!closed) {
                lastError = e.toString();
                log.warn("App-chain '{}' effect runtime tick failed: {}", chainId, e.toString());
            }
        }
    }

    private void intake() {
        long tip = ledger.tipHeight();
        long cursor = ledger.fxIntakeCursor(0);
        while (cursor < tip && !closed) {
            long next = cursor + 1;
            for (EffectRecord record : ledger.fxRecordsAt(next)) {
                if (ledger.fxClosed(record.height(), record.ordinal())) {
                    continue; // e.g. expired in the same block span
                }
                if (!settings.types().isEmpty() && !settings.types().contains(record.type())) {
                    continue; // another executor node's partition — never enqueued here
                }
                ledger.fxRuntimePutStatus(record.height(), record.ordinal(), FxStatusRecord.pending());
                ledger.fxQueuePut(record.height(), record.ordinal());
            }
            ledger.fxPutIntakeCursor(next);
            cursor = next;
        }
    }

    private void dispatch() {
        long tip = ledger.tipHeight();
        long anchored = ledger.metaLong("anchor_last_height", 0L);
        long now = System.currentTimeMillis();
        int dispatched = 0;
        for (long[] entry : ledger.fxQueueScan(settings.scanLimit())) {
            if (closed || dispatched >= settings.dispatchCap()) {
                return;
            }
            long height = entry[0];
            int ordinal = (int) entry[1];
            String key = height + "/" + ordinal;
            if (inFlight.contains(key)) {
                continue;
            }
            if (ledger.fxClosed(height, ordinal)) {
                ledger.fxQueueDelete(height, ordinal); // chain terminal won the race (expiry)
                continue;
            }
            EffectRecord record = ledger.fxRecord(height, ordinal).orElse(null);
            if (record == null) {
                log.error("App-chain '{}' effect queue references missing record {}/{} — "
                        + "fx CF inconsistent; rebuild by replay", chainId, height, ordinal);
                ledger.fxQueueDelete(height, ordinal);
                continue;
            }
            if (record.gate() == FinalityGate.L1_ANCHORED
                    && height > anchored - settings.anchorMarginBlocks()) {
                continue; // wait for the anchor high-water-mark (row stays queued)
            }
            if (record.expiryHeight() > 0 && record.expiryHeight() <= tip + EXPIRY_SAFETY_BLOCKS) {
                continue; // too close to deterministic expiry — let the sweep close it
            }
            FxStatusRecord status = ledger.fxRuntimeStatus(height, ordinal)
                    .orElse(FxStatusRecord.pending());
            if (!status.executable()) {
                // Orphan row (crash between a terminal status write and the
                // queue delete) — the status list, not the queue, tracks it now
                ledger.fxQueueDelete(height, ordinal);
                continue;
            }
            if (status.nextAttemptAt() > now) {
                continue;
            }
            AppEffectExecutor executor = find(record.type());
            if (executor == null) {
                continue; // surfaced via stats — retried when one appears
            }
            if (!inFlight.add(key)) {
                continue;
            }
            dispatched++;
            pool.submit(() -> run(key, record, status, executor));
        }
    }

    private void run(String key, EffectRecord record, FxStatusRecord before,
                     AppEffectExecutor executor) {
        long height = record.height();
        int ordinal = record.ordinal();
        try {
            EffectExecution outcome;
            try {
                outcome = executor.execute(contextFor(before.attempts() + 1, executor),
                        PendingEffect.of(record));
            } catch (Exception e) {
                outcome = EffectExecution.failed(e.toString(), true);
            }
            if (closed) {
                return; // never touch the ledger after close() — re-attempted on restart
            }
            synchronized (transitionLock) {
                if (closed) {
                    return;
                }
                switch (outcome) {
                    case EffectExecution.Confirmed confirmed -> {
                        ledger.fxRuntimePutStatus(height, ordinal, before.done(confirmed.externalRef()));
                        ledger.fxQueueDelete(height, ordinal);
                        executedCount.incrementAndGet();
                        lastError = null;
                        // ResultPolicy.CHAIN outcomes are injected as ~fx/result in
                        // FX-M3; until then the local DONE record holds the ref and
                        // the on-chain effect stays open (may still EXPIRE).
                    }
                    case EffectExecution.Failed failed -> {
                        if (!failed.retryable() && record.result()
                                == com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy.CHAIN) {
                            // Definitive external answer for a CHAIN effect: a
                            // local terminal whose FAILED outcome gets injected
                            // as ~fx/result so the application learns of it
                            ledger.fxRuntimePutStatus(height, ordinal,
                                    before.doneFailed(failed.reason()));
                            ledger.fxQueueDelete(height, ordinal);
                            lastError = failed.reason();
                        } else if (!failed.retryable()
                                || before.attempts() + 1 >= settings.maxAttempts()) {
                            ledger.fxRuntimePutStatus(height, ordinal, before.parked(failed.reason()));
                            ledger.fxQueueDelete(height, ordinal);
                            parkedCount.incrementAndGet();
                            lastError = failed.reason();
                            log.warn("App-chain '{}' effect {} PARKED after {} attempt(s): {}",
                                    chainId, key, before.attempts() + 1, failed.reason());
                        } else {
                            long delay = Math.min(settings.backoffMaxMs(),
                                    settings.backoffInitialMs() << Math.min(20, before.attempts()));
                            ledger.fxRuntimePutStatus(height, ordinal,
                                    before.retry(failed.reason(), System.currentTimeMillis() + delay));
                            lastError = failed.reason();
                        }
                    }
                    case EffectExecution.Submitted submitted ->
                            ledger.fxRuntimePutStatus(height, ordinal,
                                    before.submitted(submitted.externalRef()));
                    case EffectExecution.Retry retry ->
                            ledger.fxRuntimePutStatus(height, ordinal,
                                    before.deferred("not ready",
                                            System.currentTimeMillis() + retry.notBefore().toMillis()));
                }
            }
        } catch (Exception e) {
            if (!closed) {
                lastError = e.toString();
                log.warn("App-chain '{}' effect {} handling failed: {}", chainId, key, e.toString());
            }
        } finally {
            inFlight.remove(key);
        }
    }

    private AppEffectExecutor find(String type) {
        for (AppEffectExecutor executor : executors) {
            if (executor.supports(type)) {
                return executor;
            }
        }
        return null;
    }

    private EffectExecutionContext contextFor(int attempt, AppEffectExecutor executor) {
        Map<String, String> config = executorConfigs.getOrDefault(executor.id(), Map.of());
        return new EffectExecutionContext() {
            @Override public String chainId() { return chainId; }
            @Override public long tipHeight() { return ledger.tipHeight(); }
            @Override public long anchoredHeight() { return ledger.metaLong("anchor_last_height", 0L); }
            @Override public int attempt() { return attempt; }
            @Override public Map<String, String> settings() { return config; }
        };
    }

    /** One injectable outcome: a locally-terminal CHAIN effect not yet chain-closed. */
    record Injection(long height, int ordinal, boolean confirmed, byte[] externalRef, String reason) {
    }

    /**
     * Locally-terminal CHAIN outcomes awaiting {@code ~fx/result} injection
     * (ADR-010 F8): DONE statuses with an outcome, whose effect is still open
     * on-chain, throttled by {@code reinjectIntervalMs} so the pool isn't
     * spammed while a prior injection awaits sequencing. Marks each returned
     * entry's {@code injectedAt} — first-result-wins absorbs any overlap.
     */
    List<Injection> pendingInjections(int limit, long reinjectIntervalMs) {
        if (closed) {
            return List.of();
        }
        List<Injection> injections = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Object[] entry : ledger.fxRuntimeStatusScan(settings.scanLimit())) {
            if (injections.size() >= limit) {
                break;
            }
            long height = (long) entry[0];
            int ordinal = (int) entry[1];
            FxStatusRecord status = (FxStatusRecord) entry[2];
            if (status.status() != FxStatusRecord.DONE
                    || status.outcomeCode() == FxStatusRecord.OUTCOME_NONE
                    || now - status.injectedAt() < reinjectIntervalMs
                    || ledger.fxClosed(height, ordinal)) {
                continue;
            }
            EffectRecord record = ledger.fxRecord(height, ordinal).orElse(null);
            if (record == null || record.result()
                    != com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy.CHAIN) {
                continue;
            }
            synchronized (transitionLock) {
                ledger.fxRuntimePutStatus(height, ordinal, status.injected(now));
            }
            injections.add(new Injection(height, ordinal,
                    status.outcomeCode() == FxStatusRecord.OUTCOME_CONFIRMED,
                    status.externalRef(), status.lastError()));
        }
        return injections;
    }

    /**
     * Operator requeue (ADR-010 F9): PARKED/QUARANTINED/SKIPPED → PENDING.
     * Also the self-heal for a stranded executable status with no queue row
     * (crash/race leftovers) — re-adds the row.
     */
    boolean requeue(long height, int ordinal) {
        if (closed) {
            return false;
        }
        synchronized (transitionLock) {
            if (closed || ledger.fxRecord(height, ordinal).isEmpty()
                    || ledger.fxClosed(height, ordinal)) {
                return false;
            }
            Optional<FxStatusRecord> status = ledger.fxRuntimeStatus(height, ordinal);
            if (status.isPresent() && status.get().executable()) {
                if (ledger.fxQueueExists(height, ordinal)) {
                    return false; // genuinely live
                }
                ledger.fxQueuePut(height, ordinal); // stranded executable — heal the row
                log.info("App-chain '{}' effect {}/{} queue row restored by operator",
                        chainId, height, ordinal);
                return true;
            }
            ledger.fxRuntimePutStatus(height, ordinal,
                    status.map(FxStatusRecord::requeued).orElse(FxStatusRecord.pending()));
            ledger.fxQueuePut(height, ordinal);
            log.info("App-chain '{}' effect {}/{} requeued by operator", chainId, height, ordinal);
            return true;
        }
    }

    Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (closed) {
            stats.put("closed", true);
            return stats;
        }
        stats.put("intakeCursor", ledger.fxIntakeCursor(0));
        stats.put("queueDepth", ledger.fxQueueScan(settings.scanLimit()).size());
        stats.put("inFlight", inFlight.size());
        stats.put("executed", executedCount.get());
        stats.put("parked", parkedCount.get());
        stats.put("openOnChain", ledger.fxOpenCount());
        stats.put("executors", executors.stream().map(AppEffectExecutor::id).toList());
        if (lastError != null) {
            stats.put("lastError", lastError);
        }
        return stats;
    }

    Optional<Map<String, Object>> statusOf(long height, int ordinal) {
        if (closed) {
            return Optional.empty();
        }
        return ledger.fxRuntimeStatus(height, ordinal).map(status -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("status", status.statusName());
            view.put("attempts", status.attempts());
            if (status.nextAttemptAt() > 0) {
                view.put("nextAttemptAt", status.nextAttemptAt());
            }
            if (!status.lastError().isEmpty()) {
                view.put("lastError", status.lastError());
            }
            if (status.submittedRef().length > 0) {
                view.put("submittedRef", HexUtil.encodeHexString(status.submittedRef()));
            }
            if (status.externalRef().length > 0) {
                view.put("externalRef", HexUtil.encodeHexString(status.externalRef()));
            }
            view.put("updatedAt", status.updatedAt());
            return view;
        });
    }

    /**
     * Shutdown: stop accepting work, then WAIT for workers — the ledger
     * closes right after this returns, so no thread may still hold it.
     * Interrupt-resistant executors are given a bounded second grace period.
     */
    @Override
    public void close() {
        closed = true;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("App-chain '{}' effect workers did not terminate within grace — "
                            + "ledger writes are gated by the closed flag", chainId);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        for (AppEffectExecutor executor : executors) {
            try {
                executor.close();
            } catch (Exception e) {
                log.debug("Error closing executor {}: {}", executor.id(), e.toString());
            }
        }
    }
}
