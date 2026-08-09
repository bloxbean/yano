package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSummary;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotPage;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotCanonicalCodec;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSourceBoundary;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppChainSnapshotResourceTest {

    @Test
    void exposesCatalogDescriptorStatusAndIdempotentAdminJob() throws Exception {
        SnapshotDescriptorV1 descriptor = descriptor();
        AppChainGateway gateway = gateway(descriptor);
        var resource = new AppChainResource.ChainScopedResource(gateway);

        Response catalog = resource.snapshots("daily", null, 20);
        assertEquals(200, catalog.getStatus());
        assertEquals(1, ((List<?>) ((Map<?, ?>) catalog.getEntity()).get("items")).size());

        Response one = resource.snapshotDescriptor("daily", 0);
        assertEquals(200, one.getStatus());
        Map<?, ?> view = (Map<?, ?>) one.getEntity();
        assertEquals(HexUtil.encodeHexString(SnapshotCanonicalCodec.encodeDescriptor(descriptor)),
                view.get("descriptorCborHex"));

        Response accepted = resource.snapshotAdmin("daily", 0, "archive",
                "snapshot-admin-key",
                new AppChainResource.ChainScopedResource.SnapshotAdminRequest("demo-1", false));
        assertEquals(202, accepted.getStatus());
        assertEquals("00000000-0000-0000-0000-000000000001",
                ((Map<?, ?>) accepted.getEntity()).get("jobId"));
        assertEquals(200, resource.snapshotJob(
                "00000000-0000-0000-0000-000000000001").getStatus());

        assertEquals(AppChainAccess.Level.READ,
                AppChainResource.ChainScopedResource.class
                        .getMethod("snapshotProof", String.class, long.class,
                                AppChainResource.ChainScopedResource.SnapshotProofRequest.class)
                        .getAnnotation(AppChainAccess.class).value());
        assertEquals(AppChainAccess.Level.SNAPSHOT_ADMIN,
                AppChainResource.ChainScopedResource.class
                        .getMethod("snapshotAdmin", String.class, long.class, String.class,
                                String.class,
                                AppChainResource.ChainScopedResource.SnapshotAdminRequest.class)
                        .getAnnotation(AppChainAccess.class).value());
    }

    @Test
    void distinguishesUnanchoredAndRejectsClientSuppliedArchivePath() {
        var resource = new AppChainResource.ChainScopedResource(gateway(descriptor()));
        Response proof = resource.snapshotProof("daily", 0,
                new AppChainResource.ChainScopedResource.SnapshotProofRequest("01", null));
        assertEquals(503, proof.getStatus());
        assertEquals("SNAPSHOT_NOT_ANCHORED", ((Map<?, ?>) proof.getEntity()).get("code"));

        Response missingIdempotency = resource.snapshotAdmin("daily", 0, "archive",
                "snapshot-admin-key",
                new AppChainResource.ChainScopedResource.SnapshotAdminRequest(null, false));
        assertEquals(400, missingIdempotency.getStatus());
        assertEquals(400, resource.snapshots(null, null, 101).getStatus());
    }

    @Test
    void endpointFamilyKeepsReadsAndAdministrationAtDifferentAccessLevels() throws Exception {
        Class<AppChainResource.ChainScopedResource> type = AppChainResource.ChainScopedResource.class;
        assertEquals(AppChainAccess.Level.READ, type.getMethod("snapshots",
                        String.class, String.class, int.class)
                .getAnnotation(AppChainAccess.class).value());
        assertEquals(AppChainAccess.Level.READ, type.getMethod("snapshotDescriptor",
                        String.class, long.class)
                .getAnnotation(AppChainAccess.class).value());
        assertEquals(AppChainAccess.Level.READ, type.getMethod("verifySnapshotProof",
                        AppChainResource.ChainScopedResource.SnapshotProofVerificationRequest.class)
                .getAnnotation(AppChainAccess.class).value());
        assertEquals(AppChainAccess.Level.READ, type.getMethod("snapshotStatus")
                .getAnnotation(AppChainAccess.class).value());
        assertEquals(AppChainAccess.Level.SNAPSHOT_ADMIN, type.getMethod("snapshotJobs", int.class)
                .getAnnotation(AppChainAccess.class).value());
        assertEquals(AppChainAccess.Level.SNAPSHOT_ADMIN, type.getMethod("snapshotJob", String.class)
                .getAnnotation(AppChainAccess.class).value());
    }

    private static AppChainGateway gateway(SnapshotDescriptorV1 descriptor) {
        return (AppChainGateway) Proxy.newProxyInstance(AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "chainId" -> "chain-a";
                    case "authenticatedSnapshots" -> new AuthenticatedSnapshotPage(List.of(
                            new AuthenticatedSnapshotSummary("daily", 0, "daily-0", 1, 1,
                                    StateCommitmentProfiles.MPF.id(), "ONLINE")),
                            null, 1, repeated(3));
                    case "authenticatedSnapshot" -> Optional.of(descriptor);
                    case "authenticatedSnapshotStatus" -> Map.of("enabled", true);
                    case "authenticatedSnapshotAdmin" ->
                            "00000000-0000-0000-0000-000000000001";
                    case "authenticatedSnapshotJob" -> Optional.of(Map.of(
                            "jobId", "00000000-0000-0000-0000-000000000001",
                            "operation", "archive", "state", "SUCCEEDED"));
                    case "latestAnchorCommitment" -> Optional.empty();
                    case "toString" -> "snapshot-gateway";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static SnapshotDescriptorV1 descriptor() {
        return new SnapshotDescriptorV1(repeated(1), repeated(2), "daily", 0, "daily-0",
                StateCommitmentProfiles.MPF.id(), StateCommitmentProfiles.MPF.formatFingerprint(),
                StateCommitmentProfiles.MPF.proofEncodingId(), repeated(3), repeated(4),
                "blake2b256", "source-v1", "balances-v1", 1,
                0, 1, 0, 1, new byte[32], new SnapshotSourceBoundary.AppHeight(1),
                com.bloxbean.cardano.yano.api.appchain.snapshot
                        .AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET, true);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static byte[] repeated(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
