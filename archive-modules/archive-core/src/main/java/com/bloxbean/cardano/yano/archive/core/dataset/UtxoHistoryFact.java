package com.bloxbean.cardano.yano.archive.core.dataset;

import java.math.BigInteger;
import java.util.List;

public record UtxoHistoryFact(int era, List<PointerRegistration> pointerRegistrations,
                              List<PointerDeregistration> pointerDeregistrations,
                              List<Address> newAddresses, List<Output> outputs,
                              List<Asset> assets, List<Input> inputs,
                              List<TransactionDatum> transactionDatums,
                              List<TransactionRedeemer> transactionRedeemers) {
    public UtxoHistoryFact {
        pointerRegistrations = List.copyOf(pointerRegistrations);
        pointerDeregistrations = List.copyOf(pointerDeregistrations);
        newAddresses = List.copyOf(newAddresses); outputs = List.copyOf(outputs);
        assets = List.copyOf(assets); inputs = List.copyOf(inputs);
        transactionDatums = List.copyOf(transactionDatums);
        transactionRedeemers = List.copyOf(transactionRedeemers);
    }

    public UtxoHistoryFact(int era, List<PointerRegistration> pointerRegistrations,
                           List<Address> newAddresses, List<Output> outputs,
                           List<Asset> assets, List<Input> inputs,
                           List<TransactionDatum> transactionDatums,
                           List<TransactionRedeemer> transactionRedeemers) {
        this(era, pointerRegistrations, List.of(), newAddresses, outputs, assets, inputs,
                transactionDatums, transactionRedeemers);
    }

    public record PointerRegistration(long slot, int txIndex, int certIndex,
                                      String credentialType, byte[] credential) { }
    public record PointerDeregistration(int txIndex, int certIndex,
                                        String credentialType, byte[] credential) { }

    public record Address(byte[] addressKey, byte[] rawAddress, String displayAddress, Integer networkId,
                          String addressType, String paymentCredentialType, byte[] paymentCredential,
                          String stakeReferenceType, String stakeCredentialType, byte[] stakeCredential,
                          Long pointerSlot, Integer pointerTxIndex, Integer pointerCertIndex) { }
    public record Output(byte[] txHash, int outputIndex, int txIndex, String originType, byte[] addressKey,
                         byte[] paymentCredential, byte[] stakeCredential, long lovelace, String datumKind,
                         byte[] datumHash, byte[] inlineDatumCbor, byte[] referenceScriptHash,
                         String referenceScriptType, byte[] referenceScriptCbor,
                         boolean collateralReturn) { }
    public record Asset(byte[] txHash, int outputIndex, byte[] policyId, byte[] assetName, BigInteger quantity) { }
    public record Input(byte[] spendingTxHash, int spendingTxIndex, int inputIndex, String inputRole,
                        byte[] referencedTxHash, int referencedOutputIndex, boolean consumesOutput) { }
    public record TransactionDatum(byte[] txHash, int txIndex, byte[] datumHash, byte[] datumCbor) { }
    public record TransactionRedeemer(byte[] txHash, int txIndex, String purpose, int redeemerIndex,
                                      byte[] redeemerCbor, byte[] redeemerDataHash,
                                      BigInteger executionMem, BigInteger executionSteps) { }
}
