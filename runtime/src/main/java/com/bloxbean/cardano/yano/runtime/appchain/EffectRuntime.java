package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
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
 * per-effect retry state — no head-of-line blocking (a dead webhook never
 * delays an unrelated effect), attempt cap into the PARKED lane, and a
 * backfill quarantine so a late-enabled executor never blind-fires history.
 * <p>
 * Deliberately NOT deterministic and NOT consensus: everything here is
 * node-local bookkeeping in the {@code app_fx_runtime} CF. Discovery reads
 * only atomically-committed records (never a re-run of apply), so replay and
 * restart can never re-fire a chain-terminal effect; the at-least-once window
 * (crash between the external call and the local terminal) is closed by
 * executor idempotency on {@code idHash}, not by the runtime.
 */
final class EffectRuntime implements AutoCloseable {

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
                types = Set.of(typesValue.split("\\s*,\\s*"));
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
            return value != null && !value.isBlank() ? Long.parseLong(value.trim()) : defaultValue;
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
    private final AtomicLong executedCount = new AtomicLong();
    private final AtomicLong parkedCount = new AtomicLong();
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

    /**
     * First enablement (no cursor): start at the current tip and QUARANTINE
     * historical open effects — unexecuted obligations need an explicit
     * operator requeue, never a blind backfill (ADR-010 F10 / review #8).
     */
    private void initializeCursor() {
        if (ledger.fxIntakeCursor(-1) >= 0) {
            return;
        }
        long tip = ledger.tipHeight();
        int quarantined = 0;
        for (EffectRecord record : ledger.fxRecordsFrom(0, Integer.MAX_VALUE)) {
            if (record.height() > tip) {
                break;
            }
            if (!ledger.fxClosed(record.height(), record.ordinal())) {
                ledger.fxRuntimePutStatus(record.height(), record.ordinal(),
                        FxStatusRecord.quarantined());
                quarantined++;
            }
        }
        ledger.fxPutIntakeCursor(tip);
        if (quarantined > 0) {
            log.warn("App-chain '{}' effect executor enabled with {} historical open effect(s) — "
                    + "QUARANTINED; review and requeue via the effects admin surface", chainId, quarantined);
        }
    }

    /** One scheduler tick: intake new blocks, then gate + dispatch eligible effects. */
    void tick() {
        try {
            intake();
            dispatch();
        } catch (Exception e) {
            lastError = e.toString();
            log.warn("App-chain '{}' effect runtime tick failed: {}", chainId, e.toString());
        }
    }

    private void intake() {
        long tip = ledger.tipHeight();
        long cursor = ledger.fxIntakeCursor(0);
        while (cursor < tip) {
            long next = cursor + 1;
            for (EffectRecord record : recordsAt(next)) {
                if (ledger.fxClosed(record.height(), record.ordinal())) {
                    continue; // e.g. expired in the same block span
                }
                ledger.fxRuntimePutStatus(record.height(), record.ordinal(), FxStatusRecord.pending());
                ledger.fxQueuePut(record.height(), record.ordinal());
            }
            ledger.fxPutIntakeCursor(next);
            cursor = next;
        }
    }

    private List<EffectRecord> recordsAt(long height) {
        return ledger.fxRecordsFrom(height, Integer.MAX_VALUE).stream()
                .takeWhile(record -> record.height() == height)
                .toList();
    }

    private void dispatch() {
        long anchored = ledger.metaLong("anchor_last_height", 0L);
        long now = System.currentTimeMillis();
        for (long[] entry : ledger.fxQueueScan(settings.maxBatch())) {
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
                continue; // wait for the anchor high-water-mark
            }
            if (!settings.types().isEmpty() && !settings.types().contains(record.type())) {
                continue; // another executor node's partition
            }
            FxStatusRecord status = ledger.fxRuntimeStatus(height, ordinal)
                    .orElse(FxStatusRecord.pending());
            if (!status.executable() || status.nextAttemptAt() > now) {
                continue;
            }
            AppEffectExecutor executor = find(record.type());
            if (executor == null) {
                continue; // surfaced via stats (unroutable count) — retried when one appears
            }
            if (!inFlight.add(key)) {
                continue;
            }
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
            switch (outcome) {
                case EffectExecution.Confirmed confirmed -> {
                    ledger.fxRuntimePutStatus(height, ordinal, before.done(confirmed.externalRef()));
                    ledger.fxQueueDelete(height, ordinal);
                    executedCount.incrementAndGet();
                    lastError = null;
                    // ResultPolicy.CHAIN outcomes are injected as ~fx/result in FX-M3;
                    // until then the local DONE record holds the external ref.
                }
                case EffectExecution.Failed failed -> {
                    if (!failed.retryable() || before.attempts() + 1 >= settings.maxAttempts()) {
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
                        ledger.fxRuntimePutStatus(height, ordinal, before.retry("not ready",
                                System.currentTimeMillis() + retry.notBefore().toMillis()));
            }
        } catch (Exception e) {
            lastError = e.toString();
            log.warn("App-chain '{}' effect {} handling failed: {}", chainId, key, e.toString());
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

    /** Operator requeue: PARKED/QUARANTINED/SKIPPED → PENDING (audit-logged by the caller). */
    boolean requeue(long height, int ordinal) {
        Optional<FxStatusRecord> status = ledger.fxRuntimeStatus(height, ordinal);
        if (ledger.fxRecord(height, ordinal).isEmpty() || ledger.fxClosed(height, ordinal)) {
            return false;
        }
        if (status.isPresent() && status.get().executable()) {
            return false; // already live
        }
        ledger.fxRuntimePutStatus(height, ordinal,
                status.map(FxStatusRecord::requeued).orElse(FxStatusRecord.pending()));
        ledger.fxQueuePut(height, ordinal);
        log.info("App-chain '{}' effect {}/{} requeued by operator", chainId, height, ordinal);
        return true;
    }

    Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("intakeCursor", ledger.fxIntakeCursor(0));
        stats.put("queueDepth", ledger.fxQueueScan(settings.maxBatch()).size());
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
                view.put("submittedRef",
                        com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(status.submittedRef()));
            }
            if (status.externalRef().length > 0) {
                view.put("externalRef",
                        com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(status.externalRef()));
            }
            view.put("updatedAt", status.updatedAt());
            return view;
        });
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
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
