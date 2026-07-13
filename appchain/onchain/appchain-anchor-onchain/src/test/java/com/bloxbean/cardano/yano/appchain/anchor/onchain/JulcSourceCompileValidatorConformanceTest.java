package com.bloxbean.cardano.yano.appchain.anchor.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Drift check: compiles {@link AnchorValidator} FROM SOURCE with the locally
 * resolved julc toolchain and runs the full conformance vectors. Local-only —
 * on CI the resolved julc release may differ from the toolchain that produced
 * the checked-in artifact (found 2026-07-12: released julc 0.1.0-pre14
 * miscompiles this validator; fixed post-release in julc main). When this
 * fails locally after a julc upgrade, recompile and refresh the checked-in
 * artifacts under src/main/resources/META-INF/plutus/ deliberately.
 */
@EnabledIfSystemProperty(named = "julc.source-check", matches = "true",
        disabledReason = "opt-in: source-compile depends on the resolved julc version (released 0.1.0-pre14 "
                + "miscompiles this validator); the shipped artifact is checked in — run with "
                + "-Djulc.source-check=true when regenerating artifacts")
class JulcSourceCompileValidatorConformanceTest extends AnchorValidatorConformanceTest {

    private static Program sourceProgram;

    @Override
    Program program() {
        if (sourceProgram == null) {
            sourceProgram = compileValidator(AnchorValidator.class)
                    .program()
                    .applyParams(PlutusData.bytes(THREAD_POLICY));
        }
        return sourceProgram;
    }
}
