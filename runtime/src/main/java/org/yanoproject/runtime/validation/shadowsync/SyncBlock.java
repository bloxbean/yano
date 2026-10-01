package org.yanoproject.runtime.validation.shadowsync;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The transactions of one stored Shelley-family block, reassembled from the block's own bytes (ADR-056 Phase 7a).
 *
 * <p>A block stores its transactions as four parallel segments: bodies, witness sets, auxiliary data (a map by
 * index) and the indexes of the phase-2-invalid transactions. Each transaction is reassembled exactly as Haskell's
 * segregated-witness decoder does ({@code alonzoSegwitTx}): {@code [body, witnesses, is_valid, auxiliary data or
 * null]}, from the <em>original</em> byte slices of each segment, never a re-encoding. The body bytes (and so the
 * transaction id, the signatures and the script integrity hash inputs) and the auxiliary-data bytes (its hash) are
 * the ones the block carried; {@code is_valid} is {@code false} exactly for the indexes listed in
 * {@code invalid_transactions}.</p>
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

    private static final byte ARRAY_OF_FOUR = (byte) 0x84;
    private static final byte TRUE = (byte) 0xf5;
    private static final byte FALSE = (byte) 0xf4;
    private static final byte NULL = (byte) 0xf6;

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
     * Parses stored block bytes: the network envelope {@code [era, block]} or a bare block
     * {@code [header, bodies, witnesses, auxiliary data, invalid transactions]}.
     *
     * @throws IllegalArgumentException when the bytes are not a Shelley-family block
     */
    public static SyncBlock parse(byte[] blockBytes) {
        Objects.requireNonNull(blockBytes, "blockBytes");
        try {
            CborSlice root = CborSlice.first(blockBytes);
            List<CborSlice> items = root.items(MajorType.ARRAY);
            if (items.size() == 2 && items.get(0).type() == MajorType.UNSIGNED_INTEGER) {
                items = items.get(1).items(MajorType.ARRAY);
            }
            if (items.size() < 4) {
                throw new IllegalArgumentException("not a Shelley-family block: " + items.size() + " fields");
            }
            List<CborSlice> bodies = items.get(1).items(MajorType.ARRAY);
            List<CborSlice> witnesses = items.get(2).items(MajorType.ARRAY);
            if (bodies.size() != witnesses.size()) {
                throw new IllegalArgumentException("block has " + bodies.size() + " bodies but " + witnesses.size()
                        + " witness sets");
            }
            Map<Integer, byte[]> auxiliary = new HashMap<>();
            List<CborSlice> auxEntries = items.get(3).items(MajorType.MAP);
            for (int i = 0; i + 1 < auxEntries.size(); i += 2) {
                auxiliary.put(unsigned(auxEntries.get(i)), auxEntries.get(i + 1).bytes());
            }
            Set<Integer> invalid = new TreeSet<>();
            if (items.size() >= 5) {
                for (CborSlice index : items.get(4).items(MajorType.ARRAY)) {
                    invalid.add(unsigned(index));
                }
            }
            List<byte[]> txs = new ArrayList<>(bodies.size());
            List<String> ids = new ArrayList<>(bodies.size());
            for (int i = 0; i < bodies.size(); i++) {
                byte[] body = bodies.get(i).bytes();
                txs.add(assemble(body, witnesses.get(i).bytes(), !invalid.contains(i), auxiliary.get(i)));
                ids.add(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(body)));
            }
            return new SyncBlock(txs, ids, invalid, items.size() >= 5 ? digest(items) : null);
        } catch (CborException | RuntimeException e) {
            if (e instanceof IllegalArgumentException iae) {
                throw iae;
            }
            throw new IllegalArgumentException("the block bytes do not decode: " + e.getMessage(), e);
        }
    }

    /**
     * @return the body digest, or {@code null} when the header body is not the Babbage/Conway 10-field or the
     *         Alonzo-and-earlier 15-field shape
     */
    private static BodyDigest digest(List<CborSlice> block) throws CborException {
        List<CborSlice> header = block.get(0).items(MajorType.ARRAY);
        if (header.isEmpty() || header.get(0).type() != MajorType.ARRAY) {
            return null;
        }
        List<CborSlice> headerBody = header.get(0).items(MajorType.ARRAY);
        int sizeIndex = switch (headerBody.size()) {
            case 10 -> 6;   // Babbage, Conway: [.., vrf_result, block_body_size, block_body_hash, ..]
            case 15 -> 7;   // Shelley to Alonzo: [.., nonce_vrf, leader_vrf, block_body_size, block_body_hash, ..]
            default -> -1;
        };
        if (sizeIndex < 0) {
            return null;
        }
        DataItem size = CborSerializationUtil.deserializeOne(headerBody.get(sizeIndex).bytes());
        DataItem hash = CborSerializationUtil.deserializeOne(headerBody.get(sizeIndex + 1).bytes());
        if (!(size instanceof UnsignedInteger headerSize) || !(hash instanceof ByteString headerHash)) {
            return null;
        }
        long actualSize = 0;
        ByteArrayOutputStream parts = new ByteArrayOutputStream(128);
        for (int i = 1; i <= 4; i++) {
            byte[] segment = block.get(i).bytes();
            actualSize += segment.length;
            parts.writeBytes(Blake2bUtil.blake2bHash256(segment));
        }
        return new BodyDigest(headerSize.getValue().longValueExact(),
                HexUtil.encodeHexString(headerHash.getBytes()), actualSize,
                HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(parts.toByteArray())));
    }

    /** {@code [body, witnesses, is_valid, auxiliary data or null]} from the original segment bytes. */
    static byte[] assemble(byte[] body, byte[] witnesses, boolean isValid, byte[] auxiliary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                2 + body.length + witnesses.length + (auxiliary != null ? auxiliary.length : 1));
        out.write(ARRAY_OF_FOUR);
        out.writeBytes(body);
        out.writeBytes(witnesses);
        out.write(isValid ? TRUE : FALSE);
        if (auxiliary != null) {
            out.writeBytes(auxiliary);
        } else {
            out.write(NULL);
        }
        return out.toByteArray();
    }

    private static int unsigned(CborSlice slice) {
        DataItem item = CborSerializationUtil.deserializeOne(slice.bytes());
        if (!(item instanceof UnsignedInteger value)) {
            throw new IllegalArgumentException("expected an unsigned integer, got " + item);
        }
        return value.getValue().intValueExact();
    }
}
