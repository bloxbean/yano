package org.yanoproject.ledger.rules.conway.ruleset;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What one protocol version changes relative to the previous one (ADR-056 Phase 5c): {@code pvN = pv(N-1).with(deltaN)}.
 *
 * <p>A delta can only</p>
 * <ul>
 *   <li><b>add</b> a unit to a scope, at an explicit position relative to a unit already there
 *       ({@link Builder#add(Scope, RuleUnit, Position)});</li>
 *   <li><b>supersede</b> a unit by id with another implementation, which takes its place in the order (and may carry
 *       another id, when Haskell renamed the constructor) ({@link Builder#supersede(Scope, String, RuleUnit)});</li>
 *   <li><b>retire</b> a unit ({@link Builder#retire(Scope, String)});</li>
 *   <li><b>supersede a policy</b> ({@link Builder#supersede(PolicyKey, RulePolicy)}).</li>
 * </ul>
 *
 * <p>It never edits a unit: the superseded implementation stays as it is, and the earlier versions' rule sets keep
 * using it. The operations apply in order; a reference to a unit the scope does not hold fails the composition.</p>
 */
public final class RuleSetDelta {

    /** Where an added unit goes. */
    public record Position(Kind kind, String anchor) {

        public enum Kind {
            FIRST,
            LAST,
            BEFORE,
            AFTER
        }

        public static Position first() {
            return new Position(Kind.FIRST, null);
        }

        public static Position last() {
            return new Position(Kind.LAST, null);
        }

        /** Immediately before the unit with id {@code anchor}. */
        public static Position before(String anchor) {
            return new Position(Kind.BEFORE, Objects.requireNonNull(anchor, "anchor"));
        }

        /** Immediately after the unit with id {@code anchor}. */
        public static Position after(String anchor) {
            return new Position(Kind.AFTER, Objects.requireNonNull(anchor, "anchor"));
        }

        @Override
        public String toString() {
            return anchor == null ? kind.name().toLowerCase() : kind.name().toLowerCase() + " " + anchor;
        }
    }

    /** One operation. */
    sealed interface Op {
    }

    record Add(Scope<?> scope, RuleUnit<?> unit, Position position) implements Op {
    }

    record Supersede(Scope<?> scope, String id, RuleUnit<?> replacement) implements Op {
    }

    record Retire(Scope<?> scope, String id) implements Op {
    }

    record SupersedePolicy(PolicyKey<?> key, RulePolicy replacement) implements Op {
    }

    private final int version;
    private final String summary;
    private final List<Op> ops;

    private RuleSetDelta(int version, String summary, List<Op> ops) {
        this.version = version;
        this.summary = summary;
        this.ops = List.copyOf(ops);
    }

    /**
     * @param version the protocol major version the delta creates
     * @param summary the Haskell hard-fork gates the delta implements (for the manifest)
     */
    public static Builder toVersion(int version, String summary) {
        return new Builder(version, summary);
    }

    /** @return the protocol major version this delta creates */
    public int version() {
        return version;
    }

    public String summary() {
        return summary;
    }

    List<Op> ops() {
        return ops;
    }

    /** Collects a delta's operations. */
    public static final class Builder {

        private final int version;
        private final String summary;
        private final List<Op> ops = new ArrayList<>();

        private Builder(int version, String summary) {
            this.version = version;
            this.summary = Objects.requireNonNull(summary, "summary");
        }

        /** Adds {@code unit} to {@code scope} at {@code position}. */
        public <S> Builder add(Scope<S> scope, RuleUnit<S> unit, Position position) {
            ops.add(new Add(scope, Objects.requireNonNull(unit, "unit"), Objects.requireNonNull(position, "position")));
            return this;
        }

        /** Replaces the unit {@code id} of {@code scope} with {@code replacement}, in the same place. */
        public <S> Builder supersede(Scope<S> scope, String id, RuleUnit<S> replacement) {
            ops.add(new Supersede(scope, Objects.requireNonNull(id, "id"),
                    Objects.requireNonNull(replacement, "replacement")));
            return this;
        }

        /** Removes the unit {@code id} from {@code scope}. */
        public Builder retire(Scope<?> scope, String id) {
            ops.add(new Retire(scope, Objects.requireNonNull(id, "id")));
            return this;
        }

        /** Replaces the policy {@code key}. */
        public <T extends RulePolicy> Builder supersede(PolicyKey<T> key, T replacement) {
            ops.add(new SupersedePolicy(key, Objects.requireNonNull(replacement, "replacement")));
            return this;
        }

        public RuleSetDelta build() {
            return new RuleSetDelta(version, summary, ops);
        }
    }
}
