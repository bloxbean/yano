package org.yanoproject.ledger.rules.conway.ruleset;

import org.yanoproject.ledger.rules.conway.CheckLabel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The Conway rules of one protocol major version (ADR-056 Phase 5c): for each {@link Scope} of
 * {@link ConwayScopes#ALL}, the ordered units the rule family runs, and the version's {@link RulePolicy policies}.
 *
 * <p>Immutable. A version's set is its predecessor's {@linkplain #with(RuleSetDelta) with} one delta
 * ({@link ConwayRuleSets}); the predecessor is not changed, so adding a version cannot change an earlier one. Each unit
 * remembers the version that introduced it ({@link #since(Scope, String)}), which is what the frozen manifests show.</p>
 */
public final class ConwayRuleSet {

    /** A unit and the protocol version whose delta introduced it. */
    record Placed(RuleUnit<?> unit, int since) {
    }

    /** A policy and the protocol version whose delta introduced it. */
    record PlacedPolicy(RulePolicy policy, int since) {
    }

    private static final String PACKAGE_PREFIX = "org.yanoproject.ledger.rules.conway.";

    private final int protocolVersion;
    private final String summary;
    private final Map<Scope<?>, List<Placed>> scopes;
    private final Map<Scope<?>, List<RuleUnit<?>>> units;
    private final Map<PolicyKey<?>, PlacedPolicy> policies;

    private ConwayRuleSet(int protocolVersion, String summary, Map<Scope<?>, List<Placed>> scopes,
                          Map<PolicyKey<?>, PlacedPolicy> policies) {
        this.protocolVersion = protocolVersion;
        this.summary = summary;
        Map<Scope<?>, List<Placed>> placed = new LinkedHashMap<>();
        Map<Scope<?>, List<RuleUnit<?>>> byScope = new LinkedHashMap<>();
        for (Scope<?> scope : ConwayScopes.ALL) {
            List<Placed> list = List.copyOf(scopes.getOrDefault(scope, List.of()));
            placed.put(scope, list);
            byScope.put(scope, list.stream().<RuleUnit<?>>map(Placed::unit).toList());
        }
        for (Scope<?> scope : scopes.keySet()) {
            if (!ConwayScopes.ALL.contains(scope)) {
                throw new IllegalStateException("PV" + protocolVersion + ": " + scope + " is not a Conway scope");
            }
        }
        this.scopes = Collections.unmodifiableMap(placed);
        this.units = Collections.unmodifiableMap(byScope);
        this.policies = Collections.unmodifiableMap(new LinkedHashMap<>(policies));
        for (Map.Entry<Scope<?>, List<Placed>> e : this.scopes.entrySet()) {
            List<String> ids = new ArrayList<>();
            for (Placed p : e.getValue()) {
                if (ids.contains(p.unit().id())) {
                    throw new IllegalStateException("PV" + protocolVersion + ": " + e.getKey() + " has "
                            + p.unit().id() + " twice");
                }
                ids.add(p.unit().id());
            }
        }
    }

    /** Starts the first protocol version's rule set (the base every delta builds on). */
    public static Builder base(int protocolVersion, String summary) {
        return new Builder(protocolVersion, summary);
    }

    /** @return the protocol major version this rule set validates */
    public int protocolVersion() {
        return protocolVersion;
    }

    /** @return what the version changes (the delta's summary), or the base's description */
    public String summary() {
        return summary;
    }

    /** @return the units of {@code scope}, in execution order */
    @SuppressWarnings("unchecked")
    public <S> List<RuleUnit<S>> units(Scope<S> scope) {
        List<RuleUnit<?>> list = units.get(scope);
        if (list == null) {
            throw new IllegalArgumentException(scope + " is not a Conway scope");
        }
        return (List<RuleUnit<S>>) (List<?>) list;
    }

    /** @return the unit {@code id} of {@code scope}, if the rule set has it */
    public Optional<RuleUnit<?>> unit(Scope<?> scope, String id) {
        return scopes.getOrDefault(scope, List.of()).stream().map(Placed::unit).filter(u -> u.id().equals(id))
                .<RuleUnit<?>>map(u -> u).findFirst();
    }

    /** @return the protocol version whose rule set introduced the unit {@code id} of {@code scope} */
    public int since(Scope<?> scope, String id) {
        return scopes.getOrDefault(scope, List.of()).stream().filter(p -> p.unit().id().equals(id))
                .mapToInt(Placed::since).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PV" + protocolVersion + " has no " + scope + " " + id));
    }

    /** @return the policy {@code key} */
    public <T extends RulePolicy> T policy(PolicyKey<T> key) {
        PlacedPolicy placed = policies.get(key);
        if (placed == null) {
            throw new IllegalArgumentException("PV" + protocolVersion + " has no policy " + key);
        }
        return key.type().cast(placed.policy());
    }

    /** @return {@link ConwayPolicies#DREP_EXPIRY} */
    public DRepExpiry drepExpiry() {
        return policy(ConwayPolicies.DREP_EXPIRY);
    }

    Map<Scope<?>, List<Placed>> placedUnits() {
        return scopes;
    }

    Map<PolicyKey<?>, PlacedPolicy> placedPolicies() {
        return policies;
    }

    /**
     * @return the next protocol version's rule set: this one with {@code delta} applied; this rule set is not changed
     * @throws IllegalStateException when the delta is not for the next version or names a unit the scope does not hold
     */
    public ConwayRuleSet with(RuleSetDelta delta) {
        int version = delta.version();
        if (version != protocolVersion + 1) {
            throw new IllegalStateException("a delta to PV" + version + " cannot follow PV" + protocolVersion);
        }
        Map<Scope<?>, List<Placed>> next = new LinkedHashMap<>();
        scopes.forEach((scope, list) -> next.put(scope, new ArrayList<>(list)));
        Map<PolicyKey<?>, PlacedPolicy> nextPolicies = new LinkedHashMap<>(policies);
        for (RuleSetDelta.Op op : delta.ops()) {
            switch (op) {
                case RuleSetDelta.Add add -> {
                    List<Placed> list = scopeList(next, add.scope(), version);
                    int at = switch (add.position().kind()) {
                        case FIRST -> 0;
                        case LAST -> list.size();
                        case BEFORE -> indexOf(list, add.scope(), add.position().anchor(), version);
                        case AFTER -> indexOf(list, add.scope(), add.position().anchor(), version) + 1;
                    };
                    list.add(at, new Placed(add.unit(), version));
                }
                case RuleSetDelta.Supersede supersede -> {
                    List<Placed> list = scopeList(next, supersede.scope(), version);
                    list.set(indexOf(list, supersede.scope(), supersede.id(), version),
                            new Placed(supersede.replacement(), version));
                }
                case RuleSetDelta.Retire retire -> {
                    List<Placed> list = scopeList(next, retire.scope(), version);
                    list.remove(indexOf(list, retire.scope(), retire.id(), version));
                }
                case RuleSetDelta.SupersedePolicy policy -> {
                    if (!nextPolicies.containsKey(policy.key())) {
                        throw new IllegalStateException("PV" + version + ": no policy " + policy.key()
                                + " to supersede");
                    }
                    if (!policy.key().type().isInstance(policy.replacement())) {
                        throw new IllegalStateException("PV" + version + ": " + policy.replacement().getClass()
                                + " is not a " + policy.key().type().getSimpleName());
                    }
                    nextPolicies.put(policy.key(), new PlacedPolicy(policy.replacement(), version));
                }
            }
        }
        return new ConwayRuleSet(version, delta.summary(), next, nextPolicies);
    }

    private static List<Placed> scopeList(Map<Scope<?>, List<Placed>> scopes, Scope<?> scope, int version) {
        List<Placed> list = scopes.get(scope);
        if (list == null) {
            throw new IllegalStateException("PV" + version + ": " + scope + " is not a Conway scope");
        }
        return list;
    }

    private static int indexOf(List<Placed> list, Scope<?> scope, String id, int version) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).unit().id().equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException("PV" + version + ": " + scope + " has no unit " + id);
    }

    /** The rule set as a manifest without content fingerprints (see {@link #manifest(Function)}). */
    public String manifest() {
        return manifest(type -> "-");
    }

    /**
     * The rule set as a manifest: every non-empty scope's units in execution order with id, kind, label, the version
     * that introduced it, implementation class, content fingerprint, parameters and Haskell reference, then the
     * policies. The frozen copies are
     * {@code ledger-rules/src/test/resources/org/yanoproject/ledger/rules/conway/ruleset/conway-pv<N>.manifest}, whose
     * fingerprints are digests of the implementations' source ({@code ConwayRuleSetManifestTest}).
     *
     * @param fingerprint the content fingerprint of an implementation class
     */
    public String manifest(Function<Class<?>, String> fingerprint) {
        StringBuilder out = new StringBuilder();
        out.append("# Conway rule set, protocol version ").append(protocolVersion).append('\n');
        out.append("# ").append(summary).append('\n');
        out.append("# Generated by ConwayRuleSet#manifest; the frozen copy is checked by ConwayRuleSetManifestTest.\n");
        out.append("# id | kind | label | since | implementation | content | variant | Haskell\n");
        scopes.forEach((scope, list) -> {
            if (list.isEmpty()) {
                return;
            }
            out.append('\n').append('[').append(scope.name()).append("]\n");
            for (Placed p : list) {
                RuleUnit<?> u = p.unit();
                out.append(u.id()).append(" | ").append(u.kind().name().toLowerCase()).append(" | ")
                        .append(u.label() == CheckLabel.STATIC ? "static" : "dynamic").append(" | ")
                        .append(p.since()).append(" | ").append(className(u.getClass())).append(" | ")
                        .append(fingerprint.apply(u.getClass())).append(" | ")
                        .append(clean(u.variant())).append(" | ").append(clean(u.haskellRef())).append('\n');
            }
        });
        out.append("\n[policies]\n");
        policies.forEach((key, p) -> out.append(key.id()).append(" | policy | - | ").append(p.since()).append(" | ")
                .append(className(p.policy().getClass())).append(" | ").append(fingerprint.apply(p.policy().getClass()))
                .append(" | ").append(clean(p.policy().variant())).append(" | ").append(clean(p.policy().haskellRef()))
                .append('\n'));
        return out.toString();
    }

    private static String className(Class<?> type) {
        String name = type.getName();
        return name.startsWith(PACKAGE_PREFIX) ? name.substring(PACKAGE_PREFIX.length()) : name;
    }

    private static String clean(String text) {
        return text.replace('\n', ' ').replace("|", "\\|");
    }

    @Override
    public String toString() {
        return "ConwayRuleSet[PV" + protocolVersion + "]";
    }

    /** Builds the base rule set. */
    public static final class Builder {

        private final int protocolVersion;
        private final String summary;
        private final Map<Scope<?>, List<Placed>> scopes = new LinkedHashMap<>();
        private final Map<PolicyKey<?>, PlacedPolicy> policies = new LinkedHashMap<>();

        private Builder(int protocolVersion, String summary) {
            this.protocolVersion = protocolVersion;
            this.summary = Objects.requireNonNull(summary, "summary");
        }

        /** Sets {@code scope}'s units, in execution order. */
        @SafeVarargs
        public final <S> Builder scope(Scope<S> scope, RuleUnit<S>... units) {
            if (scopes.containsKey(scope)) {
                throw new IllegalStateException(scope + " is set twice");
            }
            List<Placed> list = new ArrayList<>();
            for (RuleUnit<S> unit : units) {
                list.add(new Placed(Objects.requireNonNull(unit, "unit"), protocolVersion));
            }
            scopes.put(scope, list);
            return this;
        }

        public <T extends RulePolicy> Builder policy(PolicyKey<T> key, T policy) {
            policies.put(key, new PlacedPolicy(Objects.requireNonNull(policy, "policy"), protocolVersion));
            return this;
        }

        /** A scope the base does not set is empty: a scope added for a later version is filled by its delta. */
        public ConwayRuleSet build() {
            return new ConwayRuleSet(protocolVersion, summary, scopes, policies);
        }
    }
}
