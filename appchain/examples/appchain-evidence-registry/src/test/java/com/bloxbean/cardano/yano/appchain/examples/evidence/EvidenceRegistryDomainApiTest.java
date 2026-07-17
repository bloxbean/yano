package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetRequestV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceCompositeKeys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceRegistryDomainApiTest {
    private static final String CHAIN = "evidence-chain";

    @Test
    void providerPublishesOneReadRouteAndProjectsBoundQueryAsJson() throws Exception {
        AtomicReference<EvidenceGetRequestV1> observed = new AtomicReference<>();
        DomainQueryService queries = service(List.of(CHAIN), (chainId, path, params) -> {
            assertThat(chainId).isEqualTo(CHAIN);
            assertThat(path).isEqualTo(EvidenceContract.GET_QUERY_PATH);
            observed.set(EvidenceGetRequestV1.decode(params));
            return result(EvidenceGetResponseV1.found(
                    new EvidenceHeadV1(EvidenceFixtures.ID, EvidenceFixtures.OWNER, 1),
                    EvidenceFixtures.storageReadyRecord()).encode());
        });
        EvidenceRegistryDomainApiProvider provider = new EvidenceRegistryDomainApiProvider();
        assertThat(provider.id()).isEqualTo(EvidenceRegistryDomainApiProvider.BUNDLE_ID);
        try (DomainApi api = provider.create(new DomainApiContext(Map.of(), queries))) {
            assertThat(api.routes()).singleElement().satisfies(route -> {
                assertThat(route.routeId()).isEqualTo(EvidenceRegistryDomainApi.GET_ROUTE);
                assertThat(route.method()).isEqualTo(DomainHttpMethod.GET);
                assertThat(route.access()).isEqualTo(DomainApiAccess.READ);
                assertThat(route.template()).isEqualTo("evidence/{evidence_id}");
            });

            DomainApiResponse response = api.handle(request(Map.of("version", List.of("1"))));
            String json = new String(response.body(), StandardCharsets.UTF_8);
            assertThat(response.status()).isEqualTo(200);
            assertThat(json).contains(
                    "\"chainId\":\"evidence-chain\"",
                    "\"evidenceId\":\"batch-001\"",
                    "\"status\":\"STORAGE_READY\"",
                    "\"stateRoot\":\"");
            assertThat(observed.get()).isEqualTo(new EvidenceGetRequestV1(EvidenceFixtures.ID, 1));
        }
    }

    @Test
    void multiChainHostRequiresOneExplicitKnownChainAndCanonicalVersion() {
        DomainQueryService queries = service(List.of("a-chain", "b-chain"),
                (chainId, path, params) -> new AppQueryResult(
                        chainId, EvidenceContract.STATE_MACHINE_ID, 7,
                        EvidenceFixtures.repeat(0x71),
                        EvidenceGetResponseV1.notFound().encode()));
        EvidenceRegistryDomainApi api = new EvidenceRegistryDomainApi(
                new DomainApiContext(Map.of(), queries));

        assertCode(api, request(Map.of()), DomainApiException.Code.INVALID_REQUEST);
        assertCode(api, request(Map.of("chain", List.of("unknown"))),
                DomainApiException.Code.INVALID_REQUEST);
        assertCode(api, request(Map.of("chain", List.of("a-chain", "b-chain"))),
                DomainApiException.Code.INVALID_REQUEST);
        assertCode(api, request(Map.of("chain", List.of("a-chain"),
                "version", List.of("01"))), DomainApiException.Code.INVALID_REQUEST);
        assertCode(api, request(Map.of("chain", List.of("a-chain"),
                "extra", List.of("x"))), DomainApiException.Code.INVALID_REQUEST);

        DomainApiResponse missing = api.handle(request(Map.of(
                "chain", List.of("b-chain"), "version", List.of("0"))));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(new String(missing.body(), StandardCharsets.UTF_8))
                .isEqualTo("{\"error\":\"not-found\"}");
    }

    @Test
    void compositeAliasReturnsPhysicalProofKeysAndMachineIdentity() {
        DomainQueryService queries = service(List.of(CHAIN), (chainId, path, params) ->
                new AppQueryResult(CHAIN, EvidenceCompositeKeys.STATE_MACHINE_ID, 7,
                        EvidenceFixtures.repeat(0x71),
                        EvidenceGetResponseV1.found(
                                new EvidenceHeadV1(EvidenceFixtures.ID, EvidenceFixtures.OWNER, 1),
                                EvidenceFixtures.storageReadyRecord()).encode()));
        EvidenceRegistryDomainApi api = new EvidenceRegistryDomainApi(
                new DomainApiContext(Map.of(), queries));

        String json = new String(api.handle(request(Map.of())).body(), StandardCharsets.UTF_8);
        assertThat(json)
                .contains("\"stateMachineId\":\"composite\"")
                .contains("\"headKey\":\"" + java.util.HexFormat.of().formatHex(
                        EvidenceCompositeKeys.physicalKey(
                                com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys
                                        .headKey(EvidenceFixtures.ID))) + "\"");
    }

    @Test
    void queryFailuresAndIdentityMismatchesAreTypedAndRedacted() {
        EvidenceRegistryDomainApi busy = new EvidenceRegistryDomainApi(new DomainApiContext(
                Map.of(), service(List.of(CHAIN), (chainId, path, params) -> {
                    throw new AppQueryException(AppQueryException.Code.BUSY,
                            "secret backend detail");
                })));
        assertThatThrownBy(() -> busy.handle(request(Map.of())))
                .isInstanceOfSatisfying(DomainApiException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(DomainApiException.Code.BUSY);
                    assertThat(failure.getMessage()).doesNotContain("secret");
                });

        EvidenceRegistryDomainApi mismatched = new EvidenceRegistryDomainApi(
                new DomainApiContext(Map.of(), service(List.of(CHAIN),
                        (chainId, path, params) -> new AppQueryResult(
                                CHAIN, "another-machine", 7,
                                EvidenceFixtures.repeat(0x71),
                                EvidenceGetResponseV1.notFound().encode()))));
        assertCode(mismatched, request(Map.of()), DomainApiException.Code.FAILED);

        EvidenceRegistryDomainApi wrongRecord = new EvidenceRegistryDomainApi(
                new DomainApiContext(Map.of(), service(List.of(CHAIN),
                        (chainId, path, params) -> result(EvidenceGetResponseV1.found(
                                new EvidenceHeadV1(EvidenceFixtures.ID,
                                        EvidenceFixtures.OWNER, 1),
                                EvidenceFixtures.storageReadyRecord()).encode()))));
        DomainApiRequest anotherId = new DomainApiRequest(
                EvidenceRegistryDomainApi.GET_ROUTE, DomainHttpMethod.GET,
                "evidence/another", Map.of("evidence_id", "another"),
                Map.of(), new byte[0]);
        assertCode(wrongRecord, anotherId, DomainApiException.Code.FAILED);

        assertThatCode(mismatched::close).doesNotThrowAnyException();
    }

    private static void assertCode(EvidenceRegistryDomainApi api,
                                   DomainApiRequest request,
                                   DomainApiException.Code code) {
        assertThatThrownBy(() -> api.handle(request))
                .isInstanceOfSatisfying(DomainApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static DomainApiRequest request(Map<String, List<String>> query) {
        return new DomainApiRequest(EvidenceRegistryDomainApi.GET_ROUTE,
                DomainHttpMethod.GET, "evidence/" + EvidenceFixtures.ID,
                Map.of("evidence_id", EvidenceFixtures.ID), query, new byte[0]);
    }

    private static AppQueryResult result(byte[] payload) {
        return new AppQueryResult(CHAIN, EvidenceContract.STATE_MACHINE_ID, 7,
                EvidenceFixtures.repeat(0x71), payload);
    }

    private static DomainQueryService service(List<String> chains, Query query) {
        return new DomainQueryService() {
            @Override public List<String> chainIds() { return chains; }

            @Override
            public AppQueryResult query(String chainId, String path, byte[] params) {
                return query.apply(chainId, path, params);
            }
        };
    }

    @FunctionalInterface
    private interface Query {
        AppQueryResult apply(String chainId, String path, byte[] params);
    }
}
