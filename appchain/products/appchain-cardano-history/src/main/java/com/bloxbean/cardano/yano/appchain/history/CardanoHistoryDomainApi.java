package com.bloxbean.cardano.yano.appchain.history;

import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateKeys;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryCodecV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded projections over canonical, root-fixed Cardano History state queries. */
public final class CardanoHistoryDomainApi implements DomainApi {
    static final String STATUS = "status";
    static final String EPOCHS = "epochs";
    static final String PARAMS = "parameters";
    static final String STAKE = "stake";
    static final String DREP = "drep";
    static final String PROPOSAL = "proposal";
    private static final int API_VERSION = 1;
    private static final Set<String> CHAIN_ONLY = Set.of("chain");
    private static final Set<String> EPOCHS_QUERY = Set.of("chain", "limit");
    private static final Set<String> PROPOSAL_QUERY = Set.of("chain", "epoch");
    private static final List<DomainApiRoute> ROUTES = List.of(
            read(STATUS, "status"), read(EPOCHS, "epochs"),
            read(PARAMS, "epochs/{epoch}/parameters"),
            read(STAKE, "epochs/{epoch}/stake/{credential_type}/{credential_hash}"),
            read(DREP, "epochs/{epoch}/dreps/{drep_type}/{drep_hash}"),
            read(PROPOSAL, "proposals/{transaction_id}/{index}"));

    private final DomainApiContext context;

    CardanoHistoryDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<DomainApiRoute> routes() {
        return ROUTES;
    }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        if (request == null || request.method() != DomainHttpMethod.GET) throw notFound();
        return switch (request.routeId()) {
            case STATUS -> latest(request, false);
            case EPOCHS -> latest(request, true);
            case PARAMS -> parameters(request);
            case STAKE -> stake(request);
            case DREP -> drep(request);
            case PROPOSAL -> proposal(request);
            default -> throw notFound();
        };
    }

    private DomainApiResponse latest(DomainApiRequest request, boolean list) {
        requireKeys(request.queryParameters(), list ? EPOCHS_QUERY : CHAIN_ONLY);
        String chain = chain(request.queryParameters());
        if (list) {
            for (int attempt = 0; attempt < 3; attempt++) {
                AppQueryResult candidate = query(
                        chain, EpochParamsContract.LATEST_QUERY_PATH, new byte[0]);
                if (candidate.payload().length == 0) {
                    return json(200, root(candidate)
                            .append(",\"latestEpoch\":null,\"epochs\":[]}").toString());
                }
                long candidateLatest = EpochParamsContract.decodeEpoch(candidate.payload());
                DomainApiResponse response = availableEpochs(request, chain, candidateLatest);
                if (response != null) return response;
            }
            throw new DomainApiException(DomainApiException.Code.BUSY,
                    "Cardano History epoch catalog changed while reading");
        }
        AppQueryResult result = query(chain, EpochParamsContract.LATEST_QUERY_PATH, new byte[0]);
        Long latest = result.payload().length == 0 ? null : EpochParamsContract.decodeEpoch(result.payload());
        StringBuilder body = root(result).append(",\"latestEpoch\":");
        if (latest == null) body.append("null"); else body.append(latest);
        return json(200, body.append('}').toString());
    }

    private DomainApiResponse availableEpochs(DomainApiRequest request, String chain, long candidateLatest) {
        int limit = boundedInt(request.queryParameters(), "limit", 15, 1, 15);
        java.util.ArrayList<AggregateQueryCodecV1.Subquery> queries = new java.util.ArrayList<>();
        queries.add(sub(CardanoHistoryProduct.PARAMS_COMPONENT,
                EpochParamsContract.LATEST_QUERY_PATH, new byte[0]));
        long first = Math.max(0, candidateLatest - limit + 1);
        for (long epoch = candidateLatest; epoch >= first; epoch--) {
            queries.add(sub(CardanoHistoryProduct.PARAMS_COMPONENT,
                    EpochParamsContract.QUERY_PATH, EpochParamsContract.query(epoch)));
        }
        AppQueryResult result = aggregate(chain, queries);
        List<AggregateQueryCodecV1.Result> values = decodeAggregate(result, queries.size());
        byte[] latestBytes = aggregateValue(values, 0, CardanoHistoryProduct.PARAMS_COMPONENT,
                EpochParamsContract.LATEST_QUERY_PATH);
        Long latest = latestBytes.length == 0 ? null : EpochParamsContract.decodeEpoch(latestBytes);
        if (latest == null || latest != candidateLatest) return null;
        StringBuilder body = root(result).append(",\"latestEpoch\":")
                .append(latest == null ? "null" : latest).append(",\"epochs\":[");
        boolean emitted = false;
        for (int index = 1; index < values.size(); index++) {
            byte[] payload = aggregateValue(values, index, CardanoHistoryProduct.PARAMS_COMPONENT,
                    EpochParamsContract.QUERY_PATH);
            if (payload.length != 0) {
                long epoch = candidateLatest - index + 1;
                if (emitted) body.append(',');
                body.append(epoch);
                emitted = true;
            }
        }
        return json(200, body.append("]}").toString());
    }

    private DomainApiResponse parameters(DomainApiRequest request) {
        requireKeys(request.queryParameters(), CHAIN_ONLY);
        long epoch = epoch(request.pathParameters().get("epoch"));
        AppQueryResult result = query(chain(request.queryParameters()), EpochParamsContract.QUERY_PATH,
                EpochParamsContract.query(epoch));
        byte[] localKey = EpochParamsContract.stateKey(epoch);
        return json(200, root(result)
                .append(",\"dataset\":\"protocol-parameters\",\"datasetVersion\":1")
                .append(",\"epoch\":").append(epoch)
                .append(",\"found\":").append(result.payload().length != 0)
                .append(",\"canonicalValueHex\":").append(hexOrNull(result.payload()))
                .append(",\"proof\":").append(primaryProof(localKey)).append('}').toString());
    }

    private DomainApiResponse stake(DomainApiRequest request) {
        requireKeys(request.queryParameters(), CHAIN_ONLY);
        long epoch = epoch(request.pathParameters().get("epoch"));
        int type = discriminator(request.pathParameters().get("credential_type"), 1);
        byte[] hash = fixedHex(request.pathParameters().get("credential_hash"), 28);
        EpochStakeContract.Query fact = new EpochStakeContract.Query(epoch, type, hash);
        AppQueryResult result = aggregate(chain(request.queryParameters()), List.of(
                sub(CardanoHistoryProduct.STAKE_COMPONENT, EpochStakeContract.QUERY_PATH,
                        EpochStakeContract.encodeQuery(fact)),
                sub(CardanoHistoryProduct.STAKE_COMPONENT, EpochStakeContract.META_QUERY_PATH,
                        EpochParamsContract.encodeEpoch(epoch))));
        List<AggregateQueryCodecV1.Result> values = decodeAggregate(result, 2);
        byte[] value = aggregateValue(values, 0, CardanoHistoryProduct.STAKE_COMPONENT,
                EpochStakeContract.QUERY_PATH);
        byte[] metaBytes = aggregateValue(values, 1, CardanoHistoryProduct.STAKE_COMPONENT,
                EpochStakeContract.META_QUERY_PATH);
        EpochStakeContract.Meta meta = metaBytes.length == 0 ? null : EpochStakeContract.decodeMeta(metaBytes);
        requireEpoch(meta == null ? null : meta.manifest().epoch(), epoch);
        EpochStakeContract.Value decoded = value.length == 0 ? null : EpochStakeContract.decodeValue(value);
        StringBuilder body = root(result).append(",\"dataset\":\"epoch-stake\",\"datasetVersion\":1")
                .append(",\"snapshotSemantics\":\"END_OF_EPOCH\",\"datasetEpoch\":").append(epoch)
                .append(",\"complete\":").append(meta != null && meta.complete())
                .append(",\"absenceProvable\":").append(meta != null && meta.complete())
                .append(",\"found\":").append(decoded != null);
        if (decoded != null) body.append(",\"coin\":\"").append(decoded.coin()).append('"')
                .append(",\"poolHash\":\"").append(hex(decoded.poolHash())).append('"');
        else body.append(",\"coin\":null,\"poolHash\":null");
        return json(200, body.append(",\"canonicalValueHex\":").append(hexOrNull(value))
                .append(",\"proof\":").append(snapshotProof(
                        CardanoHistoryProduct.STAKE_COMPONENT + ".distribution",
                        EpochStakeContract.credentialOrderKey(type, hash),
                        EpochStakeContract.metaKey(epoch))).append('}').toString());
    }

    private DomainApiResponse drep(DomainApiRequest request) {
        requireKeys(request.queryParameters(), CHAIN_ONLY);
        long epoch = epoch(request.pathParameters().get("epoch"));
        int type = discriminator(request.pathParameters().get("drep_type"), 1);
        byte[] hash = fixedHex(request.pathParameters().get("drep_hash"), 28);
        var fact = new EpochGovernanceContract.DRepQuery(epoch, type, hash);
        AppQueryResult result = aggregate(chain(request.queryParameters()), List.of(
                sub(CardanoHistoryProduct.GOVERNANCE_COMPONENT, EpochGovernanceContract.DREP_QUERY_PATH,
                        EpochGovernanceContract.encodeDRepQuery(fact)),
                sub(CardanoHistoryProduct.GOVERNANCE_COMPONENT,
                        EpochGovernanceContract.DREP_META_QUERY_PATH,
                        EpochParamsContract.encodeEpoch(epoch))));
        List<AggregateQueryCodecV1.Result> values = decodeAggregate(result, 2);
        byte[] value = aggregateValue(values, 0, CardanoHistoryProduct.GOVERNANCE_COMPONENT,
                EpochGovernanceContract.DREP_QUERY_PATH);
        byte[] metaBytes = aggregateValue(values, 1, CardanoHistoryProduct.GOVERNANCE_COMPONENT,
                EpochGovernanceContract.DREP_META_QUERY_PATH);
        EpochGovernanceContract.DRepMeta meta = metaBytes.length == 0 ? null
                : EpochGovernanceContract.decodeDRepMeta(metaBytes);
        requireEpoch(meta == null ? null : meta.epoch(), epoch);
        BigInteger coin = value.length == 0 ? null : EpochGovernanceContract.decodeCoin(value);
        return json(200, root(result).append(",\"dataset\":\"drep-distribution\",\"datasetVersion\":1")
                .append(",\"snapshotSemantics\":\"START_OF_EPOCH\",\"datasetEpoch\":").append(epoch)
                .append(",\"complete\":").append(meta != null && meta.complete())
                .append(",\"absenceProvable\":").append(meta != null && meta.complete())
                .append(",\"found\":").append(coin != null)
                .append(",\"coin\":").append(coin == null ? "null" : quote(coin.toString()))
                .append(",\"canonicalValueHex\":").append(hexOrNull(value))
                .append(",\"proof\":").append(snapshotProof(
                        CardanoHistoryProduct.GOVERNANCE_COMPONENT + ".drep-distribution",
                        EpochGovernanceContract.drepOrderKey(type, hash),
                        EpochGovernanceContract.drepMetaKey(epoch))).append('}').toString());
    }

    private DomainApiResponse proposal(DomainApiRequest request) {
        requireKeys(request.queryParameters(), PROPOSAL_QUERY);
        long epoch = requiredEpoch(request.queryParameters());
        byte[] txId = fixedHex(request.pathParameters().get("transaction_id"), 32);
        int index = decimalInt(request.pathParameters().get("index"), 0, 65_535);
        var fact = new EpochGovernanceContract.ProposalQuery(epoch, txId, index);
        AppQueryResult result = aggregate(chain(request.queryParameters()), List.of(
                sub(CardanoHistoryProduct.GOVERNANCE_COMPONENT,
                        EpochGovernanceContract.PROPOSAL_QUERY_PATH,
                        EpochGovernanceContract.encodeProposalQuery(fact)),
                sub(CardanoHistoryProduct.GOVERNANCE_COMPONENT,
                        EpochGovernanceContract.PROPOSAL_META_QUERY_PATH,
                        EpochParamsContract.encodeEpoch(epoch))));
        List<AggregateQueryCodecV1.Result> values = decodeAggregate(result, 2);
        byte[] value = aggregateValue(values, 0, CardanoHistoryProduct.GOVERNANCE_COMPONENT,
                EpochGovernanceContract.PROPOSAL_QUERY_PATH);
        byte[] metaBytes = aggregateValue(values, 1, CardanoHistoryProduct.GOVERNANCE_COMPONENT,
                EpochGovernanceContract.PROPOSAL_META_QUERY_PATH);
        var meta = metaBytes.length == 0 ? null : EpochGovernanceContract.decodeProposalMeta(metaBytes);
        requireEpoch(meta == null ? null : meta.epoch(), epoch);
        var decoded = value.length == 0 ? null : EpochGovernanceContract.decodeProposalValue(value);
        StringBuilder body = root(result).append(",\"dataset\":\"proposal-history\",\"datasetVersion\":1")
                .append(",\"datasetEpoch\":").append(epoch)
                .append(",\"complete\":").append(meta != null && meta.complete())
                .append(",\"absenceProvable\":").append(meta != null && meta.complete())
                .append(",\"found\":").append(decoded != null);
        if (decoded != null) body.append(",\"actionType\":").append(quote(decoded.actionType().name()))
                .append(",\"status\":").append(quote(decoded.status().name()))
                .append(",\"reason\":").append(quote(decoded.reason().name()))
                .append(",\"proposedEpoch\":").append(decoded.proposedEpoch())
                .append(",\"expiresAfterEpoch\":").append(decoded.expiresAfterEpoch());
        return json(200, body.append(",\"canonicalValueHex\":").append(hexOrNull(value))
                .append(",\"proof\":").append(directPairProof(
                        EpochGovernanceContract.proposalKey(epoch, txId, index),
                        EpochGovernanceContract.proposalMetaKey(epoch))).append('}').toString());
    }

    private AppQueryResult aggregate(String chain, List<AggregateQueryCodecV1.Subquery> subqueries) {
        return query(chain, AggregateQueryCodecV1.PATH,
                AggregateQueryCodecV1.encodeRequest(subqueries, AggregateQueryLimitsV1.DEFAULT));
    }

    private AppQueryResult query(String chain, String path, byte[] params) {
        try {
            AppQueryResult result = context.queryService().query(chain, path, params);
            if (!chain.equals(result.chainId())
                    || !CardanoHistoryProduct.STATE_MACHINE_ID.equals(result.stateMachineId())) {
                throw new DomainApiException(DomainApiException.Code.FAILED,
                        "Cardano History query identity mismatch");
            }
            return result;
        } catch (AppQueryException failure) {
            throw translate(failure);
        } catch (DomainApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Cardano History query failed", failure);
        }
    }

    private String chain(Map<String, List<String>> parameters) {
        List<String> supplied = parameters.get("chain");
        if (supplied == null || supplied.size() != 1 || supplied.getFirst().isBlank()
                || !context.queryService().chainIds().contains(supplied.getFirst())) throw invalid();
        return supplied.getFirst();
    }

    private static List<AggregateQueryCodecV1.Result> decodeAggregate(AppQueryResult result, int count) {
        try {
            List<AggregateQueryCodecV1.Result> decoded = AggregateQueryCodecV1.decodeResponse(
                    result.payload(), AggregateQueryLimitsV1.DEFAULT);
            if (decoded.size() != count) throw new IllegalArgumentException();
            return decoded;
        } catch (RuntimeException malformed) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Cardano History aggregate response is malformed", malformed);
        }
    }

    private static AggregateQueryCodecV1.Subquery sub(String component, String path, byte[] params) {
        return new AggregateQueryCodecV1.Subquery(component, path, params);
    }

    private static byte[] aggregateValue(List<AggregateQueryCodecV1.Result> values, int index,
                                         String component, String path) {
        AggregateQueryCodecV1.Result value = values.get(index);
        if (!component.equals(value.componentId()) || !path.equals(value.localPath())) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Cardano History aggregate response identity mismatch");
        }
        return value.payload();
    }

    private static void requireEpoch(Long actual, long expected) {
        if (actual != null && actual != expected) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Cardano History completeness epoch mismatch");
        }
    }

    private static StringBuilder root(AppQueryResult result) {
        return new StringBuilder(1_024).append("{\"apiVersion\":").append(API_VERSION)
                .append(",\"chainId\":").append(quote(result.chainId()))
                .append(",\"applicationId\":\"").append(CardanoHistoryProduct.STATE_MACHINE_ID).append('"')
                .append(",\"committedHeight\":").append(result.committedHeight())
                .append(",\"stateRoot\":\"").append(hex(result.stateRoot())).append('"');
    }

    private static String primaryProof(byte[] localKey) {
        return "{\"kind\":\"primary\",\"physicalKey\":\""
                + hex(CompositeStateKeys.componentKey(CardanoHistoryProduct.PARAMS_COMPONENT, localKey)) + "\"}";
    }

    private static String directPairProof(byte[] factKey, byte[] completeKey) {
        return "{\"kind\":\"primary-pair\",\"factPhysicalKey\":\""
                + hex(CompositeStateKeys.componentKey(CardanoHistoryProduct.GOVERNANCE_COMPONENT, factKey))
                + "\",\"completenessPhysicalKey\":\""
                + hex(CompositeStateKeys.componentKey(CardanoHistoryProduct.GOVERNANCE_COMPONENT, completeKey))
                + "\"}";
    }

    private static String snapshotProof(String series, byte[] key, byte[] completeKey) {
        String component = series.startsWith(CardanoHistoryProduct.STAKE_COMPONENT)
                ? CardanoHistoryProduct.STAKE_COMPONENT : CardanoHistoryProduct.GOVERNANCE_COMPONENT;
        return "{\"kind\":\"authenticated-snapshot\",\"seriesId\":" + quote(series)
                + ",\"secondaryKey\":\"" + hex(key) + "\",\"completenessPhysicalKey\":\""
                + hex(CompositeStateKeys.componentKey(component, completeKey)) + "\"}";
    }

    private static DomainApiRoute read(String id, String template) {
        return new DomainApiRoute(id, DomainHttpMethod.GET, template, DomainApiAccess.READ);
    }

    private static void requireKeys(Map<String, List<String>> supplied, Set<String> allowed) {
        if (!allowed.containsAll(supplied.keySet())) throw invalid();
    }

    private static long requiredEpoch(Map<String, List<String>> params) {
        List<String> value = params.get("epoch");
        if (value == null || value.size() != 1) throw invalid();
        return epoch(value.getFirst());
    }

    private static long epoch(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw invalid();
            return parsed;
        } catch (NumberFormatException malformed) {
            throw invalid();
        }
    }

    private static int discriminator(String value, int maximum) {
        if ("key".equals(value)) return 0;
        if ("script".equals(value)) return 1;
        return decimalInt(value, 0, maximum);
    }

    private static int boundedInt(Map<String, List<String>> params, String name,
                                  int fallback, int minimum, int maximum) {
        List<String> value = params.get(name);
        return value == null ? fallback : value.size() == 1
                ? decimalInt(value.getFirst(), minimum, maximum) : failInt();
    }

    private static int decimalInt(String value, int minimum, int maximum) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,9}")) throw invalid();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw invalid();
            return parsed;
        } catch (NumberFormatException malformed) {
            throw invalid();
        }
    }

    private static int failInt() {
        throw invalid();
    }

    private static byte[] fixedHex(String value, int bytes) {
        if (value == null || value.length() != bytes * 2 || !value.matches("[0-9a-f]+")) throw invalid();
        return HexFormat.of().parseHex(value);
    }

    private static String hexOrNull(byte[] value) {
        return value.length == 0 ? "null" : quote(hex(value));
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static DomainApiResponse json(int status, String body) {
        return new DomainApiResponse(status, DomainApiMediaType.JSON,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static DomainApiException translate(AppQueryException failure) {
        DomainApiException.Code code = switch (failure.code()) {
            case INVALID_REQUEST, REQUEST_TOO_LARGE -> DomainApiException.Code.INVALID_REQUEST;
            case UNSUPPORTED -> DomainApiException.Code.NOT_FOUND;
            case BUSY -> DomainApiException.Code.BUSY;
            case TIMEOUT -> DomainApiException.Code.TIMEOUT;
            case RESULT_TOO_LARGE -> DomainApiException.Code.RESULT_TOO_LARGE;
            case UNAVAILABLE -> DomainApiException.Code.UNAVAILABLE;
            case FAILED -> DomainApiException.Code.FAILED;
        };
        return new DomainApiException(code, "Cardano History query failed", failure);
    }

    private static DomainApiException invalid() {
        return new DomainApiException(DomainApiException.Code.INVALID_REQUEST,
                "Invalid Cardano History request");
    }

    private static DomainApiException notFound() {
        return new DomainApiException(DomainApiException.Code.NOT_FOUND,
                "Cardano History route not found");
    }
}
