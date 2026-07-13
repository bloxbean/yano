package com.bloxbean.cardano.yano.appchain.anchor.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Drift check for the thread policy — see
 * {@link JulcSourceCompileValidatorConformanceTest} for the rationale.
 */
@EnabledIfSystemProperty(named = "julc.source-check", matches = "true",
        disabledReason = "opt-in: source-compile depends on the resolved julc version (released 0.1.0-pre14 "
                + "miscompiles this validator); the shipped artifact is checked in — run with "
                + "-Djulc.source-check=true when regenerating artifacts")
class JulcSourceCompileThreadPolicyConformanceTest extends AnchorThreadPolicyConformanceTest {

    private static Program sourceProgram;

    @Override
    Program program() {
        if (sourceProgram == null) {
            sourceProgram = compileValidator(AnchorThreadPolicy.class)
                    .program()
                    .applyParams(PlutusData.bytes(SEED_TX_ID), PlutusData.integer(SEED_INDEX));
        }
        return sourceProgram;
    }
}
