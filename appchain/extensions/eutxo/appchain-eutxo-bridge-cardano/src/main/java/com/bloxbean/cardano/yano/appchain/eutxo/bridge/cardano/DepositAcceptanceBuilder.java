package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Builds the deterministic unsigned body that accepts one staging output. */
public final class DepositAcceptanceBuilder {
    private DepositAcceptanceBuilder() {
    }

    public static TransactionBody build(
            EutxoOutpoint stagingOutpoint,
            BigInteger stagingLovelace,
            EutxoOutpoint feeOutpoint,
            BigInteger feeInputLovelace,
            BigInteger fee,
            String vaultAddress,
            String changeAddress,
            EutxoVaultDatum datum,
            long currentSlot
    ) {
        Objects.requireNonNull(stagingOutpoint, "stagingOutpoint");
        Objects.requireNonNull(stagingLovelace, "stagingLovelace");
        Objects.requireNonNull(feeOutpoint, "feeOutpoint");
        Objects.requireNonNull(feeInputLovelace, "feeInputLovelace");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(datum, "datum");
        if (!stagingOutpoint.equals(datum.stagingOutpoint())) {
            throw new IllegalArgumentException("datum staging outpoint does not match the input");
        }
        if (stagingOutpoint.equals(feeOutpoint)) {
            throw new IllegalArgumentException("fee input must be separate from the staged deposit");
        }
        if (currentSlot < 0 || currentSlot >= datum.refundDeadline()) {
            throw new IllegalArgumentException(
                    "staging acceptance must be built before its refund deadline");
        }
        BigInteger change = feeInputLovelace.subtract(fee);
        if (stagingLovelace.signum() <= 0 || fee.signum() < 0 || change.signum() < 0) {
            throw new IllegalArgumentException(
                    "the external fee input must cover the fee and the staged value must be positive");
        }
        try {
            java.util.ArrayList<TransactionOutput> outputs = new java.util.ArrayList<>();
            outputs.add(TransactionOutput.builder()
                    .address(Objects.requireNonNull(vaultAddress, "vaultAddress").trim())
                    .value(Value.fromCoin(stagingLovelace))
                    .inlineDatum(PlutusData.deserialize(datum.encode()))
                    .build());
            if (change.signum() > 0) {
                outputs.add(TransactionOutput.builder()
                        .address(Objects.requireNonNull(changeAddress, "changeAddress").trim())
                        .value(Value.fromCoin(change))
                        .build());
            }
            return TransactionBody.builder()
                    .inputs(List.of(
                            new TransactionInput(
                                    stagingOutpoint.transactionId(), stagingOutpoint.index()),
                            new TransactionInput(
                                    feeOutpoint.transactionId(), feeOutpoint.index())))
                    .outputs(List.copyOf(outputs))
                    .fee(fee)
                    .ttl(datum.refundDeadline() - 1)
                    .build();
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "cannot build the staging acceptance transaction", failure);
        }
    }
}
