package com.bloxbean.cardano.yano.api.appchain.effects;

import java.time.Duration;
import java.util.Objects;

/**
 * Outcome of one executor attempt (ADR app-layer/010 F5). Executors report
 * what happened; retry/backoff/parking policy lives in the Effect Runtime.
 */
public sealed interface EffectExecution {

    int MAX_FAILURE_REASON_CHARACTERS = 256;

    /** The external action definitively succeeded. */
    record Confirmed(byte[] externalRef, byte[] detailHash) implements EffectExecution {
        public Confirmed {
            externalRef = externalRef != null ? externalRef.clone() : new byte[0];
            if (externalRef.length > FxResultBody.MAX_EXTERNAL_REF_BYTES) {
                throw new IllegalArgumentException("externalRef exceeds "
                        + FxResultBody.MAX_EXTERNAL_REF_BYTES + " bytes");
            }
            detailHash = detailHash != null && detailHash.length > 0
                    ? detailHash.clone() : null;
            if (detailHash != null && detailHash.length != 32) {
                throw new IllegalArgumentException(
                        "detailHash must be 32 bytes when present");
            }
        }

        @Override public byte[] externalRef() { return externalRef.clone(); }
        @Override public byte[] detailHash() {
            return detailHash != null ? detailHash.clone() : null;
        }
    }

    /** The external system answered with a definitive failure. */
    record Failed(String reason, boolean retryable) implements EffectExecution {
        public Failed {
            Objects.requireNonNull(reason, "reason");
            StringBuilder safe = new StringBuilder(
                    Math.min(reason.length(), MAX_FAILURE_REASON_CHARACTERS));
            for (int index = 0; index < reason.length()
                    && safe.length() < MAX_FAILURE_REASON_CHARACTERS; index++) {
                char character = reason.charAt(index);
                safe.append(Character.isISOControl(character) ? ' ' : character);
            }
            reason = safe.toString();
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
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
            externalRef = externalRef != null ? externalRef.clone() : new byte[0];
            if (externalRef.length > FxResultBody.MAX_EXTERNAL_REF_BYTES) {
                throw new IllegalArgumentException("externalRef exceeds "
                        + FxResultBody.MAX_EXTERNAL_REF_BYTES + " bytes");
            }
        }

        @Override public byte[] externalRef() { return externalRef.clone(); }
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
