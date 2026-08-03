package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppAnchorCommitment;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppStateProofSnapshot;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateIntegrityReport;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;
import com.bloxbean.cardano.yano.api.appchain.state.StateSnapshot;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AppChainProofResourceTest {

    @Test
    void releaseMatchedClientCatalogEqualsRuntimeCommitmentCatalog() {
        for (var runtimeProfile : StateCommitmentProfiles.all()) {
            ProofVerifier.ProfileMetadata clientProfile = ProofVerifier.profileMetadata(
                    runtimeProfile.id()).orElseThrow();
            assertEquals(runtimeProfile.backendFamily().name()
                            .toLowerCase(java.util.Locale.ROOT),
                    clientProfile.backend());
            assertEquals(runtimeProfile.dependencyDescriptor(),
                    clientProfile.dependencyDescriptor());
            assertEquals(runtimeProfile.nativeProofEncoding(),
                    clientProfile.nativeProofEncoding());
            assertEquals(runtimeProfile.nativeVersioning(),
                    clientProfile.nativeVersioning());
            assertEquals(runtimeProfile.physicalDelete(),
                    clientProfile.physicalDelete());
            assertEquals(HexUtil.encodeHexString(runtimeProfile.formatFingerprint()),
                    clientProfile.formatFingerprintHex());
        }
    }

    @Test
    void profileTaggedProofBindsCommitmentBlockCertificateAndOperationsViews() {
        byte[] root = filled(0x41, 32);
        byte[] key = new byte[]{0x0a};
        byte[] value = new byte[]{0x0b};
        StateCommitmentIdentity identity = StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.MPF, filled(0x22, 32));
        StateSnapshot snapshot = new StateSnapshot(identity, 4, root);
        StateProof stateProof = new StateProof(snapshot, key, value,
                StateProof.Presence.PRESENT,
                identity.profile().nativeProofEncoding(), new byte[]{(byte) 0x80});
        FinalityCert certificate = new FinalityCert(FinalityCert.SCHEME_ED25519,
                List.of(new FinalityCert.Signature(new byte[32], new byte[64])));
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "chain-a", 4,
                filled(0x10, 32), 0, new byte[0], 1234,
                new byte[32], root, List.of(), new byte[32], certificate);
        StateProofEnvelope envelope = new StateProofEnvelope(
                StateProofEnvelope.PROOF_SCHEMA_VERSION, "chain-a",
                AppBlockCodec.blockHash(block), stateProof, certificate);
        StateIntegrityReport integrity = new StateIntegrityReport(
                identity, 4, root, true, "head agrees");
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "chainId" -> "chain-a";
                    case "tipHeight" -> 4L;
                    case "stateRoot" -> root;
                    case "stateCommitmentIdentity" -> Optional.of(identity);
                    case "stateProofEnvelope", "stateProofEnvelopeAtHeight" -> Optional.of(envelope);
                    case "block" -> Optional.of(block);
                    case "messageHeight" -> Optional.empty();
                    case "oldestProvableHeight" -> 2L;
                    case "stateIntegrity" -> Optional.of(integrity);
                    case "toString" -> "profile-proof-gateway";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                });
        AppChainResource.ChainScopedResource resource =
                new AppChainResource.ChainScopedResource(gateway);

        Response response = resource.proof("0a", 4L);
        assertEquals(200, response.getStatus());
        Map<?, ?> proof = (Map<?, ?>) response.getEntity();
        assertEquals("mpf-blake2b256-v1", proof.get("profile"));
        assertEquals("mpf", proof.get("backend"));
        assertEquals(4L, proof.get("version"));
        assertEquals("PRESENT", proof.get("presence"));
        assertEquals("22".repeat(32), proof.get("genesisId"));
        assertEquals(2L, proof.get("oldestProvableHeight"));
        assertEquals(4L, ((Map<?, ?>) proof.get("block")).get("height"));
        assertEquals(1, ((List<?>) ((Map<?, ?>) proof.get("finalityCertificate"))
                .get("signatures")).size());

        Map<?, ?> entry = (Map<?, ?>) resource.stateEntry("0a", 4L).getEntity();
        assertFalse(entry.containsKey("proofWireHex"));
        assertEquals("PRESENT", entry.get("presence"));
        Map<?, ?> identityView = (Map<?, ?>) resource.stateIdentity().getEntity();
        assertEquals("mpf-blake2b256-v1", identityView.get("profile"));
        assertEquals(4L, identityView.get("version"));
        assertEquals(2L, ((Map<?, ?>) resource.oldestProvableHeight().getEntity())
                .get("oldestProvableHeight"));
        assertEquals(true, ((Map<?, ?>) resource.stateIntegrity().getEntity()).get("valid"));
    }

    @Test
    void snapshotResponseUsesCapturedBlockRootEvenWhenLiveTipHasAdvanced() {
        byte[] capturedRoot = filled(0x61, 32);
        byte[] newerRoot = filled(0x62, 32);
        StateCommitmentIdentity identity = StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.CLASSIC_JMT, filled(0x20, 32));
        AppBlock captured = new AppBlock(AppBlock.BLOCK_VERSION, "chain-a", 4,
                new byte[32], 0, new byte[0], 1234, new byte[32], capturedRoot,
                List.of(), new byte[32], FinalityCert.empty());
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "chainId" -> "chain-a";
                    case "snapshot" -> 4L;
                    case "block" -> Optional.of(captured);
                    case "stateRoot" -> newerRoot;
                    case "stateCommitmentIdentity" -> Optional.of(identity);
                    case "oldestProvableHeight" -> 2L;
                    case "toString" -> "snapshot-proof-gateway";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                });
        AppChainResource.ChainScopedResource resource =
                new AppChainResource.ChainScopedResource(gateway);

        Response response = resource.snapshot(
                new AppChainResource.ChainScopedResource.SnapshotRequest("/snapshot-4"));

        assertEquals(200, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("61".repeat(32), body.get("stateRoot"));
        assertEquals("61".repeat(32),
                ((Map<?, ?>) body.get("stateCommitment")).get("stateRoot"));
    }

    @Test
    void proofVerificationPostIsAuthorizedAsReadOnly() throws NoSuchMethodException {
        AppChainAccess legacyAccess = AppChainResource.class
                .getMethod("verifyProof",
                        AppChainResource.ChainScopedResource.ProofVerificationRequest.class)
                .getAnnotation(AppChainAccess.class);
        AppChainAccess scopedAccess = AppChainResource.ChainScopedResource.class
                .getMethod("verifyProof",
                        AppChainResource.ChainScopedResource.ProofVerificationRequest.class)
                .getAnnotation(AppChainAccess.class);

        assertEquals(AppChainAccess.Level.READ, legacyAccess.value());
        assertEquals(AppChainAccess.Level.READ, scopedAccess.value());
        assertEquals(AppChainAccess.Level.PRIVILEGED, AppChainResource.class
                .getMethod("stateIntegrity")
                .getAnnotation(AppChainAccess.class).value());
        assertEquals(AppChainAccess.Level.PRIVILEGED,
                AppChainResource.ChainScopedResource.class
                        .getMethod("stateIntegrity")
                        .getAnnotation(AppChainAccess.class).value());
    }

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

    @Test
    void historicalProofUsesExactRequestedHeight() {
        byte[] root = filled(0xcd, 32);
        AppStateProofSnapshot historical = new AppStateProofSnapshot(
                new byte[]{0x0a}, new byte[]{0x0b}, new byte[]{0x0c}, root, 4);
        AtomicInteger requestedHeight = new AtomicInteger();
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "chainId" -> "chain-a";
                    case "stateProofSnapshotAtHeight" -> {
                        requestedHeight.set(Math.toIntExact((long) arguments[0]));
                        yield Optional.of(historical);
                    }
                    case "messageHeight" -> Optional.of(2L);
                    case "toString" -> "historical-proof-gateway";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                });
        AppChainResource.ChainScopedResource resource =
                new AppChainResource.ChainScopedResource(gateway);

        Response response = resource.proof("0a", 4L);

        assertEquals(200, response.getStatus());
        assertEquals(4, requestedHeight.get());
        assertEquals(4L, ((Map<?, ?>) response.getEntity()).get("committedHeight"));
        assertEquals(400, resource.proof("0a", 0L).getStatus());
    }

    @Test
    void verifiesBoundedInclusionAndRejectsMalformedRequests() {
        MapNodeStore store = new MapNodeStore();
        MpfTrie trie = new MpfTrie(store);
        byte[] key = "order-1".getBytes(StandardCharsets.UTF_8);
        byte[] value = "approved".getBytes(StandardCharsets.UTF_8);
        trie.put(key, value);
        byte[] root = trie.getRootHash();
        byte[] wire = trie.getProofWire(key).orElseThrow();
        AppChainResource.ChainScopedResource resource =
                new AppChainResource.ChainScopedResource(
                        gateway(ignored -> Optional.empty(), Optional.empty()));

        Response valid = resource.verifyProof(
                new AppChainResource.ChainScopedResource.ProofVerificationRequest(
                        "inclusion", HexUtil.encodeHexString(root),
                        HexUtil.encodeHexString(key), HexUtil.encodeHexString(value),
                        HexUtil.encodeHexString(wire)));
        assertEquals(200, valid.getStatus());
        assertEquals(true, ((Map<?, ?>) valid.getEntity()).get("valid"));

        Response wrongRoot = resource.verifyProof(
                new AppChainResource.ChainScopedResource.ProofVerificationRequest(
                        "inclusion", "00".repeat(32), HexUtil.encodeHexString(key),
                        HexUtil.encodeHexString(value), HexUtil.encodeHexString(wire)));
        assertEquals(200, wrongRoot.getStatus());
        assertEquals(false, ((Map<?, ?>) wrongRoot.getEntity()).get("valid"));

        assertEquals(400, resource.verifyProof(
                new AppChainResource.ChainScopedResource.ProofVerificationRequest(
                        "unknown", "00".repeat(32), "00", null, "00")).getStatus());
        assertEquals(413, resource.verifyProof(
                new AppChainResource.ChainScopedResource.ProofVerificationRequest(
                        "exclusion", "00".repeat(32), "00", null,
                        "00".repeat(1024 * 1024 + 1))).getStatus());
    }

    @Test
    void latestAnchorCommitmentPreservesRootProvenance() {
        AppAnchorCommitment commitment = new AppAnchorCommitment(
                "chain-a", "metadata", 12, filled(0xaa, 32), filled(0xbb, 32),
                "cc".repeat(32), 1234);
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "latestAnchorCommitment" -> Optional.of(commitment);
                    case "toString" -> "anchor-proof-gateway";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                });
        AppChainResource.ChainScopedResource resource =
                new AppChainResource.ChainScopedResource(gateway);

        Response response = resource.latestAnchorCommitment();

        assertEquals(200, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(12L, body.get("anchoredHeight"));
        assertEquals("aa".repeat(32), body.get("stateRoot"));
        assertEquals("L1-confirmed by this node", body.get("provenance"));
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
                    case "stateProofSnapshotAtHeight" ->
                            snapshots.apply((byte[]) arguments[1]);
                    case "latestAnchorCommitment" -> Optional.empty();
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

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return values.get(HexUtil.encodeHexString(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            values.put(HexUtil.encodeHexString(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            values.remove(HexUtil.encodeHexString(hash));
        }
    }
}
