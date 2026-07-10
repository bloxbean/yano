package com.bloxbean.cardano.yano.appchain.anchor.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;

/**
 * The FULL validator conformance suite against the opt-in Aiken artifact
 * (ADR 008.4 §2.4: both implementations must pass the same vectors).
 */
class AikenAnchorValidatorConformanceTest extends AnchorValidatorConformanceTest {

    static Program aikenProgram;

    @Override
    Program program() {
        if (aikenProgram == null) {
            aikenProgram = AikenArtifacts.load("anchor-validator.plutus.json",
                    PlutusData.bytes(THREAD_POLICY));
        }
        return aikenProgram;
    }
}
