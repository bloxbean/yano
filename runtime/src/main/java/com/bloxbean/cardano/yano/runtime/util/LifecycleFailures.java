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
        current = normalizeProcessFatalReachable(current);
        next = normalizeProcessFatalReachable(next);
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
        Error fatal = findProcessFatalReachable(failure);
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * Return the first process-fatal value reachable through a bounded
     * cause/suppressed graph, or {@code null} when the graph is recoverable.
     */
    public static Error findProcessFatalReachable(Throwable failure) {
        if (failure == null) {
            return null;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty() && visited.size() < MAX_THROWABLE_GRAPH_NODES) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof VirtualMachineError fatal) {
                return fatal;
            }
            if (current instanceof ThreadDeath fatal) {
                return fatal;
            }
            Throwable cause = null;
            try {
                cause = current.getCause();
            } catch (Throwable inspectionFailure) {
                if (inspectionFailure instanceof VirtualMachineError fatal) {
                    return fatal;
                }
                if (inspectionFailure instanceof ThreadDeath fatal) {
                    return fatal;
                }
                // Cause inspection is plugin-overridable. A hostile ordinary
                // failure must not hide a fatal on the independent,
                // platform-owned suppressed edge.
            }
            Throwable[] suppressed;
            try {
                suppressed = current.getSuppressed();
            } catch (Throwable inspectionFailure) {
                if (inspectionFailure instanceof VirtualMachineError fatal) {
                    return fatal;
                }
                if (inspectionFailure instanceof ThreadDeath fatal) {
                    return fatal;
                }
                suppressed = new Throwable[0];
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
        return null;
    }

    /** Replace an ordinary wrapper with the process-fatal value it contains. */
    public static Throwable normalizeProcessFatalReachable(Throwable failure) {
        Error fatal = findProcessFatalReachable(failure);
        return fatal == null ? failure : fatal;
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
