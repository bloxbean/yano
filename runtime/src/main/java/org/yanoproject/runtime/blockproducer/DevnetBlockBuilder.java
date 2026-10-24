package org.yanoproject.runtime.blockproducer;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawBlockBody;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
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

    // BlockHeaderType (used in ChainSync MsgRollForward / SerialisedHeader):
    //   Byron=0, Shelley=1, Allegra=2, Mary=3, Alonzo=4, Babbage=5, Conway=6
    // It differs from the BlockType of the block envelope (Conway=7, RawBlockBody.block) because Byron has two block
    // sub-types (EBB + Main) but one header type. This matches gouroboros: BlockHeaderTypeConway=6.
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
        RawBlockBody body = computeBlockBody(transactions);
        enforceBlockLimits(slot, body.size(), transactions);

        // 2. Build header array: [[header_body], signature]
        Array headerArray = buildHeaderArray(blockNumber, slot, prevHash, body.size(), body.hash());

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

        // The longest prefix within the limits. Each limit grows with the prefix, so the prefix that fits is found by
        // a binary search over its length, each step measuring the exact encoded body.
        BigInteger[] memory = new BigInteger[transactions.size() + 1];
        BigInteger[] steps = new BigInteger[transactions.size() + 1];
        memory[0] = BigInteger.ZERO;
        steps[0] = BigInteger.ZERO;
        for (int i = 0; i < transactions.size(); i++) {
            requireReadable(transactions.get(i));
            ExecutionUnitTotal units = measureExecutionUnits(transactions.get(i));
            memory[i + 1] = memory[i].add(units.memory());
            steps[i + 1] = steps[i].add(units.steps());
        }
        int accepted = 0;
        int rejected = transactions.size() + 1;
        while (rejected - accepted > 1) {
            int count = (accepted + rejected) >>> 1;
            boolean fits = !exceeds(memory[count], limits.maxExecutionMemory())
                    && !exceeds(steps[count], limits.maxExecutionSteps())
                    && RawBlockBody.of(Era.Conway, transactions.subList(0, count)).size() <= limits.maxBodyBytes();
            if (fits) {
                accepted = count;
            } else {
                rejected = count;
            }
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
     * @param body         the block body
     * @param blockNumber  the block number (for logging and result)
     * @param slot         the slot number (for logging and result)
     * @param txCount      transaction count (for logging)
     * @return BlockBuildResult with full block, wrapped header, and computed block hash
     */
    protected BlockBuildResult assembleBlock(Array headerArray, RawBlockBody body,
                                              long blockNumber, long slot, int txCount) {
        // Compute block hash = blake2b-256(serialized header array)
        byte[] headerArrayBytes = CborSerializationUtil.serialize(headerArray);
        byte[] blockHash = Blake2bUtil.blake2bHash256(headerArrayBytes);

        // Block format: [blockType, [header, tx_bodies, witnesses, aux_data, invalid_txs]], blockType 7 for Conway
        // (BlockType numbering includes Byron EBB=0 + Main=1), with every transaction as its original bytes
        byte[] blockCbor = body.block(headerArrayBytes);

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
                blockNumber, slot, txCount, body.size(), HexUtil.encodeHexString(blockHash));

        return new BlockBuildResult(blockCbor, wrappedHeaderCbor, blockHash, blockNumber, slot);
    }

    /**
     * Compute the block body from a list of transactions: each transaction's body, witness set and aux data as its
     * original bytes (CCL {@link RawBlockBody}), never re-serialised, so the block carries every transaction exactly as
     * it was signed.
     *
     * @param transactions list of complete transaction CBOR bytes
     * @return the block body, with the size and hash the header declares
     */
    protected RawBlockBody computeBlockBody(List<byte[]> transactions) {
        List<byte[]> txs = transactions != null ? transactions : List.of();
        txs.forEach(DevnetBlockBuilder::requireReadable);
        RawBlockBody body = RawBlockBody.of(Era.Conway, txs);
        // ADR-056 §6 guard: phase-2-invalid transactions are rejected at admission and never selected, so the body
        // never needs invalid_txs. One that reaches the builder is refused as unfit (the producer invalidates it and
        // its dependents).
        int[] invalid = body.invalidTxIndexes();
        if (invalid.length > 0) {
            String hash = TransactionUtil.getTxHash(txs.get(invalid[0]));
            throw new UnfitBlockTransactionException(hash, "Selected transaction " + hash + " at index " + invalid[0]
                    + " claims isValid=false; the block builder does not forge invalid_txs (ADR-056 §6)");
        }
        return body;
    }

    /**
     * A selected transaction the raw view cannot read as submitted (admission decodes with CCL's model, which accepts
     * some encodings the raw view rejects, such as a repeated map key) is refused as unfit: the producer invalidates
     * it and its dependents instead of failing the same selection every slot.
     */
    private static void requireReadable(byte[] transaction) {
        try {
            RawTx.of(transaction);
        } catch (CborRuntimeException e) {
            String hash = TransactionUtil.getTxHash(transaction);
            throw new UnfitBlockTransactionException(hash, "Selected transaction " + hash
                    + " cannot be put in a block as submitted: " + e.getMessage());
        }
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
}
