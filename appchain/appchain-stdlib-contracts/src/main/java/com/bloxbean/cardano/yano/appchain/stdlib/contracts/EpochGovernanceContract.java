package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.HistoryContractCbor;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Canonical proposal-lifecycle and DRep-distribution history contract. */
public final class EpochGovernanceContract {
    public static final int VERSION = 1;
    public static final int HEADER = 0;
    public static final int PROPOSAL = 1;
    public static final int DREP_CHUNK = 2;
    public static final int DEFAULT_DREP_CHUNK_ENTRIES = 25_000;
    public static final int MAX_DREP_CHUNK_ENTRIES = 25_000;
    public static final String STATE_MACHINE_ID = "epoch-governance";
    public static final String OBSERVER_TYPE = "l1-epoch-governance-v1";
    public static final String DEFAULT_OBSERVER_ID = "epoch-governance";
    public static final String PROPOSAL_PROOF_SUBJECT = "cardano-history/governance-proposal-v1";
    public static final String DREP_PROOF_SUBJECT = "cardano-history/drep-distribution-v1";
    public static final String PROPOSAL_QUERY_PATH = "epoch-governance/proposal";
    public static final String DREP_QUERY_PATH = "epoch-governance/drep";
    public static final String PROPOSAL_META_QUERY_PATH = "epoch-governance/proposals/meta";
    public static final String DREP_META_QUERY_PATH = "epoch-governance/dreps/meta";
    private static final byte[] PROPOSAL_ROOT_DOMAIN = "yano-governance-proposals-v1\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DREP_ROOT_DOMAIN = "yano-governance-dreps-v1\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OUTER_ROOT_DOMAIN = "yano-governance-observation-v1\0"
            .getBytes(StandardCharsets.US_ASCII);

    private EpochGovernanceContract() { }

    public enum ActionType {
        PARAMETER_CHANGE, HARD_FORK_INITIATION, TREASURY_WITHDRAWALS,
        NO_CONFIDENCE, UPDATE_COMMITTEE, NEW_CONSTITUTION, INFO_ACTION
    }
    public enum ProposalStatus { ACTIVE, RATIFIED, ENACTED, EXPIRED, DROPPED }
    public enum ProposalReason { NONE, RATIFIED, ENACTED, EXPIRED, SUPERSEDED, INVALIDATED, REMOVED }

    public record Header(long epoch, boolean includeProposals, long proposalCount,
                         byte[] proposalRoot, boolean includeDReps, long drepCount,
                         int drepChunkEntries, int drepChunkCount, byte[] drepRoot) {
        public Header {
            if (epoch < 0 || proposalCount < 0 || drepCount < 0
                    || proposalRoot == null || proposalRoot.length != 32
                    || drepRoot == null || drepRoot.length != 32
                    || drepChunkEntries < 0 || drepChunkEntries > MAX_DREP_CHUNK_ENTRIES
                    || drepChunkCount < 0
                    || (includeDReps && drepChunkEntries == 0 && drepCount > 0)
                    || (!includeProposals && proposalCount != 0)
                    || (!includeDReps && (drepCount != 0 || drepChunkCount != 0))
                    || drepChunkCount != chunks(drepCount, drepChunkEntries)) {
                throw new IllegalArgumentException("invalid epoch-governance header");
            }
            proposalRoot = proposalRoot.clone();
            drepRoot = drepRoot.clone();
        }
        @Override public byte[] proposalRoot() { return proposalRoot.clone(); }
        @Override public byte[] drepRoot() { return drepRoot.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Header that && epoch == that.epoch
                    && includeProposals == that.includeProposals && proposalCount == that.proposalCount
                    && includeDReps == that.includeDReps && drepCount == that.drepCount
                    && drepChunkEntries == that.drepChunkEntries && drepChunkCount == that.drepChunkCount
                    && Arrays.equals(proposalRoot, that.proposalRoot) && Arrays.equals(drepRoot, that.drepRoot);
        }
        @Override public int hashCode() {
            return 31 * Objects.hash(epoch, includeProposals, proposalCount, includeDReps,
                    drepCount, drepChunkEntries, drepChunkCount)
                    + Arrays.hashCode(proposalRoot) + Arrays.hashCode(drepRoot);
        }
    }

