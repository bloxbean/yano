package com.bloxbean.cardano.yano.api.appchain.effects;

import java.util.Set;

/**
 * Executes effects against an external system (ADR app-layer/010 F5) — the
 * plugin boundary of the execution plane. Discovered via
 * {@link AppEffectExecutorFactory} (ServiceLoader) or registered
 * programmatically; the Effect Runtime supplies discovery, finality gating,
 * retries, backoff and the poison lane — an executor only performs one
 * attempt.
 * <p>
 * <b>At-least-once contract:</b> the same effect may be re-attempted after a
 * crash, restart or failover. Executions MUST be idempotent keyed on
 * {@code effect.idHash()} — pass it to the external system on every attempt
 * (HTTP {@code Idempotency-Key} header, Cardano tx metadata, DB unique
 * constraint) and, where possible, probe for a prior execution before acting.
 * Secrets (API keys, credentials) belong in executor settings, never in
 * effect payloads.
 */
public interface AppEffectExecutor extends AutoCloseable {

    /** Stable executor id, for status/metrics (e.g. "webhook", "cardano-payment"). */
    String id();

    /**
     * Exact effect types owned by this created executor product (ADR-013.1).
     * A non-empty declaration is authoritative and is validated before the
     * Effect Runtime publishes ownership. The empty default preserves legacy
     * predicate-only executors; only those products are queried through
     * {@link #supports(String)} when no declarative owner exists.
     */
    default Set<String> effectTypes() {
        return Set.of();
    }

    /**
     * Returns a bounded, memory-only operational snapshot. The Effect Runtime
     * samples this callback on its lifecycle and publishes only a sanitized
     * host-owned cache; status and metrics callers never invoke it directly.
     */
    default EffectExecutorOperationalSnapshot operationalSnapshot() {
        return EffectExecutorOperationalSnapshot.unknown();
    }

    /**
     * Legacy predicate routing. Declarative products are routed exclusively
     * through {@link #effectTypes()}, so this callback is not invoked for a
     * declared type.
     */
    boolean supports(String effectType);

    /**
     * Perform one attempt. Throwing means "retry with backoff" (equivalent to
     * {@code EffectExecution.failed(msg, retryable=true)}); return
     * {@link EffectExecution.Failed} with {@code retryable=false} for
     * definitive rejections.
     */
    EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) throws Exception;

    @Override
    default void close() {
    }
}
