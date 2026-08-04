package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.appchain.roles.RoleAwareApprovalsComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RolePendingQueriesV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapDomainApiTest {
    private static final String CHAIN = "map-chain";
    private static final byte[] ROOT = filled(0x41);

    @Test
    void exposesBoundedMetadataAndExactEntryProofMaterial() {
        AuthenticatedMapContract.Genesis genesis = basicGenesis();
        byte[] key = "sku-1".getBytes(StandardCharsets.US_ASCII);
        AuthenticatedMapContract.Entry entry = AuthenticatedMapContract.Entry.active(
                1, new byte[0], "value".getBytes(StandardCharsets.US_ASCII), 2, 2);
        AuthenticatedMapDomainApi api = api((path, params) -> {
            if (AuthenticatedMapContract.CAPABILITIES_QUERY_PATH.equals(path)) {
                return AuthenticatedMapContract.encodeGenesis(genesis);
            }
            if (AuthenticatedMapContract.POINT_QUERY_PATH.equals(path)) {
                return AuthenticatedMapContract.encodePointResult(
                        new AuthenticatedMapContract.PointResult(42, ROOT,
                                "records", key,
                                AuthenticatedMapContract.PRESENCE_ACTIVE, entry));
            }
            throw new AssertionError(path);
        });

        String metadata = body(api.handle(request("authenticated-map-metadata",
                Map.of(), Map.of())));
        String point = body(api.handle(request("authenticated-map-entry",
                Map.of("collection", "records", "key", hex(key)), Map.of())));

        assertThat(api.routes()).extracting(route -> route.routeId()).contains(
                "authenticated-map-metadata", "authenticated-map-entry",
                "authenticated-map-direct-policy", "authenticated-map-pending-approvals",
                "get-actor", "get-proposal");
        assertThat(metadata).contains(
                "\"apiVersion\":\"authenticated-map-domain-v1\"",
                "\"profile\":\"mpf-blake2b256-v1\"",
                "\"id\":\"records\"", "\"governed\":false",
                "\"recordValue\":\""
                        + hex(AuthenticatedMapContract.genesisId(genesis)) + "\"");
        assertThat(point).contains("\"presence\":1", "\"revision\":1",
                "\"recordValue\":\"" + hex(AuthenticatedMapContract.encodeEntry(entry))
                        + "\"");
    }

    @Test
    void currentDirectPolicyCarriesPointerAndRejectsMixedRootResolution() {
        DirectRolePolicyV1 policy = new DirectRolePolicyV1(
                "issuer-write", 1, RecordStatus.ACTIVE, "issuer", 100);
        AuthenticatedMapDomainApi api = api((path, params) -> {
            if (path.endsWith("/" + RoleAwareApprovalsComponent
                    .QUERY_DIRECT_POLICY_CURRENT)) {
                return ByteBuffer.allocate(Long.BYTES).putLong(1).array();
            }
            if (path.endsWith("/" + RoleAwareApprovalsComponent.QUERY_DIRECT_POLICY)) {
                return policy.encode();
            }
            throw new AssertionError(path);
        });

        String body = body(api.handle(request("authenticated-map-direct-policy",
                Map.of("id", policy.policyId()), Map.of())));

        assertThat(body).contains("\"requiredRole\":\"issuer\"",
                "\"currentPointerProofKey\"", "\"currentPointerValue\"");

        AuthenticatedMapDomainApi mixed = apiWithResult((path, params) ->
                new AppQueryResult(CHAIN, AuthenticatedMapContract.STATE_MACHINE_ID, 42,
                        path.endsWith("-current") ? ROOT : filled(0x42),
                        path.endsWith("-current")
                                ? ByteBuffer.allocate(Long.BYTES).putLong(1).array()
                                : policy.encode()));
        assertThatThrownBy(() -> mixed.handle(request(
                "authenticated-map-direct-policy", Map.of("id", policy.policyId()), Map.of())))
                .isInstanceOfSatisfying(DomainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DomainApiException.Code.FAILED));
    }

    @Test
    void pendingPageIsExplicitlyDerivedAndDoesNotClaimPageBytesAsLeafValue() {
        byte[] page = new RolePendingQueriesV1.ApprovalPage(List.of(
                new RolePendingQueriesV1.ApprovalEntry(
                        "proposal-a", 100, "release", "issuer-a")), "").encode();
        AuthenticatedMapDomainApi api = api((path, params) -> page);

        String body = body(api.handle(request("authenticated-map-pending-approvals",
                Map.of(), Map.of("limit", List.of("10")))));

        assertThat(body).contains(
                "\"verificationLevel\":\"DERIVED_FROM_PENDING_INDEX\"",
                "\"sourceIndexProofKey\"", "\"queryValue\":\"" + hex(page) + "\"")
                .doesNotContain("\"recordValue\"")
                .doesNotContain("\"proofKey\"");
    }

    private static AuthenticatedMapContract.Genesis basicGenesis() {
        return new AuthenticatedMapContract.Genesis(
                CHAIN, AuthenticatedMapContract.PROFILE_MPF_BLAKE2B256_V1,
                filled(1), filled(2), filled(3), filled(4), 16, 65_536,
                List.of(new AuthenticatedMapContract.CollectionDescriptor(
                        "records", AuthenticatedMapContract.AUTH_OPEN,
                        false, 64, 1024)), List.of(), List.of());
    }

    private static AuthenticatedMapDomainApi api(Query query) {
        return apiWithResult((path, params) -> new AppQueryResult(
                CHAIN, AuthenticatedMapContract.STATE_MACHINE_ID, 42, ROOT,
                query.apply(path, params)));
    }

    private static AuthenticatedMapDomainApi apiWithResult(ResultQuery query) {
        DomainQueryService service = new DomainQueryService() {
            @Override public List<String> chainIds() { return List.of(CHAIN); }
            @Override public AppQueryResult query(String chainId, String path, byte[] params) {
                return query.apply(path, params);
            }
        };
        return new AuthenticatedMapDomainApi(new DomainApiContext(Map.of(), service));
    }

    private static DomainApiRequest request(
            String route, Map<String, String> path, Map<String, List<String>> query) {
        return new DomainApiRequest(route, DomainHttpMethod.GET, route,
                path, query, new byte[0]);
    }

    private static String body(com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse value) {
        assertThat(value.status()).isEqualTo(200);
        return new String(value.body(), StandardCharsets.UTF_8);
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    @FunctionalInterface private interface Query {
        byte[] apply(String path, byte[] params);
    }

    @FunctionalInterface private interface ResultQuery {
        AppQueryResult apply(String path, byte[] params);
    }
}
