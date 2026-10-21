package org.yanoproject.ledger.rules.conway.ruleset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.ledger.rules.conway.PvRange;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Conway rule sets the Java engine validates with (ADR-056 Phase 5c): {@code pv9 = base},
 * {@code pv10 = pv9.with(delta10)}, {@code pv11 = pv10.with(delta11)}.
 *
 * <p>Adding a protocol version is a new {@code ConwayDeltaN} registered in {@link #DELTAS}, its new or superseding
 * units, and its frozen manifest; nothing else. The composition is checked when this class loads (a failure makes the
 * engine unusable rather than wrong):</p>
 * <ul>
 *   <li>deltas are consecutive, each applies to the previous version's set only (which it cannot change), and every
 *       unit a delta names exists;</li>
 *   <li>units and policies are named, stateless classes (the manifests name them);</li>
 *   <li>the rule sets agree with the pinned constructor catalogue: a version's set reports a constructor exactly when
 *       the constructor's protocol-version range ({@link ConwayPredicate#pvRange()}) contains the version.</li>
 * </ul>
 */
public final class ConwayRuleSets {

    /** The deltas after the base, in version order. */
    private static final List<RuleSetDelta> DELTAS = List.of(ConwayDelta10.delta(), ConwayDelta11.delta());

    private static final List<ConwayRuleSet> SETS = compose(ConwayBaseRules.pv9(), DELTAS);

    /** The protocol versions the Java engine validates (ADR-056 invariant 7): every rule set's version. */
    public static final PvRange SUPPORTED = PvRange.between(SETS.getFirst().protocolVersion(),
            SETS.getLast().protocolVersion());

    private static final Logger LOG = LoggerFactory.getLogger(ConwayRuleSets.class);
    private static final Set<Integer> WARNED = ConcurrentHashMap.newKeySet();

    private ConwayRuleSets() {
    }

    /** @return the rule set of {@code protocolMajor}, or empty when the engine does not validate it (fail closed) */
    public static Optional<ConwayRuleSet> forProtocol(int protocolMajor) {
        if (!SUPPORTED.contains(protocolMajor)) {
            return Optional.empty();
        }
        return Optional.of(SETS.get(protocolMajor - SUPPORTED.min()));
    }

    /**
     * For the engine-neutral rules other engines share ({@code MempoolRule}, the effects deriver), which may be asked
     * about a protocol version newer than the Java engine knows: the rule set of {@code protocolMajor}, else the latest
     * (logged once per version at WARN, so a new protocol version's rollout is visible). The Java engine itself uses
     * {@link #forProtocol(int)} and fails closed.
     *
     * @throws IllegalArgumentException for a version before Conway's first ({@link #SUPPORTED}{@code .min()})
     */
    public static ConwayRuleSet forProtocolOrLatest(int protocolMajor) {
        if (protocolMajor < SUPPORTED.min()) {
            throw new IllegalArgumentException("protocol version " + protocolMajor + " is before Conway (the Conway rule "
                    + "sets start at " + SUPPORTED.min() + ")");
        }
        if (protocolMajor > SUPPORTED.max()) {
            if (WARNED.add(protocolMajor)) {
                LOG.warn("Conway rules asked for protocol version {}, newer than the latest rule set ({}): using the "
                        + "latest; add a ConwayDelta{} (ADR-056 Phase 5c)", protocolMajor, SUPPORTED.max(), protocolMajor);
            }
            return latest();
        }
        return SETS.get(protocolMajor - SUPPORTED.min());
    }

    /** @return every rule set, in version order */
    public static List<ConwayRuleSet> all() {
        return SETS;
    }

    /** @return the latest protocol version's rule set */
    public static ConwayRuleSet latest() {
        return SETS.getLast();
    }

    /**
     * @return the protocol versions whose rule set holds a unit with {@code id} in some scope, open-ended when the
     *         latest holds it; empty when none does
     */
    public static Optional<PvRange> versionsOf(String id) {
        List<Integer> versions = new ArrayList<>();
        for (ConwayRuleSet set : SETS) {
            boolean holds = ConwayScopes.ALL.stream().anyMatch(scope -> set.unit(scope, id).isPresent());
            if (holds) {
                versions.add(set.protocolVersion());
            }
        }
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        int max = versions.getLast() == SUPPORTED.max() ? Integer.MAX_VALUE : versions.getLast();
        return Optional.of(PvRange.between(versions.getFirst(), max));
    }

    /**
     * Composes and checks the rule sets.
     *
     * @throws IllegalStateException when the composition is inconsistent
     */
    static List<ConwayRuleSet> compose(ConwayRuleSet base, List<RuleSetDelta> deltas) {
        List<ConwayRuleSet> sets = new ArrayList<>();
        ConwayRuleSet current = base;
        check(current);
        sets.add(current);
        for (RuleSetDelta delta : deltas) {
            current = current.with(delta);
            check(current);
            sets.add(current);
        }
        return Collections.unmodifiableList(sets);
    }

    /** The checks of one version's rule set. */
    static void check(ConwayRuleSet set) {
        int version = set.protocolVersion();
        Set<ConwayPredicate> reported = EnumSet.noneOf(ConwayPredicate.class);
        set.placedUnits().forEach((scope, units) -> units.forEach(placed -> {
            RuleUnit<?> unit = placed.unit();
            requireNamedClass(version, unit.getClass(), scope + " " + unit.id());
            for (ConwayPredicate predicate : unit.reports()) {
                if (!predicate.pvRange().contains(version)) {
                    throw new IllegalStateException("PV" + version + " runs " + scope + " " + unit.id() + ", which "
                            + "reports " + predicate.qualifiedName() + " (protocol versions " + predicate.pvRange()
                            + " in the catalogue): retire or supersede it in that version's delta");
                }
                reported.add(predicate);
            }
        }));
        for (ConwayPredicate predicate : ConwayPredicate.values()) {
            if (predicate.pvRange().contains(version) && !reported.contains(predicate)) {
                throw new IllegalStateException("PV" + version + " has no unit reporting " + predicate.qualifiedName()
                        + " (protocol versions " + predicate.pvRange() + " in the catalogue): add it in that "
                        + "version's delta");
            }
        }
        set.placedPolicies().forEach((key, placed) ->
                requireNamedClass(version, placed.policy().getClass(), "policy " + key));
    }

    private static void requireNamedClass(int version, Class<?> type, String what) {
        if (type.isAnonymousClass() || type.isLocalClass() || type.isSynthetic() || type.isHidden()) {
            throw new IllegalStateException("PV" + version + " " + what + " is implemented by " + type.getName()
                    + ": units and policies must be named classes (the manifests name them)");
        }
    }
}
