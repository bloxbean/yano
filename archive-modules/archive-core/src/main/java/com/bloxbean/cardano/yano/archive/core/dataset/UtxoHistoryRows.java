package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.address.StakeAddressCodec;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Stateless row materialization for already-normalized UTXO history facts. */
public final class UtxoHistoryRows {

    private UtxoHistoryRows() {
    }

    public static void emit(ArchiveJob job, BlockSourceContext<UtxoHistoryFact> block,
                            Consumer<ArchiveRow> sink) {
        UtxoHistoryFact facts = block.block();
        Map<String, UtxoHistoryFact.Address> addresses = new HashMap<>();
        for (UtxoHistoryFact.Address address : facts.newAddresses()) {
            if (facts.era() < Era.Conway.getValue()
                    && "pointer".equals(address.stakeReferenceType())) {
                throw new ArchiveStoreException(
                        "pre-Conway pointer address requires capture-time resolution");
            }
            addresses.put(HexUtil.encodeHexString(address.addressKey()), address);
        }

        for (UtxoHistoryFact.Output output : facts.outputs()) {
            UtxoHistoryFact.Address address = addresses.get(
                    HexUtil.encodeHexString(output.addressKey()));
            if (address == null) {
                throw new ArchiveStoreException("missing decoded address for UTXO output");
            }
            byte[] stakeCredential = address.stakeCredential();
            String stakeType = address.stakeCredentialType();
            String stakeAddress = StakeAddressCodec.encode(job.networkIdentity().networkMagic(),
                    stakeType, stakeCredential);
            sink.accept(new ArchiveRow("transaction_outputs", Arrays.asList(
                    output.txHash(), output.outputIndex(), output.txIndex(), output.originType(),
                    address.displayAddress(), address.networkId(), address.addressType(),
                    address.paymentCredentialType(), output.paymentCredential(), stakeAddress,
                    stakeType, stakeCredential, output.lovelace(), output.datumKind(),
                    output.datumHash(), output.inlineDatumCbor(), output.referenceScriptHash(),
                    output.referenceScriptType(), output.referenceScriptCbor(),
                    output.collateralReturn(), block.blockHash(), block.blockNumber(), block.slot(),
                    block.epoch(), block.blockTime().getEpochSecond(), job.jobId())));
        }
        for (UtxoHistoryFact.Asset asset : facts.assets()) {
            sink.accept(new ArchiveRow("transaction_output_assets", Arrays.asList(
                    asset.txHash(), asset.outputIndex(), asset.policyId(), asset.assetName(),
                    asset.quantity(), block.blockNumber(), block.slot(), block.epoch(), job.jobId())));
        }
        for (UtxoHistoryFact.Input input : facts.inputs()) {
            sink.accept(new ArchiveRow("transaction_inputs", Arrays.asList(
                    input.spendingTxHash(), input.spendingTxIndex(), input.inputIndex(),
                    input.inputRole(), input.referencedTxHash(), input.referencedOutputIndex(),
                    input.consumesOutput(), block.blockHash(), block.blockNumber(), block.slot(),
                    block.epoch(), block.blockTime().getEpochSecond(), job.jobId())));
        }
        for (UtxoHistoryFact.TransactionDatum datum : facts.transactionDatums()) {
            sink.accept(new ArchiveRow("transaction_datums", List.of(
                    datum.txHash(), datum.txIndex(), datum.datumHash(), datum.datumCbor(),
                    block.blockHash(), block.blockNumber(), block.slot(), block.epoch(),
                    block.blockTime().getEpochSecond(), job.jobId())));
        }
        for (UtxoHistoryFact.TransactionRedeemer redeemer : facts.transactionRedeemers()) {
            sink.accept(new ArchiveRow("transaction_redeemers", Arrays.asList(
                    redeemer.txHash(), redeemer.txIndex(), redeemer.purpose(),
                    redeemer.redeemerIndex(), redeemer.redeemerCbor(),
                    redeemer.redeemerDataHash(), redeemer.executionMem(),
                    redeemer.executionSteps(), block.blockHash(), block.blockNumber(), block.slot(),
                    block.epoch(), block.blockTime().getEpochSecond(), job.jobId())));
        }
    }
}
