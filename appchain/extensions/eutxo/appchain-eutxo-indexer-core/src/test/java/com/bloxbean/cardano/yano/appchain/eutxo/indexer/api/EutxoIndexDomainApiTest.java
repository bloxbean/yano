package com.bloxbean.cardano.yano.appchain.eutxo.indexer.api;

import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelResult;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexEvent;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoLocalReadModel;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoProjector;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexCoverage;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexHealth;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.memory.InMemoryEutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.testing.EutxoIndexFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoIndexDomainApiTest {
    @Test
    void routesExposeDerivedEnvelopeSearchAccountAndCapabilityErrors() {
        try (InMemoryEutxoIndexStore store =
                     new InMemoryEutxoIndexStore(EutxoIndexFixtures.identity())) {
            applyFixture(store);
            EutxoIndexDomainApi api = api(store);

            String status = body(api.handle(request(
                    "index-status", "index/v1/status",
                    Map.of(), Map.of("chain", List.of("payments")))));
            assertThat(status)
                    .contains("\"apiVersion\":\"eutxo-index/v1\"")
                    .contains("\"kind\":\"DERIVED\"")
                    .contains("\"normalizedDigest\"");

            String transactions = body(api.handle(request(
                    "index-transactions", "index/v1/transactions",
                    Map.of(), Map.of(
                            "chain", List.of("payments"),
                            "limit", List.of("2")))));
            assertThat(transactions)
                    .contains("\"items\"")
                    .contains(EutxoIndexFixtures.hex(3))
                    .contains("\"cursor\":\"c1_");

            String transaction = body(api.handle(request(
                    "index-transaction",
                    "index/v1/transactions/" + EutxoIndexFixtures.hex(3),
                    Map.of("transaction_id", EutxoIndexFixtures.hex(3)),
                    Map.of("chain", List.of("payments")))));
            assertThat(transaction).contains("\"lovelace\":\"100\"");

            String account = body(api.handle(request(
                    "index-account",
                    "index/v1/accounts/" + EutxoIndexFixtures.ALICE,
                    Map.of("address", EutxoIndexFixtures.ALICE),
                    Map.of("chain", List.of("payments")))));
            assertThat(account).contains("\"lovelace\":\"100\"");

            assertThat(api.handle(request(
                    "index-validity-batches",
                    "index/v1/validity/batches",
                    Map.of(), Map.of("chain", List.of("payments")))).status())
                    .isEqualTo(409);
        }
    }

    @Test
    void malformedOrCrossOrderingCursorFailsClosed() {
        try (InMemoryEutxoIndexStore store =
                     new InMemoryEutxoIndexStore(EutxoIndexFixtures.identity())) {
            applyFixture(store);
            EutxoIndexDomainApi api = api(store);
            assertThatThrownBy(() -> api.handle(request(
                    "index-transactions", "index/v1/transactions",
                    Map.of(), Map.of(
                            "chain", List.of("payments"),
                            "cursor", List.of("c1_not-valid")))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> api.handle(request(
                    "index-status", "index/v1/status",
                    Map.of(), Map.of(
                            "chain", List.of("payments"),
                            "sql", List.of("select *")))))
                    .isInstanceOf(DomainApiException.class);
        }
    }

    @Test
    void providerIsDiscoverableWithTheDedicatedIdentity() {
        EutxoIndexDomainApiProvider provider =
                java.util.ServiceLoader.load(
                                com.bloxbean.cardano.yano.api.plugin.domain
                                        .DomainApiProvider.class)
                        .stream()
                        .map(java.util.ServiceLoader.Provider::get)
                        .filter(candidate -> EutxoLocalReadModel.MODEL_ID.equals(
                                candidate.id()))
                        .map(EutxoIndexDomainApiProvider.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertThat(provider.id()).isEqualTo(EutxoLocalReadModel.MODEL_ID);
    }

    private static EutxoIndexDomainApi api(InMemoryEutxoIndexStore store) {
        EutxoLocalReadModel model = new EutxoLocalReadModel(
                "payments", store,
                () -> new IndexHealth(
                        IndexHealth.Status.READY,
                        store.checkpoint(),
                        store.checkpoint().source().appHeight(),
                        0,
                        ""));
        DomainQueryService chains = new DomainQueryService() {
            @Override
            public List<String> chainIds() {
                return List.of("payments");
            }

            @Override
            public com.bloxbean.cardano.yano.api.appchain.AppQueryResult query(
                    String chainId,
                    String path,
                    byte[] params
            ) {
                throw new AppQueryException(
                        AppQueryException.Code.UNAVAILABLE, "not used");
            }
        };
        return new EutxoIndexDomainApi(new DomainApiContext(
                Map.of(),
                chains,
                (modelId, chainId, operation, request) ->
                        EutxoLocalReadModel.MODEL_ID.equals(modelId)
                                && "payments".equals(chainId)
                                ? model.query(operation, request)
                                : LocalReadModelResult.unavailable()));
    }

    private static void applyFixture(InMemoryEutxoIndexStore store) {
        EutxoProjector projector = new EutxoProjector(store);
        List<List<EutxoIndexEvent>> events =
                EutxoIndexFixtures.splitMergeEvents();
        projector.apply(
                EutxoIndexFixtures.point(1),
                events.getFirst(),
                IndexCoverage.FULL);
        projector.apply(
                EutxoIndexFixtures.point(2),
                events.getLast(),
                IndexCoverage.FULL);
    }

    private static DomainApiRequest request(
            String route,
            String path,
            Map<String, String> pathParameters,
            Map<String, List<String>> query
    ) {
        return new DomainApiRequest(
                route, DomainHttpMethod.GET, path,
                pathParameters, query, new byte[0]);
    }

    private static String body(
            com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse response
    ) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
