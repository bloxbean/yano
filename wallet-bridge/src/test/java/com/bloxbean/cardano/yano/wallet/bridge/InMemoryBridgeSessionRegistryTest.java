package com.bloxbean.cardano.yano.wallet.bridge;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryBridgeSessionRegistryTest {
    @Test
    void createsSessionWithOpaqueTokenAndPermissions() {
        InMemoryBridgeSessionRegistry registry = new InMemoryBridgeSessionRegistry(
                new java.security.SecureRandom(),
                Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC));

        BridgeSession session = registry.createSession(
                "http://127.0.0.1:3000",
                Set.of(BridgePermission.READ_WALLET, BridgePermission.SIGN_TX));

        assertThat(session.origin()).isEqualTo("http://127.0.0.1:3000");
        assertThat(session.token()).hasSizeGreaterThan(32);
        assertThat(session.allows(BridgePermission.READ_WALLET)).isTrue();
        assertThat(session.allows(BridgePermission.SIGN_TX)).isTrue();
        assertThat(session.allows(BridgePermission.SUBMIT_TX)).isFalse();
        assertThat(session.createdAt()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(registry.findByToken(session.token())).contains(session);
    }

    @Test
    void revokesSessionByToken() {
        InMemoryBridgeSessionRegistry registry = new InMemoryBridgeSessionRegistry();
        BridgeSession session = registry.createSession("http://localhost:3000", Set.of(BridgePermission.READ_WALLET));

        assertThat(registry.revoke(session.token())).isTrue();

        assertThat(registry.findByToken(session.token())).isEmpty();
        assertThat(registry.revoke(session.token())).isFalse();
    }

    @Test
    void rejectsBlankOrigin() {
        InMemoryBridgeSessionRegistry registry = new InMemoryBridgeSessionRegistry();

        assertThatThrownBy(() -> registry.createSession(" ", Set.of(BridgePermission.READ_WALLET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("origin is required");
    }

    @Test
    void bridgeMethodNamesMatchCip30Surface() {
        assertThat(BridgeMethod.GET_BALANCE.cip30Name()).isEqualTo("getBalance");
        assertThat(BridgeMethod.GET_UTXOS.cip30Name()).isEqualTo("getUtxos");
        assertThat(BridgeMethod.SIGN_TX.cip30Name()).isEqualTo("signTx");
        assertThat(BridgeMethod.SUBMIT_TX.cip30Name()).isEqualTo("submitTx");
    }
}