    public record Proposal(long epoch, byte[] transactionId, int governanceActionIndex,
                           ActionType actionType, ProposalStatus status, ProposalReason reason,
                           long proposedEpoch, long expiresAfterEpoch) {
        public Proposal {
            if (epoch < 0 || transactionId == null || transactionId.length != 32
                    || governanceActionIndex < 0 || governanceActionIndex > 0xFFFF
                    || actionType == null || status == null || reason == null
                    || proposedEpoch < 0 || expiresAfterEpoch < proposedEpoch) {
                throw new IllegalArgumentException("invalid governance proposal claim");
            }
            transactionId = transactionId.clone();
        }
        @Override public byte[] transactionId() { return transactionId.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Proposal that && epoch == that.epoch
                    && governanceActionIndex == that.governanceActionIndex
                    && proposedEpoch == that.proposedEpoch && expiresAfterEpoch == that.expiresAfterEpoch
                    && actionType == that.actionType && status == that.status && reason == that.reason
                    && Arrays.equals(transactionId, that.transactionId);
        }
        @Override public int hashCode() { return 31 * Objects.hash(epoch, governanceActionIndex,
                actionType, status, reason, proposedEpoch, expiresAfterEpoch) + Arrays.hashCode(transactionId); }
    }

    public record DRepEntry(int drepType, byte[] drepHash, BigInteger coin) {
        public DRepEntry {
            if (drepType < 0 || drepType > 1 || drepHash == null || drepHash.length != 28
                    || coin == null || coin.signum() < 0) throw new IllegalArgumentException("invalid DRep entry");
            drepHash = drepHash.clone();
        }
        @Override public byte[] drepHash() { return drepHash.clone(); }
        @Override public boolean equals(Object other) { return other instanceof DRepEntry that
                && drepType == that.drepType && coin.equals(that.coin) && Arrays.equals(drepHash, that.drepHash); }
        @Override public int hashCode() { return 31 * Objects.hash(drepType, coin) + Arrays.hashCode(drepHash); }
    }

    public record DRepChunk(long epoch, byte[] distributionRoot, int index, List<DRepEntry> entries) {
        public DRepChunk {
            if (epoch < 0 || distributionRoot == null || distributionRoot.length != 32 || index < 0
                    || entries == null || entries.size() > MAX_DREP_CHUNK_ENTRIES)
                throw new IllegalArgumentException("invalid DRep chunk");
            distributionRoot = distributionRoot.clone();
            entries = List.copyOf(entries);
            requireCanonicalDReps(entries);
        }
        @Override public byte[] distributionRoot() { return distributionRoot.clone(); }
        @Override public boolean equals(Object other) { return other instanceof DRepChunk that
                && epoch == that.epoch && index == that.index && entries.equals(that.entries)
                && Arrays.equals(distributionRoot, that.distributionRoot); }
        @Override public int hashCode() { return 31 * Objects.hash(epoch, index, entries)
                + Arrays.hashCode(distributionRoot); }
    }

