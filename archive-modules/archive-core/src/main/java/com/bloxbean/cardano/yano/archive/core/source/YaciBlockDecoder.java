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

    /**
     * Decodes stored Byron CBOR. The mapping itself lives in {@link ByronBlockNormalizer}
     * so the replay path and the ADR-039 projection carrier share one definition of what
     * a normalized Byron block is, and cannot drift apart.
     */
    private Block decodeByron(byte[] body, long blockNumber, CanonicalBlockReference reference) {
        if (StoredBlockUtil.requireByronEnvelopeKind(body)
                == StoredBlockUtil.ByronEnvelopeKind.MAIN) {
            var byron = ByronBlockSerializer.INSTANCE.deserialize(body);
            return ByronBlockNormalizer.normalizeMain(byron, blockNumber, reference.blockHash());
        }
        var ebb = ByronEbBlockSerializer.INSTANCE.deserialize(body);
        return ByronBlockNormalizer.normalizeEpochBoundary(ebb, blockNumber, reference.blockHash());
    }
}
