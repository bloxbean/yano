package org.yanoproject.api.appchain.transition;

import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.codec.MessageCodec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Discoverable plan-producing command transition used by both standalone machines and composite workflows.
 *
 * <p>A caller decodes and admits a command, reads its facts from the supplied component view, and asks
 * {@link #decide(Object, TransitionContext, Object)} for a decision. An approved plan is not yet committed:
 * a composite may reject a later derived command and discard every plan in the source cascade.
 * Implementations must therefore avoid writes, effect emission, I/O, clocks, randomness, and mutable
 * node-local decisions in all kernel callbacks. Authorization remains part of admission and decision;
 * invoking a kernel from a binding does not confer additional authority.
 *
 * @param <C> decoded command type
 * @param <F> immutable facts required by the pure decision function
 */
public interface TransitionKernel<C, F> extends TransitionCapability<C, F> {
    /** Returns the command wire codec; derived commands undergo the same decoding as external commands. */
    MessageCodec<C> codec();

    /**
     * Performs cheap, pure admission using only the decoded command and immutable kernel configuration.
     * Local ingress may invoke this callback without an execution height, sender context, or state reader.
     * Implementations must not infer missing execution identity, perform I/O, or make authorization decisions
     * that require execution facts. Acceptance is not authorization and does not guarantee approval.
     *
     * @param command decoded command, subject to the same codec as execution
     * @return a non-null admission result; a rejection should use a bounded symbolic reason code
     */
    default AdmissionResult admit(C command) {
        return AdmissionResult.accept();
    }

    /**
     * Performs execution-context admission before facts are read. The default delegates to command-only
     * admission; overrides should preserve those checks before adding deterministic context checks.
     * This callback is not a substitute for authorization in the decision function.
     *
     * @param command decoded command
     * @param context real deterministic execution identity, never a fabricated local-admission context
     * @return a non-null admission result; acceptance does not guarantee approval
     */
    default AdmissionResult admit(C command, TransitionContext context) {
        return admit(command);
    }

    /**
     * Reads only the provided component-scoped view, which may include uncommitted cascade overlay writes.
     * Returned facts must not retain a mutable writer or consult a different state source during decision.
     */
    F facts(C command, TransitionContext context, AppStateReader state);

    /**
     * Declares additional read-only participant instance ids required to construct authorization facts.
     * The declaration must depend only on committed component configuration, not on a command or local state.
     * It grants no write access and does not make these participants implicit targets of a transition plan.
     *
     * @return stable, duplicate-free participant ids; empty for component-local kernels
     */
    default List<String> readParticipants() { return List.of(); }

    /**
     * Declares this component's exclusive counter keys and per-block limits, fixed by committed configuration.
     * Assemblers reject duplicate ids or keys and more than {@link TransitionWorkBudget#MAX_DECLARATIONS} entries.
     * These declarations grant no foreign business-state write access.
     *
     * @return immutable, stable owner declarations; empty when this component owns no work budgets
     */
    default List<TransitionWorkBudget> workBudgets() { return List.of(); }

    /**
     * Declares the exact owned budgets this kernel may charge, independently of its read participants.
     * Assemblers resolve references before execution and reject duplicates, unknown owners or budgets,
     * and more than {@link TransitionWorkBudget#MAX_DECLARATIONS} entries. Declaration alone reserves no work.
     *
     * @return stable work references derived only from committed configuration
     */
    default List<TransitionWorkReference> workReferences() { return List.of(); }

    /**
     * Computes at most one cheap work reservation after admission and before facts or signature verification.
     * This callback must neither perform the charged work nor read state, write, or consult node-local data.
     * The executor verifies that the reference was declared, then reserves through the owner counter outside
     * the cascade's refundable business overlay. Exhaustion rejects the command without invoking its facts
     * or decision callbacks. A successful reservation remains charged even if those callbacks reject.
     *
     * @param command already decoded and admitted command
     * @param context immutable deterministic execution identity
     * @return a declared positive request, or empty when this command performs no charged work
     */
    default Optional<TransitionWorkRequest> workRequest(C command, TransitionContext context) {
        return Optional.empty();
    }

    /**
     * Constructs facts with explicitly declared foreign read views. Composites provide an unmodifiable map
     * containing exactly {@link #readParticipants()}, each backed by its current cascade overlay. A kernel
     * needing these views overrides this method; the default supports only the component-local case.
     * Returned facts must snapshot relevant values rather than retain state readers for later decisions.
     *
     * @param state this component's read-only view
     * @param participants the declared foreign component views, never writers or global namespace access
     * @throws IllegalArgumentException if a local-only kernel is given foreign participants
     */
    default F facts(C command, TransitionContext context, AppStateReader state,
                    Map<String, AppStateReader> participants) {
        if (!participants.isEmpty()) throw new IllegalArgumentException("kernel does not accept foreign participants");
        return facts(command, context, state);
    }

    /**
     * Declares the facts this kernel can establish for admission rules. The list depends only on
     * committed configuration, is fixed for the kernel's lifetime, and has at most
     * {@link RuleFact#MAX_FACTS} entries with unique names. Empty by default: a kernel built
     * against an earlier API level declares no facts.
     *
     * <p>Declarations and values are consensus-relevant for every profile whose rules read them:
     * changing which facts a kernel declares, or how it computes them, changes the kernel's consensus
     * semantics and requires a versioned profile decision.
     *
     * @return stable fact declarations derived only from committed configuration
     */
    default List<RuleFact> ruleFacts() { return List.of(); }

    /**
     * Returns the values of declared facts for a command whose decision was
     * {@link TransitionDecision.Approved} with exactly this facts instance. Callers invoke it only
     * after {@code decide(command, context, facts)} approved, and never for a rejection.
     *
     * <p>The method is pure: no I/O, clock, state reader, randomness, or node-local input. It
     * returns only values this kernel established by its own verification. A declared name that
     * is absent means "not established for this command". The result is never {@code null}; values
     * are {@code Long}, {@code String} (at most {@link RuleFact#MAX_VALUE_BYTES} UTF-8 bytes),
     * {@code byte[]} (at most {@link RuleFact#MAX_VALUE_BYTES} bytes), or {@code Boolean} for the
     * scalar types, and for {@link RuleFact.Type#TEXT_SET} a {@code List<String>} of at most
     * {@link RuleFact#MAX_SET_ENTRIES} entries, each at most {@link RuleFact#MAX_SET_ENTRY_BYTES}
     * UTF-8 bytes, strictly increasing by unsigned UTF-8 bytes (sorted and duplicate-free).
     *
     * <p>Callers validate the result defensively and treat a violation (a {@code null} result, key, or
     * value, an undeclared name, a wrong type, or an exceeded bound) as a deterministic rejection of
     * the command, never as a block failure. An exception is not a violation: like an exception from
     * {@link #decide}, it propagates as an implementation failure, because a node-local fault must
     * not become a consensus outcome.
     *
     * @param command the decoded command that was approved
     * @param context the execution context of that decision
     * @param facts the exact facts instance passed to the approving decision
     * @return values keyed by declared fact name; empty by default
     */
    default Map<String, Object> ruleFactValues(C command, TransitionContext context, F facts) {
        return Map.of();
    }

    /** Returns stable command layouts, including evidence fields that mappings must not manufacture. */
    List<CommandDescriptor> commands();

    /** Returns schemas for native events the kernel can produce; composite baseline events are added by its caller. */
    List<EventDescriptor> events();

    /** Returns the configuration schema and defaults used to normalize committed component configuration. */
    ConfigurationDescriptor configuration();

    /**
     * Resolves one documented logical lookup key to an owner-local canonical state key.
     * The default is identity for machines whose logical keys are already physical local keys. Overrides
     * must be pure, bounded, configuration-only codecs: no state reads, scans, or namespace prefixes.
     * A composite always adds its own component namespace after this conversion. Changing the conversion
     * changes the selected kernel's consensus semantics and requires a versioned profile decision.
     *
     * @param logicalKey bounded canonical logical key, in the machine contracts module's documented encoding
     * @return a fresh local key; callers must validate their namespace's physical key limit
     * @throws IllegalArgumentException for malformed or unsupported logical keys
     */
    default byte[] lookupKey(byte[] logicalKey) {
        if (logicalKey == null || logicalKey.length == 0 || logicalKey.length > 65_536) {
            throw new IllegalArgumentException("invalid logical lookup key");
        }
        return logicalKey.clone();
    }
}