    public record ProposalMeta(long epoch, long total, byte[] root, long received, boolean complete) {
        public ProposalMeta {
            if (epoch < 0 || total < 0 || received < 0 || received > total
                    || complete != (received == total) || root == null || root.length != 32)
                throw new IllegalArgumentException("invalid proposal metadata");
            root = root.clone();
        }
        @Override public byte[] root() { return root.clone(); }
        @Override public boolean equals(Object other) { return other instanceof ProposalMeta that
                && epoch == that.epoch && total == that.total && received == that.received
                && complete == that.complete && Arrays.equals(root, that.root); }
        @Override public int hashCode() { return 31 * Objects.hash(epoch, total, received, complete)
                + Arrays.hashCode(root); }
    }
    public record DRepMeta(long epoch, long total, int chunkEntries, int chunkCount,
                           byte[] root, int receivedChunks, boolean complete) {
        public DRepMeta {
            if (epoch < 0 || total < 0 || chunkEntries < 0 || chunkEntries > MAX_DREP_CHUNK_ENTRIES
                    || chunkCount != chunks(total, chunkEntries) || receivedChunks < 0
                    || receivedChunks > chunkCount || complete != (receivedChunks == chunkCount)
                    || root == null || root.length != 32) throw new IllegalArgumentException("invalid DRep metadata");
            root = root.clone();
        }
        @Override public byte[] root() { return root.clone(); }
        @Override public boolean equals(Object other) { return other instanceof DRepMeta that
                && epoch == that.epoch && total == that.total && chunkEntries == that.chunkEntries
                && chunkCount == that.chunkCount && receivedChunks == that.receivedChunks
                && complete == that.complete && Arrays.equals(root, that.root); }
        @Override public int hashCode() { return 31 * Objects.hash(epoch, total, chunkEntries,
                chunkCount, receivedChunks, complete) + Arrays.hashCode(root); }
    }
    public record ProposalQuery(long epoch, byte[] transactionId, int governanceActionIndex) {
        public ProposalQuery { new Proposal(epoch, transactionId, governanceActionIndex, ActionType.INFO_ACTION,
                ProposalStatus.ACTIVE, ProposalReason.NONE, 0, 0); transactionId = transactionId.clone(); }
        @Override public byte[] transactionId() { return transactionId.clone(); }
    }
    public record DRepQuery(long epoch, int drepType, byte[] drepHash) {
        public DRepQuery { new DRepEntry(drepType, drepHash, BigInteger.ZERO); if (epoch < 0)
            throw new IllegalArgumentException("invalid DRep query"); drepHash = drepHash.clone(); }
        @Override public byte[] drepHash() { return drepHash.clone(); }
    }
    public record ProposalValue(ActionType actionType, ProposalStatus status, ProposalReason reason,
                                long proposedEpoch, long expiresAfterEpoch) { }

    public static byte[] encodeHeader(Header h) {
        Array a = prefix(HEADER); a.add(new UnsignedInteger(h.epoch()));
        a.add(bool(h.includeProposals())); a.add(new UnsignedInteger(h.proposalCount()));
        a.add(new ByteString(h.proposalRoot())); a.add(bool(h.includeDReps()));
        a.add(new UnsignedInteger(h.drepCount())); a.add(new UnsignedInteger(h.drepChunkEntries()));
        a.add(new UnsignedInteger(h.drepChunkCount())); a.add(new ByteString(h.drepRoot()));
        return HistoryContractCbor.encode(a);
    }
    public static Header decodeHeader(byte[] bytes) {
        Array a = HistoryContractCbor.decodeArray(bytes, 11); requireTag(a, HEADER);
        return new Header(u(a, 2), bool(a, 3), u(a, 4), b(a, 5, 32), bool(a, 6),
                u(a, 7), ui(a, 8), ui(a, 9), b(a, 10, 32));
    }
    public static byte[] encodeProposal(Proposal p) {
        Array a = prefix(PROPOSAL); a.add(new UnsignedInteger(p.epoch()));
        a.add(new ByteString(p.transactionId())); a.add(new UnsignedInteger(p.governanceActionIndex()));
        a.add(new UnsignedInteger(p.actionType().ordinal())); a.add(new UnsignedInteger(p.status().ordinal()));
        a.add(new UnsignedInteger(p.reason().ordinal())); a.add(new UnsignedInteger(p.proposedEpoch()));
        a.add(new UnsignedInteger(p.expiresAfterEpoch())); return HistoryContractCbor.encode(a);
    }
    public static Proposal decodeProposal(byte[] bytes) {
        Array a = HistoryContractCbor.decodeArray(bytes, 10); requireTag(a, PROPOSAL);
        return new Proposal(u(a, 2), b(a, 3, 32), ui(a, 4), enumAt(ActionType.values(), ui(a, 5)),
                enumAt(ProposalStatus.values(), ui(a, 6)), enumAt(ProposalReason.values(), ui(a, 7)),
                u(a, 8), u(a, 9));
    }
    public static byte[] encodeDRepChunk(DRepChunk c) {
        Array entries = new Array();
        for (DRepEntry e : c.entries()) { Array x = new Array(); x.add(new UnsignedInteger(e.drepType()));
            x.add(new ByteString(e.drepHash())); x.add(new UnsignedInteger(e.coin())); entries.add(x); }
        Array a = prefix(DREP_CHUNK); a.add(new UnsignedInteger(c.epoch()));
        a.add(new ByteString(c.distributionRoot())); a.add(new UnsignedInteger(c.index())); a.add(entries);
        return HistoryContractCbor.encode(a);
    }
    public static DRepChunk decodeDRepChunk(byte[] bytes) {
        Array a = HistoryContractCbor.decodeArray(bytes, 6); requireTag(a, DREP_CHUNK);
        Array xs = HistoryContractCbor.array(a.getDataItems().get(5), MAX_DREP_CHUNK_ENTRIES);
        List<DRepEntry> entries = new ArrayList<>(xs.getDataItems().size());
        for (var item : xs.getDataItems()) { Array x = HistoryContractCbor.array(item, 3);
            if (x.getDataItems().size() != 3) throw HistoryContractCbor.malformed();
            entries.add(new DRepEntry(ui(x, 0), b(x, 1, 28), unsigned(x, 2))); }
        return new DRepChunk(u(a, 2), b(a, 3, 32), ui(a, 4), entries);
    }
    public static int claimType(byte[] bytes) { Array a = HistoryContractCbor.decodeArray(bytes, 6, 10, 11);
        if (ui(a, 0) != VERSION) throw HistoryContractCbor.malformed(); return ui(a, 1); }

