package org.yanoproject.ledger.rules.phase2;

import java.util.List;
import java.util.Objects;

/**
 * One script execution.
 *
 * @param purpose  the redeemer purpose ({@code spend}, {@code mint}, {@code cert}, {@code reward},
 *                 {@code voting}, {@code proposing})
 * @param index    the redeemer index within its purpose
 * @param success  whether the script succeeded within its budget
 * @param mem      memory units consumed
 * @param steps    CPU steps consumed
 * @param logs     the script's trace messages
 * @param error    why it failed, or {@code null}
 */
public record ScriptOutcome(String purpose, int index, boolean success, long mem, long steps, List<String> logs,
                            String error) {

    public ScriptOutcome {
        Objects.requireNonNull(purpose, "purpose");
        logs = logs == null ? List.of() : List.copyOf(logs);
    }
}
