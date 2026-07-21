package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppStateProofSnapshot;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AppChainProofResourceTest {

    @Test
    void inclusionUsesOnlyAtomicSnapshotAndKeepsLegacyResponseFields() {
        byte[] root = filled(0xab, 32);
        AppStateProofSnapshot snapshot = new AppStateProofSnapshot(
                new byte[]{0x0a}, new byte[]{0x0b}, new byte[]{0x0c}, root, 7);
        AppChainGateway gateway = gateway(ignored -> Optional.of(snapshot), Optional.of(3L));
        AppChainResource.ChainScopedResource resource =
                new AppChainResource.ChainScopedResource(gateway);

        Response response = resource.proof("0a");

        assertEquals(200, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("0a", body.get("key"));
        assertEquals("chain-a", body.get("chainId"));
        assertEquals(7L, body.get("committedHeight"));
        assertEquals("ab".repeat(32), body.get("stateRoot"));
        assertEquals("0c", body.get("proofWireHex"));
        assertEquals("0b", body.get("valueHex"));
        assertEquals(3L, body.get("finalizedAtHeight"));
    }

    @Test
    void exclusionReturnsAtomicProofWithoutValueAndUnavailableProofIsNotFound() {
        AppStateProofSnapshot exclusion = new AppStateProofSnapshot(
                new byte[]{0x0a}, null, new byte[]{0x0c}, new byte[32], 9);
        AppChainResource.ChainScopedResource resource =
                new AppChainResource.ChainScopedResource(
                        gateway(ignored -> Optional.of(exclusion), Optional.empty()));

        Response response = resource.proof("0a");

        assertEquals(200, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(9L, body.get("committedHeight"));
        assertFalse(body.containsKey("valueHex"));
        assertFalse(body.containsKey("finalizedAtHeight"));

        AppChainResource.ChainScopedResource unavailable =
                new AppChainResource.ChainScopedResource(
                        gateway(ignored -> Optional.empty(), Optional.empty()));
        assertEquals(404, unavailable.proof("0a").getStatus());
    }

    @Test
    void gatewayWithoutAtomicProofCapabilityIsServiceUnavailable() {
        AppChainResource.ChainScopedResource resource =
                new AppChainResource.ChainScopedResource(gateway(ignored -> {
                    throw new UnsupportedOperationException("legacy gateway");
                }, Optional.empty()));

        Response response = resource.proof("0a");

        assertEquals(503, response.getStatus());
        assertEquals("STATE_PROOF_UNAVAILABLE",
                ((Map<?, ?>) response.getEntity()).get("code"));
    }

    @Test
    void proofKeyIsBoundedCanonicalAndSnapshotIdentityMustMatch() {
        AtomicInteger calls = new AtomicInteger();
        AppChainResource.ChainScopedResource resource =
                new AppChainResource.ChainScopedResource(gateway(key -> {
                    calls.incrementAndGet();
                    return Optional.empty();
                }, Optional.empty()));

        for (String invalid : new String[]{null, "", "0", "AA", "gg", "0x00", "%30%30"}) {
            assertEquals(400, resource.proof(invalid).getStatus(), String.valueOf(invalid));
        }
        assertEquals(413, resource.proof("00".repeat(257)).getStatus());
        assertEquals(0, calls.get());

        assertEquals(404, resource.proof("00".repeat(256)).getStatus());
        assertEquals(1, calls.get());

        AppStateProofSnapshot wrongKey = new AppStateProofSnapshot(
                new byte[]{0x0b}, null, new byte[]{1}, new byte[32], 1);
        AppChainResource.ChainScopedResource mismatched =
                new AppChainResource.ChainScopedResource(
                        gateway(ignored -> Optional.of(wrongKey), Optional.empty()));
        Response mismatch = mismatched.proof("0a");
        assertEquals(500, mismatch.getStatus());
        assertEquals("State proof snapshot identity mismatch",
                ((Map<?, ?>) mismatch.getEntity()).get("error"));
    }

    @Test
    void proofResponseRejectsOversizedValueOrProofBeforeHexExpansion() {
        byte[] root = new byte[32];
        AppStateProofSnapshot oversizedValue = new AppStateProofSnapshot(
                new byte[]{0x0a}, new byte[1024 * 1024 + 1], new byte[]{1}, root, 1);
        AppChainResource.ChainScopedResource valueResource =
                new AppChainResource.ChainScopedResource(
                        gateway(ignored -> Optional.of(oversizedValue), Optional.empty()));
        assertEquals(413, valueResource.proof("0a").getStatus());

        AppStateProofSnapshot oversizedWire = new AppStateProofSnapshot(
                new byte[]{0x0a}, null, new byte[1024 * 1024 + 1], root, 1);
        AppChainResource.ChainScopedResource wireResource =
                new AppChainResource.ChainScopedResource(
                        gateway(ignored -> Optional.of(oversizedWire), Optional.empty()));
        Response response = wireResource.proof("0a");
        assertEquals(413, response.getStatus());
        assertEquals("State proof response exceeds the size limit",
                ((Map<?, ?>) response.getEntity()).get("error"));
    }

    @Test
    void legacyMessageHeightCannotAdvanceBeyondAtomicProofSnapshot() {
        AppStateProofSnapshot snapshot = new AppStateProofSnapshot(
                new byte[]{0x0a}, null, new byte[]{1}, new byte[32], 7);
        AppChainResource.ChainScopedResource resource =
                new AppChainResource.ChainScopedResource(
                        gateway(ignored -> Optional.of(snapshot), Optional.of(8L)));

        Response response = resource.proof("0a");

        assertEquals(200, response.getStatus());
        assertFalse(((Map<?, ?>) response.getEntity()).containsKey("finalizedAtHeight"));
    }

    private static AppChainGateway gateway(
            Function<byte[], Optional<AppStateProofSnapshot>> snapshots,
            Optional<Long> finalizedHeight
    ) {
        return (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "chainId" -> "chain-a";
                    case "stateProofSnapshot" -> snapshots.apply((byte[]) arguments[0]);
                    case "messageHeight" -> finalizedHeight;
                    case "stateRoot", "stateValue", "stateProof" ->
                            throw new AssertionError("legacy non-atomic proof read was invoked");
                    case "toString" -> "proof-resource-test-gateway";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static byte[] filled(int value, int size) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
