package com.bloxbean.cardano.yano.archive.core.dataset;

import java.math.BigInteger;
import java.util.List;

public record UtxoHistoryFact(List<Address> newAddresses, List<Output> outputs,
                              List<Asset> assets, List<Input> inputs,
                              List<Datum> datums, List<Script> scripts) {
    public UtxoHistoryFact {
        newAddresses = List.copyOf(newAddresses); outputs = List.copyOf(outputs);
        assets = List.copyOf(assets); inputs = List.copyOf(inputs);
        datums = List.copyOf(datums); scripts = List.copyOf(scripts);
    }

    public record Address(byte[] addressKey, byte[] rawAddress, String displayAddress, Integer networkId,
                          String addressType, String paymentCredentialType, byte[] paymentCredential,
                          String stakeReferenceType, String stakeCredentialType, byte[] stakeCredential,
                          Long pointerSlot, Integer pointerTxIndex, Integer pointerCertIndex) { }
    public record Output(byte[] txHash, int outputIndex, int txIndex, String originType, byte[] addressKey,
                         byte[] paymentCredential, byte[] stakeCredential, long lovelace, String datumKind,
                         byte[] datumHash, byte[] referenceScriptHash, boolean collateralReturn) { }
    public record Asset(byte[] txHash, int outputIndex, byte[] policyId, byte[] assetName, BigInteger quantity) { }
    public record Input(byte[] spendingTxHash, int spendingTxIndex, int inputIndex, String inputRole,
                        byte[] referencedTxHash, int referencedOutputIndex, boolean consumesOutput) { }
    public record Datum(byte[] hash, byte[] cbor) { }
    public record Script(byte[] hash, String type, byte[] cbor) { }
}
