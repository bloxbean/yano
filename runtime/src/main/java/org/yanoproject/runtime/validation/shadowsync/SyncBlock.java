package org.yanoproject.runtime.validation.shadowsync;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The transactions of one stored Shelley-family block, reassembled from the block's own bytes (ADR-056 Phase 7a).
 *
 * <p>A block stores its transactions as four parallel segments: bodies, witness sets, auxiliary data (a map by
 * index) and the indexes of the phase-2-invalid transactions. Each transaction is reassembled exactly as Haskell's
 * segregated-witness decoder does ({@code alonzoSegwitTx}): {@code [body, witnesses, is_valid, auxiliary data or
 * null]}, from the <em>original</em> byte slices of each segment, never a re-encoding (CCL's
 * {@link RawBlock#txBytes}). The body bytes (and so the transaction id, the signatures and the script integrity hash
 * inputs) and the auxiliary-data bytes (its hash) are the ones the block carried; {@code is_valid} is {@code false}
 * exactly for the indexes listed in {@code invalid_transactions}.</p>
 *
 * @param txs          the reassembled transactions, in block order
 * @param txIds        their ids (Blake2b-256 of the original body bytes), hex
 * @param invalidTxs   the indexes listed in {@code invalid_transactions}, ascending
 * @param body         the block body's size and hash, as the header states them and as the body bytes give them;
 *                     {@code null} when the block carries no Alonzo-style header ({@code BBODY} body checks skipped)
 */
public record SyncBlock(List<byte[]> txs, List<String> txIds, Set<Integer> invalidTxs, BodyDigest body) {

    /**
     * The block body's size and hash: Shelley {@code validateBlockBodySize} / {@code validateBlockBodyHash}
     * (Shelley/Rules/Bbody.hs, called from Alonzo {@code alonzoBbodyTransition}, cardano-ledger {@code f649f975}).
     * For an Alonzo-and-later body the size is the length of its four segments as stored
     * ({@code blockBodySize = length . encCBORGroup}, Alonzo/BlockBody/Internal.hs:103) and the hash is
     * {@code blake2b_256(h(bodies) ‖ h(witnesses) ‖ h(auxiliary data) ‖ h(invalid transactions))} over the original
     * segment bytes ({@code hashAlonzoSegWits}, :188-211).
     *
     * @param headerSize the header's {@code block_body_size}
     * @param headerHash the header's {@code block_body_hash}, hex
     * @param actualSize the body's size
     * @param actualHash the body's hash, hex
     */
    public record BodyDigest(long headerSize, String headerHash, long actualSize, String actualHash) {
        public boolean sizeMatches() {
            return headerSize == actualSize;
        }

        public boolean hashMatches() {
            return headerHash.equalsIgnoreCase(actualHash);
        }
    }

    /** A block whose header is not checked. */
    public SyncBlock(List<byte[]> txs, List<String> txIds, Set<Integer> invalidTxs) {
        this(txs, txIds, invalidTxs, null);
    }

    public SyncBlock {
        txs = List.copyOf(Objects.requireNonNull(txs, "txs"));
        txIds = List.copyOf(Objects.requireNonNull(txIds, "txIds"));
        invalidTxs = Set.copyOf(new TreeSet<>(Objects.requireNonNull(invalidTxs, "invalidTxs")));
        if (txs.size() != txIds.size()) {
            throw new IllegalArgumentException("one id per transaction");
        }
    }

    /** @return whether transaction {@code index} is listed in {@code invalid_transactions} */
    public boolean phase2Invalid(int index) {
        return invalidTxs.contains(index);
    }

    public int size() {
        return txs.size();
    }

    /**
     * Parses stored block bytes with CCL's block view ({@link RawBlock}): the network envelope {@code [era, block]}
     * or a bare block {@code [header, bodies, witnesses, auxiliary data, invalid transactions]} (read with Conway's
     * rules; a four-part bare block with Mary's, which have no {@code invalid_transactions}).
     *
     * @throws IllegalArgumentException when the bytes are not a Shelley-family block
     */
    public static SyncBlock parse(byte[] blockBytes) {
        Objects.requireNonNull(blockBytes, "blockBytes");
        try {
            RawBlock block = block(blockBytes);
            Set<Integer> invalid = new TreeSet<>();
            for (int index : block.invalidTxIndexes()) {
                invalid.add(index);
            }
            List<byte[]> txs = new ArrayList<>(block.txCount());
            List<String> ids = new ArrayList<>(block.txCount());
            for (int i = 0; i < block.txCount(); i++) {
                txs.add(block.txBytes(i));
                ids.add(HexUtil.encodeHexString(block.tx(i).txId()));
            }
            return new SyncBlock(txs, ids, invalid, digest(block));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the block bytes do not decode: " + e.getMessage(), e);
        }
    }

    private static RawBlock block(byte[] blockBytes) {
        CborSpan top = CborSpan.of(blockBytes);
        if (top.tag() == -1 && top.majorType() == 4) {
            List<CborSpan> items = top.items();
            boolean envelope = items.size() == 2 && items.get(0).tag() == -1 && items.get(0).majorType() == 0;
            if (!envelope) {
                return switch (items.size()) {
                    case 5 -> RawBlock.of(blockBytes, Era.Conway);
                    case 4 -> RawBlock.of(blockBytes, Era.Mary);
                    default -> throw new IllegalArgumentException("not a Shelley-family block: " + items.size()
                            + " fields");
                };
            }
        }
        return RawBlock.of(blockBytes);
    }

    /**
     * @return the body digest, or {@code null} when the block has no {@code invalid_transactions} or its header body
     *         is not the Babbage/Conway 10-field or the Alonzo-and-earlier 15-field shape
     */
    private static BodyDigest digest(RawBlock block) {
        Optional<CborSpan> invalid = block.invalidTransactions();
        if (invalid.isEmpty()) {
            return null;
        }
        List<CborSpan> header = block.header().items();
        if (header.isEmpty() || header.get(0).tag() != -1 || header.get(0).majorType() != 4) {
            return null;
        }
        List<CborSpan> headerBody = header.get(0).items();
        int sizeIndex = switch (headerBody.size()) {
            case 10 -> 6;   // Babbage, Conway: [.., vrf_result, block_body_size, block_body_hash, ..]
            case 15 -> 7;   // Shelley to Alonzo: [.., nonce_vrf, leader_vrf, block_body_size, block_body_hash, ..]
            default -> -1;
        };
        if (sizeIndex < 0) {
            return null;
        }
        CborSpan size = headerBody.get(sizeIndex);
        CborSpan hash = headerBody.get(sizeIndex + 1);
        if (size.tag() != -1 || size.majorType() != 0 || size.asBigInteger().bitLength() > 63
                || hash.tag() != -1 || hash.majorType() != 2) {
            return null;
        }
        long actualSize = (long) block.transactionBodies().length() + block.transactionWitnessSets().length()
                + block.auxiliaryDataSet().length() + invalid.get().length();
        return new BodyDigest(size.asLong(), HexUtil.encodeHexString(hash.byteString()), actualSize,
                HexUtil.encodeHexString(block.bodyHash()));
    }
}
