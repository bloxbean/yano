package org.yanoproject.ledger.rules.conway.failure;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.conway.CheckLabel;
import org.yanoproject.ledger.rules.fixtures.conformance.ConwayConstructorCatalogue;

import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** The engine's typed constructors agree with the pinned catalogue (rule, PV range, REAPPLY label). */
class ConwayPredicateCatalogueTest {

    @Test
    void everyPredicateIsACatalogueConstructorWithTheSameGateAndLabel() {
        ConwayConstructorCatalogue catalogue = ConwayConstructorCatalogue.get();
        for (ConwayPredicate predicate : ConwayPredicate.values()) {
            ConwayConstructorCatalogue.Entry entry = catalogue.find(predicate.qualifiedName()).orElseThrow(
                    () -> new AssertionError(predicate.qualifiedName() + " is not in the catalogue"));
            assertThat(entry.check()).as(predicate.qualifiedName())
                    .isEqualTo(predicate.label().name().toLowerCase(Locale.ROOT));
            assertThat(predicate.pvRange().contains(entry.pvMin())).as(predicate.qualifiedName()).isTrue();
            Integer max = predicate.pvRange().max() == Integer.MAX_VALUE ? null : predicate.pvRange().max();
            assertThat(entry.pvMax()).as(predicate.qualifiedName()).isEqualTo(max);
            assertThat(entry.phase()).isEqualTo(predicate.phase().ordinal() + 1);
        }
    }

    @Test
    void phases3To5CoverEveryReachableConstructor() {
        var implemented = Arrays.stream(ConwayPredicate.values()).map(ConwayPredicate::qualifiedName).toList();
        // Every in-scope constructor of every family (Phases 3-5), plus OutsideForecast: unreachable at the pin, but
        // Haskell still runs its (never failing) check, so the engine keeps it in Haskell's order.
        var expected = ConwayConstructorCatalogue.get().all().stream()
                .filter(e -> e.inScope() || e.constructor().equals("OutsideForecast"))
                .map(ConwayConstructorCatalogue.Entry::qualifiedName).toList();
        assertThat(implemented).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ConwayPredicate.VALIDATION_TAG_MISMATCH.label()).isEqualTo(CheckLabel.STATIC);
    }
}
