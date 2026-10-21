package org.yanoproject.api.appchain.transition;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleFactTest {
    @Test
    void acceptsIdentifierNamesForEveryType() {
        for (RuleFact.Type type : RuleFact.Type.values()) {
            assertThat(new RuleFact("actorId", type).type()).isEqualTo(type);
        }
        assertThat(new RuleFact("a", RuleFact.Type.TEXT).name()).isEqualTo("a");
        assertThat(new RuleFact("A" + "b_9".repeat(20) + "xy", RuleFact.Type.TEXT).name()).hasSize(63);
    }

    @Test
    void rejectsNamesRulesCannotSelectAndMissingTypes() {
        for (String name : new String[]{null, "", "9role", "_role", "actor-id", "actor.id", "rôle", "a".repeat(64),
                "in", "null", "true", "if"}) {
            assertThatThrownBy(() -> new RuleFact(name, RuleFact.Type.TEXT))
                    .as(String.valueOf(name)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RuleFact("roles", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void typeOrdinalsAndBoundsAreFrozen() {
        assertThat(RuleFact.Type.values()).containsExactly(RuleFact.Type.INTEGER, RuleFact.Type.TEXT,
                RuleFact.Type.BYTES, RuleFact.Type.BOOLEAN, RuleFact.Type.TEXT_SET);
        assertThat(RuleFact.MAX_FACTS).isEqualTo(32);
        assertThat(RuleFact.MAX_SET_ENTRIES).isEqualTo(64);
        assertThat(RuleFact.MAX_SET_ENTRY_BYTES).isEqualTo(128);
        assertThat(RuleFact.MAX_VALUE_BYTES).isEqualTo(4096);
    }
}
