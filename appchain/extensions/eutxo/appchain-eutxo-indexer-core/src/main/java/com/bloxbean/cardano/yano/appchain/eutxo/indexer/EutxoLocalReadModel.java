package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelHost;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelResult;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** JSON-producing read model kept independent of HTTP, JDBC, and Quarkus. */
public final class EutxoLocalReadModel
        implements LocalReadModelHost.LocalReadModel {
    public static final String MODEL_ID =
            "com.bloxbean.cardano.yano.appchain.eutxo.indexer";
    public static final String STATUS = "status";
    public static final String TRANSACTIONS = "transactions";
    public static final String TRANSACTION = "transaction";
    public static final String MESSAGE = "message";
    public static final String ACCOUNT = "account";
    public static final String ACCOUNT_UTXOS = "account-utxos";
    public static final String ACCOUNT_ACTIVITY = "account-activity";
    public static final String DEPOSITS = "deposits";
    public static final String DEPOSIT = "deposit";
    public static final String WITHDRAWALS = "withdrawals";
    public static final String WITHDRAWAL = "withdrawal";
    public static final String LINEAGE = "lineage";
    public static final String VALIDITY_BATCHES = "validity-batches";
    public static final String VALIDITY_BATCH = "validity-batch";

    private final String chainId;
    private final EutxoIndexStore store;
    private final Supplier<IndexHealth> health;
    private final EutxoIndexMetrics metrics;

    public EutxoLocalReadModel(
            String chainId,
            EutxoIndexStore store,
            Supplier<IndexHealth> health
    ) {
        this(chainId, store, health, new EutxoIndexMetrics());
    }

    public EutxoLocalReadModel(
            String chainId,
            EutxoIndexStore store,
            Supplier<IndexHealth> health,
            EutxoIndexMetrics metrics
    ) {
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.store = Objects.requireNonNull(store, "store");
        this.health = Objects.requireNonNull(health, "health");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public LocalReadModelResult query(String operation, byte[] boundedRequest) {
        long started = System.nanoTime();
        try {
            EutxoIndexRequest request = EutxoIndexRequest.decode(boundedRequest);
            IndexHealth current = health.get();
            if (current.status() == IndexHealth.Status.FAILED
                    || current.status() == IndexHealth.Status.IDENTITY_MISMATCH) {
                return result(
                        LocalReadModelResult.Status.FAILED,
                        "{\"error\":\"INDEX_FAILED\"}",
                        current);
            }
            String data = switch (operation) {
                case STATUS -> status(current);
                case TRANSACTIONS -> transactions(request);
                case TRANSACTION -> transaction(request.id());
                case MESSAGE -> message(request.id());
                case ACCOUNT -> account(request, false, false);
                case ACCOUNT_UTXOS -> account(request, true, false);
                case ACCOUNT_ACTIVITY -> account(request, false, true);
                case DEPOSITS -> deposits(request);
                case DEPOSIT -> deposit(request.id());
                case WITHDRAWALS -> withdrawals(request);
                case WITHDRAWAL -> withdrawal(request.id());
                case LINEAGE -> lineage(request);
                case VALIDITY_BATCHES, VALIDITY_BATCH ->
                        "{\"error\":\"CAPABILITY_UNAVAILABLE\"}";
                default -> throw new IllegalArgumentException(
                        "unsupported EUTxO index operation");
            };
            LocalReadModelResult.Status status = current.status()
                    == IndexHealth.Status.READY
                    ? LocalReadModelResult.Status.READY
                    : current.status() == IndexHealth.Status.REBUILDING
                    ? LocalReadModelResult.Status.REBUILDING
                    : LocalReadModelResult.Status.CATCHING_UP;
            return result(status, data, current);
        } finally {
            metrics.recordQuery(System.nanoTime() - started);
        }
    }

    private String transactions(EutxoIndexRequest request) {
        List<EutxoTransactionSummary> selected = new ArrayList<>();
        long cursor = request.before();
        boolean more = false;
        int scanned = 0;
        do {
            int pageSize = Math.min(
                    request.limit() - selected.size(),
                    1_000 - scanned);
            IndexPage<EutxoTransactionSummary> page =
                    store.reader().transactions(cursor, pageSize);
            for (EutxoTransactionSummary summary : page.items()) {
                scanned++;
                if (matches(summary, request)) {
                    selected.add(summary);
                }
                if (selected.size() == request.limit() || scanned == 1_000) {
                    break;
                }
            }
            cursor = page.nextBefore();
            more = page.hasMore();
        } while (selected.size() < request.limit() && more && scanned < 1_000);
        String items = selected.stream().map(EutxoLocalReadModel::summary)
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"items\":" + items
                + ",\"cursor\":" + string(more
                ? EutxoCursorCodec.encode(chainId, TRANSACTIONS, cursor) : "")
                + ",\"scanTruncated\":" + (more && scanned == 1_000) + "}";
    }

    private String transaction(String id) {
        return store.reader().transaction(id)
                .map(EutxoLocalReadModel::summary)
                .orElse("{\"error\":\"NOT_FOUND\"}");
    }

    private String message(String id) {
        return store.reader().message(id)
                .map(EutxoLocalReadModel::summary)
                .orElse("{\"error\":\"NOT_FOUND\"}");
    }

    private String account(
            EutxoIndexRequest request,
            boolean utxosOnly,
            boolean activityOnly
    ) {
        IndexedAccount account =
                store.reader().account(request.address(), request.limit());
        String utxos = account.utxos().stream()
                .map(EutxoLocalReadModel::entry)
                .collect(Collectors.joining(",", "[", "]"));
        String activity = account.activityTransactionIds().stream()
                .map(EutxoLocalReadModel::string)
                .collect(Collectors.joining(",", "[", "]"));
        if (utxosOnly) {
            return "{\"items\":" + utxos + ",\"cursor\":\"\"}";
        }
        if (activityOnly) {
            return "{\"transactionIds\":" + activity
                    + ",\"cursor\":\"\"}";
        }
        return "{\"address\":" + string(account.address())
                + ",\"lovelace\":" + string(account.lovelace().toString())
                + ",\"utxos\":" + utxos
                + ",\"activityTransactionIds\":" + activity + "}";
    }

    private String deposits(EutxoIndexRequest request) {
        IndexPage<EutxoDepositRecord> page =
                store.reader().deposits(request.before(), request.limit());
        String items = page.items().stream()
                .filter(record -> request.address().isEmpty()
                        || request.address().equals(record.claim().l2Address()))
                .map(EutxoLocalReadModel::deposit)
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"items\":" + items + ",\"cursor\":"
                + string(page.hasMore()
                ? EutxoCursorCodec.encode(
                        chainId, DEPOSITS, page.nextBefore()) : "") + "}";
    }

    private String deposit(String id) {
        return store.reader().deposit(id)
                .map(EutxoLocalReadModel::deposit)
                .orElse("{\"error\":\"NOT_FOUND\"}");
    }

    private String withdrawals(EutxoIndexRequest request) {
        IndexPage<EutxoWithdrawalRecord> page =
                store.reader().withdrawals(request.before(), request.limit());
        String items = page.items().stream()
                .filter(record -> request.address().isEmpty()
                        || request.address().equals(
                        record.claim().destinationAddress()))
                .filter(record -> request.status().isEmpty()
                        || request.status().equalsIgnoreCase(
                        record.status().name()))
                .map(EutxoLocalReadModel::withdrawal)
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"items\":" + items + ",\"cursor\":"
                + string(page.hasMore()
                ? EutxoCursorCodec.encode(
                        chainId, WITHDRAWALS, page.nextBefore()) : "") + "}";
    }

    private String withdrawal(String id) {
        return store.reader().withdrawal(id)
                .map(EutxoLocalReadModel::withdrawal)
                .orElse("{\"error\":\"NOT_FOUND\"}");
    }

    private String lineage(EutxoIndexRequest request) {
        EutxoLineage lineage = store.reader().lineage(
                request.id(), request.depth(), request.maximumNodes());
        String nodes = lineage.nodes().stream().map(node ->
                        "{\"kind\":" + string(node.kind())
                                + ",\"id\":" + string(node.id())
                                + ",\"status\":" + string(node.status()) + "}")
                .collect(Collectors.joining(",", "[", "]"));
        String edges = lineage.edges().stream().map(edge ->
                        "{\"from\":" + string(edge.from())
                                + ",\"to\":" + string(edge.to())
                                + ",\"relation\":" + string(edge.relation()) + "}")
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"nodes\":" + nodes + ",\"edges\":" + edges
                + ",\"truncated\":" + lineage.truncated() + "}";
    }

    private static boolean matches(
            EutxoTransactionSummary summary,
            EutxoIndexRequest request
    ) {
        boolean status = request.status().isEmpty()
                || request.status().equalsIgnoreCase(summary.status().name());
        boolean address = request.address().isEmpty()
                || summary.inputs().stream().anyMatch(entry ->
                request.address().equals(entry.address()))
                || summary.outputs().stream().anyMatch(entry ->
                request.address().equals(entry.address()));
        return status && address;
    }

    private LocalReadModelResult result(
            LocalReadModelResult.Status status,
            String data,
            IndexHealth current
    ) {
        return new LocalReadModelResult(
                status,
                data.getBytes(StandardCharsets.UTF_8),
                current.checkpoint().source().appHeight(),
                current.finalizedHeight(),
                current.checkpoint().coverage().name(),
                current.diagnostic());
    }

    private String status(IndexHealth health) {
        return "{\"storeType\":\"jdbc-sqlite\""
                + ",\"checkpointHeight\":"
                + health.checkpoint().source().appHeight()
                + ",\"finalizedHeight\":" + health.finalizedHeight()
                + ",\"lagBlocks\":" + health.lagBlocks()
                + ",\"coverage\":"
                + string(health.checkpoint().coverage().name())
                + ",\"normalizedDigest\":"
                + string(store.reader().normalizedDigest()) + "}";
    }

    private static String summary(EutxoTransactionSummary value) {
        return "{\"transactionId\":" + string(value.transactionId())
                + ",\"messageId\":" + string(value.messageId())
                + ",\"sequence\":" + value.sequence()
                + ",\"appHeight\":" + value.appHeight()
                + ",\"ordinal\":" + value.ordinal()
                + ",\"l1Slot\":" + value.l1Slot()
                + ",\"status\":" + string(value.status().name())
                + ",\"authorizationProfile\":"
                + string(value.authorizationProfile())
                + ",\"inputs\":" + entries(value.inputs())
                + ",\"outputs\":" + entries(value.outputs())
                + ",\"code\":" + string(value.code()) + "}";
    }

    private static String entries(
            List<EutxoTransactionSummary.Entry> values
    ) {
        return values.stream().map(EutxoLocalReadModel::entry)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String entry(EutxoTransactionSummary.Entry value) {
        return "{\"outpoint\":" + string(value.outpoint().toString())
                + ",\"address\":" + string(value.address())
                + ",\"lovelace\":" + string(value.lovelace().toString())
                + "}";
    }

    private static String deposit(EutxoDepositRecord value) {
        return "{\"acceptedOutpoint\":"
                + string(value.claim().acceptedOutpoint().toString())
                + ",\"stagingOutpoint\":"
                + string(value.claim().stagingOutpoint().toString())
                + ",\"mirroredOutpoint\":"
                + string(value.mirroredOutpoint().toString())
                + ",\"l2Address\":" + string(value.claim().l2Address())
                + ",\"l1Slot\":" + value.claim().l1Slot()
                + ",\"l1BlockHash\":"
                + string(java.util.HexFormat.of().formatHex(
                value.claim().l1BlockHash()))
                + ",\"creditedHeight\":" + value.creditedHeight() + "}";
    }

    private static String withdrawal(EutxoWithdrawalRecord value) {
        return "{\"claimId\":" + string(value.claim().claimId())
                + ",\"status\":" + string(value.status().name())
                + ",\"withdrawalOutpoint\":"
                + string(value.claim().withdrawalOutpoint().toString())
                + ",\"destinationAddress\":"
                + string(value.claim().destinationAddress())
                + ",\"lovelace\":"
                + string(value.claim().lovelace().toString())
                + ",\"requestedHeight\":"
                + value.claim().requestedHeight()
                + ",\"settlementTransactionId\":"
                + string(value.settlementTransactionId())
                + ",\"confirmedSlot\":" + value.confirmedSlot()
                + ",\"confirmedBlockHash\":"
                + string(java.util.HexFormat.of().formatHex(
                value.confirmedBlockHash()))
                + ",\"updatedHeight\":" + value.updatedHeight() + "}";
    }

    public static String string(String value) {
        StringBuilder result =
                new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                result.append('\\').append(character);
            } else if (character >= 0x20 && character <= 0x7e) {
                result.append(character);
            } else {
                result.append(String.format(
                        java.util.Locale.ROOT,
                        "\\u%04x", (int) character));
            }
        }
        return result.append('"').toString();
    }
}
