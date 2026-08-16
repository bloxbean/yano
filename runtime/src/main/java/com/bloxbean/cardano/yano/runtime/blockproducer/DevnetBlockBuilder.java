package com.bloxbean.cardano.yano.runtime.blockproducer;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Builds structurally valid Conway-era CBOR blocks for devnet block production.
 * No real cryptography — uses dummy zero-filled byte arrays of correct lengths.
 * <p>
 * Produces two outputs per block:
 * - Full block CBOR for ChainState.storeBlock(): [6, [header, tx_bodies, witnesses, aux_data, invalid_txs]]
 * - Wrapped header CBOR for ChainState.storeBlockHeader(): [6, 24(h'serialized_header_array')]
 */
@Slf4j
public class DevnetBlockBuilder {

    // Cardano N2N wire format uses TWO different era numbering schemes:
    //
    // BlockType (used in BlockFetch MsgBlock / Serialised blk):
    //   Byron EBB=0, Byron Main=1, Shelley=2, Allegra=3, Mary=4, Alonzo=5, Babbage=6, Conway=7
    //
    // BlockHeaderType (used in ChainSync MsgRollForward / SerialisedHeader):
    //   Byron=0, Shelley=1, Allegra=2, Mary=3, Alonzo=4, Babbage=5, Conway=6
    //
    // The difference exists because Byron has 2 block sub-types (EBB + Main) but 1 header type.
    // This matches gouroboros constants: BlockTypeConway=7, BlockHeaderTypeConway=6.
    protected static final int CONWAY_BLOCK_TYPE = 7;       // For BlockFetch blocks
    protected static final int CONWAY_HEADER_TYPE = 6;      // For ChainSync headers
    private static final int HASH_LENGTH = 32;
    private static final int VKEY_LENGTH = 32;
    private static final int VRF_VKEY_LENGTH = 32;
    private static final int VRF_PROOF_LENGTH = 80;
    private static final int VRF_OUTPUT_LENGTH = 64;
    private static final int KES_SIGNATURE_LENGTH = 448;
    private static final int OPCERT_SIGMA_LENGTH = 64;

    // Backward-compatible default for isolated tests and standalone callers.
    // Production Yano wiring passes a runtime ProtocolVersionSupplier.
    private static final long DEFAULT_PROTOCOL_MAJOR = 10;
    private static final long DEFAULT_PROTOCOL_MINOR = 2;

    private final ProtocolVersionSupplier protocolVersionSupplier;
    private final BlockBodySizeLimitSupplier blockBodySizeLimitSupplier;

    public DevnetBlockBuilder() {
        this(DEFAULT_PROTOCOL_MAJOR, DEFAULT_PROTOCOL_MINOR);
    }

    public DevnetBlockBuilder(long protocolMajor, long protocolMinor) {
        this(ProtocolVersionSupplier.fixed(protocolMajor, protocolMinor));
    }

    public DevnetBlockBuilder(ProtocolVersionSupplier protocolVersionSupplier) {
        this(protocolVersionSupplier, BlockBodySizeLimitSupplier.unbounded());
    }

    public DevnetBlockBuilder(ProtocolVersionSupplier protocolVersionSupplier,
                              BlockBodySizeLimitSupplier blockBodySizeLimitSupplier) {
        this.protocolVersionSupplier = Objects.requireNonNull(protocolVersionSupplier,
                "protocolVersionSupplier must not be null");
        this.blockBodySizeLimitSupplier = Objects.requireNonNull(blockBodySizeLimitSupplier,
                "blockBodySizeLimitSupplier must not be null");
    }

    /**
     * Result of building a block: full block CBOR and wrapped header CBOR.
     */
    public record BlockBuildResult(
            byte[] blockCbor,
            byte[] wrappedHeaderCbor,
            byte[] blockHash,
            long blockNumber,
            long slot
    ) {
    }

    /**
     * Build a Conway-era block.
     *
     * @param blockNumber  the block number
     * @param slot         the slot number
     * @param prevHash     the previous block hash (null for genesis)
     * @param transactions list of complete transaction CBOR bytes (each is [body, witnesses, is_valid, aux_data])
     * @return BlockBuildResult with full block, wrapped header, and computed block hash
     */
    public BlockBuildResult buildBlock(long blockNumber, long slot, byte[] prevHash,
                                       List<byte[]> transactions) {
        // 1. Compute block body
        BlockBodyResult body = computeBlockBody(transactions);
        enforceBlockLimits(slot, body.bodySize(), transactions);

        // 2. Build header array: [[header_body], signature]
        Array headerArray = buildHeaderArray(blockNumber, slot, prevHash, body.bodySize(), body.bodyHash());

        // 3-5. Compute block hash, assemble full block and wrapped header
        return assembleBlock(headerArray, body, blockNumber, slot, transactions != null ? transactions.size() : 0);
    }

