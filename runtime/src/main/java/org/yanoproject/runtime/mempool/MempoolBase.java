package org.yanoproject.runtime.mempool;

import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Retainable;
import org.yanoproject.runtime.validation.ValidationEnvFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The canonical base of a mempool state (ADR-056 §6): a ledger view of one canonical generation ticked to the
 * admission slot (the slot after the tip, decision 6b), with the {@link CanonicalMark} it is fresh for and the
 * forecast-horizon basis {@code next(tip)}.
 *
 * <p><b>Ownership.</b> Reference counted. {@link MempoolBaseSource#acquire()} hands out the first reference; the
 * published mempool state owns one reference for as long as it is published (the rebuild that acquired it
 * transfers it on a successful swap); every admission and every frozen shadow view retains it for its duration.
 * When the count reaches zero the view's resources (the canonical snapshot) are freed.</p>
 */
public final class MempoolBase implements Retainable {

    private final LedgerView view;
    private final CanonicalMark mark;
    private final long targetSlot;
    private final long forecastBasisSlot;
    private final String unavailableReason;
    private final Runnable onFree;
    private final long createdNanos = System.nanoTime();
    private final AtomicInteger refs = new AtomicInteger(1);
    private volatile Object env; // ValidationEnv, or the failure message

    private MempoolBase(LedgerView view, CanonicalMark mark, long targetSlot, long forecastBasisSlot,
                        String unavailableReason, Runnable onFree) {
        this.view = Objects.requireNonNull(view, "view");
        this.mark = Objects.requireNonNull(mark, "mark");
        this.targetSlot = targetSlot;
        this.forecastBasisSlot = forecastBasisSlot;
        this.unavailableReason = unavailableReason;
        this.onFree = onFree != null ? onFree : () -> { };
    }

    /**
     * @param view              the ticked canonical view
     * @param mark              the generation and target epoch it belongs to
     * @param targetSlot        the validation slot ({@code ValidationEnv.currentSlot})
     * @param forecastBasisSlot {@code next(tip)}, the forecast-horizon basis
     * @param onFree            frees the view's resources when the last reference is released
     */
    public static MempoolBase of(LedgerView view, CanonicalMark mark, long targetSlot, long forecastBasisSlot,
                                 Runnable onFree) {
        return new MempoolBase(view, mark, targetSlot, forecastBasisSlot, null, onFree);
    }

    /** A base without state: every read is unavailable, so every validation fails closed. */
    public static MempoolBase unavailable(String reason, CanonicalMark mark) {
        return new MempoolBase(new UnavailableLedgerView(reason), mark, -1, 0, reason, null);
    }

    public LedgerView view() {
        return view;
    }

    public CanonicalMark mark() {
        return mark;
    }

    public long generation() {
        return mark.generation();
    }

    public long targetSlot() {
        return targetSlot;
    }

    public long forecastBasisSlot() {
        return forecastBasisSlot;
    }

    public boolean isUnavailable() {
        return unavailableReason != null;
    }

    /** @return why the base has no state, or {@code null} */
    public String unavailableReason() {
        return unavailableReason;
    }

    public long ageMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdNanos);
    }

    /**
     * The validation environment of this base, computed once (the view's protocol parameters are those of the
     * ticked epoch), with the forecast horizon based on {@code next(tip)}.
     *
     * @throws LedgerStateUnavailableException when it cannot be built
     */
    public ValidationEnv env(ValidationEnvFactory factory) {
        Object cached = env;
        if (cached == null) {
            synchronized (this) {
                cached = env;
                if (cached == null) {
                    if (unavailableReason != null) {
                        cached = "no canonical state: " + unavailableReason;
                    } else {
                        try {
                            cached = factory.create(targetSlot, view).withForecastBasisSlot(forecastBasisSlot);
                        } catch (RuntimeException e) {
                            cached = "validation environment: " + e.getMessage();
                        }
                    }
                    env = cached;
                }
            }
        }
        if (cached instanceof ValidationEnv validationEnv) {
            return validationEnv;
        }
        throw new LedgerStateUnavailableException((String) cached);
    }

    /** @return the current reference count; 0 once freed */
    public int refCount() {
        return refs.get();
    }

    @Override
    public MempoolBase retain() {
        while (true) {
            int current = refs.get();
            if (current <= 0) {
                throw new IllegalStateException("mempool base of generation " + generation() + " is already freed");
            }
            if (refs.compareAndSet(current, current + 1)) {
                return this;
            }
        }
    }

    @Override
    public void release() {
        while (true) {
            int current = refs.get();
            if (current <= 0) {
                return; // a double release is a caller bug; never free twice
            }
            if (refs.compareAndSet(current, current - 1)) {
                if (current == 1) {
                    onFree.run();
                }
                return;
            }
        }
    }

    @Override
    public String toString() {
        return "MempoolBase[generation=" + mark.generation() + ", targetEpoch=" + mark.targetEpoch() + ", slot="
                + targetSlot + (unavailableReason != null ? ", unavailable: " + unavailableReason : "") + ", refs="
                + refs.get() + "]";
    }
}
