package com.bloxbean.cardano.yano.api.appchain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppStateProofSnapshotTest {

    @Test
    void legacyGatewayDefaultFailsUnavailableWithoutComposingMovingReads() {
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, arguments);
                    }
                    if (method.getName().equals("stateRoot")
                            || method.getName().equals("stateValue")
                            || method.getName().equals("stateProof")) {
                        throw new AssertionError("default composed non-atomic legacy reads");
                    }
                    return null;
                });

        assertThatThrownBy(() -> gateway.stateProofSnapshot(new byte[]{1}))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void inclusionSnapshotCopiesEveryArrayOnConstructionAndAccess() {
        byte[] key = new byte[]{1};
        byte[] value = new byte[]{2};
        byte[] wire = new byte[]{3};
        byte[] root = new byte[32];
        AppStateProofSnapshot snapshot = new AppStateProofSnapshot(
                key, value, wire, root, 7);

        key[0] = 9;
        value[0] = 9;
        wire[0] = 9;
        root[0] = 9;
        assertThat(snapshot.key()).containsExactly(1);
        assertThat(snapshot.value()).containsExactly(2);
        assertThat(snapshot.proofWire()).containsExactly(3);
        assertThat(snapshot.stateRoot()).containsOnly((byte) 0);

        snapshot.key()[0] = 8;
        snapshot.value()[0] = 8;
        snapshot.proofWire()[0] = 8;
        snapshot.stateRoot()[0] = 8;
        assertThat(snapshot.key()).containsExactly(1);
        assertThat(snapshot.value()).containsExactly(2);
        assertThat(snapshot.proofWire()).containsExactly(3);
        assertThat(snapshot.stateRoot()).containsOnly((byte) 0);
        assertThat(snapshot.committedHeight()).isEqualTo(7);
    }

    @Test
    void exclusionSnapshotPreservesNullValueAndRejectsInvalidIdentity() {
        AppStateProofSnapshot exclusion = new AppStateProofSnapshot(
                new byte[]{1}, null, new byte[]{2}, new byte[32], 1);
        assertThat(exclusion.value()).isNull();

        assertThatThrownBy(() -> new AppStateProofSnapshot(
                null, null, new byte[0], new byte[32], 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key");
        assertThatThrownBy(() -> new AppStateProofSnapshot(
                new byte[0], null, null, new byte[32], 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("proofWire");
        assertThatThrownBy(() -> new AppStateProofSnapshot(
                new byte[0], null, new byte[]{1}, new byte[31], 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new AppStateProofSnapshot(
                new byte[0], null, new byte[]{1}, new byte[32], -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new AppStateProofSnapshot(
                new byte[0], null, new byte[0], new byte[32], 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proofWire");
    }
}
