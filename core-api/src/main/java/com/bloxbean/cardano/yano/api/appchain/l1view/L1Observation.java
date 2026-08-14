package com.bloxbean.cardano.yano.api.appchain.l1view;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One canonical L1 observation emitted on {@code ~l1/<observer-id>} and
 * sequenced into an app block. The tagged anchor points either to an L1
 * transaction or to the first applied block boundary of a new L1 epoch.
 * Every member recomputes the observer-specific claim from its own L1 view.
 *
 * <p>Wire format (CBOR, see {@code core-api/src/main/cddl/appchain/
 * l1-observation-v1.cddl}):
 * {@code [1, observer-id, [anchor-tag, anchor-value], slot, block-hash, claim]}.
 * The former transaction-only six-field preview encoding is intentionally
 * unsupported.</p>
 *
 * @param observerId configured observer instance id (topic suffix)
 * @param anchor     tagged transaction or epoch boundary anchor
 * @param slot       L1 slot of the block identified by the anchor
 * @param blockHash  L1 block hash (32B) at that slot
 * @param claim      observer-specific canonical claim CBOR
 */
public record L1Observation(String observerId,
                            Anchor anchor,
                            long slot,
                            byte[] blockHash,
                            byte[] claim) {

    public static final int WIRE_VERSION = 1;
    public static final int TRANSACTION_ANCHOR_TAG = 0;
    public static final int EPOCH_ANCHOR_TAG = 1;
    private static final int HASH_BYTES = 32;
    private static final int MAX_OBSERVER_ID_BYTES = 128;
    private static final CborStructurePreflight.Limits CBOR_LIMITS =
            new CborStructurePreflight.Limits(
                    AppChainConfig.MAX_MESSAGE_BYTES, 4, 16, 8,
                    AppChainConfig.MAX_MESSAGE_BYTES);

    /** Reserved topic prefix for observation messages. */
    public static final String TOPIC_PREFIX = "~l1/";

    public L1Observation {
        observerId = requireObserverId(observerId);
        anchor = Objects.requireNonNull(anchor, "anchor");
        if (slot < 0) {
            throw new IllegalArgumentException("L1 observation slot must not be negative");
        }
        Objects.requireNonNull(blockHash, "blockHash");
        Objects.requireNonNull(claim, "claim");
        if (blockHash.length != HASH_BYTES) {
            throw new IllegalArgumentException("L1 block hash must be 32 bytes");
        }
        blockHash = blockHash.clone();
        claim = claim.clone();
    }

    /** Create a transaction-anchored observation. */
    public static L1Observation transaction(String observerId,
                                            byte[] transactionHash,
                                            long slot,
                                            byte[] blockHash,
                                            byte[] claim) {
        return new L1Observation(observerId, new TransactionAnchor(transactionHash),
                slot, blockHash, claim);
    }

    /** Create an epoch-boundary-anchored observation. */
    public static L1Observation epoch(String observerId,
                                      long newEpoch,
                                      long slot,
                                      byte[] blockHash,
                                      byte[] claim) {
        return new L1Observation(observerId, new EpochAnchor(newEpoch),
                slot, blockHash, claim);
    }

    @Override
    public byte[] blockHash() {
        return blockHash.clone();
    }

    @Override
    public byte[] claim() {
        return claim.clone();
    }

    /** Require the observation to have a transaction anchor. */
    public TransactionAnchor transactionAnchor() {
        if (anchor instanceof TransactionAnchor transactionAnchor) {
            return transactionAnchor;
        }
        throw new IllegalStateException("L1 observation is not transaction-anchored");
    }

    /** Require the observation to have an epoch boundary anchor. */
    public EpochAnchor epochAnchor() {
        if (anchor instanceof EpochAnchor epochAnchor) {
            return epochAnchor;
        }
        throw new IllegalStateException("L1 observation is not epoch-anchored");
    }

    public String topic() {
        return TOPIC_PREFIX + observerId;
    }

    /** Encode the unique canonical v1 representation. */
    public byte[] encode() {
        try {
            Array array = new Array();
            array.add(new UnsignedInteger(WIRE_VERSION));
            array.add(new UnicodeString(observerId));
            array.add(encodeAnchor(anchor));
            array.add(new UnsignedInteger(BigInteger.valueOf(slot)));
            array.add(new ByteString(blockHash));
            array.add(new ByteString(claim));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new CborEncoder(out).encode(array);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("L1 observation encoding failed", e);
        }
    }

    /**
     * @return the decoded observation, or null when the body is not the exact
     * canonical tagged v1 representation
     */
    public static L1Observation decode(byte[] body) {
        try {
            if (!CborStructurePreflight.accepts(body, CBOR_LIMITS)) {
                return null;
            }
            List<DataItem> items = CborDecoder.decode(body);
            if (items.size() != 1 || !(items.getFirst() instanceof Array array)) {
                return null;
            }
            List<DataItem> fields = array.getDataItems();
            if (fields.size() != 6) {
                return null;
            }
            long version = ((UnsignedInteger) fields.get(0)).getValue().longValueExact();
            if (version != WIRE_VERSION) {
                return null;
            }
            Anchor decodedAnchor = decodeAnchor(fields.get(2));
            if (decodedAnchor == null) {
                return null;
            }
            L1Observation observation = new L1Observation(
                    ((UnicodeString) fields.get(1)).getString(),
                    decodedAnchor,
                    ((UnsignedInteger) fields.get(3)).getValue().longValueExact(),
                    ((ByteString) fields.get(4)).getBytes(),
                    ((ByteString) fields.get(5)).getBytes());
            return Arrays.equals(body, observation.encode()) ? observation : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Stable identity for deduplication and verification windows. */
    public String key() {
        return observerId + '/' + anchor.key() + '/' + slot;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof L1Observation that
                && slot == that.slot
                && observerId.equals(that.observerId)
                && anchor.equals(that.anchor)
                && Arrays.equals(blockHash, that.blockHash)
                && Arrays.equals(claim, that.claim);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(observerId, anchor, slot);
        result = 31 * result + Arrays.hashCode(blockHash);
        return 31 * result + Arrays.hashCode(claim);
    }

    private static Array encodeAnchor(Anchor anchor) {
        Array encoded = new Array();
        if (anchor instanceof TransactionAnchor transaction) {
            encoded.add(new UnsignedInteger(TRANSACTION_ANCHOR_TAG));
            encoded.add(new ByteString(transaction.transactionHash()));
        } else if (anchor instanceof EpochAnchor epoch) {
            encoded.add(new UnsignedInteger(EPOCH_ANCHOR_TAG));
            encoded.add(new UnsignedInteger(BigInteger.valueOf(epoch.newEpoch())));
        } else {
            throw new IllegalStateException("Unsupported L1 observation anchor");
        }
        return encoded;
    }

    private static Anchor decodeAnchor(DataItem item) {
        if (!(item instanceof Array array) || array.getDataItems().size() != 2) {
            return null;
        }
        List<DataItem> fields = array.getDataItems();
        long tag = ((UnsignedInteger) fields.get(0)).getValue().longValueExact();
        if (tag == TRANSACTION_ANCHOR_TAG && fields.get(1) instanceof ByteString hash) {
            return new TransactionAnchor(hash.getBytes());
        }
        if (tag == EPOCH_ANCHOR_TAG && fields.get(1) instanceof UnsignedInteger epoch) {
            return new EpochAnchor(epoch.getValue().longValueExact());
        }
        return null;
    }

    private static String requireObserverId(String value) {
        Objects.requireNonNull(value, "observerId");
        int utf8Length = value.getBytes(StandardCharsets.UTF_8).length;
        if (value.isBlank() || utf8Length > MAX_OBSERVER_ID_BYTES || value.indexOf('/') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid L1 observer id");
        }
        return value;
    }

    /** Closed anchor family; new tags require a new wire/API release. */
    public sealed interface Anchor permits TransactionAnchor, EpochAnchor {
        String key();
    }

    /** L1 transaction pointer. */
    public record TransactionAnchor(byte[] transactionHash) implements Anchor {
        public TransactionAnchor {
            Objects.requireNonNull(transactionHash, "transactionHash");
            if (transactionHash.length != HASH_BYTES) {
                throw new IllegalArgumentException("L1 transaction hash must be 32 bytes");
            }
            transactionHash = transactionHash.clone();
        }

        @Override
        public byte[] transactionHash() {
            return transactionHash.clone();
        }

        @Override
        public String key() {
            return "tx:" + HexUtil.encodeHexString(transactionHash);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TransactionAnchor that
                    && Arrays.equals(transactionHash, that.transactionHash);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(transactionHash);
        }
    }

    /** First-applied-block pointer for {@code newEpoch}. */
    public record EpochAnchor(long newEpoch) implements Anchor {
        public EpochAnchor {
            if (newEpoch < 0) {
                throw new IllegalArgumentException("L1 epoch must not be negative");
            }
        }

        @Override
        public String key() {
            return "epoch:" + newEpoch;
        }
    }
}
