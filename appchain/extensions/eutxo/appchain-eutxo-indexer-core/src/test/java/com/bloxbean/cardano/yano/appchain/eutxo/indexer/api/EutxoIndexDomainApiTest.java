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
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoValidityBatchRecord;
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
                    .isInstanceOf(DomainApiException.class)
                    .extracting(failure ->
                            ((DomainApiException) failure).code())
                    .isEqualTo(
                            DomainApiException.Code.INVALID_REQUEST);
            assertThatThrownBy(() -> api.handle(request(
                    "index-status", "index/v1/status",
                    Map.of(), Map.of(
                            "chain", List.of("payments"),
                            "sql", List.of("select *")))))
                    .isInstanceOf(DomainApiException.class);
        }
    }

    @Test
    void malformedIdentifiersAddressesBoundsAndDuplicatesUseStableCode() {
        try (InMemoryEutxoIndexStore store =
                     new InMemoryEutxoIndexStore(
                             EutxoIndexFixtures.identity())) {
            applyFixture(store);
            EutxoIndexDomainApi api = api(store);
            List<DomainApiRequest> invalid = List.of(
                    request(
                            "index-transaction",
                            "index/v1/transactions/not-hex",
                            Map.of("transaction_id", "not-hex"),
                            Map.of("chain", List.of("payments"))),
                    request(
                            "index-account",
                            "index/v1/accounts/not-an-address",
                            Map.of("address", "not-an-address"),
                            Map.of("chain", List.of("payments"))),
                    request(
                            "index-transactions",
                            "index/v1/transactions",
                            Map.of(),
                            Map.of(
                                    "chain", List.of("payments"),
                                    "limit", List.of("101"))),
                    request(
                            "index-transactions",
                            "index/v1/transactions",
                            Map.of(),
                            Map.of(
                                    "chain", List.of(
                                            "payments", "payments"))),
                    request(
                            "index-lineage",
                            "index/v1/lineage/outpoints/"
                                    + EutxoIndexFixtures.hex(1)
                                    + "/70000",
                            Map.of(
                                    "transaction_id",
                                    EutxoIndexFixtures.hex(1),
                                    "output_index", "70000"),
                            Map.of("chain", List.of("payments"))));
            for (DomainApiRequest request : invalid) {
                assertThatThrownBy(() -> api.handle(request))
                        .isInstanceOf(DomainApiException.class)
                        .extracting(failure ->
                                ((DomainApiException) failure).code())
                        .isEqualTo(
                                DomainApiException.Code.INVALID_REQUEST);
            }
        }
    }

    @Test
    void providerUsesTheDedicatedIdentity() {
        EutxoIndexDomainApiProvider provider =
                new EutxoIndexDomainApiProvider();
        assertThat(provider.id()).isEqualTo(EutxoLocalReadModel.MODEL_ID);
    }

    @Test
    void validitySourceUsesNeutralBoundedRecordsAndOrderedMembership() {
        try (InMemoryEutxoIndexStore store =
                     new InMemoryEutxoIndexStore(EutxoIndexFixtures.identity())) {
            applyFixture(store);
            String transaction = EutxoIndexFixtures.hex(3);
            EutxoValidityBatchRecord batch = new EutxoValidityBatchRecord(
                    EutxoIndexFixtures.hex(21),
                    "fixture",
                    "groth16",
                    "payment-b16",
                    EutxoIndexFixtures.hex(22),
                    List.of(transaction, EutxoIndexFixtures.hex(4)),
                    EutxoIndexFixtures.hex(23),
                    EutxoIndexFixtures.hex(24),
                    EutxoIndexFixtures.hex(25),
                    "AVAILABLE",
                    EutxoIndexFixtures.hex(26),
                    EutxoIndexFixtures.hex(27),
                    "VERIFIED",
                    "STABLE",
                    EutxoIndexFixtures.hex(28),
                    100,
                    EutxoIndexFixtures.hex(29));
            EutxoIndexDomainApi api = api(store, () -> List.of(batch));

            String page = body(api.handle(request(
                    "index-validity-batches",
                    "index/v1/validity/batches",
                    Map.of(), Map.of("chain", List.of("payments")))));
            assertThat(page)
                    .contains("\"provider\":\"fixture\"")
                    .contains("\"proofStatus\":\"VERIFIED\"")
                    .contains("\"transactionIds\":[\"" + transaction);
            String detail = body(api.handle(request(
                    "index-validity-batch",
                    "index/v1/validity/batches/" + batch.batchId(),
                    Map.of("batch_id", batch.batchId()),
                    Map.of("chain", List.of("payments")))));
            assertThat(detail)
                    .contains("\"settlementStatus\":\"STABLE\"")
                    .contains("\"settlementSlot\":100");
        }
    }

    private static EutxoIndexDomainApi api(InMemoryEutxoIndexStore store) {
        return api(store, null);
    }

    private static EutxoIndexDomainApi api(
            InMemoryEutxoIndexStore store,
            com.bloxbean.cardano.yano.appchain.eutxo.indexer
                    .EutxoValidityIndexSource validity
    ) {
        EutxoLocalReadModel model = new EutxoLocalReadModel(
                "payments", store,
                () -> new IndexHealth(
                        IndexHealth.Status.READY,
                        store.checkpoint(),
                        store.checkpoint().source().appHeight(),
                        0,
                        ""),
                new com.bloxbean.cardano.yano.appchain.eutxo.indexer
                        .EutxoIndexMetrics(),
                validity);
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
