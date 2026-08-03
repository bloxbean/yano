package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoValidityDiscoveryTest {

    @Test
    void unselectedBaseLedgerHasNoOptionalRuntimeRequirement() {
        assertThat(new EutxoStateMachineProvider().create(context(Map.of())))
                .isNotNull();
    }

    @Test
    void selectedMissingProviderAndInvalidBooleanFailClosed() {
        assertThatThrownBy(() -> new EutxoStateMachineProvider().create(context(
                Map.of(
                        EutxoValidityEngines.ENABLED, "true",
                        EutxoValidityEngines.PROVIDER, "missing"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> new EutxoStateMachineProvider().create(context(
                Map.of(EutxoValidityEngines.ENABLED, "yes"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true or false");
    }

    private static AppStateMachineContext context(Map<String, String> settings) {
        return new AppStateMachineContext() {
            @Override
            public String chainId() {
                return "eutxo-validity-discovery";
            }

            @Override
            public Map<String, String> settings() {
                return settings;
            }
        };
    }
}
