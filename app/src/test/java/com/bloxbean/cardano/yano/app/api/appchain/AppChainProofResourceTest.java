package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppAnchorCommitment;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
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
            assertEquals(runtimeProfile.commitmentFormatId(),
                    clientProfile.commitmentFormatId());
            assertEquals(runtimeProfile.proofEncodingId(),
                    clientProfile.proofEncodingId());
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
                identity.profile().proofEncodingId(), new byte[]{(byte) 0x80});
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
        AppChainAccess unscopedAccess = AppChainResource.class
                .getMethod("verifyProof",
                        AppChainResource.ChainScopedResource.ProofVerificationRequest.class)
                .getAnnotation(AppChainAccess.class);
        AppChainAccess scopedAccess = AppChainResource.ChainScopedResource.class
                .getMethod("verifyProof",
                        AppChainResource.ChainScopedResource.ProofVerificationRequest.class)
                .getAnnotation(AppChainAccess.class);

        assertEquals(AppChainAccess.Level.READ, unscopedAccess.value());
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
                        gateway());

        Response valid = resource.verifyProof(
                new AppChainResource.ChainScopedResource.ProofVerificationRequest(
                        "inclusion", StateCommitmentProfiles.MPF.id(), "PRESENT",
                        HexUtil.encodeHexString(root),
                        HexUtil.encodeHexString(key), HexUtil.encodeHexString(value),
                        HexUtil.encodeHexString(wire)));
        assertEquals(200, valid.getStatus());
        assertEquals(true, ((Map<?, ?>) valid.getEntity()).get("valid"));

        Response wrongRoot = resource.verifyProof(
                new AppChainResource.ChainScopedResource.ProofVerificationRequest(
                        "inclusion", StateCommitmentProfiles.MPF.id(), "PRESENT",
                        "00".repeat(32), HexUtil.encodeHexString(key),
                        HexUtil.encodeHexString(value), HexUtil.encodeHexString(wire)));
        assertEquals(200, wrongRoot.getStatus());
        assertEquals(false, ((Map<?, ?>) wrongRoot.getEntity()).get("valid"));

        assertEquals(400, resource.verifyProof(
                new AppChainResource.ChainScopedResource.ProofVerificationRequest(
                        "unknown", StateCommitmentProfiles.MPF.id(), "PRESENT",
                        "00".repeat(32), "00", null, "00")).getStatus());
        assertEquals(400, resource.verifyProof(
                new AppChainResource.ChainScopedResource.ProofVerificationRequest(
                        "exclusion", null, "ABSENT",
                        "00".repeat(32), "00", null, "00")).getStatus());
        assertEquals(400, resource.verifyProof(
                new AppChainResource.ChainScopedResource.ProofVerificationRequest(
                        "exclusion", StateCommitmentProfiles.MPF.id(), null,
                        "00".repeat(32), "00", null, "00")).getStatus());
        assertEquals(413, resource.verifyProof(
                new AppChainResource.ChainScopedResource.ProofVerificationRequest(
                        "exclusion", StateCommitmentProfiles.MPF.id(), "ABSENT",
                        "00".repeat(32), "00", null,
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

    private static AppChainGateway gateway() {
        return (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "chainId" -> "chain-a";
                    case "latestAnchorCommitment" -> Optional.empty();
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
