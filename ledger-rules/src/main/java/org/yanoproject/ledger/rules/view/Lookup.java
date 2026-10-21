package org.yanoproject.ledger.rules.view;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Result of every {@link LedgerView} read (ADR-056 invariant 2).
 *
 * <ul>
 *   <li>{@link Present}: the record exists, with its value.</li>
 *   <li>{@link Absent}: the view is certain the record does not exist. This is ordinary ledger data
 *       (a first-time registration, a spent output) and goes to the rules, which report the typed
 *       ledger failure if one applies.</li>
 *   <li>{@link Unavailable}: the view could not answer (read failure, store not ready, snapshot
 *       released). Rules must never treat this as absence; they throw
 *       {@link LedgerStateUnavailableException} and the engine rejects the transaction with the
 *       engine failure {@code LedgerStateUnavailable}.</li>
 * </ul>
 *
 * @param <T> value type
 */
public sealed interface Lookup<T> permits Lookup.Present, Lookup.Absent, Lookup.Unavailable {

    record Present<T>(T value) implements Lookup<T> {
        public Present {
            Objects.requireNonNull(value, "value");
        }
    }

    record Absent<T>() implements Lookup<T> {
    }

    record Unavailable<T>(String reason) implements Lookup<T> {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    static <T> Lookup<T> present(T value) {
        return new Present<>(value);
    }

    @SuppressWarnings("unchecked")
    static <T> Lookup<T> absent() {
        return (Lookup<T>) Constants.ABSENT;
    }

    static <T> Lookup<T> unavailable(String reason) {
        return new Unavailable<>(reason);
    }

    /** @return {@link Present} for a non-null value, otherwise {@link Absent} */
    static <T> Lookup<T> ofNullable(T value) {
        return value == null ? absent() : present(value);
    }

    default boolean isPresent() {
        return this instanceof Present;
    }

    default boolean isAbsent() {
        return this instanceof Absent;
    }

    default boolean isUnavailable() {
        return this instanceof Unavailable;
    }

    /**
     * Collapses the three outcomes for rule code: present and absent become an {@link Optional};
     * unavailable throws.
     *
     * @return the value, or empty when confirmed absent
     * @throws LedgerStateUnavailableException when the read is unavailable
     */
    default Optional<T> orElseThrowUnavailable() {
        return switch (this) {
            case Present<T> p -> Optional.of(p.value());
            case Absent<T> a -> Optional.empty();
            case Unavailable<T> u -> throw new LedgerStateUnavailableException(u.reason());
        };
    }

    /**
     * Like {@link #orElseThrowUnavailable()} but also treats confirmed absence as a broken
     * precondition, for callers that already know the record must exist.
     *
     * @param what description used in the exception message
     * @return the value
     * @throws LedgerStateUnavailableException when the read is unavailable
     * @throws IllegalStateException           when the record is absent
     */
    default T require(String what) {
        return orElseThrowUnavailable()
                .orElseThrow(() -> new IllegalStateException("Expected ledger state is absent: " + what));
    }

    /** Maps a present value; absent and unavailable pass through unchanged. */
    default <R> Lookup<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return switch (this) {
            case Present<T> p -> Lookup.ofNullable(mapper.apply(p.value()));
            case Absent<T> a -> absent();
            case Unavailable<T> u -> unavailable(u.reason());
        };
    }

    /** Holder for the shared {@link Absent} instance. */
    final class Constants {
        private static final Absent<Object> ABSENT = new Absent<>();

        private Constants() {
        }
    }
}
