package com.bloxbean.cardano.yano.runtime.peer;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Formats peer connection failures for operator logs without stack traces.
 */
public final class PeerFailureMessage {
    private PeerFailureMessage() {
    }

    public static String summarize(Throwable failure) {
        if (failure == null) {
            return "unknown peer failure";
        }

        Throwable root = rootCause(failure);
        Throwable networkCause = deepestSuppressed(root != null ? root : failure);
        Throwable detail = networkCause != null ? networkCause : root != null ? root : failure;

        String primary = oneLine(failure);
        String detailText = oneLine(detail);
        if (primary.equals(detailText)) {
            return primary;
        }
        return primary + "; cause=" + detailText;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current.getCause() != null && seen.add(current)) {
            current = current.getCause();
        }
        return current;
    }

    private static Throwable deepestSuppressed(Throwable failure) {
        ArrayDeque<Throwable> stack = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        stack.push(failure);
        Throwable deepest = null;
        while (!stack.isEmpty()) {
            Throwable current = stack.pop();
            if (current == null || !seen.add(current)) {
                continue;
            }
            deepest = current;
            if (current.getCause() != null) {
                stack.push(current.getCause());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                stack.push(suppressed);
            }
        }
        return deepest != failure ? deepest : null;
    }

    private static String oneLine(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        return type + ": " + message.replaceAll("\\s+", " ").trim();
    }
}
