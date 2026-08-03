package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoDomainApiTest {
    private static final String ID = "11".repeat(32);
    private static final String MESSAGE = "22".repeat(32);
    private static final EutxoTransactionSummary SUMMARY =
            new EutxoTransactionSummary(
                    ID, MESSAGE, 1, 4, 0, 90,
                    EutxoTransactionSummary.Status.ACCEPTED,
                    "cardano-vkey",
                    List.of(new EutxoTransactionSummary.Entry(
                            new EutxoOutpoint("33".repeat(32), 0),
                            "addr_test1owner", BigInteger.TEN)),
                    List.of(new EutxoTransactionSummary.Entry(
                            new EutxoOutpoint(ID, 0),
                            "addr_test1receiver", BigInteger.TEN)),
                    "");

    @Test
    void listsAndFindsCommittedTransactionSummaries() {
        DomainQueryService queries = new DomainQueryService() {
            @Override
            public List<String> chainIds() {
                return List.of("payments");
            }

            @Override
            public AppQueryResult query(
                    String chainId,
                    String path,
                    byte[] params) {
                byte[] payload = EutxoQueryCodec.TRANSACTION_SUMMARIES_PATH
                        .equals(path)
                        ? EutxoTransactionSummary.encodeList(List.of(SUMMARY))
                        : SUMMARY.encode();
                return new AppQueryResult(
                        chainId, EutxoStateMachine.ID, 4,
                        new byte[32], payload);
            }
        };
        EutxoDomainApi api = new EutxoDomainApi(
                new DomainApiContext(Map.of(), queries));

        var list = api.handle(request(
                "list-transactions", "transactions",
                Map.of(), Map.of("limit", List.of("20"))));
        var byTransaction = api.handle(request(
                "get-transaction", "transactions/" + ID,
                Map.of("id", ID), Map.of()));
        var byMessage = api.handle(request(
                "get-transaction-by-message", "messages/" + MESSAGE,
                Map.of("id", MESSAGE), Map.of()));

        assertThat(list.status()).isEqualTo(200);
        assertThat(new String(list.body(), StandardCharsets.UTF_8))
                .contains("\"data\":[", ID, "addr_test1receiver");
        assertThat(new String(byTransaction.body(), StandardCharsets.UTF_8))
                .contains("\"transactionId\":\"" + ID + "\"");
        assertThat(new String(byMessage.body(), StandardCharsets.UTF_8))
                .contains("\"messageId\":\"" + MESSAGE + "\"");
    }

    @Test
    void malformedIdsAmbiguousChainsAndIdentityMismatchesFailClosed() {
        DomainQueryService ambiguous = queries(
                List.of("payments-a", "payments-b"),
                EutxoStateMachine.ID);
        EutxoDomainApi ambiguousApi = new EutxoDomainApi(
                new DomainApiContext(Map.of(), ambiguous));

        assertThatThrownBy(() -> ambiguousApi.handle(request(
                "list-transactions", "transactions",
                Map.of(), Map.of())))
                .isInstanceOfSatisfying(
                        DomainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DomainApiException.Code.INVALID_REQUEST));

        EutxoDomainApi api = new EutxoDomainApi(
                new DomainApiContext(Map.of(),
                        queries(List.of("payments"), EutxoStateMachine.ID)));
        assertThatThrownBy(() -> api.handle(request(
                "get-transaction", "transactions/not-hex",
                Map.of("id", "not-hex"),
                Map.of("chain", List.of("payments")))))
                .isInstanceOfSatisfying(
                        DomainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DomainApiException.Code.INVALID_REQUEST));

        EutxoDomainApi wrongMachine = new EutxoDomainApi(
                new DomainApiContext(Map.of(),
                        queries(List.of("payments"), "ordered-log")));
        assertThatThrownBy(() -> wrongMachine.handle(request(
                "get-transaction", "transactions/" + ID,
                Map.of("id", ID),
                Map.of("chain", List.of("payments")))))
                .isInstanceOfSatisfying(
                        DomainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DomainApiException.Code.FAILED));
    }

    private static DomainQueryService queries(
            List<String> chains,
            String stateMachineId
    ) {
        return new DomainQueryService() {
            @Override
            public List<String> chainIds() {
                return chains;
            }

            @Override
            public AppQueryResult query(
                    String chainId,
                    String path,
                    byte[] params
            ) {
                byte[] payload = EutxoQueryCodec.TRANSACTION_SUMMARIES_PATH
                        .equals(path)
                        ? EutxoTransactionSummary.encodeList(List.of(SUMMARY))
                        : SUMMARY.encode();
                return new AppQueryResult(
                        chainId, stateMachineId, 4, new byte[32], payload);
            }
        };
    }

    private static DomainApiRequest request(
            String route,
            String path,
            Map<String, String> parameters,
            Map<String, List<String>> query) {
        return new DomainApiRequest(
                route, DomainHttpMethod.GET, path,
                parameters, query, new byte[0]);
    }
}
