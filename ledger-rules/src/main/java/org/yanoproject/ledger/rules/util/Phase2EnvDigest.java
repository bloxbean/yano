package org.yanoproject.ledger.rules.util;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * {@code ValidationEnv.phase2EnvDigest} (ADR-056 §6): a hash of what a phase-2 verdict depends on besides the
 * resolved inputs.
 *
 * <p>It covers the protocol version, every language's cost model, the ExUnits prices and the per-transaction
 * ExUnits limit. ADR-056 §6 only requires the cost models of the languages a transaction uses; hashing all of
 * them is stricter (any cost-model change invalidates every cached verdict), which is always safe.</p>
 */
public final class Phase2EnvDigest {

    private Phase2EnvDigest() {
    }

    /** @return blake2b-256 of a canonical text rendering of the phase-2 environment */
    public static byte[] of(ProtocolParams params) {
        Objects.requireNonNull(params, "params");
        StringBuilder text = new StringBuilder(256);
        text.append("pv=").append(params.getProtocolMajorVer()).append('.').append(params.getProtocolMinorVer());
        text.append(";prices=").append(plain(params.getPriceMem())).append('/').append(plain(params.getPriceStep()));
        text.append(";maxTx=").append(params.getMaxTxExMem()).append('/').append(params.getMaxTxExSteps());
        text.append(";costModels=");
        Map<String, List<Long>> raw = params.getCostModelsRaw();
        if (raw != null && !raw.isEmpty()) {
            new TreeMap<>(raw).forEach((language, values) -> text.append(language).append(values));
        } else if (params.getCostModels() != null) {
            new TreeMap<>(params.getCostModels()).forEach((language, values) ->
                    text.append(language).append(values != null ? new TreeMap<>(values) : "{}"));
        }
        return Blake2bUtil.blake2bHash256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }
}
