package com.bloxbean.cardano.yano.runtime.util;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Shared failure ordering for best-effort lifecycle cleanup. */
public final class LifecycleFailures {
    private static final int MAX_THROWABLE_GRAPH_NODES = 256;

    private LifecycleFailures() {
    }

    /** Errors after which in-process recovery must not be attempted. */
    public static boolean isProcessFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    /** Whether {@code candidate} must replace {@code current} as primary. */
    public static boolean outranks(Throwable candidate, Throwable current) {
        Objects.requireNonNull(candidate, "candidate");
        return current == null || rank(candidate) > rank(current);
    }

    /**
     * Merge cleanup failures without losing the strongest signal.
     * Process-fatal errors win over other {@link Error}s, which win over
     * ordinary failures. Equal ranks retain the earlier failure. Suppression
     * is identity-cycle-safe, including plugin-constructed cause graphs.
     */
    public static Throwable merge(Throwable current, Throwable next) {
        Objects.requireNonNull(next, "next");
        if (current == null || current == next) {
            return current == null ? next : current;
        }
        Throwable winner;
        Throwable loser;
        if (rank(next) > rank(current)) {
            winner = next;
            loser = current;
        } else {
            winner = current;
            loser = next;
        }
        suppressIfAcyclic(winner, loser);
        return winner;
    }

    /** Rethrow only process-fatal errors; all other throwables return normally. */
    public static void rethrowIfProcessFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    /**
     * Promote a process-fatal value reachable through a bounded cause/suppressed
     * graph. An ordinary wrapper must not make an OOME or ThreadDeath recoverable
     * at a sanitizing boundary.
     */
    public static void rethrowIfProcessFatalReachable(Throwable failure) {
        if (failure == null) {
            return;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty() && visited.size() < MAX_THROWABLE_GRAPH_NODES) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            rethrowIfProcessFatal(current);
            Throwable cause;
            Throwable[] suppressed;
            try {
                cause = current.getCause();
                suppressed = current.getSuppressed();
            } catch (Throwable inspectionFailure) {
                rethrowIfProcessFatal(inspectionFailure);
                return;
            }
            if (cause != null
                    && pending.size() + visited.size() < MAX_THROWABLE_GRAPH_NODES) {
                pending.addLast(cause);
            }
            for (Throwable nested : suppressed) {
                if (nested != null
                        && pending.size() + visited.size() < MAX_THROWABLE_GRAPH_NODES) {
                    pending.addLast(nested);
                }
            }
        }
    }

    private static int rank(Throwable failure) {
        if (isProcessFatal(failure)) {
            return 3;
        }
        return failure instanceof Error ? 2 : 1;
    }

    private static void suppressIfAcyclic(Throwable winner, Throwable loser) {
        if (reachableOrUnsafe(winner, loser)) {
            return;
        }
        if (reachableOrUnsafe(loser, winner)) {
            return;
        }
        winner.addSuppressed(loser);
    }

    private static boolean reachableOrUnsafe(Throwable root, Throwable target) {
        try {
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<Throwable> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty() && visited.size() < MAX_THROWABLE_GRAPH_NODES) {
                Throwable current = pending.removeFirst();
                if (current == target) {
                    return true;
                }
                if (!visited.add(current)) {
                    continue;
                }
                // Throwable.getCause() is overridable plugin code. If graph
                // inspection itself fails, conservatively skip suppression;
                // cleanup must never abort while trying to enrich diagnostics.
                Throwable cause = current.getCause();
                if (cause != null) {
                    if (pending.size() + visited.size() >= MAX_THROWABLE_GRAPH_NODES) {
                        return true;
                    }
                    pending.addLast(cause);
                }
                for (Throwable suppressed : current.getSuppressed()) {
                    if (suppressed != null) {
                        if (pending.size() + visited.size()
                                >= MAX_THROWABLE_GRAPH_NODES) {
                            return true;
                        }
                        pending.addLast(suppressed);
                    }
                }
            }
            return !pending.isEmpty();
        } catch (Throwable unsafeGraph) {
            rethrowIfProcessFatal(unsafeGraph);
            return true;
        }
    }
}
