package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.*;

public final class UtxoHistoryDataset implements BlockArchiveDataset<UtxoHistoryFact> {
    @Override public ArchiveDatasetId dataset() { return ArchiveDatasetId.UTXO_HISTORY; }
    @Override public int projectionVersion() { return 1; }

    @Override
    public void derive(ArchiveJob job, BlockSourceContext<UtxoHistoryFact> block,
                       java.util.function.Consumer<ArchiveRow> sink) {
        var facts = block.block();
        for (var address : facts.newAddresses()) sink.accept(new ArchiveRow("addresses", java.util.Arrays.asList(
                address.addressKey(), address.rawAddress(), address.displayAddress(), address.networkId(),
                address.addressType(), address.paymentCredentialType(), address.paymentCredential(),
                address.stakeReferenceType(), address.stakeCredentialType(), address.stakeCredential(),
                address.pointerSlot(), address.pointerTxIndex(), address.pointerCertIndex(),
                block.blockNumber(), block.slot(), block.epoch())));
        for (var output : facts.outputs()) sink.accept(new ArchiveRow("transaction_outputs", java.util.Arrays.asList(
                output.txHash(), output.outputIndex(), output.txIndex(), output.originType(), output.addressKey(),
                output.paymentCredential(), output.stakeCredential(), output.lovelace(), output.datumKind(),
                output.datumHash(), output.referenceScriptHash(), output.collateralReturn(), block.blockHash(),
                block.blockNumber(), block.slot(), block.epoch(), block.blockTime().getEpochSecond(), job.jobId())));
        for (var asset : facts.assets()) sink.accept(new ArchiveRow("transaction_output_assets", java.util.Arrays.asList(
                asset.txHash(), asset.outputIndex(), asset.policyId(), asset.assetName(), asset.quantity(),
                block.blockNumber(), block.slot(), block.epoch(), job.jobId())));
        for (var input : facts.inputs()) sink.accept(new ArchiveRow("transaction_inputs", java.util.Arrays.asList(
                input.spendingTxHash(), input.spendingTxIndex(), input.inputIndex(), input.inputRole(),
                input.referencedTxHash(), input.referencedOutputIndex(), input.consumesOutput(), block.blockHash(),
                block.blockNumber(), block.slot(), block.epoch(), block.blockTime().getEpochSecond(), job.jobId())));
        for (var datum : facts.datums()) sink.accept(new ArchiveRow("datums", java.util.List.of(datum.hash(), datum.cbor())));
        for (var script : facts.scripts()) sink.accept(new ArchiveRow("scripts", java.util.List.of(script.hash(), script.type(), script.cbor())));
    }
}
