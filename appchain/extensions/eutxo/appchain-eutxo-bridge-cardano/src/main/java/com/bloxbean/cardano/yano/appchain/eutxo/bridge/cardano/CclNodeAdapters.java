package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.model.TxEvaluationResult;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ADR-UTXO-009 SP-M6: adapts the node's own surfaces (core-api
 * {@link UtxoState}, {@link TxEvaluationGateway}, tx submission) to the
 * cardano-client-lib supplier interfaces QuickTx assembly needs — the
 * settlement executor builds its Plutus transactions entirely against the
 * node it runs in, no external backend.
 */
final class CclNodeAdapters {
    private CclNodeAdapters() {
    }

    static UtxoSupplier utxoSupplier(Supplier<UtxoState> view) {
        Objects.requireNonNull(view, "view");
        return new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems,
                                      Integer page, OrderEnum order) {
                UtxoState state = view.get();
                if (state == null) {
                    return List.of();
                }
                int size = nrOfItems != null ? nrOfItems : 100;
                int pageNumber = page != null ? page : 0;
                List<Utxo> converted = new ArrayList<>();
                for (var utxo : state.getUtxosByAddress(
                        address, pageNumber, size)) {
                    converted.add(convert(utxo));
                }
                return converted;
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int index) {
                UtxoState state = view.get();
                if (state == null) {
                    return Optional.empty();
                }
                return state.getUtxo(txHash, index)
                        .map(CclNodeAdapters::convert);
            }
        };
    }

    static ProtocolParamsSupplier protocolParamsSupplier(
            Supplier<ProtocolParams> params) {
        Objects.requireNonNull(params, "params");
        return () -> {
            ProtocolParams current = params.get();
            if (current == null) {
                throw new IllegalStateException(
                        "L1 protocol parameters are not wired yet");
            }
            return current;
        };
    }

    static TransactionProcessor transactionProcessor(
            Function<byte[], String> submitTx,
            Supplier<TxEvaluationGateway> evaluation) {
        Objects.requireNonNull(submitTx, "submitTx");
        Objects.requireNonNull(evaluation, "evaluation");
        return new TransactionProcessor() {
            @Override
            public Result<String> submitTransaction(byte[] cborData) {
                try {
                    String transactionId = submitTx.apply(cborData);
                    return Result.success(transactionId).withValue(transactionId);
                } catch (RuntimeException failure) {
                    return Result.error(String.valueOf(failure.getMessage()));
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public Result<List<EvaluationResult>> evaluateTx(
                    byte[] cbor, Set<Utxo> inputUtxos) {
                TxEvaluationGateway gateway = evaluation.get();
                if (gateway == null) {
                    return Result.error("transaction evaluation is unavailable");
                }
                try {
                    List<EvaluationResult> results = new ArrayList<>();
                    for (TxEvaluationResult result
                            : gateway.evaluateTransaction(cbor)) {
                        EvaluationResult converted = new EvaluationResult();
                        converted.setRedeemerTag(redeemerTag(result.tag()));
                        converted.setIndex(result.index());
                        converted.setExUnits(ExUnits.builder()
                                .mem(BigInteger.valueOf(result.memory()))
                                .steps(BigInteger.valueOf(result.steps()))
                                .build());
                        results.add(converted);
                    }
                    return Result.success("evaluated").withValue(results);
                } catch (Exception failure) {
                    return Result.error(String.valueOf(failure.getMessage()));
                }
            }
        };
    }

    static RedeemerTag redeemerTag(String tag) {
        String normalized = tag == null ? ""
                : tag.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "spend" -> RedeemerTag.Spend;
            case "mint" -> RedeemerTag.Mint;
            case "cert" -> RedeemerTag.Cert;
            case "reward" -> RedeemerTag.Reward;
            default -> throw new IllegalArgumentException(
                    "unsupported redeemer tag: " + tag);
        };
    }

    static Utxo convert(com.bloxbean.cardano.yano.api.utxo.model.Utxo utxo) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(new Amount("lovelace", utxo.lovelace()));
        if (utxo.assets() != null) {
            for (var asset : utxo.assets()) {
                amounts.add(new Amount(
                        asset.policyId() + asset.assetName(), asset.quantity()));
            }
        }
        return Utxo.builder()
                .txHash(utxo.outpoint().txHash())
                .outputIndex(utxo.outpoint().index())
                .address(utxo.address())
                .amount(amounts)
                .dataHash(utxo.datumHash())
                .inlineDatum(utxo.inlineDatum() != null
                        ? HexFormat.of().formatHex(utxo.inlineDatum()) : null)
                .referenceScriptHash(utxo.referenceScriptHash())
                .build();
    }
}
