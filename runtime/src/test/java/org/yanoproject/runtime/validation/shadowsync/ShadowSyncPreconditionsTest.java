package org.yanoproject.runtime.validation.shadowsync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-056 Phase 7a: shadow sync is not started when no pre-block state can be captured. */
class ShadowSyncPreconditionsTest {

    @Test
    void accountStateAndASynchronousUtxoStoreAreRequired() {
        assertThat(ShadowSyncPreconditions.unmetReason(true, true, false)).isEmpty();
        assertThat(ShadowSyncPreconditions.unmetReason(false, true, false)).get().asString()
                .contains("yano.account-state.enabled=false");
        assertThat(ShadowSyncPreconditions.unmetReason(true, false, false)).get().asString()
                .contains("yano.utxo.enabled=false");
        assertThat(ShadowSyncPreconditions.unmetReason(true, true, true)).get().asString()
                .contains("yano.utxo.applyAsync=true");
        assertThat(ShadowSyncPreconditions.unmetReason(false, false, false)).get().asString()
                .contains("account state").contains("UTxO store");
    }
}