    public static byte[] encodeProposalValue(Proposal p) {
        Array a = new Array(); a.add(new UnsignedInteger(p.actionType().ordinal()));
        a.add(new UnsignedInteger(p.status().ordinal())); a.add(new UnsignedInteger(p.reason().ordinal()));
        a.add(new UnsignedInteger(p.proposedEpoch())); a.add(new UnsignedInteger(p.expiresAfterEpoch()));
        return HistoryContractCbor.encode(a);
    }
    public static ProposalValue decodeProposalValue(byte[] bytes) { Array a = HistoryContractCbor.decodeArray(bytes, 5);
        return new ProposalValue(enumAt(ActionType.values(), ui(a, 0)), enumAt(ProposalStatus.values(), ui(a, 1)),
                enumAt(ProposalReason.values(), ui(a, 2)), u(a, 3), u(a, 4)); }
    public static ProposalMeta decodeProposalMeta(byte[] bytes) { Array a = HistoryContractCbor.decodeArray(bytes, 6);
        requireVersion(a); int complete = ui(a, 5); if (complete > 1) throw HistoryContractCbor.malformed();
        return new ProposalMeta(u(a, 1), u(a, 2), b(a, 3, 32), u(a, 4), complete == 1); }
    public static byte[] encodeProposalMeta(ProposalMeta m) { Array a = version(); a.add(new UnsignedInteger(m.epoch()));
        a.add(new UnsignedInteger(m.total())); a.add(new ByteString(m.root())); a.add(new UnsignedInteger(m.received()));
        a.add(bool(m.complete())); return HistoryContractCbor.encode(a); }
    public static DRepMeta decodeDRepMeta(byte[] bytes) { Array a = HistoryContractCbor.decodeArray(bytes, 8);
        requireVersion(a); int complete = ui(a, 7); if (complete > 1) throw HistoryContractCbor.malformed();
        return new DRepMeta(u(a, 1), u(a, 2), ui(a, 3), ui(a, 4), b(a, 5, 32), ui(a, 6), complete == 1); }
    public static byte[] encodeDRepMeta(DRepMeta m) { Array a = version(); a.add(new UnsignedInteger(m.epoch()));
        a.add(new UnsignedInteger(m.total())); a.add(new UnsignedInteger(m.chunkEntries()));
        a.add(new UnsignedInteger(m.chunkCount())); a.add(new ByteString(m.root()));
        a.add(new UnsignedInteger(m.receivedChunks())); a.add(bool(m.complete())); return HistoryContractCbor.encode(a); }
    public static byte[] encodeCoin(BigInteger coin) { return HistoryContractCbor.encode(new UnsignedInteger(coin)); }
    public static BigInteger decodeCoin(byte[] bytes) {
        // Strict scalar decoding is obtained by wrapping it in a one-element preferred-CBOR array.
        byte[] encoded = new byte[bytes.length + 1];
        encoded[0] = (byte) 0x81; System.arraycopy(bytes, 0, encoded, 1, bytes.length);
        Array a = HistoryContractCbor.decodeArray(encoded, 1); return unsigned(a, 0); }

