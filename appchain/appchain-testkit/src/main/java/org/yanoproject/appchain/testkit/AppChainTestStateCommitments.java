package org.yanoproject.appchain.testkit;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;

import java.nio.charset.StandardCharsets;

/** Deterministic authenticated-state identities for isolated testkit chains. */
public final class AppChainTestStateCommitments {
    private AppChainTestStateCommitments() {
    }

    public static StateCommitmentIdentity mpf(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId is required");
        }
        return StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF,
                Blake2bUtil.blake2bHash256(
                        ("yano-appchain-testkit-genesis-v1\0" + chainId)
                                .getBytes(StandardCharsets.UTF_8)));
    }
}
