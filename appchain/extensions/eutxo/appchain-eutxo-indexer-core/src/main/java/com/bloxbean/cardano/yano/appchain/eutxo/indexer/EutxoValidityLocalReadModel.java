package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelHost;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Storage-neutral, bounded view of an optional validity lifecycle. */
public final class EutxoValidityLocalReadModel
        implements LocalReadModelHost.LocalReadModel {
    public static final String MODEL_ID =
            "com.bloxbean.cardano.yano.appchain.eutxo.validity-index";

    private final EutxoValidityIndexSource source;

    public EutxoValidityLocalReadModel(EutxoValidityIndexSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public LocalReadModelResult query(String operation, byte[] boundedRequest) {
        EutxoIndexRequest request = EutxoIndexRequest.decode(boundedRequest);
        String payload = switch (operation) {
            case EutxoLocalReadModel.VALIDITY_BATCHES -> batches(request);
            case EutxoLocalReadModel.VALIDITY_BATCH -> batch(request.id());
            default -> throw new IllegalArgumentException(
                    "unsupported EUTxO validity index operation");
        };
        return new LocalReadModelResult(
                LocalReadModelResult.Status.READY,
                payload.getBytes(StandardCharsets.UTF_8),
                0, 0, "FULL", "");
    }

    private String batches(EutxoIndexRequest request) {
        List<EutxoValidityBatchRecord> batches = source.batches();
        int from = (int) Math.min(request.before(), batches.size());
        int to = Math.min(batches.size(), from + request.limit());
        String items = batches.subList(from, to).stream()
                .map(EutxoValidityLocalReadModel::json)
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"items\":" + items + ",\"cursor\":\""
                + (to < batches.size() ? to : "") + "\"}";
    }

    private String batch(String batchId) {
        return source.batches().stream()
                .filter(value -> value.batchId().equals(batchId))
                .findFirst()
                .map(EutxoValidityLocalReadModel::json)
                .orElse("{\"error\":\"NOT_FOUND\"}");
    }

    private static String json(EutxoValidityBatchRecord value) {
        String transactions = value.transactionIds().stream()
                .map(EutxoValidityLocalReadModel::string)
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"batchId\":" + string(value.batchId())
                + ",\"provider\":" + string(value.provider())
                + ",\"proofSystem\":" + string(value.proofSystem())
                + ",\"profileId\":" + string(value.profileId())
                + ",\"profileDigest\":" + string(value.profileDigest())
                + ",\"transactionIds\":" + transactions
                + ",\"previousRoot\":" + string(value.previousRoot())
                + ",\"nextRoot\":" + string(value.nextRoot())
                + ",\"dataCommitment\":" + string(value.dataCommitment())
                + ",\"dataStatus\":" + string(value.dataStatus())
                + ",\"proofDigest\":" + string(value.proofDigest())
                + ",\"verificationKeyDigest\":"
                + string(value.verificationKeyDigest())
                + ",\"proofStatus\":" + string(value.proofStatus())
                + ",\"settlementStatus\":" + string(value.settlementStatus())
                + ",\"settlementTransactionId\":"
                + string(value.settlementTransactionId())
                + ",\"settlementSlot\":" + value.settlementSlot()
                + ",\"settlementBlockHash\":"
                + string(value.settlementBlockHash()) + "}";
    }

    private static String string(String value) {
        String escaped = Objects.requireNonNullElse(value, "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }
}