    public static byte[] encodeProposalQuery(ProposalQuery q) { Array a = version(); a.add(new UnsignedInteger(q.epoch()));
        a.add(new ByteString(q.transactionId())); a.add(new UnsignedInteger(q.governanceActionIndex()));
        return HistoryContractCbor.encode(a); }
    public static ProposalQuery decodeProposalQuery(byte[] bytes) { Array a = HistoryContractCbor.decodeArray(bytes, 4);
        requireVersion(a); return new ProposalQuery(u(a, 1), b(a, 2, 32), ui(a, 3)); }
    public static byte[] encodeDRepQuery(DRepQuery q) { Array a = version(); a.add(new UnsignedInteger(q.epoch()));
        a.add(new UnsignedInteger(q.drepType())); a.add(new ByteString(q.drepHash())); return HistoryContractCbor.encode(a); }
    public static DRepQuery decodeDRepQuery(byte[] bytes) { Array a = HistoryContractCbor.decodeArray(bytes, 4);
        requireVersion(a); return new DRepQuery(u(a, 1), ui(a, 2), b(a, 3, 28)); }

    public static byte[] proposalHash(Proposal proposal) { return Blake2bUtil.blake2bHash256(encodeProposal(proposal)); }
    public static byte[] drepChunkHash(List<DRepEntry> entries) {
        return Blake2bUtil.blake2bHash256(encodeDRepChunk(new DRepChunk(0, new byte[32], 0, entries)));
    }
    public static byte[] proposalRoot(List<byte[]> hashes) { return fold(PROPOSAL_ROOT_DOMAIN, hashes); }
    public static byte[] drepRoot(List<byte[]> hashes) {
        byte[] root = initialDRepRoot();
        for (byte[] hash : hashes) root = appendDRepRoot(root, hash);
        return root;
    }
    public static byte[] initialDRepRoot() { return Blake2bUtil.blake2bHash256(DREP_ROOT_DOMAIN); }
    public static byte[] appendDRepRoot(byte[] current, byte[] chunkHash) {
        if (current == null || current.length != 32 || chunkHash == null || chunkHash.length != 32)
            throw new IllegalArgumentException("DRep and chunk hashes must contain 32 bytes");
        return Blake2bUtil.blake2bHash256(ByteBuffer.allocate(64).put(current).put(chunkHash).array());
    }
    public static byte[] outerRoot(Header header, List<byte[]> proposalHashes, List<byte[]> drepHashes) {
        List<byte[]> all = new ArrayList<>(1 + proposalHashes.size() + drepHashes.size());
        all.add(Blake2bUtil.blake2bHash256(encodeHeader(header))); all.addAll(proposalHashes); all.addAll(drepHashes);
        return fold(OUTER_ROOT_DOMAIN, all);
    }

