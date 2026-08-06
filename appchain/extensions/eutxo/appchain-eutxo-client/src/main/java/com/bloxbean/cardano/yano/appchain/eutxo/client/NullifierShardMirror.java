package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR-UTXO-009 SP-M4: the off-chain nullifier mirror. It maintains {@code k =
 * 16} Merkle-Patricia-Forestry tries — one per shard — that reproduce the
 * on-chain {@code NullifierShardValidator} roots. A claim id's shard is its
 * last-byte low nibble ({@code claimId[31] & 0x0F}); the trie value for a
 * settled claim is the claim id itself, exactly as the on-chain
 * {@code including(claimId, claimId, proof)} expects.
 *
 * <p>The mirror is a cache, not a trust dependency: the MPF root is a pure
 * function of the settled-id <em>set</em> (insertion order is irrelevant), so
 * any party can reconstruct a shard from the L1 spend history alone and get
 * the same root — see {@link #reconstructShardRoot(List)}. It backs proof
 * serving for the settlement executor and for permissionless crankers (A3),
 * and the batch-insert proof chain a settlement transaction needs
 * ({@link #planInserts(int, List)}), which follows the on-chain fold exactly:
 * the non-membership proof at the running root, then the insert.
 */
public final class NullifierShardMirror {
    /** Matches {@code EutxoProfile.V3_NULLIFIER_SHARDS}. */
    public static final int SHARD_COUNT = 16;

    private final MpfTrie[] shards = new MpfTrie[SHARD_COUNT];

    public NullifierShardMirror() {
        for (int shard = 0; shard < SHARD_COUNT; shard++) {
            shards[shard] = new MpfTrie(new InMemoryNodeStore());
        }
    }

    /** A claim id's shard is the low nibble of its last byte. */
    public static int shardOf(byte[] claimId) {
        if (Objects.requireNonNull(claimId, "claimId").length != 32) {
            throw new IllegalArgumentException("claim id must be 32 bytes");
        }
        return claimId[31] & 0x0F;
    }

    public static int shardOf(String claimIdHex) {
        return shardOf(parse(claimIdHex));
    }

    /** Record a settled claim id in its shard (idempotent per id). */
    public void insert(byte[] claimId) {
        shards[shardOf(claimId)].put(claimId.clone(), claimId.clone());
    }

    public void insert(String claimIdHex) {
        insert(parse(claimIdHex));
    }

    public byte[] root(int shard) {
        return shards[checkShard(shard)].getRootHash();
    }

    public boolean contains(int shard, byte[] claimId) {
        checkShard(shard);
        if (shardOf(claimId) != shard) {
            return false;
        }
        return shards[shard].get(claimId) != null;
    }

    /**
     * The MPF wire proof for {@code claimId} in its shard — a membership proof
     * once the id is settled, a non-membership proof beforehand. Empty when
     * the shard does not match the id.
     */
    public Optional<byte[]> proofWire(int shard, byte[] claimId) {
        checkShard(shard);
        if (shardOf(claimId) != shard) {
            return Optional.empty();
        }
        return shards[shard].getProofWire(claimId);
    }

    /**
     * Plan the insert proof chain for a batch of NOT-yet-settled ids in one
     * shard, mirroring the on-chain {@code foldInserts}: each step carries the
     * non-membership proof at the running root, then the id is inserted. The
     * ids must all belong to {@code shard} and must be absent. Mutates the
     * shard trie to the post-batch state (as a real settlement would).
     */
    public InsertPlan planInserts(int shard, List<byte[]> newClaimIds) {
        checkShard(shard);
        Objects.requireNonNull(newClaimIds, "newClaimIds");
        MpfTrie trie = shards[shard];
        byte[] priorRoot = trie.getRootHash();
        List<PlannedInsert> planned = new ArrayList<>(newClaimIds.size());
        for (byte[] claimId : newClaimIds) {
            if (shardOf(claimId) != shard) {
                throw new IllegalArgumentException(
                        "claim id does not belong to shard " + shard);
            }
            if (trie.get(claimId) != null) {
                throw new IllegalArgumentException(
                        "claim id is already settled in shard " + shard);
            }
            byte[] proofWire = trie.getProofWire(claimId).orElseThrow(
                    () -> new IllegalStateException(
                            "shard trie could not produce a non-membership proof"));
            planned.add(new PlannedInsert(claimId.clone(), proofWire));
            trie.put(claimId.clone(), claimId.clone());
        }
        return new InsertPlan(shard, planned, priorRoot, trie.getRootHash());
    }

    /**
     * Reconstruct a shard root from the settled ids alone — the trust anchor
     * for A3 when no L2 node survives. Order-independent: the MPF root depends
     * only on the id set.
     */
    public static byte[] reconstructShardRoot(List<byte[]> claimIdsForShard) {
        MpfTrie trie = new MpfTrie(new InMemoryNodeStore());
        for (byte[] claimId : Objects.requireNonNull(claimIdsForShard, "claimIds")) {
            trie.put(claimId.clone(), claimId.clone());
        }
        return trie.getRootHash();
    }

    /** The root of an empty shard — the value shard threads are bootstrapped with. */
    public static byte[] emptyRoot() {
        return new MpfTrie(new InMemoryNodeStore()).getRootHash();
    }

    /** Verify a served membership proof (settled id) against a shard root. */
    public static boolean verifyMembership(
            byte[] expectedRoot, byte[] claimId, byte[] proofWire) {
        return new MpfTrie(new InMemoryNodeStore())
                .verifyProofWire(expectedRoot, claimId, claimId, true, proofWire);
    }

    /** Verify a served non-membership proof (unsettled id) against a shard root. */
    public static boolean verifyAbsence(
            byte[] expectedRoot, byte[] claimId, byte[] proofWire) {
        return new MpfTrie(new InMemoryNodeStore())
                .verifyProofWire(expectedRoot, claimId, null, false, proofWire);
    }

    private static int checkShard(int shard) {
        if (shard < 0 || shard >= SHARD_COUNT) {
            throw new IllegalArgumentException("shard must be in 0.." + (SHARD_COUNT - 1));
        }
        return shard;
    }

    private static byte[] parse(String claimIdHex) {
        try {
            return HexFormat.of().parseHex(
                    Objects.requireNonNull(claimIdHex, "claimIdHex").trim());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("claim id must be canonical hex", failure);
        }
    }

    /** One planned insert: the id and its non-membership proof at insert time. */
    public record PlannedInsert(byte[] claimId, byte[] proofWire) {
        public PlannedInsert {
            claimId = Objects.requireNonNull(claimId, "claimId").clone();
            proofWire = Objects.requireNonNull(proofWire, "proofWire").clone();
        }

        @Override public byte[] claimId() { return claimId.clone(); }
        @Override public byte[] proofWire() { return proofWire.clone(); }
    }

    /** A shard's batch-insert plan: ordered proofs plus the root transition. */
    public record InsertPlan(
            int shard,
            List<PlannedInsert> inserts,
            byte[] priorRoot,
            byte[] nextRoot
    ) {
        public InsertPlan {
            inserts = List.copyOf(Objects.requireNonNull(inserts, "inserts"));
            priorRoot = Objects.requireNonNull(priorRoot, "priorRoot").clone();
            nextRoot = Objects.requireNonNull(nextRoot, "nextRoot").clone();
        }

        @Override public byte[] priorRoot() { return priorRoot.clone(); }
        @Override public byte[] nextRoot() { return nextRoot.clone(); }
    }

    /** Minimal heap-backed {@link NodeStore} — the mirror is rebuildable. */
    private static final class InMemoryNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }

        @Override public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes.clone());
        }

        @Override public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }
}
