package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Maps a decoded Byron block into the Shelley {@link Block} model the projection code
 * expects.
 *
 * <p>This is the normalizer ADR-039 requires be <em>retained</em> rather than deleted
 * with the replay workers: it is the only path by which roughly a third of mainnet
 * becomes projectable, and under ADR-039 it runs against an already-decoded block from
 * the projection carrier instead of re-parsing stored CBOR.
 *
 * <p>The mapping is deliberately lossy in exactly the ways Byron is a strict subset:
 *
 * <ul>
 *   <li>no fee is set, so {@code chain_transaction.fee} is {@code NULL} — Byron's
 *       transaction body genuinely does not carry one, and the column is nullable;</li>
 *   <li>addresses keep their raw base58 representation;</li>
 *   <li>there are no invalid transactions, collateral, reference inputs, datums,
 *       redeemers, multi-asset values, or pointer addresses;</li>
 *   <li>an epoch-boundary block normalises to zero transactions while keeping its block
 *       number and parent, which is what lets it emit an empty envelope.</li>
 * </ul>
 */
public final class ByronBlockNormalizer {

    private ByronBlockNormalizer() {}

    public static Block normalizeMain(ByronMainBlock byron, long blockNumber, byte[] blockHash) {
        List<TransactionBody> transactions =
                byron.getBody() == null || byron.getBody().getTxPayload() == null
                        ? List.of()
                        : byron.getBody().getTxPayload().stream().map(payload -> {
                            var tx = payload.getTransaction();
                            var inputs = tx.getInputs() == null ? List.<TransactionInput>of()
                                    : tx.getInputs().stream().map(input -> TransactionInput.builder()
                                            .transactionId(input.getTxId()).index(input.getIndex()).build()).toList();
                            var outputs = tx.getOutputs() == null ? List.<TransactionOutput>of()
                                    : tx.getOutputs().stream().map(output -> TransactionOutput.builder()
                                            .address(output.getAddress().getBase58Raw())
                                            .amounts(List.of(Amount.builder()
                                                    .unit("lovelace").quantity(output.getAmount()).build()))
                                            .build()).toList();
                            return TransactionBody.builder()
                                    .txHash(tx.getTxHash())
                                    .inputs(new LinkedHashSet<>(inputs))
                                    .outputs(outputs)
                                    .build();
                        }).toList();
        return block(blockNumber, byron.getHeader().getConsensusData().getAbsoluteSlot(),
                byron.getHeader().getPrevBlock(), blockHash, transactions);
    }

    public static Block normalizeEpochBoundary(ByronEbBlock ebb, long blockNumber, byte[] blockHash) {
        return block(blockNumber, ebb.getHeader().getConsensusData().getAbsoluteSlot(),
                ebb.getHeader().getPrevBlock(), blockHash, List.of());
    }

    static Block block(long blockNumber, long slot, String parentHash, byte[] blockHash,
                       List<TransactionBody> transactions) {
        var header = BlockHeader.builder()
                .headerBody(HeaderBody.builder()
                        .blockNumber(blockNumber).slot(slot).prevHash(parentHash)
                        .blockHash(HexUtil.encodeHexString(blockHash)).build())
                .build();
        return Block.builder().era(Era.Byron).header(header).transactionBodies(transactions)
                .transactionWitness(List.of()).invalidTransactions(List.of()).build();
    }
}
