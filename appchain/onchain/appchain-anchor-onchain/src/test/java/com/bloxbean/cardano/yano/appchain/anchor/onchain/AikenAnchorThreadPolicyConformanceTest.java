package com.bloxbean.cardano.yano.appchain.anchor.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;

/**
 * The FULL thread-policy conformance suite against the opt-in Aiken artifact
 * (ADR 008.4 §2.4: both implementations must pass the same vectors).
 */
class AikenAnchorThreadPolicyConformanceTest extends AnchorThreadPolicyConformanceTest {

    static Program aikenProgram;

    @Override
    Program program() {
        if (aikenProgram == null) {
            aikenProgram = AikenArtifacts.load("thread-policy.plutus.json",
                    PlutusData.bytes(SEED_TX_ID), PlutusData.integer(SEED_INDEX));
        }
        return aikenProgram;
    }
}
