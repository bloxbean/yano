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

    /**
     * Declares the namespaces of this kernel's state that admission rules can read, and the typed fields each
     * namespace's stored values decode to (Yano X ADR-031.4). The list depends only on committed configuration, is
     * fixed for the kernel's lifetime, and has at most {@link RuleValueView#MAX_VIEWS} views with unique
     * namespaces. Empty by default: the kernel exposes no values.
     *
     * <p>Declarations and decodings are consensus-relevant for every profile whose rules read them, exactly like
     * {@link #ruleFacts()}.
     *
     * @return stable view declarations derived only from committed configuration
     */
    default List<RuleValueView> ruleValueViews() { return List.of(); }

    /**
     * Resolves a rule read's key within one declared namespace to the owner-local state key the read uses. The
     * method is pure and configuration-only, like {@link #lookupKey(byte[])}: no state reads, scans, or namespace
     * prefixes; a composite adds its own component prefix afterwards. The default supports only the
     * single-namespace case and delegates to {@link #lookupKey(byte[])}.
     *
     * @param namespace a namespace this kernel declared
     * @param key the rule's key bytes (text keys are UTF-8 encoded by the caller)
     * @return the owner-local state key; never {@code null} or empty
     * @throws IllegalArgumentException for a namespace or key the kernel does not accept; callers turn this into a
     *                                  deterministic rule error, never a block failure. Every other exception
     *                                  propagates as an implementation failure, as from {@link #decide}.
     */
    default byte[] ruleValueKey(String namespace, byte[] key) {
        if (!namespace.isEmpty()) throw new IllegalArgumentException("unknown rule value namespace");
        return lookupKey(key);
    }

    /**
     * Decodes one stored value of a declared namespace into its declared fields. The method is pure: no I/O, clock,
     * state reader, randomness, or node-local input. It returns a flat map keyed by declared field name, with value
     * fields under {@code value.<name>} ({@link RuleValueView#VALUE_PREFIX}). A declared name that is absent means
     * "not established": an optional member that was omitted, a text or bytes value longer than
     * {@link RuleFact#MAX_VALUE_BYTES}, or a record the kernel cannot decode (which yields no fields). A value that
     * cannot be represented in its declared type, such as an integer outside int64, is returned unchanged rather
     * than coerced; callers treat it as a declaration violation. Values otherwise follow {@link #ruleFactValues}.
     *
     * <p>Callers validate the result as they validate {@link #ruleFactValues} and turn a violation into a
     * deterministic rejection. An exception propagates as an implementation failure.
     *
     * @param namespace the declared namespace that was read
     * @param key the rule's key bytes, as passed to {@link #ruleValueKey}
     * @param stored the stored value found at that key
     * @return decoded values; empty by default
     */
    default Map<String, Object> ruleValueFields(String namespace, byte[] key, byte[] stored) {
        return Map.of();
    }

    /**
     * Declares the content fields of this kernel's write view: one element per write of a command, readable by
     * admission rules through a bounded quantifier (Yano X ADR-031.4). A non-empty list declares that the kernel has
     * a write view. At most {@link RuleValueView#MAX_FIELDS} unique names, fixed by committed configuration. No
     * content field is named {@code index} (the caller defines every element's position), {@code value} (the prefix
     * of value fields), or {@code present}.
     *
     * @return content field declarations; empty by default (no write view)
     */
    default List<RuleFact> ruleWriteFields() { return List.of(); }

    /**
     * Declares the verified-coverage fields of this kernel's write view: who verifiably authorized each write. They
     * are established only after an approved decision (see {@link #ruleWriteCoverage}). At most
     * {@link RuleValueView#MAX_FIELDS} unique names, distinct from {@link #ruleWriteFields()} and following the same
     * reservations ({@code index}, {@code value}, {@code present}).
     *
     * @return coverage field declarations; empty by default
     */
    default List<RuleFact> ruleWriteCoverageFields() { return List.of(); }

    /**
     * Returns the write view of a decoded command: one element per write, in command order, at most
     * {@link RuleValueView#MAX_WRITES}. The method is a pure function of the command and committed configuration;
     * local ingress may call it without state or an execution context. Each element is a flat map of declared
     * content fields, with value fields under {@code value.<name>}, and never an {@code index}, which the caller
     * defines. An element carries only value fields declared for the write view: {@code value.<f>} where some
     * {@link RuleValueView} declares value field {@code f} and every view that declares it gives it the same type. A
     * kernel omits a member whose type conflicts between views rather than returning it. A declared name that is
     * absent means "not established" for that write.
     *
     * <p>Callers validate the result and turn a violation into a deterministic rejection. An exception propagates as
     * an implementation failure.
     *
     * @param command an admitted, decoded command
     * @return the write view; empty by default
     */
    default List<Map<String, Object>> ruleWrites(C command) { return List.of(); }

    /**
     * Returns the verified coverage of each write of a command whose decision was
     * {@link TransitionDecision.Approved} with exactly this facts instance: a list of the same length and order as
     * {@link #ruleWrites}, each element holding declared coverage fields. Callers invoke it only after
     * {@code decide(command, context, facts)} approved, and never for a rejection. The method is pure and returns
     * only what this kernel established by its own verification; a write with no established coverage has an empty
     * element.
     *
     * <p>Callers validate the result as they validate {@link #ruleFactValues}. An exception propagates as an
     * implementation failure.
     *
     * @param command the decoded command that was approved
     * @param context the execution context of that decision
     * @param facts the exact facts instance passed to the approving decision
     * @return per-write coverage; empty by default
     */
    default List<Map<String, Object>> ruleWriteCoverage(C command, TransitionContext context, F facts) {
        return List.of();
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
