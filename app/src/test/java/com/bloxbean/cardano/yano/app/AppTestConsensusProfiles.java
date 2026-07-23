package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcomeCommitment;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;

import java.util.List;

final class AppTestConsensusProfiles {
    private AppTestConsensusProfiles() {
    }

    static AppChainConsensusProfile enabledEffects(int maxPerBlock, int maxPayloadBytes) {
        return enabledEffects(maxPerBlock, maxPayloadBytes, 1);
    }

    static AppChainConsensusProfile enabledEffects(
            int maxPerBlock,
            int maxPayloadBytes,
            int maxBlockMessages
    ) {
        return new AppChainConsensusProfile(
                AppChainConsensusProfile.SCHEMA_VERSION,
                AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES, maxBlockMessages,
                AppChainConfig.DEFAULT_BLOCK_MAX_BYTES, 0, false,
                true, maxPerBlock, maxPayloadBytes, 100_000, 100_000,
                FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                true, List.of());
    }
}
