package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.ComposableAppStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.EpochGovernanceStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.EpochParamsStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.EpochStakeStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.util.List;
import java.util.Objects;

/**
 * ADR-028 qualification assembly. ADR-035 owns the distributable Cardano History product;
 * this provider exists only so the foundation can run its three-component preprod pilot.
 */
public final class CardanoHistoryPilotPreset {
    public static final String ID = "cardano-history-pilot";
    public static final String PROFILE = "adr028-full-pilot-v1";
    public static final String PARAMS = "epoch-params";
    public static final String STAKE = "epoch-stake";
    public static final String GOVERNANCE = "epoch-governance";

    private CardanoHistoryPilotPreset() {
    }

    public static CompositeStateMachine create(AppStateMachineContext context) {
        Objects.requireNonNull(context, "context");
        int stakeChunks = positiveInt(context, "machines.epoch-stake.chunk-entries",
                EpochStakeContract.DEFAULT_CHUNK_ENTRIES);
        int drepChunks = positiveInt(context,
                "machines.epoch-governance.drep-chunk-entries",
                EpochGovernanceContract.DEFAULT_DREP_CHUNK_ENTRIES);

        EpochParamsStateMachine params = new EpochParamsStateMachine(
                EpochParamsContract.DEFAULT_OBSERVER_ID);
        EpochStakeStateMachine stake = new EpochStakeStateMachine(
                EpochStakeContract.DEFAULT_OBSERVER_ID, stakeChunks);
        EpochGovernanceStateMachine governance = new EpochGovernanceStateMachine(
                EpochGovernanceContract.DEFAULT_OBSERVER_ID, true, true, drepChunks);

        return ComposableAppStateMachine.builder(ID, context, PROFILE, "1.0.0")
                .machine(descriptor(PARAMS, "params-v1",
                        "~l1/" + EpochParamsContract.DEFAULT_OBSERVER_ID,
                        List.of(EpochParamsContract.QUERY_PATH,
                                EpochParamsContract.LATEST_QUERY_PATH)), params)
                .machine(descriptor(STAKE, "stake-v1/chunk-" + stakeChunks,
                        "~l1/" + EpochStakeContract.DEFAULT_OBSERVER_ID,
                        List.of(EpochStakeContract.QUERY_PATH,
                                EpochStakeContract.META_QUERY_PATH)), stake)
                .machine(descriptor(GOVERNANCE,
                        "governance-full-v1/chunk-" + drepChunks,
                        "~l1/" + EpochGovernanceContract.DEFAULT_OBSERVER_ID,
                        List.of(EpochGovernanceContract.PROPOSAL_QUERY_PATH,
                                EpochGovernanceContract.DREP_QUERY_PATH,
                                EpochGovernanceContract.PROPOSAL_META_QUERY_PATH,
                                EpochGovernanceContract.DREP_META_QUERY_PATH)), governance)
                .build();
    }

    private static ComponentDescriptor descriptor(String id, String configuration,
                                                   String topic, List<String> queries) {
        return new ComponentDescriptor(id, "1.0.0", configuration,
                id + "-state-v1", 1, 0, List.of(topic), queries, 0);
    }

    private static int positiveInt(AppStateMachineContext context, String key, int fallback) {
        String configured = context.settings().get(key);
        try {
            int value = configured == null ? fallback : Integer.parseInt(configured);
            if (value <= 0 || value > 25_000) {
                throw new IllegalArgumentException(key + " must be between 1 and 25000");
            }
            return value;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(key + " must be an integer", malformed);
        }
    }
}
