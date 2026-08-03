package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfileCommitment;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfile;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.appchain.config.AppChainEffectsConfig;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Side-effect-free construction of canonical authenticated-map genesis configuration. */
public final class AuthenticatedMapGenesisFactory {
    private AuthenticatedMapGenesisFactory() {
    }

    /** Construct the Phase-1 MPF genesis identity from normalized framework configuration. */
    public static AuthenticatedMapContract.Genesis mpf(
            AppChainConfig config,
            byte[] anchorPolicyCommitment,
            int maxBatchItems,
            int maxBatchBytes,
            List<AuthenticatedMapContract.CollectionDescriptor> collections,
            List<AuthenticatedMapContract.GenesisEntry> initialEntries
    ) {
        return create(config, StateCommitmentProfiles.MPF, anchorPolicyCommitment,
                maxBatchItems, maxBatchBytes, collections, List.of(), initialEntries);
    }

    /** Construct MPF genesis with optional genesis-bound value validators. */
    public static AuthenticatedMapContract.Genesis mpf(
            AppChainConfig config,
            byte[] anchorPolicyCommitment,
            int maxBatchItems,
            int maxBatchBytes,
            List<AuthenticatedMapContract.CollectionDescriptor> collections,
            List<AuthenticatedMapContract.ValidatorDescriptor> validators,
            List<AuthenticatedMapContract.GenesisEntry> initialEntries
    ) {
        return create(config, StateCommitmentProfiles.MPF, anchorPolicyCommitment,
                maxBatchItems, maxBatchBytes, collections, validators, initialEntries);
    }

    /** Construct a Phase-3 classic Blake2b-256 JMT genesis identity. */
    public static AuthenticatedMapContract.Genesis classicJmt(
            AppChainConfig config,
            byte[] anchorPolicyCommitment,
            int maxBatchItems,
            int maxBatchBytes,
            List<AuthenticatedMapContract.CollectionDescriptor> collections,
            List<AuthenticatedMapContract.GenesisEntry> initialEntries
    ) {
        return create(config, StateCommitmentProfiles.CLASSIC_JMT, anchorPolicyCommitment,
                maxBatchItems, maxBatchBytes, collections, List.of(), initialEntries);
    }

    /** Construct classic-JMT genesis with optional genesis-bound validators. */
    public static AuthenticatedMapContract.Genesis classicJmt(
            AppChainConfig config,
            byte[] anchorPolicyCommitment,
            int maxBatchItems,
            int maxBatchBytes,
            List<AuthenticatedMapContract.CollectionDescriptor> collections,
            List<AuthenticatedMapContract.ValidatorDescriptor> validators,
            List<AuthenticatedMapContract.GenesisEntry> initialEntries
    ) {
        return create(config, StateCommitmentProfiles.CLASSIC_JMT, anchorPolicyCommitment,
                maxBatchItems, maxBatchBytes, collections, validators, initialEntries);
    }

    /** Encode one genesis as the exact plugin setting consumed by the provider. */
    public static Map<String, String> settings(AuthenticatedMapContract.Genesis genesis) {
        String encoded = HexFormat.of().formatHex(
                AuthenticatedMapContract.encodeGenesis(genesis));
        StateCommitmentProfile profile = StateCommitmentProfiles.require(
                genesis.commitmentProfileId());
        StateCommitmentIdentity identity = StateCommitmentIdentity.explicit(
                profile, AuthenticatedMapContract.genesisId(genesis));
        Map<String, String> settings = new LinkedHashMap<>(identity.settings());
        settings.put(StdlibStateMachineProviders.AUTHENTICATED_MAP_GENESIS_SETTING, encoded);
        return Map.copyOf(settings);
    }

    private static AuthenticatedMapContract.Genesis create(
            AppChainConfig config,
            StateCommitmentProfile stateProfile,
            byte[] anchorPolicyCommitment,
            int maxBatchItems,
            int maxBatchBytes,
            List<AuthenticatedMapContract.CollectionDescriptor> collections,
            List<AuthenticatedMapContract.ValidatorDescriptor> validators,
            List<AuthenticatedMapContract.GenesisEntry> initialEntries
    ) {
        Objects.requireNonNull(config, "config");
        if (!AuthenticatedMapStateMachine.ID.equals(config.stateMachineId())) {
            throw new IllegalArgumentException(
                    "AppChainConfig stateMachineId must be authenticated-map");
        }
        AppChainConsensusProfile consensus = AppChainEffectsConfig.from(config)
                .consensusProfile(config);
        if (maxBatchBytes > consensus.maxMessageBytes()) {
            throw new IllegalArgumentException(
                    "authenticated-map maxBatchBytes exceeds framework maxMessageBytes");
        }
        AppChainMembershipEpoch membership = new AppChainMembershipEpoch(
                0, config.memberKeysHex().stream().toList(), config.threshold());
        return new AuthenticatedMapContract.Genesis(
                config.chainId(),
                stateProfile.id(),
                stateProfile.formatFingerprint(),
                AppChainConsensusProfileCommitment.digest(consensus),
                membership.digest(),
                anchorPolicyCommitment,
                maxBatchItems,
                maxBatchBytes,
                collections,
                validators,
                initialEntries);
    }
}
