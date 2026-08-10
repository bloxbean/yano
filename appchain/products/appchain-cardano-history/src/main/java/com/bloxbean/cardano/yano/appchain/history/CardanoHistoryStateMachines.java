package com.bloxbean.cardano.yano.appchain.history;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfile;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.ComposableAppStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.EpochGovernanceStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.EpochParamsStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.EpochStakeStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves and validates one Cardano History preset without duplicating epoch logic. */
public final class CardanoHistoryStateMachines {
    private CardanoHistoryStateMachines() { }

    public static CompositeStateMachine create(AppStateMachineContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> settings = context.settings();
        CardanoHistoryPreset preset = CardanoHistoryPreset.require(settings.getOrDefault(
                "machines.cardano-history.preset", CardanoHistoryPreset.PARAMS_ONLY.id()));
        requireObserver(settings, "epoch-params", EpochParamsContract.OBSERVER_TYPE, true);
        requireObserver(settings, "epoch-stake", EpochStakeContract.OBSERVER_TYPE, preset.stake());
        requireObserver(settings, "epoch-governance", EpochGovernanceContract.OBSERVER_TYPE,
                preset.governance());

        var identity = context.stateCommitmentIdentity().orElseThrow(() ->
                new IllegalArgumentException("cardano-history requires a state commitment identity"));
        boolean l1Required = strictBoolean(settings, "state.l1-proof-consumption-required", false);
        if (l1Required && identity.profile().backendFamily()
                != StateCommitmentProfile.BackendFamily.MPF) {
            throw new IllegalArgumentException("on-chain Cardano History requires an MPF profile");
        }

        int stakeChunks = positive(settings, "machines.epoch-stake.chunk-entries",
                EpochStakeContract.DEFAULT_CHUNK_ENTRIES);
        int drepChunks = positive(settings, "machines.epoch-governance.drep-chunk-entries",
                EpochGovernanceContract.DEFAULT_DREP_CHUNK_ENTRIES);
        String stakeProfile = settings.getOrDefault(
                "machines.epoch-stake.snapshot-profile", identity.profile().id());
        String drepProfile = settings.getOrDefault(
                "machines.epoch-governance.drep-snapshot-profile", stakeProfile);
        requireAuthenticatedSnapshots(settings, preset);

        var builder = ComposableAppStateMachine.builder(
                CardanoHistoryProduct.STATE_MACHINE_ID, context, preset.id(),
                CardanoHistoryProduct.APPLICATION_VERSION);
        builder.machine(descriptor(CardanoHistoryProduct.PARAMS_COMPONENT, preset.id(),
                        "~l1/" + EpochParamsContract.DEFAULT_OBSERVER_ID,
                        List.of(EpochParamsContract.QUERY_PATH,
                                EpochParamsContract.FIELD_QUERY_PATH,
                                EpochParamsContract.META_QUERY_PATH,
                                EpochParamsContract.LATEST_QUERY_PATH)),
                new EpochParamsStateMachine(EpochParamsContract.DEFAULT_OBSERVER_ID));
        if (preset.stake()) {
            builder.machine(descriptor(CardanoHistoryProduct.STAKE_COMPONENT,
                            preset.id() + "/chunk-" + stakeChunks + "/" + stakeProfile,
                            "~l1/" + EpochStakeContract.DEFAULT_OBSERVER_ID,
                            List.of(EpochStakeContract.QUERY_PATH, EpochStakeContract.META_QUERY_PATH)),
                    new EpochStakeStateMachine(
                            EpochStakeContract.DEFAULT_OBSERVER_ID, stakeChunks, stakeProfile));
        }
        if (preset.governance()) {
            builder.machine(descriptor(CardanoHistoryProduct.GOVERNANCE_COMPONENT,
                            preset.id() + "/chunk-" + drepChunks + "/" + drepProfile,
                            "~l1/" + EpochGovernanceContract.DEFAULT_OBSERVER_ID,
                            List.of(EpochGovernanceContract.PROPOSAL_QUERY_PATH,
                                    EpochGovernanceContract.DREP_QUERY_PATH,
                                    EpochGovernanceContract.PROPOSAL_META_QUERY_PATH,
                                    EpochGovernanceContract.DREP_META_QUERY_PATH)),
                    new EpochGovernanceStateMachine(EpochGovernanceContract.DEFAULT_OBSERVER_ID,
                            true, true, drepChunks, drepProfile));
        }
        return builder.build();
    }

    private static ComponentDescriptor descriptor(
            String id, String configuration, String topic, List<String> queries) {
        return new ComponentDescriptor(id, "1.0.0", configuration,
                id + "-state-v1", 1, 0, List.of(topic), queries, 0);
    }

    private static void requireObserver(
            Map<String, String> settings, String id, String expectedType, boolean enabled) {
        String key = "observers." + id + ".type";
        String configured = settings.get(key);
        if (enabled && !expectedType.equals(configured)) {
            throw new IllegalArgumentException(key + " must be " + expectedType);
        }
        if (!enabled && configured != null) {
            throw new IllegalArgumentException(key + " is not enabled by the selected preset");
        }
    }

    private static int positive(Map<String, String> settings, String key, int fallback) {
        String configured = settings.get(key);
        try {
            int value = configured == null ? fallback : Integer.parseInt(configured);
            if (value <= 0 || value > 25_000) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(key + " must be between 1 and 25000", malformed);
        }
    }

    private static void requireAuthenticatedSnapshots(
            Map<String, String> settings, CardanoHistoryPreset preset) {
        if (!preset.stake() && !preset.governance()) {
            return;
        }
        String enabled = settings.getOrDefault(
                "capabilities.authenticated-snapshots.enabled", "false");
        if (!"true".equals(enabled)) {
            throw new IllegalArgumentException("Cardano History stake/governance presets require "
                    + "capabilities.authenticated-snapshots.enabled=true");
        }
        String selected = settings.getOrDefault(
                "capabilities.authenticated-snapshots.series", "all");
        List<String> series = "all".equals(selected)
                ? List.of("*") : List.of(selected.split(",", -1));
        if (series.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(
                    "Cardano History authenticated snapshot series must not be empty");
        }
        if (!series.contains("*")) {
            if (preset.stake() && !series.contains(CardanoHistoryProduct.STAKE_COMPONENT
                    + "." + EpochStakeStateMachine.SNAPSHOT_SERIES_ID)) {
                throw new IllegalArgumentException(
                        "Cardano History stake preset requires its per-epoch snapshot series");
            }
            if (preset.governance() && !series.contains(CardanoHistoryProduct.GOVERNANCE_COMPONENT
                    + "." + EpochGovernanceStateMachine.DREP_SNAPSHOT_SERIES_ID)) {
                throw new IllegalArgumentException(
                        "Cardano History governance preset requires its per-epoch DRep snapshot series");
            }
        }
    }

    private static boolean strictBoolean(
            Map<String, String> settings, String key, boolean fallback) {
        String value = settings.get(key);
        if (value == null) return fallback;
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
