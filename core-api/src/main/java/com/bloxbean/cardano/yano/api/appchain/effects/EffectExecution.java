package com.bloxbean.cardano.yano.api.appchain.effects;

import java.time.Duration;
import java.util.Objects;

/**
 * Outcome of one executor attempt (ADR app-layer/010 F5). Executors report
 * what happened; retry/backoff/parking policy lives in the Effect Runtime.
 */
public sealed interface EffectExecution {

    /** The external action definitively succeeded. */
    record Confirmed(byte[] externalRef, byte[] detailHash) implements EffectExecution {
        public Confirmed {
            externalRef = externalRef != null ? externalRef : new byte[0];
            detailHash = detailHash != null && detailHash.length > 0 ? detailHash : null;
        }
    }

    /** The external system answered with a definitive failure. */
    record Failed(String reason, boolean retryable) implements EffectExecution {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Long-running action started (e.g. an L1 tx submitted, awaiting
     * confirmation depth). The runtime re-invokes the executor on later
     * ticks; probe the external system by the idempotency key / externalRef
     * and return Confirmed/Failed when it resolves.
     */
    record Submitted(byte[] externalRef) implements EffectExecution {
        public Submitted {
            externalRef = externalRef != null ? externalRef : new byte[0];
        }
    }

    /** Not attempted (precondition not met); retry no earlier than {@code notBefore}. */
    record Retry(Duration notBefore) implements EffectExecution {
        public Retry {
            Objects.requireNonNull(notBefore, "notBefore");
        }
    }

    static EffectExecution confirmed(byte[] externalRef) {
        return new Confirmed(externalRef, null);
    }

    static EffectExecution confirmed(byte[] externalRef, byte[] detailHash) {
        return new Confirmed(externalRef, detailHash);
    }

    static EffectExecution failed(String reason, boolean retryable) {
        return new Failed(reason, retryable);
    }

    static EffectExecution submitted(byte[] externalRef) {
        return new Submitted(externalRef);
    }

    static EffectExecution retry(Duration notBefore) {
        return new Retry(notBefore);
    }
}
