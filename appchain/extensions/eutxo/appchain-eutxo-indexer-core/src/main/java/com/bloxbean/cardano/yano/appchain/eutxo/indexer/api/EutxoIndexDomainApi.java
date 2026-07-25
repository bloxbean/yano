package com.bloxbean.cardano.yano.appchain.eutxo.indexer.api;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelResult;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoCursorCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexRequest;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoLocalReadModel;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned read-only lifecycle API over the bounded local-model facade. */
public final class EutxoIndexDomainApi implements DomainApi {
    private static final String STATUS = "index-status";
    private static final String TRANSACTIONS = "index-transactions";
    private static final String TRANSACTION = "index-transaction";
    private static final String MESSAGE = "index-message";
    private static final String ACCOUNT = "index-account";
    private static final String ACCOUNT_UTXOS = "index-account-utxos";
    private static final String ACCOUNT_ACTIVITY = "index-account-activity";
    private static final String DEPOSITS = "index-deposits";
    private static final String DEPOSIT = "index-deposit";
    private static final String WITHDRAWALS = "index-withdrawals";
    private static final String WITHDRAWAL = "index-withdrawal";
    private static final String LINEAGE = "index-lineage";
    private static final String VALIDITY_BATCHES = "index-validity-batches";
    private static final String VALIDITY_BATCH = "index-validity-batch";

    private static final List<DomainApiRoute> ROUTES = List.of(
            route(STATUS, "index/v1/status"),
            route(TRANSACTIONS, "index/v1/transactions"),
            route(TRANSACTION, "index/v1/transactions/{transaction_id}"),
            route(MESSAGE, "index/v1/messages/{message_id}"),
            route(ACCOUNT, "index/v1/accounts/{address}"),
            route(ACCOUNT_UTXOS, "index/v1/accounts/{address}/utxos"),
            route(ACCOUNT_ACTIVITY, "index/v1/accounts/{address}/activity"),
            route(DEPOSITS, "index/v1/bridge/deposits"),
            route(DEPOSIT,
                    "index/v1/bridge/deposits/{transaction_id}/{output_index}"),
            route(WITHDRAWALS, "index/v1/bridge/withdrawals"),
            route(WITHDRAWAL, "index/v1/bridge/withdrawals/{claim_id}"),
            route(LINEAGE,
                    "index/v1/lineage/outpoints/{transaction_id}/{output_index}"),
            route(VALIDITY_BATCHES, "index/v1/validity/batches"),
            route(VALIDITY_BATCH, "index/v1/validity/batches/{batch_id}"));

    private final DomainApiContext context;

