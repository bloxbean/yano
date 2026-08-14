package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronBlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronEbBlockSerializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.util.StoredBlockUtil;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;

import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.function.LongUnaryOperator;
import java.util.function.LongFunction;

/** Canonical decoder retaining the complete Yaci block for resolver-dependent projections. */
public final class YaciBlockDecoder implements CanonicalBlockDecoder<Block> {
    private final LongUnaryOperator slotToEpoch;
    private final LongUnaryOperator slotToUnixTime;
    private final LongFunction<Era> storedEra;

    public YaciBlockDecoder(LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime) {
        this(slotToEpoch, slotToUnixTime, ignored -> null);
    }

    public YaciBlockDecoder(LongUnaryOperator slotToEpoch, LongUnaryOperator slotToUnixTime,
                            LongFunction<Era> storedEra) {
        this.slotToEpoch = Objects.requireNonNull(slotToEpoch, "slotToEpoch");
        this.slotToUnixTime = Objects.requireNonNull(slotToUnixTime, "slotToUnixTime");
        this.storedEra = Objects.requireNonNull(storedEra, "storedEra");
    }

    @Override
    public BlockSourceContext<Block> decode(long blockNumber, CanonicalBlockReference reference, byte[] body) {
        try {
            Era era = storedEra.apply(blockNumber);
            Block block = StoredBlockUtil.isStoredByronBlock(era, body)
                    ? decodeByron(body, blockNumber, reference) : BlockSerializer.INSTANCE.deserialize(body);
            if (block.getEra() == null) {
                block = new Block(storedEra.apply(blockNumber), block.getHeader(), block.getTransactionBodies(),
                        block.getTransactionWitness(), block.getAuxiliaryDataMap(),
                        block.getInvalidTransactions(), block.getCbor());
            }
            var header = block.getHeader().getHeaderBody();
            byte[] parent = header.getPrevHash() == null || header.getPrevHash().isBlank()
                    ? new byte[0] : HexUtil.decodeHexString(header.getPrevHash());
            return new BlockSourceContext<>(blockNumber, header.getSlot(), slotToEpoch.applyAsLong(header.getSlot()),
                    Instant.ofEpochSecond(slotToUnixTime.applyAsLong(header.getSlot())), reference.blockHash(),
                    parent, block);
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot decode canonical block " + blockNumber, e);
        }
    }

    private Block decodeByron(byte[] body, long blockNumber, CanonicalBlockReference reference) {
        try {
            var byron = ByronBlockSerializer.INSTANCE.deserialize(body);
            List<com.bloxbean.cardano.yaci.core.model.TransactionBody> transactions =
                    byron.getBody() == null || byron.getBody().getTxPayload() == null
                    ? List.<com.bloxbean.cardano.yaci.core.model.TransactionBody>of()
                    : byron.getBody().getTxPayload().stream().map(payload -> {
                        var tx = payload.getTransaction();
                        var inputs = tx.getInputs() == null ? List.<com.bloxbean.cardano.yaci.core.model.TransactionInput>of()
                                : tx.getInputs().stream().map(input ->
                                com.bloxbean.cardano.yaci.core.model.TransactionInput.builder()
                                        .transactionId(input.getTxId()).index(input.getIndex()).build()).toList();
                        var outputs = tx.getOutputs() == null ? List.<com.bloxbean.cardano.yaci.core.model.TransactionOutput>of()
                                : tx.getOutputs().stream().map(output ->
                                com.bloxbean.cardano.yaci.core.model.TransactionOutput.builder()
                                        .address(output.getAddress().getBase58Raw())
                                        .amounts(List.of(com.bloxbean.cardano.yaci.core.model.Amount.builder()
                                                .unit("lovelace").quantity(output.getAmount()).build()))
                                        .build()).toList();
                        return com.bloxbean.cardano.yaci.core.model.TransactionBody.builder()
                                .txHash(tx.getTxHash()).inputs(new java.util.LinkedHashSet<>(inputs))
                                .outputs(outputs).build();
                    }).toList();
            long slot = byron.getHeader().getConsensusData().getAbsoluteSlot();
            return byronBlock(blockNumber, slot, byron.getHeader().getPrevBlock(), reference, transactions);
        } catch (Exception notMainBlock) {
            var ebb = ByronEbBlockSerializer.INSTANCE.deserialize(body);
            long slot = ebb.getHeader().getConsensusData().getAbsoluteSlot();
            return byronBlock(blockNumber, slot, ebb.getHeader().getPrevBlock(), reference, List.of());
        }
    }

    private Block byronBlock(long blockNumber, long slot, String parent,
                             CanonicalBlockReference reference,
                             List<com.bloxbean.cardano.yaci.core.model.TransactionBody> transactions) {
        var header = com.bloxbean.cardano.yaci.core.model.BlockHeader.builder()
                .headerBody(com.bloxbean.cardano.yaci.core.model.HeaderBody.builder()
                        .blockNumber(blockNumber).slot(slot).prevHash(parent)
                        .blockHash(HexUtil.encodeHexString(reference.blockHash())).build())
                .build();
        return Block.builder().era(Era.Byron).header(header).transactionBodies(transactions)
                .transactionWitness(List.of()).invalidTransactions(List.of()).build();
    }
}
