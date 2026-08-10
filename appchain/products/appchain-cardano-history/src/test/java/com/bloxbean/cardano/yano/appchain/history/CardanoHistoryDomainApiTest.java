package com.bloxbean.cardano.yano.appchain.history;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsCanonicalCodec;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.api.util.CardanoBech32Ids;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryCodecV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardanoHistoryDomainApiTest {
    private static final String CHAIN = "history-chain";
    private static final long EPOCH = 170;
    private static final byte[] CREDENTIAL = filled(3, 28);
    private static final byte[] POOL = filled(4, 28);

    @Test
    void stakeFactAndCompletenessUseOneRootFixedAggregateQuery() {
        var entry = new EpochStakeContract.Entry(0, CREDENTIAL,
                BigInteger.valueOf(12_000_000), POOL);
        var meta = new EpochStakeContract.Meta(new EpochStakeContract.Manifest(
                EPOCH, 1, 1, 1, filled(5, 32)), 1, true);
        DomainQueryService queries = service((chain, path, params) -> {
            assertThat(chain).isEqualTo(CHAIN);
            assertThat(path).isEqualTo(AggregateQueryCodecV1.PATH);
            List<AggregateQueryCodecV1.Subquery> decoded = AggregateQueryCodecV1.decodeRequest(
                    params, AggregateQueryLimitsV1.DEFAULT);
            assertThat(decoded).extracting(AggregateQueryCodecV1.Subquery::localPath)
                    .containsExactly(EpochStakeContract.QUERY_PATH, EpochStakeContract.META_QUERY_PATH);
            return result(AggregateQueryCodecV1.encodeResponse(List.of(
                    new AggregateQueryCodecV1.Result(CardanoHistoryProduct.STAKE_COMPONENT,
                            EpochStakeContract.QUERY_PATH, EpochStakeContract.encodeValue(entry)),
                    new AggregateQueryCodecV1.Result(CardanoHistoryProduct.STAKE_COMPONENT,
                            EpochStakeContract.META_QUERY_PATH, EpochStakeContract.encodeMeta(meta))),
                    AggregateQueryLimitsV1.DEFAULT));
        });
        CardanoHistoryDomainApi api = new CardanoHistoryDomainApi(new DomainApiContext(Map.of(), queries));
        String response = new String(api.handle(request(CardanoHistoryDomainApi.STAKE,
                "epochs/170/stake/key/" + hex(CREDENTIAL),
                Map.of("epoch", "170", "credential_type", "key",
                        "credential_hash", hex(CREDENTIAL)), Map.of("chain", List.of(CHAIN))))
                .body(), StandardCharsets.UTF_8);

        assertThat(response).contains("\"committedHeight\":41", "\"complete\":true",
                "\"absenceProvable\":true", "\"coin\":\"12000000\"",
                "\"kind\":\"authenticated-snapshot\"");
    }

    @Test
    void stakeAddressIsParsedWithTheSharedCardanoAddressContract() {
        String address = CardanoBech32Ids.stakeAddress(0, hex(CREDENTIAL), 0);
        var entry = new EpochStakeContract.Entry(0, CREDENTIAL,
                BigInteger.valueOf(12_000_000), POOL);
        var meta = new EpochStakeContract.Meta(new EpochStakeContract.Manifest(
                EPOCH, 1, 1, 1, filled(5, 32)), 1, true);
        DomainQueryService queries = service((chain, path, params) -> result(
                AggregateQueryCodecV1.encodeResponse(List.of(
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.STAKE_COMPONENT,
                                EpochStakeContract.QUERY_PATH, EpochStakeContract.encodeValue(entry)),
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.STAKE_COMPONENT,
                                EpochStakeContract.META_QUERY_PATH, EpochStakeContract.encodeMeta(meta))),
                        AggregateQueryLimitsV1.DEFAULT)));
        CardanoHistoryDomainApi api = new CardanoHistoryDomainApi(new DomainApiContext(Map.of(), queries));

        String response = new String(api.handle(request(CardanoHistoryDomainApi.STAKE_ADDRESS,
                "epochs/170/stake-address/" + address,
                Map.of("epoch", "170", "stake_address", address),
                Map.of("chain", List.of(CHAIN)))).body(), StandardCharsets.UTF_8);

        assertThat(response).contains("\"stakeAddress\":\"" + address + "\"",
                "\"credentialType\":0", "\"credentialHash\":\"" + hex(CREDENTIAL) + "\"");
    }

    @Test
    void malformedOrNonRewardStakeAddressIsRejected() {
        CardanoHistoryDomainApi api = new CardanoHistoryDomainApi(new DomainApiContext(Map.of(),
                service((chain, path, params) -> result(new byte[0]))));

        assertThatThrownBy(() -> api.handle(request(CardanoHistoryDomainApi.STAKE_ADDRESS,
                "epochs/170/stake-address/not-an-address",
                Map.of("epoch", "170", "stake_address", "not-an-address"),
                Map.of("chain", List.of(CHAIN)))))
                .isInstanceOfSatisfying(DomainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DomainApiException.Code.INVALID_REQUEST));
    }

    @Test
    void incompleteDatasetNeverClaimsAbsenceAndInputsAreStrict() {
        DomainQueryService queries = service((chain, path, params) -> result(
                AggregateQueryCodecV1.encodeResponse(List.of(
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.STAKE_COMPONENT,
                                EpochStakeContract.QUERY_PATH, new byte[0]),
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.STAKE_COMPONENT,
                                EpochStakeContract.META_QUERY_PATH, new byte[0])),
                        AggregateQueryLimitsV1.DEFAULT)));
        CardanoHistoryDomainApi api = new CardanoHistoryDomainApi(new DomainApiContext(Map.of(), queries));
        String response = new String(api.handle(request(CardanoHistoryDomainApi.STAKE,
                "epochs/170/stake/0/" + hex(CREDENTIAL),
                Map.of("epoch", "170", "credential_type", "0",
                        "credential_hash", hex(CREDENTIAL)), Map.of("chain", List.of(CHAIN))))
                .body(), StandardCharsets.UTF_8);
        assertThat(response).contains("\"complete\":false", "\"absenceProvable\":false",
                "\"found\":false");

        assertThatThrownBy(() -> api.handle(request(CardanoHistoryDomainApi.STAKE,
                "epochs/0170/stake/0/" + hex(CREDENTIAL),
                Map.of("epoch", "0170", "credential_type", "0",
                        "credential_hash", hex(CREDENTIAL)), Map.of("chain", List.of(CHAIN)))))
                .isInstanceOfSatisfying(DomainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DomainApiException.Code.INVALID_REQUEST));
    }

    @Test
    void completenessForAnotherEpochFailsClosed() {
        var wrong = new EpochStakeContract.Meta(new EpochStakeContract.Manifest(
                EPOCH + 1, 0, 1, 0, filled(7, 32)), 0, true);
        DomainQueryService queries = service((chain, path, params) -> result(
                AggregateQueryCodecV1.encodeResponse(List.of(
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.STAKE_COMPONENT,
                                EpochStakeContract.QUERY_PATH, new byte[0]),
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.STAKE_COMPONENT,
                                EpochStakeContract.META_QUERY_PATH,
                                EpochStakeContract.encodeMeta(wrong))),
                        AggregateQueryLimitsV1.DEFAULT)));
        CardanoHistoryDomainApi api = new CardanoHistoryDomainApi(new DomainApiContext(Map.of(), queries));
        assertThatThrownBy(() -> api.handle(request(CardanoHistoryDomainApi.STAKE,
                "epochs/170/stake/0/" + hex(CREDENTIAL),
                Map.of("epoch", "170", "credential_type", "0",
                        "credential_hash", hex(CREDENTIAL)), Map.of("chain", List.of(CHAIN)))))
                .isInstanceOfSatisfying(DomainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DomainApiException.Code.FAILED));
    }

    @Test
    void chainIsAlwaysExplicitAndParameterCoordinatesAreCompositePhysicalKeys() {
        DomainQueryService queries = service((chain, path, params) -> result(
                AggregateQueryCodecV1.encodeResponse(List.of(
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.PARAMS_COMPONENT,
                                EpochParamsContract.QUERY_PATH, parameterDocument())),
                        AggregateQueryLimitsV1.DEFAULT)));
        CardanoHistoryDomainApi api = new CardanoHistoryDomainApi(new DomainApiContext(Map.of(), queries));
        assertThatThrownBy(() -> api.handle(request(CardanoHistoryDomainApi.PARAMS,
                "epochs/170/parameters", Map.of("epoch", "170"), Map.of())))
                .isInstanceOfSatisfying(DomainApiException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DomainApiException.Code.INVALID_REQUEST));

        String response = new String(api.handle(request(CardanoHistoryDomainApi.PARAMS,
                "epochs/170/parameters", Map.of("epoch", "170"),
                Map.of("chain", List.of(CHAIN)))).body(), StandardCharsets.UTF_8);
        assertThat(response).contains("\"dataset\":\"protocol-parameters\"",
                "\"kind\":\"primary\"", "\"physicalKey\":");
    }

    @Test
    void namedParameterFieldReturnsTypedValueAndRootFixedProofPair() {
        byte[] leaf = ProtocolParamsCanonicalCodec.encodeLeaf(EPOCH, "key-deposit",
                ProtocolParamsCanonicalCodec.TYPE_LOVELACE, BigInteger.valueOf(2_000_000));
        byte[] meta = EpochParamsContract.encodeMeta(new EpochParamsContract.Meta(
                EPOCH, 1, filled(7, 32)));
        DomainQueryService queries = service((chain, path, params) -> result(
                AggregateQueryCodecV1.encodeResponse(List.of(
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.PARAMS_COMPONENT,
                                EpochParamsContract.FIELD_QUERY_PATH, leaf),
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.PARAMS_COMPONENT,
                                EpochParamsContract.META_QUERY_PATH, meta)),
                        AggregateQueryLimitsV1.DEFAULT)));
        CardanoHistoryDomainApi api = new CardanoHistoryDomainApi(
                new DomainApiContext(Map.of(), queries));

        String response = new String(api.handle(request(CardanoHistoryDomainApi.PARAM_FIELD,
                "epochs/170/parameters/fields/key-deposit",
                Map.of("epoch", "170", "field_id", "key-deposit"),
                Map.of("chain", List.of(CHAIN)))).body(), StandardCharsets.UTF_8);

        assertThat(response).contains("\"dataset\":\"protocol-parameter-field\"",
                "\"fieldId\":\"key-deposit\"", "\"type\":\"lovelace\"",
                "\"value\":\"2000000\"", "\"complete\":true",
                "\"kind\":\"primary-pair\"");
    }

    @Test
    void epochCatalogReturnsOnlyAuthenticatedAvailableRecords() {
        DomainQueryService queries = service((chain, path, params) -> {
            List<AggregateQueryCodecV1.Subquery> request = AggregateQueryCodecV1.decodeRequest(
                    params, AggregateQueryLimitsV1.DEFAULT);
            if (request.size() == 1) {
                return result(AggregateQueryCodecV1.encodeResponse(List.of(
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.PARAMS_COMPONENT,
                                EpochParamsContract.LATEST_QUERY_PATH,
                                EpochParamsContract.encodeEpoch(EPOCH))),
                        AggregateQueryLimitsV1.DEFAULT));
            }
            return result(AggregateQueryCodecV1.encodeResponse(List.of(
                    new AggregateQueryCodecV1.Result(CardanoHistoryProduct.PARAMS_COMPONENT,
                            EpochParamsContract.LATEST_QUERY_PATH,
                            EpochParamsContract.encodeEpoch(EPOCH)),
                    new AggregateQueryCodecV1.Result(CardanoHistoryProduct.PARAMS_COMPONENT,
                            EpochParamsContract.QUERY_PATH, new byte[]{1}),
                    new AggregateQueryCodecV1.Result(CardanoHistoryProduct.PARAMS_COMPONENT,
                            EpochParamsContract.QUERY_PATH, new byte[0]),
                    new AggregateQueryCodecV1.Result(CardanoHistoryProduct.PARAMS_COMPONENT,
                            EpochParamsContract.QUERY_PATH, new byte[]{2})),
                    AggregateQueryLimitsV1.DEFAULT));
        });
        CardanoHistoryDomainApi api = new CardanoHistoryDomainApi(new DomainApiContext(Map.of(), queries));
        String response = new String(api.handle(request(CardanoHistoryDomainApi.EPOCHS, "epochs",
                Map.of(), Map.of("chain", List.of(CHAIN), "limit", List.of("3")))).body(),
                StandardCharsets.UTF_8);
        assertThat(response).contains("\"epochs\":[170,168]")
                .doesNotContain("170,169");
    }

    @Test
    void epochCatalogRetriesInsteadOfMixingLatestAcrossRoots() {
        AtomicInteger latestReads = new AtomicInteger();
        DomainQueryService queries = service((chain, path, params) -> {
            List<AggregateQueryCodecV1.Subquery> request = AggregateQueryCodecV1.decodeRequest(
                    params, AggregateQueryLimitsV1.DEFAULT);
            if (request.size() == 1) {
                long latest = latestReads.getAndIncrement() == 0 ? EPOCH : EPOCH + 1;
                return result(AggregateQueryCodecV1.encodeResponse(List.of(
                        new AggregateQueryCodecV1.Result(CardanoHistoryProduct.PARAMS_COMPONENT,
                                EpochParamsContract.LATEST_QUERY_PATH,
                                EpochParamsContract.encodeEpoch(latest))),
                        AggregateQueryLimitsV1.DEFAULT));
            }
            long aggregateLatest = EPOCH + 1;
            List<AggregateQueryCodecV1.Result> results = request.stream().map(query ->
                    new AggregateQueryCodecV1.Result(query.componentId(), query.localPath(),
                            EpochParamsContract.LATEST_QUERY_PATH.equals(query.localPath())
                                    ? EpochParamsContract.encodeEpoch(aggregateLatest)
                                    : EpochParamsContract.decodeEpoch(query.params()) == aggregateLatest
                                    ? new byte[]{1} : new byte[0])).toList();
            return result(AggregateQueryCodecV1.encodeResponse(
                    results, AggregateQueryLimitsV1.DEFAULT));
        });
        CardanoHistoryDomainApi api = new CardanoHistoryDomainApi(new DomainApiContext(Map.of(), queries));
        String response = new String(api.handle(request(CardanoHistoryDomainApi.EPOCHS, "epochs",
                Map.of(), Map.of("chain", List.of(CHAIN), "limit", List.of("3")))).body(),
                StandardCharsets.UTF_8);
        assertThat(response).contains("\"latestEpoch\":171", "\"epochs\":[171]");
        assertThat(latestReads).hasValue(2);
    }

    private static DomainApiRequest request(String id, String path, Map<String, String> pathParams,
                                            Map<String, List<String>> query) {
        return new DomainApiRequest(id, DomainHttpMethod.GET, path, pathParams, query, new byte[0]);
    }

    private static DomainQueryService service(Query handler) {
        return new DomainQueryService() {
            @Override public List<String> chainIds() { return List.of(CHAIN); }
            @Override public AppQueryResult query(String chainId, String path, byte[] params) {
                return handler.query(chainId, path, params);
            }
        };
    }

    private static AppQueryResult result(byte[] payload) {
        return new AppQueryResult(CHAIN, CardanoHistoryProduct.STATE_MACHINE_ID,
                41, filled(9, 32), payload);
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static byte[] filled(int value, int size) {
        byte[] bytes = new byte[size]; Arrays.fill(bytes, (byte) value); return bytes;
    }

    private static byte[] parameterDocument() {
        return new byte[]{(byte) 0x83, 0x02, 0x18, (byte) 0xaa, (byte) 0x80};
    }

    @FunctionalInterface private interface Query {
        AppQueryResult query(String chain, String path, byte[] params);
    }
}
