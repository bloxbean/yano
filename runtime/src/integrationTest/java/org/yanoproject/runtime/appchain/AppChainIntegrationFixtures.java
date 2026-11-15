package org.yanoproject.runtime.appchain;

import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;

final class AppChainIntegrationFixtures {
    static final StateCommitmentIdentity MPF = StateCommitmentIdentity.explicit(
            StateCommitmentProfiles.MPF, new byte[32]);

    private AppChainIntegrationFixtures() {
    }
}
