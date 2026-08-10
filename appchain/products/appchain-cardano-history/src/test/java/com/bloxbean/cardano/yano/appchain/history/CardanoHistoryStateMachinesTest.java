package com.bloxbean.cardano.yano.appchain.history;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainTestProfiles;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardanoHistoryStateMachinesTest {
    @Test
    void paramsOnlyDoesNotActivateLargeDatasetComponents() {
        var machine = CardanoHistoryStateMachines.create(context(settings("params-only-v1")));

        assertThat(machine.capabilityManifest().applicationId()).isEqualTo("cardano-history");
        assertThat(machine.capabilityManifest().components())
                .extracting(component -> component.id())
                .containsExactly(CardanoHistoryProduct.PARAMS_COMPONENT);
        assertThat(machine.authenticatedSnapshotSeries()).isEmpty();
    }

    @Test
    void allReleasedPresetsResolveOnlyTheirDeclaredComponents() {
        assertThat(CardanoHistoryStateMachines.create(context(settings("params-stake-v1")))
                .capabilityManifest().components()).extracting(component -> component.id())
                .containsExactly(CardanoHistoryProduct.PARAMS_COMPONENT,
                        CardanoHistoryProduct.STAKE_COMPONENT);
        assertThat(CardanoHistoryStateMachines.create(context(settings("params-governance-v1")))
                .capabilityManifest().components()).extracting(component -> component.id())
                .containsExactly(CardanoHistoryProduct.GOVERNANCE_COMPONENT,
                        CardanoHistoryProduct.PARAMS_COMPONENT);
        assertThat(CardanoHistoryStateMachines.create(context(settings("full-v1")))
                .capabilityManifest().components()).extracting(component -> component.id())
                .containsExactly(CardanoHistoryProduct.GOVERNANCE_COMPONENT,
                        CardanoHistoryProduct.PARAMS_COMPONENT,
                        CardanoHistoryProduct.STAKE_COMPONENT);
    }

    @Test
    void rejectsObserverPresetMismatchAndJmtForOnChainConsumption() {
        Map<String, String> mismatch = new LinkedHashMap<>(settings("params-only-v1"));
        mismatch.put("observers.epoch-stake.type", "l1-epoch-stake-v1");
        assertThatThrownBy(() -> CardanoHistoryStateMachines.create(context(mismatch)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not enabled");

        Map<String, String> jmt = new LinkedHashMap<>(settings("params-only-v1"));
        jmt.put("state.l1-proof-consumption-required", "true");
        assertThatThrownBy(() -> CardanoHistoryStateMachines.create(context(
                jmt, StateCommitmentProfiles.CLASSIC_JMT.id())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MPF");
    }

    @Test
    void largeDatasetPresetsRequireTheirPerEpochAuthenticatedSnapshots() {
        Map<String, String> disabled = new LinkedHashMap<>(settings("params-stake-v1"));
        disabled.remove("capabilities.authenticated-snapshots.enabled");
        assertThatThrownBy(() -> CardanoHistoryStateMachines.create(context(disabled)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authenticated-snapshots.enabled=true");

        Map<String, String> missingStake = new LinkedHashMap<>(settings("params-stake-v1"));
        missingStake.put("capabilities.authenticated-snapshots.series",
                "l1-epoch-governance-v1.drep-distribution");
        assertThatThrownBy(() -> CardanoHistoryStateMachines.create(context(missingStake)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("per-epoch snapshot series");
    }

    @Test
    void profileIdentityIsIndependentOfConfigurationMapOrder() {
        Map<String, String> first = settings("full-v1");
        Map<String, String> reversed = new LinkedHashMap<>();
        first.entrySet().stream().sorted(Map.Entry.<String, String>comparingByKey().reversed())
                .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));

        assertThat(CardanoHistoryStateMachines.create(context(first)).profile().digest())
                .containsExactly(CardanoHistoryStateMachines.create(context(reversed)).profile().digest());
    }

    private static Map<String, String> settings(String preset) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("machines.cardano-history.preset", preset);
        values.put("observers.epoch-params.type", "l1-epoch-params-v1");
        if (preset.equals("params-stake-v1") || preset.equals("full-v1")) {
            values.put("observers.epoch-stake.type", "l1-epoch-stake-v1");
        }
        if (preset.equals("params-governance-v1") || preset.equals("full-v1")) {
            values.put("observers.epoch-governance.type", "l1-epoch-governance-v1");
        }
        if (!preset.equals("params-only-v1")) {
            values.put("capabilities.authenticated-snapshots.enabled", "true");
            values.put("capabilities.authenticated-snapshots.series", "all");
        }
        return values;
    }

    private static AppStateMachineContext context(Map<String, String> settings) {
        return context(settings, StateCommitmentProfiles.MPF.id());
    }

    private static AppStateMachineContext context(
            Map<String, String> settings, String commitmentProfile) {
        var identity = StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.require(commitmentProfile), new byte[32]);
        AppChainConsensusProfile consensus = AppChainTestProfiles.fromSettings(settings);
        return new AppStateMachineContext() {
            @Override public String chainId() { return "cardano-history-chain"; }
            @Override public Map<String, String> settings() { return Map.copyOf(settings); }
            @Override public Optional<StateCommitmentIdentity> stateCommitmentIdentity() {
                return Optional.of(identity);
            }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                return Optional.of(consensus);
            }
        };
    }
}