    /**
     * Return the largest insertion-ordered transaction prefix whose exact encoded block body
     * fits the epoch-effective protocol limit. Transactions outside the prefix remain in the
     * mempool and are eligible for the next block.
     */
    public List<byte[]> fitTransactions(long slot, List<byte[]> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return List.of();
        }

        BlockProductionLimits limits = resolveBlockProductionLimits(slot);
        if (limits.maxBodyBytes() == Long.MAX_VALUE
                && limits.maxExecutionMemory() == null
                && limits.maxExecutionSteps() == null) {
            return List.copyOf(transactions);
        }

        BodySizeAccumulator size = new BodySizeAccumulator();
        int accepted = 0;
        for (byte[] transaction : transactions) {
            TransactionComponentSize componentSize = measureTransaction(transaction, accepted);
            if (!size.canAdd(componentSize, limits)) {
                break;
            }
            size.add(componentSize);
            accepted++;
        }

        if (accepted == transactions.size()) {
            return List.copyOf(transactions);
        }
        if (accepted == 0) {
            throw new UnfitBlockTransactionException(
                    TransactionUtil.getTxHash(transactions.getFirst()));
        }

        log.info("Block resource limits selected {} of {} mempool transactions "
                        + "(maxBlockSize={} bytes, maxBlockExMem={}, maxBlockExSteps={})",
                accepted, transactions.size(), limits.maxBodyBytes(),
                limits.maxExecutionMemory(), limits.maxExecutionSteps());
        return List.copyOf(transactions.subList(0, accepted));
    }

    /**
     * Compute block hash, assemble full block CBOR and wrapped header CBOR from a header array and body.
     *
     * @param headerArray  the CBOR header array [headerBody, signature]
     * @param body         the block body result
     * @param blockNumber  the block number (for logging and result)
     * @param slot         the slot number (for logging and result)
     * @param txCount      transaction count (for logging)
     * @return BlockBuildResult with full block, wrapped header, and computed block hash
     */
    protected BlockBuildResult assembleBlock(Array headerArray, BlockBodyResult body,
                                              long blockNumber, long slot, int txCount) {
        // Compute block hash = blake2b-256(serialized header array)
        byte[] headerArrayBytes = CborSerializationUtil.serialize(headerArray);
        byte[] blockHash = Blake2bUtil.blake2bHash256(headerArrayBytes);

        // Build block content array: [header, tx_bodies, witnesses, aux_data, invalid_txs]
        Array blockContentArray = new Array();
        blockContentArray.add(headerArray);
        blockContentArray.add(body.txBodiesArray());
        blockContentArray.add(body.txWitnessesArray());
        blockContentArray.add(body.auxDataMap());
        blockContentArray.add(body.invalidTxsArray());

        // Block format: [blockType, [header, tx_bodies, witnesses, aux_data, invalid_txs]]
        // blockType=7 for Conway (BlockType numbering includes Byron EBB=0 + Main=1)
        Array fullBlock = new Array();
        fullBlock.add(new UnsignedInteger(CONWAY_BLOCK_TYPE));
        fullBlock.add(blockContentArray);
        byte[] blockCbor = CborSerializationUtil.serialize(fullBlock);

        // Build wrapped header (ChainSync format):
        //    [headerType, 24(h'<serialized_header_array>')]
        //    headerType=6 for Conway (BlockHeaderType numbering, Byron=1 entry)
        Array wrappedHeader = new Array();
        wrappedHeader.add(new UnsignedInteger(CONWAY_HEADER_TYPE));
        ByteString headerByteString = new ByteString(headerArrayBytes);
        headerByteString.setTag(24L);
        wrappedHeader.add(headerByteString);
        byte[] wrappedHeaderCbor = CborSerializationUtil.serialize(wrappedHeader);

        log.debug("Built block #{} at slot {} with {} txs, bodySize={}, blockHash={}",
                blockNumber, slot, txCount, body.bodySize(), HexUtil.encodeHexString(blockHash));

        return new BlockBuildResult(blockCbor, wrappedHeaderCbor, blockHash, blockNumber, slot);
    }

    /**
     * Result of computing the block body from transactions.
     */
    public record BlockBodyResult(
            Array txBodiesArray,
            Array txWitnessesArray,
            Map auxDataMap,
            Array invalidTxsArray,
            long bodySize,
            byte[] bodyHash
    ) {
    }

    /**
     * Compute the block body arrays and body hash from a list of transactions.
     *
     * @param transactions list of complete transaction CBOR bytes
     * @return BlockBodyResult with parallel arrays and computed body hash
     */
    protected BlockBodyResult computeBlockBody(List<byte[]> transactions) {
        Array txBodiesArray = new Array();
        Array txWitnessesArray = new Array();
        Map auxDataMap = new Map();
        Array invalidTxsArray = new Array();

        if (transactions != null) {
            for (int i = 0; i < transactions.size(); i++) {
                splitTransaction(transactions.get(i), i, txBodiesArray, txWitnessesArray, auxDataMap);
            }
        }

        // Alonzo/Conway segregated witness body hash (two-level):
        // 1. Hash each component individually
        // 2. Concatenate the four 32-byte hashes
        // 3. Hash the 128-byte concatenation
        byte[] txBodiesBytes = CborSerializationUtil.serialize(txBodiesArray);
        byte[] txWitnessesBytes = CborSerializationUtil.serialize(txWitnessesArray);
        byte[] auxDataBytes = CborSerializationUtil.serialize(auxDataMap);
        byte[] invalidTxsBytes = CborSerializationUtil.serialize(invalidTxsArray);

        byte[] h1 = Blake2bUtil.blake2bHash256(txBodiesBytes);
        byte[] h2 = Blake2bUtil.blake2bHash256(txWitnessesBytes);
        byte[] h3 = Blake2bUtil.blake2bHash256(auxDataBytes);
        byte[] h4 = Blake2bUtil.blake2bHash256(invalidTxsBytes);
        byte[] combined = new byte[128];
        System.arraycopy(h1, 0, combined, 0, 32);
        System.arraycopy(h2, 0, combined, 32, 32);
        System.arraycopy(h3, 0, combined, 64, 32);
        System.arraycopy(h4, 0, combined, 96, 32);
        byte[] bodyHash = Blake2bUtil.blake2bHash256(combined);
        long bodySize = txBodiesBytes.length + txWitnessesBytes.length + auxDataBytes.length + invalidTxsBytes.length;

        return new BlockBodyResult(txBodiesArray, txWitnessesArray, auxDataMap, invalidTxsArray, bodySize, bodyHash);
    }

    protected long resolveMaxBlockBodySize(long slot) {
        return resolveBlockProductionLimits(slot).maxBodyBytes();
    }

    protected void enforceBlockBodySize(long slot, long bodySize) {
        BlockProductionLimits limits = resolveBlockProductionLimits(slot);
        if (bodySize > limits.maxBodyBytes()) {
            throw new IllegalStateException("Encoded block body size " + bodySize
                    + " exceeds effective maxBlockSize " + limits.maxBodyBytes() + " at slot " + slot);
        }
    }

    protected BlockProductionLimits resolveBlockProductionLimits(long slot) {
        return blockBodySizeLimitSupplier.getLimits(slot);
    }

    protected void enforceBlockLimits(long slot, long bodySize, List<byte[]> transactions) {
        BlockProductionLimits limits = resolveBlockProductionLimits(slot);
        if (bodySize > limits.maxBodyBytes()) {
            throw new IllegalStateException("Encoded block body size " + bodySize
                    + " exceeds effective maxBlockSize " + limits.maxBodyBytes() + " at slot " + slot);
        }

        ExecutionUnitTotal total = measureExecutionUnits(transactions);
        if (exceeds(total.memory(), limits.maxExecutionMemory())) {
            throw new IllegalStateException("Block execution memory " + total.memory()
                    + " exceeds effective maxBlockExMem " + limits.maxExecutionMemory()
                    + " at slot " + slot);
        }
        if (exceeds(total.steps(), limits.maxExecutionSteps())) {
            throw new IllegalStateException("Block execution steps " + total.steps()
                    + " exceeds effective maxBlockExSteps " + limits.maxExecutionSteps()
                    + " at slot " + slot);
        }
    }

    private TransactionComponentSize measureTransaction(byte[] txCbor, int index) {
        try {
            DataItem txDI = CborSerializationUtil.deserializeOne(txCbor);
            Array txArray = (Array) txDI;
            List<DataItem> items = txArray.getDataItems();
            int bodyBytes = CborSerializationUtil.serialize(items.get(0)).length;
            int witnessBytes = CborSerializationUtil.serialize(items.get(1)).length;
            int auxiliaryEntries = 0;
            int auxiliaryBytes = 0;
            if (items.size() > 3 && items.get(3).getMajorType() != MajorType.SPECIAL) {
                auxiliaryEntries = 1;
                auxiliaryBytes = CborSerializationUtil.serialize(new UnsignedInteger(index)).length
                        + CborSerializationUtil.serialize(items.get(3)).length;
            }
            ExecutionUnitTotal executionUnits = measureExecutionUnits(txCbor);
            return new TransactionComponentSize(
                    bodyBytes, witnessBytes, auxiliaryEntries, auxiliaryBytes,
                    executionUnits.memory(), executionUnits.steps());
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to size selected transaction at index " + index, e);
        }
    }

    private record TransactionComponentSize(
            int bodyBytes,
            int witnessBytes,
            int auxiliaryEntries,
            int auxiliaryBytes,
            BigInteger executionMemory,
            BigInteger executionSteps) {
    }

    private static final class BodySizeAccumulator {
        private int transactions;
        private int auxiliaryEntries;
        private long bodyBytes;
        private long witnessBytes;
        private long auxiliaryBytes;
        private BigInteger executionMemory = BigInteger.ZERO;
        private BigInteger executionSteps = BigInteger.ZERO;

        boolean canAdd(TransactionComponentSize addition, BlockProductionLimits limits) {
            int projectedTransactions = transactions + 1;
            int projectedAuxiliaryEntries = auxiliaryEntries + addition.auxiliaryEntries();
            long projected = cborCollectionHeaderSize(projectedTransactions) * 2L
                    + bodyBytes + addition.bodyBytes()
                    + witnessBytes + addition.witnessBytes()
                    + cborCollectionHeaderSize(projectedAuxiliaryEntries)
                    + auxiliaryBytes + addition.auxiliaryBytes()
                    + cborCollectionHeaderSize(0); // invalid transaction index array
            return projected <= limits.maxBodyBytes()
                    && !exceeds(executionMemory.add(addition.executionMemory()),
                    limits.maxExecutionMemory())
                    && !exceeds(executionSteps.add(addition.executionSteps()),
                    limits.maxExecutionSteps());
        }

        void add(TransactionComponentSize addition) {
            transactions++;
            auxiliaryEntries += addition.auxiliaryEntries();
            bodyBytes += addition.bodyBytes();
            witnessBytes += addition.witnessBytes();
            auxiliaryBytes += addition.auxiliaryBytes();
            executionMemory = executionMemory.add(addition.executionMemory());
            executionSteps = executionSteps.add(addition.executionSteps());
        }

        private static int cborCollectionHeaderSize(long entries) {
            if (entries <= 23) return 1;
            if (entries <= 0xffL) return 2;
            if (entries <= 0xffffL) return 3;
            if (entries <= 0xffff_ffffL) return 5;
            return 9;
        }
    }

    private static ExecutionUnitTotal measureExecutionUnits(List<byte[]> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return ExecutionUnitTotal.ZERO;
        }
        BigInteger memory = BigInteger.ZERO;
        BigInteger steps = BigInteger.ZERO;
        for (byte[] transaction : transactions) {
            ExecutionUnitTotal total = measureExecutionUnits(transaction);
            memory = memory.add(total.memory());
            steps = steps.add(total.steps());
        }
        return new ExecutionUnitTotal(memory, steps);
    }

    private static ExecutionUnitTotal measureExecutionUnits(byte[] txCbor) {
        try {
            Transaction transaction = Transaction.deserialize(txCbor);
            if (transaction.getWitnessSet() == null
                    || transaction.getWitnessSet().getRedeemers() == null) {
                return ExecutionUnitTotal.ZERO;
            }
            BigInteger memory = BigInteger.ZERO;
            BigInteger steps = BigInteger.ZERO;
            for (Redeemer redeemer : transaction.getWitnessSet().getRedeemers()) {
                ExUnits exUnits = redeemer != null ? redeemer.getExUnits() : null;
                if (exUnits == null) continue;
                if (exUnits.getMem() != null) memory = memory.add(exUnits.getMem());
                if (exUnits.getSteps() != null) steps = steps.add(exUnits.getSteps());
            }
            if (memory.signum() < 0 || steps.signum() < 0) {
                throw new IllegalArgumentException("negative execution units");
            }
            return new ExecutionUnitTotal(memory, steps);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to read selected transaction execution units", e);
        }
    }

    private static boolean exceeds(BigInteger value, BigInteger limit) {
        return limit != null && value.compareTo(limit) > 0;
    }

    private record ExecutionUnitTotal(BigInteger memory, BigInteger steps) {
        private static final ExecutionUnitTotal ZERO =
                new ExecutionUnitTotal(BigInteger.ZERO, BigInteger.ZERO);
    }

    /**
     * Build the header array: [[header_body_fields...], signature]
     * Post-Babbage header body fields (per BlockHeaderSerializer.postBabbageHeader):
     * [blockNumber, slot, prevHash, issuerVkey, vrfVkey, vrfResult,
     * blockBodySize, blockBodyHash, operationalCert, protocolVersion]
     */
    protected Array buildHeaderArray(long blockNumber, long slot, byte[] prevHash,
                                   long bodySize, byte[] bodyHash) {
        Array headerBody = new Array();

        // 0: blockNumber
        headerBody.add(new UnsignedInteger(blockNumber));
        // 1: slot
        headerBody.add(new UnsignedInteger(slot));
        // 2: prevHash (null for genesis block 0)
        if (prevHash == null) {
            headerBody.add(SimpleValue.NULL);
        } else {
            headerBody.add(new ByteString(prevHash));
        }
        // 3: issuerVkey
        headerBody.add(new ByteString(new byte[VKEY_LENGTH]));
        // 4: vrfVkey
        headerBody.add(new ByteString(new byte[VRF_VKEY_LENGTH]));
        // 5: vrfResult [output, proof]
        Array vrfResult = new Array();
        vrfResult.add(new ByteString(new byte[VRF_OUTPUT_LENGTH]));
        vrfResult.add(new ByteString(new byte[VRF_PROOF_LENGTH]));
        headerBody.add(vrfResult);
        // 6: blockBodySize
        headerBody.add(new UnsignedInteger(bodySize));
        // 7: blockBodyHash
        headerBody.add(new ByteString(bodyHash));
        // 8: operationalCert [hotVKey, sequenceNumber, kesPeriod, sigma]
        Array opCert = new Array();
        opCert.add(new ByteString(new byte[VKEY_LENGTH]));
        opCert.add(new UnsignedInteger(0));
        opCert.add(new UnsignedInteger(0));
        opCert.add(new ByteString(new byte[OPCERT_SIGMA_LENGTH]));
        headerBody.add(opCert);
        // 9: protocolVersion [major, minor]
        ProtocolVersion protocolVersion = resolveProtocolVersion(slot);
        Array protoVersion = new Array();
        protoVersion.add(new UnsignedInteger(protocolVersion.major()));
        protoVersion.add(new UnsignedInteger(protocolVersion.minor()));
        headerBody.add(protoVersion);

        // Header array: [header_body, signature]
        Array headerArray = new Array();
        headerArray.add(headerBody);
        headerArray.add(new ByteString(new byte[KES_SIGNATURE_LENGTH]));

        return headerArray;
    }

    protected ProtocolVersion resolveProtocolVersion(long slot) {
        return protocolVersionSupplier.getProtocolVersion(slot);
    }

    /**
     * Split a complete transaction CBOR into the parallel block arrays.
     * Each tx is CBOR-encoded as: [body, witnesses, is_valid, aux_data]
     */
    protected void splitTransaction(byte[] txCbor, int index,
                                  Array txBodiesArray, Array txWitnessesArray,
                                  Map auxDataMap) {
        try {
            DataItem txDI = CborSerializationUtil.deserializeOne(txCbor);
            Array txArray = (Array) txDI;
            List<DataItem> items = txArray.getDataItems();

            // tx_body (index 0)
            txBodiesArray.add(items.get(0));

            // witnesses (index 1)
            txWitnessesArray.add(items.get(1));

            // is_valid (index 2) - if false, add to invalid_txs
            // For now, assume all txs are valid

            // aux_data (index 3) - add to map if not null
            if (items.size() > 3 && items.get(3).getMajorType() != MajorType.SPECIAL) {
                auxDataMap.put(new UnsignedInteger(index), items.get(3));
            }
        } catch (Exception e) {
            log.warn("Failed to split transaction at index {}: {}", index, e.getMessage());
            // Add empty placeholders to maintain alignment
            txBodiesArray.add(new Map()); // empty tx body
            txWitnessesArray.add(new Map()); // empty witnesses
        }
    }
}