    public static byte[] proposalKey(long epoch, byte[] txId, int index) { return ("governance/" + epoch
            + "/proposals/" + java.util.HexFormat.of().formatHex(txId) + "/" + index).getBytes(StandardCharsets.US_ASCII); }
    public static byte[] proposalMetaKey(long epoch) { return ("governance/" + epoch + "/proposals/meta").getBytes(StandardCharsets.US_ASCII); }
    public static byte[] drepKey(long epoch, int type, byte[] hash) { return ("governance/" + epoch + "/dreps/"
            + String.format("%02x", type) + java.util.HexFormat.of().formatHex(hash)).getBytes(StandardCharsets.US_ASCII); }
    public static byte[] drepMetaKey(long epoch) { return ("governance/" + epoch + "/dreps/meta").getBytes(StandardCharsets.US_ASCII); }
    public static byte[] proposalCursorKey(long epoch) { return ("governance/" + epoch + "/proposals/cursor").getBytes(StandardCharsets.US_ASCII); }
    public static byte[] proposalClaimKey(long epoch, long index) { return ("governance/" + epoch
            + "/proposals/claims/" + index).getBytes(StandardCharsets.US_ASCII); }
    public static byte[] drepCursorKey(long epoch) { return ("governance/" + epoch + "/dreps/cursor").getBytes(StandardCharsets.US_ASCII); }
    public static byte[] drepChunkKey(long epoch, int index) { return ("governance/" + epoch + "/dreps/chunks/" + index).getBytes(StandardCharsets.US_ASCII); }
    public static byte[] proposalOrderKey(Proposal p) { return ByteBuffer.allocate(34).put(p.transactionId())
            .putShort((short) p.governanceActionIndex()).array(); }
    public static byte[] drepOrderKey(DRepEntry e) {
        return drepOrderKey(e.drepType(), e.drepHash());
    }
    public static byte[] drepOrderKey(int drepType, byte[] drepHash) {
        if (drepType < 0 || drepType > 2 || drepHash == null || drepHash.length != 28) {
            throw new IllegalArgumentException("invalid DRep order key");
        }
        return ByteBuffer.allocate(29).put((byte) drepType).put(drepHash).array();
    }
    public static int compare(Proposal l, Proposal r) { int tx = Arrays.compareUnsigned(l.transactionId(), r.transactionId());
        return tx != 0 ? tx : Integer.compare(l.governanceActionIndex(), r.governanceActionIndex()); }
    public static int compare(DRepEntry l, DRepEntry r) { int type = Integer.compare(l.drepType(), r.drepType());
        return type != 0 ? type : Arrays.compareUnsigned(l.drepHash(), r.drepHash()); }
    public static int chunks(long total, int size) { if (total == 0) return 0;
        if (size <= 0) throw new IllegalArgumentException("chunk size required");
        return Math.toIntExact((total + size - 1) / size); }

    private static byte[] fold(byte[] domain, List<byte[]> hashes) { byte[] root = Blake2bUtil.blake2bHash256(domain);
        for (byte[] hash : hashes) { if (hash == null || hash.length != 32) throw new IllegalArgumentException("invalid hash");
            root = Blake2bUtil.blake2bHash256(ByteBuffer.allocate(64).put(root).put(hash).array()); } return root; }
    private static void requireCanonicalDReps(List<DRepEntry> entries) { DRepEntry previous = null;
        for (DRepEntry entry : entries) { if (previous != null && compare(previous, entry) >= 0)
            throw new IllegalArgumentException("DRep entries are not canonical"); previous = entry; } }
    private static Array prefix(int type) { Array a = version(); a.add(new UnsignedInteger(type)); return a; }
    private static Array version() { Array a = new Array(); a.add(new UnsignedInteger(VERSION)); return a; }
    private static UnsignedInteger bool(boolean value) { return new UnsignedInteger(value ? 1 : 0); }
    private static boolean bool(Array a, int i) { int value = ui(a, i); if (value > 1) throw HistoryContractCbor.malformed(); return value == 1; }
    private static long u(Array a, int i) { return HistoryContractCbor.uint(a.getDataItems().get(i)); }
    private static int ui(Array a, int i) { return HistoryContractCbor.uintInt(a.getDataItems().get(i)); }
    private static byte[] b(Array a, int i, int size) { return HistoryContractCbor.bytes(a.getDataItems().get(i), size); }
    private static BigInteger unsigned(Array a, int i) { if (!(a.getDataItems().get(i) instanceof UnsignedInteger u))
        throw HistoryContractCbor.malformed(); return u.getValue(); }
    private static void requireVersion(Array a) { if (ui(a, 0) != VERSION) throw HistoryContractCbor.malformed(); }
    private static void requireTag(Array a, int tag) { requireVersion(a); if (ui(a, 1) != tag) throw HistoryContractCbor.malformed(); }
    private static <T> T enumAt(T[] values, int index) { if (index < 0 || index >= values.length)
        throw HistoryContractCbor.malformed(); return values[index]; }
}