    public EutxoIndexDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<DomainApiRoute> routes() {
        return ROUTES;
    }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        if (request == null || request.method() != DomainHttpMethod.GET
                || ROUTES.stream().noneMatch(route ->
                route.routeId().equals(request.routeId()))) {
            throw invalid();
        }
        String chain = chain(request.queryParameters());
        String operation = operation(request.routeId());
        validateParameters(request.routeId(), request.queryParameters());
        EutxoIndexRequest localRequest = localRequest(
                request, chain, operation);
        LocalReadModelResult result = context.localReadModels().query(
                EutxoLocalReadModel.MODEL_ID,
                chain,
                operation,
                localRequest.encode());
        if (result.status() == LocalReadModelResult.Status.UNAVAILABLE) {
            return error(409, "INDEX_DISABLED");
        }
        String data = new String(result.payload(), StandardCharsets.UTF_8);
        if (data.startsWith("{\"error\":\"NOT_FOUND\"")) {
            return error(404, "NOT_FOUND");
        }
        if (data.startsWith("{\"error\":\"CAPABILITY_UNAVAILABLE\"")) {
            return error(409, "CAPABILITY_UNAVAILABLE");
        }
        if (result.status() == LocalReadModelResult.Status.FAILED) {
            return error(409, "INDEX_FAILED");
        }
        String projectionStatus = result.status().name()
                + "_" + result.coverage();
        String body = "{\"apiVersion\":\"eutxo-index/v1\""
                + ",\"chainId\":" + string(chain)
                + ",\"stateMachineId\":\"eutxo-ledger\""
                + ",\"projection\":{\"kind\":\"DERIVED\""
                + ",\"status\":" + string(projectionStatus)
                + ",\"indexedHeight\":" + result.indexedHeight()
                + ",\"finalizedHeight\":" + result.finalizedHeight()
                + ",\"lagBlocks\":"
                + Math.max(0, result.finalizedHeight()
                - result.indexedHeight())
                + ",\"historyFromHeight\":"
                + (result.indexedHeight() == 0 ? 0 : 1)
                + ",\"fullHistory\":"
                + "FULL".equals(result.coverage()) + "}"
                + ",\"data\":" + data + "}";
        return json(200, body);
    }

    private EutxoIndexRequest localRequest(
            DomainApiRequest request,
            String chain,
            String operation
    ) {
        Map<String, List<String>> query = request.queryParameters();
        int limit = integer(query, "limit", 25, 1, 100);
        int depth = integer(query, "depth", 2, 0, 6);
        long before = EutxoCursorCodec.decode(
                chain, operation, single(query, "cursor", ""));
        String address = request.pathParameters().getOrDefault(
                "address", single(query, "address", ""));
        String id = switch (request.routeId()) {
            case TRANSACTION -> canonicalHash(
                    request.pathParameters().get("transaction_id"));
            case MESSAGE -> canonicalHash(
                    request.pathParameters().get("message_id"));
            case DEPOSIT, LINEAGE -> new EutxoOutpoint(
                    canonicalHash(request.pathParameters().get("transaction_id")),
                    outputIndex(request.pathParameters().get("output_index")))
                    .toString();
            case WITHDRAWAL -> canonicalHash(
                    request.pathParameters().get("claim_id"));
            case VALIDITY_BATCH ->
                    boundedId(request.pathParameters().get("batch_id"));
            default -> "";
        };
        if (LINEAGE.equals(request.routeId())) {
            id = EutxoOutpoint.parse(id).transactionId();
        }
        String status = single(query, "status", "");
        return new EutxoIndexRequest(
                before, limit, depth, 256, id, address, status);
    }

    private String chain(Map<String, List<String>> parameters) {
        List<String> available = context.queryService().chainIds();
        String supplied = single(parameters, "chain", "");
        if (supplied.isEmpty()) {
            if (available.size() != 1) {
                throw invalid();
            }
            return available.getFirst();
        }
        if (!available.contains(supplied)) {
            throw invalid();
        }
        return supplied;
    }

    private static void validateParameters(
            String route,
            Map<String, List<String>> parameters
    ) {
        Set<String> allowed = switch (route) {
            case STATUS, TRANSACTION, MESSAGE, ACCOUNT, DEPOSIT,
                    WITHDRAWAL, VALIDITY_BATCH -> Set.of("chain");
            case TRANSACTIONS -> Set.of(
                    "chain", "limit", "cursor", "address", "status");
            case ACCOUNT_UTXOS -> Set.of("chain", "limit", "cursor");
            case ACCOUNT_ACTIVITY ->
                    Set.of("chain", "limit", "cursor", "type");
            case DEPOSITS, WITHDRAWALS -> Set.of(
                    "chain", "limit", "cursor", "address", "status");
            case LINEAGE -> Set.of("chain", "direction", "depth");
            case VALIDITY_BATCHES ->
                    Set.of("chain", "limit", "cursor", "status");
            default -> Set.of();
        };
        if (!allowed.containsAll(parameters.keySet())) {
            throw invalid();
        }
        String direction = single(parameters, "direction", "both");
        if (LINEAGE.equals(route)
                && !Set.of("both", "ancestors", "descendants")
                .contains(direction)) {
            throw invalid();
        }
    }

    private static String operation(String route) {
        return switch (route) {
            case STATUS -> EutxoLocalReadModel.STATUS;
            case TRANSACTIONS -> EutxoLocalReadModel.TRANSACTIONS;
            case TRANSACTION -> EutxoLocalReadModel.TRANSACTION;
            case MESSAGE -> EutxoLocalReadModel.MESSAGE;
            case ACCOUNT -> EutxoLocalReadModel.ACCOUNT;
            case ACCOUNT_UTXOS -> EutxoLocalReadModel.ACCOUNT_UTXOS;
            case ACCOUNT_ACTIVITY -> EutxoLocalReadModel.ACCOUNT_ACTIVITY;
            case DEPOSITS -> EutxoLocalReadModel.DEPOSITS;
            case DEPOSIT -> EutxoLocalReadModel.DEPOSIT;
            case WITHDRAWALS -> EutxoLocalReadModel.WITHDRAWALS;
            case WITHDRAWAL -> EutxoLocalReadModel.WITHDRAWAL;
            case LINEAGE -> EutxoLocalReadModel.LINEAGE;
            case VALIDITY_BATCHES -> EutxoLocalReadModel.VALIDITY_BATCHES;
            case VALIDITY_BATCH -> EutxoLocalReadModel.VALIDITY_BATCH;
            default -> throw invalid();
        };
    }

    private static int integer(
            Map<String, List<String>> values,
            String name,
            int fallback,
            int minimum,
            int maximum
    ) {
        String supplied = single(values, name, "");
        if (supplied.isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(supplied);
            if (value < minimum || value > maximum) {
                throw invalid();
            }
            return value;
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static String single(
            Map<String, List<String>> values,
            String name,
            String fallback
    ) {
        List<String> supplied = values.get(name);
        if (supplied == null) {
            return fallback;
        }
        if (supplied.size() != 1) {
            throw invalid();
        }
        return supplied.getFirst();
    }

    private static int outputIndex(String value) {
        try {
            int index = Integer.parseInt(value);
            if (index < 0 || index > 65_535) {
                throw invalid();
            }
            return index;
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static String canonicalHash(String value) {
        try {
            byte[] bytes = HexFormat.of().parseHex(value);
            if (bytes.length != 32
                    || !HexFormat.of().formatHex(bytes).equals(value)) {
                throw invalid();
            }
            return value;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid();
        }
    }

    private static String boundedId(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw invalid();
        }
        return value;
    }

    private static DomainApiRoute route(String id, String template) {
        return new DomainApiRoute(
                id, DomainHttpMethod.GET, template,
                DomainApiAccess.READ);
    }

    private static DomainApiResponse error(int status, String code) {
        return json(status, "{\"error\":" + string(code) + "}");
    }

    private static DomainApiResponse json(int status, String body) {
        return new DomainApiResponse(
                status, DomainApiMediaType.JSON,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static DomainApiException invalid() {
        return new DomainApiException(
                DomainApiException.Code.INVALID_REQUEST,
                "Invalid EUTxO index request");
    }

    private static String string(String value) {
        return EutxoLocalReadModel.string(value);
    }
}
