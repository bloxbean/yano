package org.yanoproject.ledger.rules.conway.ruleset;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.conway.PvRange;
import org.yanoproject.ledger.rules.conway.certs.DRepExpiries;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.utxo.UtxoChecks;
import org.yanoproject.ledger.rules.conway.utxo.UtxoSubject;
import org.yanoproject.ledger.rules.conway.utxow.UtxowChecks;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.ledger.rules.conway.ruleset.RuleSetDelta.Position.after;
import static org.yanoproject.ledger.rules.conway.ruleset.RuleSetDelta.Position.first;

/**
 * The structure of the versioned rule sets (ADR-056 Phase 5c): {@code pv9 = base}, {@code pv(N) = pv(N-1).with(deltaN)},
 * a delta cannot reach an earlier version, the composition agrees with the pinned constructor catalogue, and units are
 * stateless named classes.
 */
class ConwayRuleSetsTest {

    private static final List<RuleSetDelta> DELTAS = List.of(ConwayDelta10.delta(), ConwayDelta11.delta());

    @Test
    void theSupportedVersionsAreTheRuleSets() {
        assertThat(ConwayRuleSets.all()).extracting(ConwayRuleSet::protocolVersion).containsExactly(9, 10, 11);
        assertThat(ConwayRuleSets.SUPPORTED).isEqualTo(PvRange.between(9, 11));
        assertThat(JavaLedgerValidationEngine.SUPPORTED).isEqualTo(ConwayRuleSets.SUPPORTED);
        assertThat(ConwayRuleSets.forProtocol(8)).isEmpty();
        assertThat(ConwayRuleSets.forProtocol(12)).isEmpty();
        assertThat(ConwayRuleSets.forProtocol(10)).get().extracting(ConwayRuleSet::protocolVersion).isEqualTo(10);
        // The engine-neutral rules (MEMPOOL, the effects deriver) use the latest set after the range, nothing before it.
        assertThat(ConwayRuleSets.forProtocolOrLatest(12).protocolVersion()).isEqualTo(11);
        assertThat(ConwayRuleSets.forProtocolOrLatest(9).protocolVersion()).isEqualTo(9);
        assertThatThrownBy(() -> ConwayRuleSets.forProtocolOrLatest(8)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before Conway");
    }

    @Test
    void addingADeltaNeverChangesAnEarlierVersion() {
        // Each prefix of the deltas composes the same earlier rule sets as the whole chain.
        List<ConwayRuleSet> full = ConwayRuleSets.compose(ConwayBaseRules.pv9(), DELTAS);
        for (int k = 0; k <= DELTAS.size(); k++) {
            List<ConwayRuleSet> prefix = ConwayRuleSets.compose(ConwayBaseRules.pv9(), DELTAS.subList(0, k));
            for (int v = 0; v < prefix.size(); v++) {
                assertThat(prefix.get(v).manifest()).as("PV%d with %d deltas", prefix.get(v).protocolVersion(), k)
                        .isEqualTo(full.get(v).manifest());
            }
        }
        // with() leaves its receiver unchanged.
        ConwayRuleSet pv9 = ConwayBaseRules.pv9();
        String before = pv9.manifest();
        pv9.with(ConwayDelta10.delta());
        assertThat(pv9.manifest()).isEqualTo(before);
        assertThat(full.getLast().manifest()).isEqualTo(ConwayRuleSets.latest().manifest());
    }

    @Test
    void deltasAreConsecutive() {
        assertThatThrownBy(() -> ConwayBaseRules.pv9().with(ConwayDelta11.delta()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot follow PV9");
    }

    @Test
    void aDeltaMustNameUnitsTheScopeHolds() {
        ConwayRuleSet pv10 = ConwayRuleSets.forProtocol(10).orElseThrow();
        RuleSetDelta retireUnknown = RuleSetDelta.toVersion(11, "test").retire(ConwayScopes.UTXO, "UTXO.NoSuchCheck")
                .build();
        assertThatThrownBy(() -> pv10.with(retireUnknown)).hasMessageContaining("UTXO has no unit UTXO.NoSuchCheck");
        RuleSetDelta wrongScope = RuleSetDelta.toVersion(11, "test").add(ConwayScopes.UTXO,
                new UtxoChecks.FeeTooSmall(), after("UTXOW.PPViewHashesDontMatch")).build();
        assertThatThrownBy(() -> pv10.with(wrongScope)).hasMessageContaining("UTXO has no unit");
        RuleSetDelta twice = RuleSetDelta.toVersion(11, "test").add(ConwayScopes.UTXO, new UtxoChecks.FeeTooSmall(),
                after("UTXO.FeeTooSmallUTxO")).build();
        assertThatThrownBy(() -> pv10.with(twice)).hasMessageContaining("has UTXO.FeeTooSmallUTxO twice");
    }

    @Test
    void theCompositionMustAgreeWithTheCatalogue() {
        // A hypothetical protocol version 12 that brings back a check whose constructor ends at 10.
        RuleSetDelta revivesDisjointRefInputs = RuleSetDelta.toVersion(12, "test")
                .add(ConwayScopes.UTXO, new UtxoChecks.NonDisjointRefInputs(), first()).build();
        assertThatThrownBy(() -> ConwayRuleSets.check(ConwayRuleSets.latest().with(revivesDisjointRefInputs)))
                .hasMessageContaining("PV12 runs UTXO UTXO.BabbageNonDisjointRefInputs")
                .hasMessageContaining("retire or supersede it");
        // One that drops a check whose constructor still exists.
        RuleSetDelta dropsFee = RuleSetDelta.toVersion(12, "test").retire(ConwayScopes.UTXO, "UTXO.FeeTooSmallUTxO")
                .build();
        assertThatThrownBy(() -> ConwayRuleSets.check(ConwayRuleSets.latest().with(dropsFee)))
                .hasMessageContaining("PV12 has no unit reporting UTXO.FeeTooSmallUTxO");
        // A superseding implementation that is a lambda or anonymous class cannot be named by the manifests.
        RuleSetDelta anonymous = RuleSetDelta.toVersion(12, "test").supersede(ConwayScopes.UTXO,
                "UTXO.FeeTooSmallUTxO", new PredicateCheck<UtxoSubject>(ConwayPredicate.FEE_TOO_SMALL) {
                    @Override
                    protected String detail(UtxoSubject subject) {
                        return null;
                    }
                }).build();
        assertThatThrownBy(() -> ConwayRuleSets.check(ConwayRuleSets.latest().with(anonymous)))
                .hasMessageContaining("must be named classes");
        // The real protocol version 12 would be the latest set with its delta: the composition itself is fine.
        ConwayRuleSets.check(ConwayRuleSets.latest().with(RuleSetDelta.toVersion(12, "test").build()));
    }

    @Test
    void everyConstructorIsReportedExactlyWhereTheCatalogueSaysItExists() {
        for (ConwayRuleSet set : ConwayRuleSets.all()) {
            Set<ConwayPredicate> reported = new LinkedHashSet<>();
            for (Scope<?> scope : ConwayScopes.ALL) {
                set.units(scope).forEach(u -> reported.addAll(u.reports()));
            }
            for (ConwayPredicate predicate : ConwayPredicate.values()) {
                assertThat(reported.contains(predicate)).as("PV%d reports %s", set.protocolVersion(),
                        predicate.qualifiedName()).isEqualTo(predicate.pvRange().contains(set.protocolVersion()));
            }
        }
    }

    /**
     * The composition check is per constructor; this one is per (scope, constructor) and per (scope, unit): from one
     * version to the next, the scopes that report a constructor, and the scopes that hold a unit id, change only in the
     * scopes the delta names. And a delta that supersedes or retires a unit does so in every scope that holds it, unless
     * recorded here as a deliberate one-branch change (none so far).
     */
    @Test
    void aDeltaChangesOnlyTheScopesItNames() {
        List<ConwayRuleSet> sets = ConwayRuleSets.all();
        for (int k = 0; k < DELTAS.size(); k++) {
            ConwayRuleSet before = sets.get(k);
            ConwayRuleSet after = sets.get(k + 1);
            RuleSetDelta delta = DELTAS.get(k);
            Set<Scope<?>> named = new LinkedHashSet<>();
            Map<String, Set<Scope<?>>> opsById = new LinkedHashMap<>();
            for (RuleSetDelta.Op op : delta.ops()) {
                switch (op) {
                    case RuleSetDelta.Add a -> named.add(a.scope());
                    case RuleSetDelta.Supersede r -> {
                        named.add(r.scope());
                        opsById.computeIfAbsent(r.id(), x -> new LinkedHashSet<>()).add(r.scope());
                    }
                    case RuleSetDelta.Retire r -> {
                        named.add(r.scope());
                        opsById.computeIfAbsent(r.id(), x -> new LinkedHashSet<>()).add(r.scope());
                    }
                    case RuleSetDelta.SupersedePolicy p -> {
                    }
                }
            }
            for (ConwayPredicate predicate : ConwayPredicate.values()) {
                Set<Scope<?>> changed = symmetricDifference(scopesReporting(before, predicate),
                        scopesReporting(after, predicate));
                assertThat(named).as("PV%d: scopes reporting %s changed in %s", after.protocolVersion(),
                        predicate.qualifiedName(), changed).containsAll(changed);
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Scope<?> scope : ConwayScopes.ALL) {
                before.units(scope).forEach(u -> ids.add(u.id()));
                after.units(scope).forEach(u -> ids.add(u.id()));
            }
            for (String id : ids) {
                Set<Scope<?>> changed = symmetricDifference(scopesHolding(before, id), scopesHolding(after, id));
                assertThat(named).as("PV%d: scopes holding %s changed in %s", after.protocolVersion(), id, changed)
                        .containsAll(changed);
            }
            opsById.forEach((id, scopes) -> assertThat(scopes).as("PV%d: %s is superseded or retired in every scope "
                    + "that holds it", after.protocolVersion(), id).containsAll(scopesHolding(before, id)));
        }
    }

    private static Set<Scope<?>> scopesReporting(ConwayRuleSet set, ConwayPredicate predicate) {
        Set<Scope<?>> scopes = new LinkedHashSet<>();
        for (Scope<?> scope : ConwayScopes.ALL) {
            if (set.units(scope).stream().anyMatch(u -> u.reports().contains(predicate))) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    private static Set<Scope<?>> scopesHolding(ConwayRuleSet set, String id) {
        Set<Scope<?>> scopes = new LinkedHashSet<>();
        for (Scope<?> scope : ConwayScopes.ALL) {
            if (set.unit(scope, id).isPresent()) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    private static Set<Scope<?>> symmetricDifference(Set<Scope<?>> a, Set<Scope<?>> b) {
        Set<Scope<?>> result = new LinkedHashSet<>(a);
        result.addAll(b);
        Set<Scope<?>> both = new LinkedHashSet<>(a);
        both.retainAll(b);
        result.removeAll(both);
        return result;
    }

    @Test
    void aUnitsVersionsComeFromTheComposition() {
        assertThat(ConwayRuleSets.versionsOf("UTXO.BabbageNonDisjointRefInputs")).contains(PvRange.between(9, 10));
        assertThat(ConwayRuleSets.versionsOf("UTXOW.ScriptIntegrityHashMismatch")).contains(PvRange.from(11));
        assertThat(ConwayRuleSets.versionsOf("GOV.DisallowedProposalDuringBootstrap")).contains(PvRange.BOOTSTRAP);
        assertThat(ConwayRuleSets.versionsOf("LEDGER.ConwayWdrlNotDelegatedToDRep")).contains(PvRange.POST_BOOTSTRAP);
        assertThat(ConwayRuleSets.versionsOf("CERTS.preCertificateStep")).contains(PvRange.between(9, 10));
        assertThat(ConwayRuleSets.versionsOf("LEDGER.preCertificateStep")).contains(PvRange.from(11));
        assertThat(ConwayRuleSets.versionsOf("UTXO.NoSuchCheck")).isEmpty();
        // A check whose id is its constructor exists exactly where the constructor does.
        for (ConwayRuleSet set : ConwayRuleSets.all()) {
            for (Scope<?> scope : ConwayScopes.ALL) {
                for (RuleUnit<?> unit : set.units(scope)) {
                    if (unit instanceof PredicateCheck<?> check && check.id().equals(check.predicate().qualifiedName())) {
                        assertThat(ConwayRuleSets.versionsOf(check.id())).as(check.id())
                                .contains(check.predicate().pvRange());
                    }
                }
            }
        }
        // The version a unit was introduced by is the manifest's "since".
        ConwayRuleSet pv11 = ConwayRuleSets.latest();
        assertThat(pv11.since(ConwayScopes.UTXOW, "UTXOW.ScriptIntegrityHashMismatch")).isEqualTo(11);
        assertThat(pv11.since(ConwayScopes.GOV_PROPOSAL, "GOV.MalformedProposal")).isEqualTo(11);
        assertThat(pv11.since(ConwayScopes.UTXO, "UTXO.FeeTooSmallUTxO")).isEqualTo(9);
    }

    @Test
    void policiesAreVersioned() {
        assertThat(ConwayRuleSets.forProtocol(9).orElseThrow().drepExpiry()).isInstanceOf(DRepExpiries.Bootstrap.class);
        assertThat(ConwayRuleSets.forProtocol(10).orElseThrow().drepExpiry())
                .isInstanceOf(DRepExpiries.DormantAdjusted.class);
        assertThat(ConwayRuleSets.forProtocol(11).orElseThrow().drepExpiry())
                .isInstanceOf(DRepExpiries.DormantAdjusted.class);
    }

    @Test
    void unitsAndPoliciesAreStatelessNamedClasses() {
        List<Object> implementations = new ArrayList<>();
        for (ConwayRuleSet set : ConwayRuleSets.all()) {
            for (Scope<?> scope : ConwayScopes.ALL) {
                implementations.addAll(set.units(scope));
            }
            implementations.add(set.drepExpiry());
        }
        for (Object implementation : implementations) {
            Class<?> type = implementation.getClass();
            assertThat(type.isAnonymousClass() || type.isLocalClass() || type.isSynthetic() || type.isHidden())
                    .as("%s is a named class", type.getName()).isFalse();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers())).as("%s.%s is final", c.getName(),
                                field.getName()).isTrue();
                    }
                }
            }
        }
    }
}
