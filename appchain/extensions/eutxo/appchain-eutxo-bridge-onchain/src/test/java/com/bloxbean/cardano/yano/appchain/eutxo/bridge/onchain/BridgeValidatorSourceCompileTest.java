package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.testkit.ContractTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the alpha Julc sources compilable with the release-pinned authoring
 * toolchain. Checked production artifacts and their hashes remain a separate
 * release decision.
 */
class BridgeValidatorSourceCompileTest extends ContractTest {
    @Test
    void releasePinnedJulcCompilesAllBridgeValidators() {
        assertThat(compileValidator(DepositStagingValidator.class).program()).isNotNull();
        assertThat(compileValidator(VaultValidator.class).program()).isNotNull();
        assertThat(compileValidator(FederatedRootValidator.class).program()).isNotNull();
        assertThat(compileValidator(NullifierStateValidator.class).program()).isNotNull();
        assertThat(compileValidator(ProofVaultValidator.class).program()).isNotNull();
    }
}
