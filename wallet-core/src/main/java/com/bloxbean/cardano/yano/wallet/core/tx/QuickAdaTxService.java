package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.cip.cip20.MessageMetadata;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.hdwallet.Wallet;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class QuickAdaTxService {
    public QuickAdaTxDraft buildSignedDraft(
            Wallet wallet,
            UtxoSupplier utxoSupplier,
            ProtocolParamsSupplier protocolParamsSupplier,
            TransactionProcessor transactionProcessor,
            String receiverAddress,
            BigInteger lovelace) {
        return buildSignedDraft(
                wallet,
                utxoSupplier,
                protocolParamsSupplier,
                transactionProcessor,
                receiverAddress,
                lovelace,
                List.of(),
                null);
    }

    public QuickAdaTxDraft buildSignedDraft(
            Wallet wallet,
            UtxoSupplier utxoSupplier,
            ProtocolParamsSupplier protocolParamsSupplier,
            TransactionProcessor transactionProcessor,
            String receiverAddress,
            BigInteger lovelace,
            List<Amount> nativeAssets,
            String metadataMessage) {
        return buildSignedDraft(
                wallet,
                utxoSupplier,
                protocolParamsSupplier,
                transactionProcessor,
                List.of(new QuickTxPayment(receiverAddress, lovelace, nativeAssets)),
                metadataMessage);
    }

    public QuickAdaTxDraft buildSignedDraft(
            Wallet wallet,
            UtxoSupplier utxoSupplier,
            ProtocolParamsSupplier protocolParamsSupplier,
            TransactionProcessor transactionProcessor,
            List<QuickTxPayment> payments,
            String metadataMessage) {
        Objects.requireNonNull(wallet, "wallet is required");
        Objects.requireNonNull(utxoSupplier, "utxoSupplier is required");
        Objects.requireNonNull(protocolParamsSupplier, "protocolParamsSupplier is required");
        Objects.requireNonNull(transactionProcessor, "transactionProcessor is required");
        if (payments == null || payments.isEmpty()) {
            throw new IllegalArgumentException("at least one payment is required");
        }
        List<QuickTxPayment> normalizedPayments = payments.stream()
                .filter(Objects::nonNull)
                .toList();
        if (normalizedPayments.isEmpty()) {
            throw new IllegalArgumentException("at least one payment is required");
        }

        QuickTxBuilder builder = new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, transactionProcessor);
        String senderAddress = wallet.getBaseAddressString(0);
        Tx tx = new Tx();
        normalizedPayments.forEach(payment -> tx.payToAddress(payment.receiverAddress(), payment.amounts()));
        tx.from(wallet);
        tx.withChangeAddress(senderAddress);
        String normalizedMetadata = metadataMessage == null ? null : metadataMessage.trim();
        if (normalizedMetadata != null && !normalizedMetadata.isBlank()) {
            tx.attachMetadata(MessageMetadata.create().add(normalizedMetadata));
        }

        Transaction signed = builder.compose(tx)
                .feePayer(wallet)
                .withSigner(SignerProviders.signerFrom(wallet))
                .buildAndSign();

        byte[] cbor;
        try {
            cbor = signed.serialize();
        } catch (CborSerializationException e) {
            throw new IllegalStateException("Unable to serialize signed transaction draft", e);
        }

        return new QuickAdaTxDraft(
                TransactionUtil.getTxHash(signed),
                HexUtil.encodeHexString(cbor),
                senderAddress,
                receiverSummary(normalizedPayments),
                totalLovelace(normalizedPayments),
                signed.getBody().getFee(),
                signed.getBody().getTtl() > 0 ? signed.getBody().getTtl() : null,
                assetSummary(normalizedPayments),
                normalizedMetadata == null || normalizedMetadata.isBlank() ? "" : normalizedMetadata,
                signed.getBody().getInputs() == null ? 0 : signed.getBody().getInputs().size(),
                signed.getBody().getOutputs() == null ? 0 : signed.getBody().getOutputs().size());
    }

    private BigInteger totalLovelace(List<QuickTxPayment> payments) {
        return payments.stream()
                .map(QuickTxPayment::lovelace)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private String receiverSummary(List<QuickTxPayment> payments) {
        return payments.size() == 1 ? payments.getFirst().receiverAddress() : payments.size() + " recipients";
    }

    private String assetSummary(List<QuickTxPayment> payments) {
        Map<String, BigInteger> assets = new LinkedHashMap<>();
        payments.stream()
                .flatMap(payment -> payment.nativeAssets().stream())
                .forEach(amount -> assets.merge(amount.getUnit(), amount.getQuantity(), BigInteger::add));
        return assets.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(", "));
    }
}
