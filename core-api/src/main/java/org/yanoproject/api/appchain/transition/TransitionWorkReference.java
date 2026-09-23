package org.yanoproject.api.appchain.transition;

/**
 * Names an owned budget a kernel is allowed to charge, not a namespace it may mutate.
 * Assemblers resolve both identifiers against the committed component catalog and reject unknown budgets.
 * The owning component may also be the requesting component.
 *
 * @param participantId exact component instance identifier in the assembly
 * @param budgetId identifier declared by that participant's kernel
 */
public record TransitionWorkReference(String participantId, String budgetId) {
    /** Requires bounded ASCII identifiers so references have an unambiguous stable identity. */
    public TransitionWorkReference {
        TransitionWorkBudget.requireIdentifier(participantId);
        TransitionWorkBudget.requireIdentifier(budgetId);
    }
}
