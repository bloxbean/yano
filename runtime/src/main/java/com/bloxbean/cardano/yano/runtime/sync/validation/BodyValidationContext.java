package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.helper.model.Transaction;

import java.util.List;
import java.util.Objects;

/**
 * Typed input for synchronous block-body validation.
 */
public record BodyValidationContext(
        Era era,
        Block block,
        List<Transaction> transactions,
        byte[] blockBytes,
        long slot,
        long blockNumber,
        String blockHash
) {
    public BodyValidationContext {
        era = Objects.requireNonNull(era, "era");
        block = Objects.requireNonNull(block, "block");
        transactions = transactions != null ? List.copyOf(transactions) : List.of();
        blockBytes = Objects.requireNonNull(blockBytes, "blockBytes").clone();
        blockHash = Objects.requireNonNull(blockHash, "blockHash");
    }

    @Override
    public byte[] blockBytes() {
        return blockBytes.clone();
    }
}
