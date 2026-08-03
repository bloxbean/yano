package com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain;

import com.bloxbean.cardano.julc.testkit.ContractTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoValidityRootValidatorCompileTest extends ContractTest {

    @Test
    void releasePinnedJulcCompilesContextBoundValidator() {
        assertThat(compileValidator(
                EutxoValidityRootValidator.class).program()).isNotNull();
    }
}
