package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainTestProfiles;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CardanoHistoryPilotPresetTest {
    @Test
    void fullPilotIsOneNormalAppStateMachineComposedFromTheThreeReusableMachines() {
        var machine = CardanoHistoryPilotPreset.create(context(Map.of()));

        assertThat(machine.id()).isEqualTo("cardano-history-pilot");
        assertThat(machine.profile().profileId()).isEqualTo("adr028-full-pilot-v1");
        assertThat(machine.profile().components())
                .extracting(component -> component.componentId())
                .containsExactly("epoch-params", "epoch-stake", "epoch-governance");
        assertThat(machine.profile().components())
                .allSatisfy(component -> assertThat(component.topics())
                        .singleElement().asString().startsWith("~l1/"));
        assertThat(machine.capabilityManifest().components()).hasSize(3);
        assertThat(machine.profile().digest()).hasSize(32);
    }

    private static AppStateMachineContext context(Map<String, String> settings) {
        AppChainConsensusProfile profile = AppChainTestProfiles.fromSettings(settings);
        return new AppStateMachineContext() {
            @Override public String chainId() { return "cardano-history-chain"; }
            @Override public Map<String, String> settings() { return settings; }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                return Optional.of(profile);
            }
        };
    }
}
