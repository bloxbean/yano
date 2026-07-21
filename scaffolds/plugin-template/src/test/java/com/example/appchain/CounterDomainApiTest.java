package com.example.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CounterDomainApiTest {

    @Test
    void providerPublishesValidatedRoutesAndSafelyEncodesQueryResults() throws Exception {
        DomainQueryService queries = new DomainQueryService() {
            @Override
            public List<String> chainIds() {
                return List.of("chain");
            }

            @Override
            public AppQueryResult query(String chainId, String path, byte[] params) {
                assertThat(chainId).isEqualTo("chain");
                assertThat(path).isEqualTo("counter/read");
                assertThat(params).asString(StandardCharsets.US_ASCII).isEqualTo("visits");
                return new AppQueryResult("chain\"\n", "counter", 7,
                        filled(3), "2".getBytes(StandardCharsets.UTF_8));
            }
        };
        CounterDomainApiProvider provider = new CounterDomainApiProvider();

        try (DomainApi api = provider.create(new DomainApiContext(Map.of(), queries))) {
            assertThat(provider.id()).isEqualTo(CounterDomainApiProvider.BUNDLE_ID);
            assertThat(api.routes()).extracting(route -> route.routeId())
                    .containsExactlyInAnyOrder(
                            "status", "counter.read", "operator", "internal");

            var response = api.handle(new DomainApiRequest(
                    "counter.read",
                    DomainHttpMethod.GET,
                    "counters/visits",
                    Map.of("key", "visits"),
                    Map.of("chain", List.of("chain")),
                    new byte[0]));
            String json = new String(response.body(), StandardCharsets.UTF_8);

            assertThat(json)
                    .contains("\"chainId\":\"chain\\\"\\n\"")
                    .contains("\"height\":7")
                    .contains("\"valueHex\":\"32\"")
                    .doesNotContain("chain\"\n");
        }
    }

    @Test
    void queryFailureTranslationPreservesOnlyStableCode() {
        AppQueryException source = new AppQueryException(
                AppQueryException.Code.TIMEOUT, "do-not-expose");

        DomainApiException translated =
                CounterDomainApiProvider.translateQueryFailure(source);

        assertThat(translated.code()).isEqualTo(DomainApiException.Code.TIMEOUT);
        assertThat(translated.getMessage()).isEqualTo("counter query failed");
        assertThat(translated.getMessage()).doesNotContain("do-not-expose");
        assertThat(translated.getCause()).isSameAs(source);
    }

    @Test
    void closeMakesTheProductUnavailable() {
        CounterDomainApiProvider provider = new CounterDomainApiProvider();
        DomainApi api = provider.create(new DomainApiContext(Map.of(), unavailableQueries()));
        api.close();

        assertThatThrownBy(() -> api.handle(new DomainApiRequest(
                "status", DomainHttpMethod.GET, "status",
                Map.of(), Map.of(), new byte[0])))
                .isInstanceOfSatisfying(DomainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DomainApiException.Code.UNAVAILABLE));
    }

    private static DomainQueryService unavailableQueries() {
        return new DomainQueryService() {
            @Override
            public List<String> chainIds() {
                return List.of();
            }

            @Override
            public AppQueryResult query(String chainId, String path, byte[] params) {
                throw new AppQueryException(AppQueryException.Code.UNAVAILABLE,
                        "not configured");
            }
        };
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
