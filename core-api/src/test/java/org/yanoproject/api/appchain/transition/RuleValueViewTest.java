package org.yanoproject.api.appchain.transition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleValueViewTest {
    private static final RuleFact STATUS = new RuleFact("status", RuleFact.Type.TEXT);
    private static final RuleFact PRICE = new RuleFact("price", RuleFact.Type.INTEGER);

    @Test
    void namespacesAreEmptyOrCollectionIds() {
        for (String namespace : new String[]{"", "holders", "a", "a.b_c-9", "0" + "x".repeat(63)}) {
            assertThat(new RuleValueView(namespace, List.of(STATUS), List.of()).namespace()).isEqualTo(namespace);
        }
        for (String namespace : new String[]{"Holders", "-a", ".a", "a b", "x".repeat(65), "a\n"}) {
            assertThatThrownBy(() -> new RuleValueView(namespace, List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RuleValueView(null, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fieldListsAreBoundedUniqueCopiesWithoutReservedNames() {
        List<RuleFact> fields = new ArrayList<>(List.of(STATUS));
        var view = new RuleValueView("items", fields, List.of(PRICE));
        fields.add(PRICE);
        assertThat(view.fields()).containsExactly(STATUS);
        assertThatThrownBy(() -> view.fields().add(PRICE)).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new RuleValueView("", List.of(STATUS, STATUS), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleValueView("", List.of(), List.of(PRICE, PRICE)))
                .isInstanceOf(IllegalArgumentException.class);
        // "present" is defined by the caller for every read.
        assertThatThrownBy(() -> new RuleValueView("", List.of(new RuleFact("present", RuleFact.Type.BOOLEAN)),
                List.of())).isInstanceOf(IllegalArgumentException.class);
        // Under the value. prefix, "present" and "value" collide with nothing, so a schema may use them.
        var present = new RuleFact("present", RuleFact.Type.BOOLEAN);
        var valueNamed = new RuleFact("value", RuleFact.Type.TEXT);
        assertThat(new RuleValueView("", List.of(STATUS), List.of(present, valueNamed)).valueFields())
                .containsExactly(present, valueNamed);
        // A plain "value" field would collide with value.<field> only when value fields exist.
        var value = new RuleFact("value", RuleFact.Type.BYTES);
        assertThat(new RuleValueView("", List.of(value), List.of()).fields()).containsExactly(value);
        assertThatThrownBy(() -> new RuleValueView("", List.of(value), List.of(PRICE)))
                .isInstanceOf(IllegalArgumentException.class);

        List<RuleFact> most = new ArrayList<>();
        for (int index = 0; index < RuleValueView.MAX_FIELDS; index++) {
            most.add(new RuleFact("f" + index, RuleFact.Type.INTEGER));
        }
        assertThat(new RuleValueView("", most, most).valueFields()).hasSize(RuleValueView.MAX_FIELDS);
        List<RuleFact> tooMany = new ArrayList<>(most);
        tooMany.add(new RuleFact("extra", RuleFact.Type.INTEGER));
        assertThatThrownBy(() -> new RuleValueView("", tooMany, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleValueView("", List.of(), tooMany))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleValueView("", Collections.singletonList(null), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void boundsAndConventionsAreFrozen() {
        assertThat(RuleValueView.MAX_VIEWS).isEqualTo(64);
        assertThat(RuleValueView.MAX_FIELDS).isEqualTo(RuleFact.MAX_FACTS);
        assertThat(RuleValueView.MAX_WRITES).isEqualTo(128);
        assertThat(RuleValueView.VALUE_PREFIX).isEqualTo("value.");
        assertThat(RuleValueView.PRESENT).isEqualTo("present");
    }
}
