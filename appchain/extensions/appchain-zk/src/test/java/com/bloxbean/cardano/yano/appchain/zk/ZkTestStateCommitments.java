package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;

import java.nio.charset.StandardCharsets;

final class ZkTestStateCommitments {
    private ZkTestStateCommitments() {
    }

    static StateCommitmentIdentity mpf(String chainId) {
        return StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF,
                Blake2bUtil.blake2bHash256(
                        ("appchain-zk-test-genesis-v1\0" + chainId)
                                .getBytes(StandardCharsets.UTF_8)));
    }
}
