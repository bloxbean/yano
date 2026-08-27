package com.bloxbean.cardano.yano.archive.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Operator-facing view of archive resource contention.
 *
 * <p>It reports only scheduling facts — gate occupancy, holder operation names,
 * wait durations, and the last genuine mutation failure. It never carries row
 * data, transaction payloads, addresses, or credentials.
 */
public record ArchiveResourceDiagnostics(
        List<GateUsage> gates,
        Optional<WaitEvent> lastWaitWarning,
        Optional<FailureEvent> lastMutationFailure,
        Optional<String> lastMaintenanceDeferral) {

    public ArchiveResourceDiagnostics {
        gates = List.copyOf(Objects.requireNonNull(gates, "gates"));
        Objects.requireNonNull(lastWaitWarning, "lastWaitWarning");
        Objects.requireNonNull(lastMutationFailure, "lastMutationFailure");
        Objects.requireNonNull(lastMaintenanceDeferral, "lastMaintenanceDeferral");
    }

    public static ArchiveResourceDiagnostics empty() {
        return new ArchiveResourceDiagnostics(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Occupancy of one named gate. {@code holder} is the longest-running current holder. */
    public record GateUsage(String name, int inUse, int totalPermits, int waiters,
                            String holder, Duration holderDuration) {
        public GateUsage {
            Objects.requireNonNull(name, "name");
            holder = Objects.requireNonNullElse(holder, "");
            Objects.requireNonNull(holderDuration, "holderDuration");
        }
    }

    /** A caller that was still waiting when the warn interval elapsed. Not a failure. */
    public record WaitEvent(String gate, String operation, Duration waited, String holderDetail, Instant at) {
        public WaitEvent {
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(waited, "waited");
            holderDetail = Objects.requireNonNullElse(holderDetail, "");
            Objects.requireNonNull(at, "at");
        }
    }

    /** A mutation that actually failed, as opposed to one that merely waited. */
    public record FailureEvent(String operation, String detail, Instant at) {
        public FailureEvent {
            Objects.requireNonNull(operation, "operation");
            detail = Objects.requireNonNullElse(detail, "");
            Objects.requireNonNull(at, "at");
        }
    }
}
