package org.yanoproject.runtime.validation;

import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Retainable;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The published mempool state one admission validates against (ADR-056 §6, "Admission"), handed from the mempool's
 * mutation lane to the validation listener on the admitting thread.
 *
 * <p>The mempool opens the scope under its lane, with the published state's overlay (one layer per mempool
 * transaction over the canonical base ticked to the admission slot) and that state's retained base; the default
 * validation listener ({@link EngineAdmission}) validates against the overlay with rule {@code MEMPOOL} and records
 * the outcome here, so the mempool can append the {@code ValidatedTx} and its effects layer. Shadow engines get the
 * same (immutable) overlay and retain the base for their duration.</p>
 */
public final class LedgerAdmissionScope implements AutoCloseable {

    private static final ThreadLocal<LedgerAdmissionScope> CURRENT = new ThreadLocal<>();

    private final String txHash;
    private final LedgerView view;
    private final ValidationEnv env;
    private final String envFailure;
    private final TxValidationRequest.Origin origin;
    private final Retainable base;
    private final LongSupplier baseAgeMillis;
    private final LedgerAdmissionScope previous;
    private TxValidationOutcome outcome;
    private boolean closed;

    private LedgerAdmissionScope(String txHash, LedgerView view, ValidationEnv env, String envFailure,
                                 TxValidationRequest.Origin origin, Retainable base, LongSupplier baseAgeMillis,
                                 LedgerAdmissionScope previous) {
        this.txHash = txHash;
        this.view = view;
        this.env = env;
        this.envFailure = envFailure;
        this.origin = origin;
        this.base = base;
        this.baseAgeMillis = baseAgeMillis;
        this.previous = previous;
    }

    /**
     * Publishes a scope on the calling thread until {@link #close()}.
     *
     * @param txHash        the transaction being admitted (lowercase hex)
     * @param view          the immutable admission view (the published overlay)
     * @param env           the environment, or {@code null} when it could not be built
     * @param envFailure    why there is no environment, or {@code null}
     * @param origin        the request origin
     * @param base          the retained canonical base the view reads
     * @param baseAgeMillis the base's age, for the shadow max-age policy
     */
    public static LedgerAdmissionScope open(String txHash, LedgerView view, ValidationEnv env, String envFailure,
                                            TxValidationRequest.Origin origin, Retainable base,
                                            LongSupplier baseAgeMillis) {
        LedgerAdmissionScope scope = new LedgerAdmissionScope(Objects.requireNonNull(txHash, "txHash"),
                Objects.requireNonNull(view, "view"), env, envFailure, Objects.requireNonNull(origin, "origin"),
                Objects.requireNonNull(base, "base"), baseAgeMillis, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /** @return the scope of the admission of {@code txHash} running on this thread, or {@code null} */
    public static LedgerAdmissionScope current(String txHash) {
        LedgerAdmissionScope scope = CURRENT.get();
        return scope != null && !scope.closed && (txHash == null || scope.txHash.equalsIgnoreCase(txHash))
                ? scope : null;
    }

    public String txHash() {
        return txHash;
    }

    public LedgerView view() {
        return view;
    }

    /** @return the environment, or {@code null} when {@link #envFailure()} is set */
    public ValidationEnv env() {
        return env;
    }

    public String envFailure() {
        return envFailure;
    }

    public TxValidationRequest.Origin origin() {
        return origin;
    }

    /** @return the retained base; a holder that outlives the admission retains it again */
    public Retainable base() {
        return base;
    }

    public long baseAgeMillis() {
        return baseAgeMillis != null ? baseAgeMillis.getAsLong() : 0;
    }

    /** Records the admission engine's outcome (the first one recorded wins). */
    public void record(TxValidationOutcome result) {
        if (outcome == null) {
            outcome = Objects.requireNonNull(result, "result");
        }
    }

    /** @return the recorded outcome, or {@code null} when no engine validated in this scope */
    public TxValidationOutcome outcome() {
        return outcome;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (CURRENT.get() == this) {
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                CURRENT.remove();
            }
        }
    }
}
