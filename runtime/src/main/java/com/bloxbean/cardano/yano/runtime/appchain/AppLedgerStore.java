package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.mpf.rocksdb.RocksDbNodeStore;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateProofSnapshot;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectProof;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectProofLookup;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FxKeys;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.rocksdb.*;
import org.slf4j.Logger;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable app-chain ledger: hash-linked blocks, tip metadata, per-height vote
 * locks, an included-message index, and the MPF state trie — all in one
 * RocksDB instance so a finalized block commits in a single atomic WriteBatch
 * (block + tip + message index + trie nodes + state root). The ledger is
 * append-only after APP_FINAL: there is no rollback path by construction
 * (ADR app-layer/005, risk table).
 */
final class AppLedgerStore implements AutoCloseable {
    private static final byte[] CF_BLOCKS = "app_blocks".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CF_META = "app_meta".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CF_MSGS = "app_msgs".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CF_MPF_NODES = "mpf_nodes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CF_QUERY_INDEX = "app_query_index".getBytes(StandardCharsets.UTF_8);
    /** Effect outbox (ADR app-layer/010 F3): consensus-derived, atomic with the block, NOT in the root. */
    private static final byte[] CF_FX_RECORDS = "app_fx_records".getBytes(StandardCharsets.UTF_8);
    /** Effect runtime tier (ADR-010 F3): node-local execution progress — never replicated, disposable. */
    private static final byte[] CF_FX_RUNTIME = "app_fx_runtime".getBytes(StandardCharsets.UTF_8);

    private static final byte[] KEY_TIP_HEIGHT = "tip_height".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_TIP_HASH = "tip_hash".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_STATE_ROOT = "state_root".getBytes(StandardCharsets.UTF_8);
    private static final String KEY_VOTE_LOCK_PREFIX = "vote_lock_";

    private final RocksDB db;
    private final DBOptions dbOptions;
    private final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
    private final ColumnFamilyHandle blocksCf;
    private final ColumnFamilyHandle metaCf;
    private final ColumnFamilyHandle msgsCf;
    private final ColumnFamilyHandle queryIndexCf;
    private final ColumnFamilyHandle fxRecordsCf;
    private final ColumnFamilyHandle fxRuntimeCf;
    private final RocksDbNodeStore mpfNodeStore;
    private final Logger log;

    AppLedgerStore(String path, Logger log) {
        this.log = Objects.requireNonNull(log, "log");
        try {
            RocksDB.loadLibrary();
            new File(path).mkdirs();

            ColumnFamilyOptions defaultCfOptions = new ColumnFamilyOptions();
            ColumnFamilyOptions mpfCfOptions = new ColumnFamilyOptions()
                    .useFixedLengthPrefixExtractor(1); // namespace prefix used by RocksDbNodeStore

            List<ColumnFamilyDescriptor> descriptors = List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultCfOptions),
                    new ColumnFamilyDescriptor(CF_BLOCKS, defaultCfOptions),
                    new ColumnFamilyDescriptor(CF_META, defaultCfOptions),
                    new ColumnFamilyDescriptor(CF_MSGS, defaultCfOptions),
                    new ColumnFamilyDescriptor(CF_MPF_NODES, mpfCfOptions),
                    new ColumnFamilyDescriptor(CF_QUERY_INDEX, defaultCfOptions),
                    new ColumnFamilyDescriptor(CF_FX_RECORDS, defaultCfOptions),
                    new ColumnFamilyDescriptor(CF_FX_RUNTIME, defaultCfOptions));

            this.dbOptions = new DBOptions()
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true);
            this.db = RocksDB.open(dbOptions, path, descriptors, cfHandles);
            this.blocksCf = cfHandles.get(1);
            this.metaCf = cfHandles.get(2);
            this.msgsCf = cfHandles.get(3);
            this.mpfNodeStore = new RocksDbNodeStore(db, cfHandles.get(4));
            this.queryIndexCf = cfHandles.get(5);
            this.fxRecordsCf = cfHandles.get(6);
            this.fxRuntimeCf = cfHandles.get(7);
            log.info("App ledger opened at {} (tip height: {})", path, tipHeight());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open app ledger at " + path, e);
        }
    }

    RocksDbNodeStore mpfNodeStore() {
        return mpfNodeStore;
    }

    /** Committed state root, or null before the first block. */
    byte[] stateRoot() {
        return getMeta(KEY_STATE_ROOT);
    }

    long tipHeight() {
        byte[] value = getMeta(KEY_TIP_HEIGHT);
        return value != null ? ByteBuffer.wrap(value).getLong() : 0L;
    }

    /**
     * Capture the finalized height and its block-bound state root from one
     * RocksDB snapshot. The snapshot is released immediately: historical MPF
     * nodes are immutable, so query reads can subsequently remain fixed to the
     * captured root while new blocks commit.
     */
    CommittedStateSnapshot captureCommittedState() {
        Snapshot snapshot = db.getSnapshot();
        try (ReadOptions readOptions = new ReadOptions().setSnapshot(snapshot)) {
            byte[] heightBytes = db.get(metaCf, readOptions, KEY_TIP_HEIGHT);
            if (heightBytes == null) {
                return new CommittedStateSnapshot(0L, new byte[32]);
            }
            if (heightBytes.length != Long.BYTES) {
                throw new IllegalStateException("Committed app-chain height is malformed");
            }
            long height = ByteBuffer.wrap(heightBytes).getLong();
            if (height <= 0) {
                throw new IllegalStateException("Committed app-chain height must be positive");
            }
            byte[] blockBytes = db.get(blocksCf, readOptions, heightKey(height));
            if (blockBytes == null) {
                throw new IllegalStateException(
                        "Committed app-chain tip block is unavailable at height " + height);
            }
            AppBlock block = AppBlockCodec.deserialize(blockBytes);
            if (block.height() != height || block.stateRoot().length != 32) {
                throw new IllegalStateException(
                        "Committed app-chain tip block identity is malformed at height " + height);
            }
            byte[] metaRoot = db.get(metaCf, readOptions, KEY_STATE_ROOT);
            if (metaRoot == null || !Arrays.equals(metaRoot, block.stateRoot())) {
                throw new IllegalStateException(
                        "Committed app-chain tip block and state root disagree at height " + height);
            }
            return new CommittedStateSnapshot(height, block.stateRoot());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to capture committed app-chain state", e);
        } finally {
            db.releaseSnapshot(snapshot);
        }
    }

    record CommittedStateSnapshot(long height, byte[] stateRoot) {
        CommittedStateSnapshot {
            if (height < 0 || stateRoot == null || stateRoot.length != 32) {
                throw new IllegalArgumentException("Invalid committed app-chain state snapshot");
            }
            stateRoot = stateRoot.clone();
        }

        @Override
        public byte[] stateRoot() {
            return stateRoot.clone();
        }
    }

    byte[] tipHash() {
        byte[] value = getMeta(KEY_TIP_HASH);
        return value != null ? value : AppBlock.GENESIS_PREV_HASH;
    }

    Optional<AppBlock> block(long height) {
        try {
            byte[] bytes = db.get(blocksCf, heightKey(height));
            return bytes != null ? Optional.of(AppBlockCodec.deserialize(bytes)) : Optional.empty();
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read app block " + height, e);
        }
    }

    /** Raw block CBOR for a height range (inclusive), for catch-up serving. */
    List<byte[]> blockBytesRange(long fromHeight, long toHeight) {
        List<byte[]> blocks = new ArrayList<>();
        try {
            for (long h = fromHeight; h <= toHeight; h++) {
                byte[] bytes = db.get(blocksCf, heightKey(h));
                if (bytes == null) {
                    break;
                }
                blocks.add(bytes);
            }
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read app block range", e);
        }
        return blocks;
    }

    private static final String PRUNE_CURSOR = "prune_body_cursor";

    long pruneCursor() {
        return metaLong(PRUNE_CURSOR, 0L);
    }

    /**
     * Atomic snapshot of the app ledger via RocksDB Checkpoint (hard links —
     * fast, space-efficient), for fast member onboarding (ADR 006 E5.3). Copy
     * the resulting directory to a new node's ledger path; it restores the full
     * state (blocks, MPF trie, message index) with no replay. The new member
     * then catches up any blocks after the snapshot over protocol 103.
     *
     * @param snapshotPath directory to create the checkpoint in (must not exist)
     */
    void createSnapshot(String snapshotPath) {
        try (org.rocksdb.Checkpoint checkpoint = org.rocksdb.Checkpoint.create(db)) {
            checkpoint.createCheckpoint(snapshotPath);
            log.info("App-chain ledger snapshot created at {} (tip height {})", snapshotPath, tipHeight());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to snapshot app ledger to " + snapshotPath, e);
        }
    }

    /**
     * Integrity check after a restore: the tip block's recorded state-root must
     * equal the committed MPF root. A mismatch means a corrupt/partial snapshot.
     */
    boolean verifyIntegrity() {
        long tip = tipHeight();
        if (tip == 0) {
            return true; // empty ledger
        }
        byte[] committedRoot = stateRoot();
        byte[] tipBlockRoot = block(tip).map(AppBlock::stateRoot).orElse(null);
        return committedRoot != null && tipBlockRoot != null
                && java.util.Arrays.equals(committedRoot, tipBlockRoot);
    }

    /**
     * Strip message BODIES from finalized blocks in (pruneCursor, toHeight]
     * (retention / data-minimization, ADR 006 E4.4). Headers, message ids,
     * messages-root, state-root and finality certs are kept, so block hashes,
     * the prev-hash chain, inclusion proofs and evidence remain verifiable —
     * only the (large / possibly sensitive) payloads are dropped. With encrypted
     * bodies this is crypto-shredding.
     * <p>
     * Note: from-genesis catch-up past the prune horizon is no longer possible
     * (replay needs bodies) — new members onboard from a snapshot (E5.3).
     *
     * @return number of blocks pruned
     */
    int pruneBodiesBelow(long toHeight) {
        long from = pruneCursor() + 1;
        int pruned = 0;
        try {
            for (long h = from; h <= toHeight; h++) {
                byte[] bytes = db.get(blocksCf, heightKey(h));
                if (bytes == null) {
                    continue;
                }
                AppBlock block = AppBlockCodec.deserialize(bytes);
                boolean hasBodies = block.messages().stream().anyMatch(m -> m.getSize() > 0);
                if (hasBodies) {
                    AppBlock stripped = stripBodies(block);
                    try (WriteBatch batch = new WriteBatch();
                         WriteOptions writeOptions = new WriteOptions()) {
                        batch.put(blocksCf, heightKey(h), AppBlockCodec.serialize(stripped));
                        batch.put(metaCf, PRUNE_CURSOR.getBytes(StandardCharsets.UTF_8), longBytes(h));
                        db.write(writeOptions, batch);
                    }
                    pruned++;
                } else {
                    metaPutLong(PRUNE_CURSOR, h);
                }
            }
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to prune app block bodies up to " + toHeight, e);
        }
        if (pruned > 0) {
            log.info("Pruned bodies from {} app block(s) up to height {}", pruned, toHeight);
        }
        return pruned;
    }

    private static AppBlock stripBodies(AppBlock block) {
        List<com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage> stripped =
                new ArrayList<>(block.messages().size());
        for (var message : block.messages()) {
            stripped.add(com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage.builder()
                    .version(message.getVersion())
                    .messageId(message.getMessageId())   // keep id → messages-root + inclusion still verify
                    .chainId(message.getChainId())
                    .topic(message.getTopic())
                    .sender(message.getSender())
                    .senderSeq(message.getSenderSeq())
                    .expiresAt(message.getExpiresAt())
                    .body(new byte[0])                    // drop the payload
                    .authScheme(message.getAuthScheme())
                    .authProof(new byte[0])
                    .build());
        }
        return new AppBlock(block.version(), block.chainId(), block.height(), block.prevHash(),
                block.l1Slot(), block.l1BlockHash(), block.timestamp(), block.messagesRoot(),
                block.stateRoot(), stripped, block.proposer(), block.cert());
    }

    private static final byte[] SENDER_SEQ_PREFIX = "sender_seq_".getBytes(StandardCharsets.UTF_8);

    /**
     * Highest finalized sender-seq for a member key — the replay floor
     * (ADR app-layer/008.1 I1.2). 0 = sender never finalized a message.
     */
    long senderSeq(byte[] sender) {
        byte[] value = getMeta(senderSeqKey(sender));
        return value != null ? ByteBuffer.wrap(value).getLong() : 0L;
    }

    private static byte[] senderSeqKey(byte[] sender) {
        ByteBuffer buffer = ByteBuffer.allocate(SENDER_SEQ_PREFIX.length + sender.length);
        buffer.put(SENDER_SEQ_PREFIX).put(sender);
        return buffer.array();
    }

    /** Height the message id was finalized at, or empty if never included. */
    Optional<Long> messageHeight(byte[] messageId) {
        try {
            byte[] value = db.get(msgsCf, messageId);
            return value != null ? Optional.of(ByteBuffer.wrap(value).getLong()) : Optional.empty();
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read message index", e);
        }
    }

    /** Read a key from the committed state trie. */
    Optional<byte[]> stateGet(byte[] key) {
        byte[] root = stateRoot();
        if (root == null) {
            return Optional.empty();
        }
        MpfTrie trie = new MpfTrie(mpfNodeStore, root);
        return Optional.ofNullable(trie.get(key));
    }

    /** MPF inclusion or exclusion proof for a key against the committed root. */
    Optional<byte[]> stateProofWire(byte[] key) {
        byte[] root = stateRoot();
        if (root == null) {
            return Optional.empty();
        }
        return new MpfTrie(mpfNodeStore, root).getProofWire(key);
    }

    /** Read a key against a retained historical MPF root. */
    Optional<byte[]> stateGetAtRoot(byte[] root, byte[] key) {
        if (root == null || root.length != 32) {
            return Optional.empty();
        }
        return Optional.ofNullable(new MpfTrie(mpfNodeStore, root).get(key));
    }

    /** Build an inclusion or exclusion proof against a retained historical root. */
    Optional<byte[]> stateProofWireAtRoot(byte[] root, byte[] key) {
        if (root == null || root.length != 32) {
            return Optional.empty();
        }
        return new MpfTrie(mpfNodeStore, root).getProofWire(key);
    }

    /**
     * Build one inclusion or exclusion proof from a single committed root.
     * Historical MPF nodes are immutable, so value and proof remain fixed to
     * the captured root while a later block commits concurrently.
     */
    Optional<AppStateProofSnapshot> stateProofSnapshot(byte[] key) {
        byte[] keySnapshot = Objects.requireNonNull(key, "key").clone();
        CommittedStateSnapshot committed = captureCommittedState();
        if (committed.height() == 0) {
            return Optional.empty();
        }
        byte[] root = committed.stateRoot();
        Optional<byte[]> proof = stateProofWireAtRoot(root, keySnapshot);
        if (proof.isEmpty()) {
            return Optional.empty();
        }
        byte[] value = stateGetAtRoot(root, keySnapshot).orElse(null);
        return Optional.of(new AppStateProofSnapshot(
                keySnapshot, value, proof.orElseThrow(), root, committed.height()));
    }

    /** Build a proof against the exact post-state root of a retained block. */
    Optional<AppStateProofSnapshot> stateProofSnapshotAtHeight(long height, byte[] key) {
        if (height <= 0) {
            return Optional.empty();
        }
        byte[] keySnapshot = Objects.requireNonNull(key, "key").clone();
        Optional<AppBlock> block = block(height);
        if (block.isEmpty()) {
            return Optional.empty();
        }
        byte[] root = block.orElseThrow().stateRoot();
        Optional<byte[]> proof = stateProofWireAtRoot(root, keySnapshot);
        if (proof.isEmpty()) {
            return Optional.empty();
        }
        byte[] value = stateGetAtRoot(root, keySnapshot).orElse(null);
        return Optional.of(new AppStateProofSnapshot(
                keySnapshot, value, proof.orElseThrow(), root, height));
    }

    /**
     * Atomically read the confirmed-anchor marker and its referenced app block.
     * Anchor metadata is written in one RocksDB batch; a snapshot prevents a
     * reader from combining fields from consecutive confirmations.
     */
    Optional<ConfirmedAnchorSnapshot> confirmedAnchorSnapshot() {
        Snapshot snapshot = db.getSnapshot();
        try (ReadOptions readOptions = new ReadOptions().setSnapshot(snapshot)) {
            byte[] heightBytes = db.get(metaCf, readOptions,
                    "anchor_last_height".getBytes(StandardCharsets.UTF_8));
            byte[] blockHash = db.get(metaCf, readOptions,
                    "anchor_last_block_hash".getBytes(StandardCharsets.UTF_8));
            byte[] txBytes = db.get(metaCf, readOptions,
                    "anchor_last_tx".getBytes(StandardCharsets.UTF_8));
            byte[] slotBytes = db.get(metaCf, readOptions,
                    "anchor_last_slot".getBytes(StandardCharsets.UTF_8));
            if (heightBytes == null || heightBytes.length != Long.BYTES
                    || slotBytes == null || slotBytes.length != Long.BYTES
                    || blockHash == null || blockHash.length != 32
                    || txBytes == null || txBytes.length == 0) {
                return Optional.empty();
            }
            long height = ByteBuffer.wrap(heightBytes).getLong();
            long slot = ByteBuffer.wrap(slotBytes).getLong();
            if (height <= 0 || slot < 0) {
                return Optional.empty();
            }
            String transactionHash = new String(txBytes, StandardCharsets.UTF_8);
            if (transactionHash.isBlank()) {
                return Optional.empty();
            }
            byte[] blockBytes = db.get(blocksCf, readOptions, heightKey(height));
            if (blockBytes == null) {
                return Optional.empty();
            }
            AppBlock block = AppBlockCodec.deserialize(blockBytes);
            return Optional.of(new ConfirmedAnchorSnapshot(
                    height, blockHash, transactionHash, slot, block));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read confirmed app-chain anchor", e);
        } finally {
            db.releaseSnapshot(snapshot);
        }
    }

    record ConfirmedAnchorSnapshot(
            long height, byte[] blockHash, String transactionHash, long l1Slot, AppBlock block) {
        ConfirmedAnchorSnapshot {
            blockHash = blockHash.clone();
        }

        @Override
        public byte[] blockHash() {
            return blockHash.clone();
        }
    }

    /**
     * Persisted vote lock: the block hash this member voted for at the given
     * height. Guarantees at-most-one vote per height across restarts.
     */
    Optional<byte[]> voteLock(long height) {
        return Optional.ofNullable(getMeta(voteLockKey(height)));
    }

    void putVoteLock(long height, byte[] blockHash) {
        try {
            db.put(metaCf, voteLockKey(height), blockHash);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to persist vote lock at height " + height, e);
        }
    }

    /**
     * The original proposer-signed proposal ENVELOPE this member voted for
     * (ADR 008.2 §2.3) — re-gossiped to finish partial rounds after timeouts
     * or restarts. Stored alongside the vote-lock hash.
     */
    void putVoteLockEnvelope(long height, byte[] envelopeCbor) {
        try {
            db.put(metaCf, voteLockEnvelopeKey(height), envelopeCbor);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to persist locked proposal at height " + height, e);
        }
    }

    Optional<byte[]> voteLockEnvelope(long height) {
        return Optional.ofNullable(getMeta(voteLockEnvelopeKey(height)));
    }

    /**
     * Operator escape hatch (stale-lock runbook, Iteration 4): clear the vote
     * lock + stored envelope at a height so this member may vote once more
     * there. Callers must ensure the locked round is UNRECOVERABLE (expired
     * proposal) — this consciously trades the at-most-one-vote guarantee for
     * liveness under operator supervision.
     */
    void removeVoteLock(long height) {
        try {
            db.delete(metaCf, voteLockKey(height));
            db.delete(metaCf, voteLockEnvelopeKey(height));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to clear vote lock at height " + height, e);
        }
    }

    private static byte[] voteLockEnvelopeKey(long height) {
        return (KEY_VOTE_LOCK_PREFIX + "env_" + height).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Atomically commit a finalized block: block bytes (with cert), tip, message
     * index and everything already staged in {@code batch} (MPF nodes + state
     * writes from apply).
     */
    void commitBlock(AppBlock block, byte[] blockHash, byte[] newStateRoot, WriteBatch batch) {
        commitBlock(block, blockHash, newStateRoot, batch, List.of());
    }

    /**
     * Commit with extra meta writes in the SAME atomic batch — used by
     * chain-governed membership (ADR 008.3): pending-approval state and
     * membership epochs commit atomically with the block that changed them.
     */
    void commitBlock(AppBlock block, byte[] blockHash, byte[] newStateRoot, WriteBatch batch,
                     List<GovernedMembership.MetaWrite> metaWrites) {
        try {
            for (GovernedMembership.MetaWrite write : metaWrites) {
                batch.put(metaCf, write.key().getBytes(StandardCharsets.UTF_8), write.value());
            }
            batch.put(blocksCf, heightKey(block.height()), AppBlockCodec.serialize(block));
            batch.put(metaCf, KEY_TIP_HEIGHT, longBytes(block.height()));
            batch.put(metaCf, KEY_TIP_HASH, blockHash);
            batch.put(metaCf, KEY_STATE_ROOT, newStateRoot);
            byte[] heightBytes = longBytes(block.height());
            int index = 0;
            java.util.Map<String, Long> senderMaxSeq = new java.util.LinkedHashMap<>();
            java.util.Map<String, byte[]> senderKeys = new java.util.LinkedHashMap<>();
            for (var message : block.messages()) {
                batch.put(msgsCf, message.getMessageId(), heightBytes);
                // Query index (ADR 006 E3.3): topic/sender -> message refs, same atomic batch
                batch.put(queryIndexCf, topicIndexKey(message.getTopic(), block.height(), index),
                        message.getMessageId());
                batch.put(queryIndexCf, senderIndexKey(message.getSender(), block.height(), index),
                        message.getMessageId());
                byte[] sender = message.getSender();
                if (sender != null && sender.length > 0 && message.getSenderSeq() > 0) {
                    String senderHex = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(sender);
                    senderKeys.putIfAbsent(senderHex, sender);
                    senderMaxSeq.merge(senderHex, message.getSenderSeq(), Math::max);
                }
                index++;
            }
            // Per-sender replay floor (ADR 008.1 I1.2), same atomic batch. Max with
            // the committed value so the floor never regresses (db.get sees only
            // committed state — one write per sender per block avoids the
            // WriteBatch read-visibility trap).
            for (var seqEntry : senderMaxSeq.entrySet()) {
                byte[] sender = senderKeys.get(seqEntry.getKey());
                long floor = Math.max(senderSeq(sender), seqEntry.getValue());
                batch.put(metaCf, senderSeqKey(sender), longBytes(floor));
            }
            try (WriteOptions writeOptions = new WriteOptions().setSync(true)) {
                db.write(writeOptions, batch);
            }
            log.info("App block committed: height={}, hash={}, msgs={}, stateRoot={}",
                    block.height(), com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(blockHash),
                    block.messages().size(),
                    com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(newStateRoot));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to commit app block " + block.height(), e);
        }
    }

    // ------------------------------------------------------------------
    // Effect outbox (ADR app-layer/010 F3): consensus-derived records,
    // committed atomically with the block; rebuildable by replay.
    // Key tags: 'r'+h(8BE)+ord(4BE) → record CBOR; 'n'+h(8BE) → count+root;
    //           'x'+h(8BE) → expiry bucket (12-byte entries);
    //           'c'+h(8BE)+ord(4BE) → incorporated outcome envelope.
    // ------------------------------------------------------------------

    private static final String FX_OPEN_COUNT = "fx_open_count";
    private static final String FX_EXPIRED_COUNT = "fx_expired_count";

    /**
     * Stage one block's effect writes into the commit batch (same atomicity as
     * the block). Pure writes — every read (bucket merges, open count) already
     * happened in {@link FxKernel#apply} at apply time.
     */
    void stageFx(WriteBatch batch, long height, FxKernel.Result fx) {
        if (fx == null || fx.isEmpty()) {
            return;
        }
        try {
            for (FxKernel.StagedEffect staged : fx.emitted()) {
                batch.put(fxRecordsCf,
                        fxRecordKey(staged.record().height(), staged.record().ordinal()),
                        staged.encoded());
            }
            if (!fx.emitted().isEmpty() && fx.effectsRoot() != null) {
                ByteBuffer meta = ByteBuffer.allocate(4 + fx.effectsRoot().length);
                meta.putInt(fx.emitted().size()).put(fx.effectsRoot());
                batch.put(fxRecordsCf, fxBlockMetaKey(height), meta.array());
            }
            for (var bucketPut : fx.bucketPuts().entrySet()) {
                batch.put(fxRecordsCf, fxExpiryKey(bucketPut.getKey()), bucketPut.getValue());
            }
            for (var result : fx.incorporated()) {
                batch.put(fxRecordsCf,
                        fxClosureKey(result.effectId().height(), result.effectId().ordinal()),
                        result.encodeEnvelope());
                // Runtime rows are disposable and may not exist on every
                // member. Removing these local indexes in the same commit as
                // the consensus closure prevents stale queue/backlog gauges.
                batch.delete(fxRuntimeCf,
                        fxQueueKey(result.effectId().height(), result.effectId().ordinal()));
                batch.delete(fxRuntimeCf,
                        fxResultReadyKey(result.effectId().height(), result.effectId().ordinal()));
            }
            if (fx.consumedExpiryBucket() >= 0) {
                batch.delete(fxRecordsCf, fxExpiryKey(fx.consumedExpiryBucket()));
            }
            batch.put(metaCf, FX_OPEN_COUNT.getBytes(StandardCharsets.UTF_8),
                    longBytes(fx.newOpenCount()));
            long expired = fx.incorporated().stream()
                    .filter(result -> result.outcome()
                            == com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome.EXPIRED)
                    .count();
            if (expired > 0) {
                batch.put(metaCf, FX_EXPIRED_COUNT.getBytes(StandardCharsets.UTF_8),
                        longBytes(fx.newExpiredCount()));
            }
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to stage effect writes at height " + height, e);
        }
    }

    /** The kernel's consensus-tier read surface over this ledger — one adapter for all callers. */
    FxKernel.FxReader fxReader() {
        return new FxKernel.FxReader() {
            @Override
            public List<long[]> expiryBucket(long height) {
                return fxExpiryBucket(height);
            }

            @Override
            public Optional<EffectRecord> record(long height, int ordinal) {
                return fxRecord(height, ordinal);
            }

            @Override
            public boolean closed(long height, int ordinal) {
                return fxClosed(height, ordinal);
            }

            @Override
            public long openCount() {
                return fxOpenCount();
            }

            @Override
            public long expiredCount() {
                return fxExpiredCount();
            }
        };
    }

    Optional<EffectRecord> fxRecord(long height, int ordinal) {
        try {
            byte[] bytes = db.get(fxRecordsCf, fxRecordKey(height, ordinal));
            return bytes != null ? Optional.of(EffectRecord.decode(bytes)) : Optional.empty();
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read effect record " + height + "/" + ordinal, e);
        }
    }

    /** Emission records in (height, ordinal) order starting at fromHeight. */
    List<EffectRecord> fxRecordsFrom(long fromHeight, int limit) {
        List<EffectRecord> records = new ArrayList<>();
        ByteBuffer seek = ByteBuffer.allocate(1 + 8);
        seek.put((byte) 'r').putLong(Math.max(0, fromHeight));
        try (RocksIterator iterator = db.newIterator(fxRecordsCf)) {
            for (iterator.seek(seek.array()); iterator.isValid() && records.size() < limit; iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != 13 || key[0] != 'r') {
                    break;
                }
                records.add(EffectRecord.decode(iterator.value()));
            }
        }
        return records;
    }

    /** Expiry-bucket entries {@code (height, ordinal)} registered at this height. */
    List<long[]> fxExpiryBucket(long height) {
        try {
            return FxBucketCodec.decode(db.get(fxRecordsCf, fxExpiryKey(height)));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read effect expiry bucket " + height, e);
        }
    }

    /** True when a terminal outcome for this effect has been incorporated. */
    boolean fxClosed(long height, int ordinal) {
        try {
            return db.get(fxRecordsCf, fxClosureKey(height, ordinal)) != null;
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read effect closure " + height + "/" + ordinal, e);
        }
    }

    /** Incorporated outcome envelope, if any. */
    Optional<byte[]> fxClosure(long height, int ordinal) {
        try {
            return Optional.ofNullable(db.get(fxRecordsCf, fxClosureKey(height, ordinal)));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read effect closure " + height + "/" + ordinal, e);
        }
    }

    /** Per-block emission meta: {@code count(4BE) + effectsRoot(32)}, if the block emitted. */
    Optional<byte[]> fxBlockMeta(long height) {
        try {
            return Optional.ofNullable(db.get(fxRecordsCf, fxBlockMetaKey(height)));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read effect block meta " + height, e);
        }
    }

    /**
     * Build the composed effect proof against the state root at {@code height},
     * never against the current tip. The compact per-block metadata row is
     * retained after record pruning, so callers can distinguish NOT_FOUND
     * from PRUNED. All three roots (records, metadata, historical trie leaf)
     * are cross-checked before any proof is returned.
     */
    EffectProofLookup fxEffectProof(long height, int ordinal) {
        if (height <= 0 || ordinal < 0) {
            return EffectProofLookup.notFound(0);
        }
        Optional<byte[]> encodedMeta = fxBlockMeta(height);
        if (encodedMeta.isEmpty()) {
            // A retained authenticated leaf without its local lookup metadata
            // is corruption, not an ordinary 404.
            block(height).ifPresent(emissionBlock -> {
                if (stateGetAtRoot(emissionBlock.stateRoot(), FxKeys.effectsRootKey(height))
                        .isPresent()) {
                    throw new IllegalStateException(
                            "Historical effect root exists without metadata at height " + height);
                }
            });
            return EffectProofLookup.notFound(0);
        }
        byte[] meta = encodedMeta.get();
        if (meta.length != 36) {
            throw new IllegalStateException("Malformed effect metadata at height " + height);
        }
        ByteBuffer metaBuffer = ByteBuffer.wrap(meta);
        int count = metaBuffer.getInt();
        byte[] committedEffectsRoot = new byte[32];
        metaBuffer.get(committedEffectsRoot);
        if (count <= 0 || count > FxKeys.MAX_EFFECTS_PER_BLOCK) {
            throw new IllegalStateException("Invalid effect count " + count + " at height " + height);
        }
        // The retained block metadata is sufficient to reject an ordinal
        // outside the committed emission range. Do this before consulting
        // historical block/trie material so pruning does not turn a provable
        // 404 into a node-local 410.
        if (ordinal >= count) {
            return EffectProofLookup.notFound(count);
        }

        AppBlock emissionBlock = block(height).orElse(null);
        if (emissionBlock == null) {
            return EffectProofLookup.pruned(count);
        }
        byte[] historicalStateRoot = emissionBlock.stateRoot();
        byte[] stateKey = FxKeys.effectsRootKey(height);
        byte[] trieValue = stateGetAtRoot(historicalStateRoot, stateKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Historical state omits effect root at height " + height));
        if (!java.util.Arrays.equals(trieValue, committedEffectsRoot)) {
            throw new IllegalStateException("Effect metadata does not match historical state at height "
                    + height);
        }

        List<byte[]> hashes = new ArrayList<>(count);
        EffectRecord target = null;
        for (int i = 0; i < count; i++) {
            Optional<EffectRecord> candidate = fxRecord(height, i);
            if (candidate.isEmpty()) {
                return EffectProofLookup.pruned(count);
            }
            EffectRecord record = candidate.get();
            if (record.height() != height || record.ordinal() != i) {
                throw new IllegalStateException("Non-contiguous effect record at height "
                        + height + ", ordinal " + i);
            }
            if (i == ordinal) {
                target = record;
            }
            hashes.add(record.effectHash());
        }
        byte[] recomputedRoot = FxKeys.effectsRoot(hashes);
        if (!java.util.Arrays.equals(recomputedRoot, committedEffectsRoot)) {
            throw new IllegalStateException("Effect records do not match metadata root at height " + height);
        }

        byte[] stateProof = stateProofWireAtRoot(historicalStateRoot, stateKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Historical effect-root proof unavailable at height " + height));
        EffectProof proof = new EffectProof(EffectProof.PROOF_VERSION,
                java.util.Objects.requireNonNull(target), count,
                FxKeys.effectsProof(hashes, ordinal), committedEffectsRoot,
                historicalStateRoot, stateProof);
        return EffectProofLookup.available(proof);
    }

    /** Committed count of open CHAIN effects. */
    long fxOpenCount() {
        return metaLong(FX_OPEN_COUNT, 0L);
    }

    /** Monotonic committed count of deterministic EXPIRED outcomes. */
    long fxExpiredCount() {
        return metaLong(FX_EXPIRED_COUNT, 0L);
    }

    private static byte[] fxRecordKey(long height, int ordinal) {
        return ByteBuffer.allocate(13).put((byte) 'r').putLong(height).putInt(ordinal).array();
    }

    private static byte[] fxBlockMetaKey(long height) {
        return ByteBuffer.allocate(9).put((byte) 'n').putLong(height).array();
    }

    private static byte[] fxExpiryKey(long height) {
        return ByteBuffer.allocate(9).put((byte) 'x').putLong(height).array();
    }

    private static byte[] fxClosureKey(long height, int ordinal) {
        return ByteBuffer.allocate(13).put((byte) 'c').putLong(height).putInt(ordinal).array();
    }

    // ------------------------------------------------------------------
    // Effect runtime tier (ADR-010 F3): node-local execution progress in
    // CF app_fx_runtime — plain puts, never in any root, disposable.
    // Key tags: 'o' → runtime owner identity;
    //           's'+h(8BE)+ord(4BE) → FxStatusRecord CBOR;
    //           'q'+h(8BE)+ord(4BE) → (empty) pending-queue row;
    //           'i'+h(8BE)+ord(4BE) → (empty) CHAIN result-ready row;
    //           'd'/'e'/'u' → dispatch/claim/result-injection scan cursors;
    //           'v' → runtime schema/index version.
    // Wall clock is FINE here — this is the execution plane.
    // ------------------------------------------------------------------

    private static final String FX_INTAKE_CURSOR = "fx_intake_cursor";
    private static final byte[] FX_RUNTIME_OWNER = new byte[]{'o'};
    private static final byte[] FX_RUNTIME_SCHEMA = new byte[]{'v'};
    private static final byte[] FX_RESULT_INJECTION_CURSOR = new byte[]{'u'};
    private static final byte[] FX_DISPATCH_CURSOR = new byte[]{'d'};
    private static final byte[] FX_CLAIM_CURSOR = new byte[]{'e'};
    private static final byte[] FX_RUNTIME_SCHEMA_V2 = new byte[]{2};
    private static final byte[] FX_RUNTIME_RANGE_START = new byte[]{0};
    private static final byte[] FX_RUNTIME_RANGE_END = new byte[]{(byte) 0xff};

    /** Result of binding the disposable execution tier to one executor identity. */
    record FxRuntimeBinding(boolean ownerChanged, boolean discardedState, String previousOwner) {
    }

    /** Current owner of the node-local execution tier, or null on a legacy/fresh ledger. */
    String fxRuntimeOwner() {
        try {
            byte[] value = db.get(fxRuntimeCf, FX_RUNTIME_OWNER);
            return value != null ? new String(value, StandardCharsets.UTF_8) : null;
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read effect runtime owner", e);
        }
    }

    /**
     * Bind node-local execution progress to {@code owner}. A copied snapshot,
     * a changed executor partition, or a pre-owner legacy runtime must never
     * inherit another worker's attempts, leases, submitted refs or cursor.
     * Reset is one synchronous cross-CF batch and deliberately leaves the
     * consensus-derived {@code app_fx_records} tier untouched.
     */
    FxRuntimeBinding bindFxRuntimeOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 512) {
            throw new IllegalArgumentException("Effect runtime owner must be 1..512 characters");
        }
        String encodedOwner = "v1:" + owner.trim();
        String previous = fxRuntimeOwner();
        if (encodedOwner.equals(previous)) {
            return new FxRuntimeBinding(false, false, previous);
        }

        boolean hadRuntimeState = previous != null || fxIntakeCursor(-1) >= 0;
        if (!hadRuntimeState) {
            try (RocksIterator iterator = db.newIterator(fxRuntimeCf)) {
                iterator.seekToFirst();
                hadRuntimeState = iterator.isValid();
            }
        }
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions().setSync(true)) {
            // All runtime keys use ASCII tags, hence this range covers the
            // whole disposable CF while allowing the owner put later in the
            // same ordered batch to survive the tombstone.
            batch.deleteRange(fxRuntimeCf, FX_RUNTIME_RANGE_START, FX_RUNTIME_RANGE_END);
            batch.delete(metaCf, FX_INTAKE_CURSOR.getBytes(StandardCharsets.UTF_8));
            batch.put(fxRuntimeCf, FX_RUNTIME_OWNER, encodedOwner.getBytes(StandardCharsets.UTF_8));
            db.write(writeOptions, batch);
            return new FxRuntimeBinding(true, hadRuntimeState, previous);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to bind/reset effect runtime owner", e);
        }
    }

    long fxIntakeCursor(long defaultValue) {
        return metaLong(FX_INTAKE_CURSOR, defaultValue);
    }

    void fxPutIntakeCursor(long height) {
        metaPutLong(FX_INTAKE_CURSOR, height);
    }

    void fxRuntimePutStatus(long height, int ordinal, FxStatusRecord status) {
        try {
            db.put(fxRuntimeCf, fxRuntimeStatusKey(height, ordinal), status.encode());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to write effect runtime status", e);
        }
    }

    /**
     * Atomically publish a local terminal, remove it from dispatch, and add it
     * to the durable result-ready index when a CHAIN outcome must be injected.
     */
    void fxRuntimeComplete(long height, int ordinal, FxStatusRecord status,
                           boolean resultReady) {
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            batch.put(fxRuntimeCf, fxRuntimeStatusKey(height, ordinal), status.encode());
            batch.delete(fxRuntimeCf, fxQueueKey(height, ordinal));
            if (resultReady) {
                batch.put(fxRuntimeCf, fxResultReadyKey(height, ordinal), new byte[0]);
            } else {
                batch.delete(fxRuntimeCf, fxResultReadyKey(height, ordinal));
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to complete effect runtime transition", e);
        }
    }

    /**
     * One-time v1→v2 backfill for DONE CHAIN outcomes created before the
     * result-ready index existed. The marker is written only with the complete
     * rebuilt index, so a crash retries safely on startup.
     */
    void fxEnsureResultReadyIndex() {
        try {
            if (java.util.Arrays.equals(db.get(fxRuntimeCf, FX_RUNTIME_SCHEMA),
                    FX_RUNTIME_SCHEMA_V2)) {
                return;
            }
            // Clear any partial prior rebuild first. Chunk writes keep startup
            // memory bounded; the v2 marker remains the commit point. A crash
            // before it is published simply repeats this delete+rebuild.
            try (WriteBatch batch = new WriteBatch();
                 WriteOptions writeOptions = new WriteOptions().setSync(true)) {
                batch.deleteRange(fxRuntimeCf, new byte[]{'i'}, new byte[]{'j'});
                batch.delete(fxRuntimeCf, FX_RESULT_INJECTION_CURSOR);
                db.write(writeOptions, batch);
            }

            List<long[]> chunk = new ArrayList<>(10_000);
            try (RocksIterator iterator = db.newIterator(fxRuntimeCf)) {
                for (iterator.seek(new byte[]{'s'}); iterator.isValid(); iterator.next()) {
                    byte[] key = iterator.key();
                    if (key.length != 13 || key[0] != 's') {
                        break;
                    }
                    FxStatusRecord status = FxStatusRecord.decode(iterator.value());
                    if (status.status() != FxStatusRecord.DONE
                            || status.outcomeCode() == FxStatusRecord.OUTCOME_NONE) {
                        continue;
                    }
                    ByteBuffer position = ByteBuffer.wrap(key, 1, 12);
                    long height = position.getLong();
                    int ordinal = position.getInt();
                    EffectRecord record = fxRecord(height, ordinal).orElse(null);
                    if (record != null && record.result()
                            == com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy.CHAIN
                            && !fxClosed(height, ordinal)) {
                        chunk.add(new long[]{height, ordinal});
                        if (chunk.size() == 10_000) {
                            writeResultReadyChunk(chunk);
                            chunk.clear();
                        }
                    }
                }
            }
            if (!chunk.isEmpty()) {
                writeResultReadyChunk(chunk);
            }
            try (WriteBatch batch = new WriteBatch();
                 WriteOptions writeOptions = new WriteOptions().setSync(true)) {
                batch.put(fxRuntimeCf, FX_RUNTIME_SCHEMA, FX_RUNTIME_SCHEMA_V2);
                db.write(writeOptions, batch);
            }
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to rebuild effect result-ready index", e);
        }
    }

    private void writeResultReadyChunk(List<long[]> entries) throws RocksDBException {
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            for (long[] entry : entries) {
                batch.put(fxRuntimeCf,
                        fxResultReadyKey(entry[0], Math.toIntExact(entry[1])), new byte[0]);
            }
            db.write(writeOptions, batch);
        }
    }

    Optional<FxStatusRecord> fxRuntimeStatus(long height, int ordinal) {
        try {
            byte[] bytes = db.get(fxRuntimeCf, fxRuntimeStatusKey(height, ordinal));
            return bytes != null ? Optional.of(FxStatusRecord.decode(bytes)) : Optional.empty();
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read effect runtime status", e);
        }
    }

    void fxQueuePut(long height, int ordinal) {
        try {
            db.put(fxRuntimeCf, fxQueueKey(height, ordinal), new byte[0]);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to write effect queue row", e);
        }
    }

    void fxQueueDelete(long height, int ordinal) {
        try {
            db.delete(fxRuntimeCf, fxQueueKey(height, ordinal));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to delete effect queue row", e);
        }
    }

    /** Queue rows in (height, ordinal) order — the runtime's work list. */
    List<long[]> fxQueueScan(int limit) {
        return fxQueueScanAfter(limit, null);
    }

    /**
     * At most {@code limit} queue rows strictly after {@code cursor}, wrapping
     * once to the head. This keeps work selection bounded without permanently
     * pinning it to the oldest ineligible prefix.
     */
    List<long[]> fxQueueScanAfter(int limit, long[] cursor) {
        List<long[]> entries = new ArrayList<>(Math.min(limit, 256));
        if (limit <= 0) {
            return entries;
        }
        try (RocksIterator iterator = db.newIterator(fxRuntimeCf)) {
            byte[] cursorKey = cursor != null
                    ? fxQueueKey(cursor[0], Math.toIntExact(cursor[1])) : null;
            iterator.seek(cursorKey != null ? cursorKey : new byte[]{'q'});
            if (cursorKey != null && iterator.isValid()
                    && java.util.Arrays.equals(iterator.key(), cursorKey)) {
                iterator.next(); // cursor is exclusive
            }
            collectPositionRows(iterator, entries, limit, null, (byte) 'q');
            if (cursorKey != null && entries.size() < limit) {
                iterator.seek(new byte[]{'q'});
                collectPositionRows(iterator, entries, limit, cursorKey, (byte) 'q');
            }
        }
        return entries;
    }

    private static void collectPositionRows(RocksIterator iterator, List<long[]> entries,
                                            int limit, byte[] inclusiveEnd, byte tag) {
        while (iterator.isValid() && entries.size() < limit) {
            byte[] key = iterator.key();
            if (key.length != 13 || key[0] != tag) {
                break;
            }
            if (inclusiveEnd != null
                    && java.util.Arrays.compareUnsigned(key, inclusiveEnd) > 0) {
                break;
            }
            ByteBuffer buffer = ByteBuffer.wrap(key, 1, 12);
            entries.add(new long[]{buffer.getLong(), buffer.getInt()});
            iterator.next();
        }
    }

    /** Every queue row; intended for memoized operational snapshots, not dispatch. */
    List<long[]> fxQueueScanAll() {
        return fxQueueScan(Integer.MAX_VALUE);
    }

    /** Durable local CHAIN outcomes awaiting incorporation, in chain order. */
    List<long[]> fxResultReadyScan() {
        return fxResultReadyScanAfter(Integer.MAX_VALUE, null);
    }

    /** Bounded, wrapping result-ready scan used by the fair injection loop. */
    List<long[]> fxResultReadyScanAfter(int limit, long[] cursor) {
        List<long[]> entries = new ArrayList<>(Math.min(limit, 256));
        if (limit <= 0) {
            return entries;
        }
        try (RocksIterator iterator = db.newIterator(fxRuntimeCf)) {
            byte[] cursorKey = cursor != null
                    ? fxResultReadyKey(cursor[0], Math.toIntExact(cursor[1])) : null;
            iterator.seek(cursorKey != null ? cursorKey : new byte[]{'i'});
            if (cursorKey != null && iterator.isValid()
                    && java.util.Arrays.equals(iterator.key(), cursorKey)) {
                iterator.next();
            }
            collectPositionRows(iterator, entries, limit, null, (byte) 'i');
            if (cursorKey != null && entries.size() < limit) {
                iterator.seek(new byte[]{'i'});
                collectPositionRows(iterator, entries, limit, cursorKey, (byte) 'i');
            }
        }
        return entries;
    }

    /** Last result-ready position visited by the fair injection scanner. */
    Optional<long[]> fxResultInjectionCursor() {
        try {
            byte[] value = db.get(fxRuntimeCf, FX_RESULT_INJECTION_CURSOR);
            if (value == null) {
                return Optional.empty();
            }
            if (value.length != Long.BYTES + Integer.BYTES) {
                throw new IllegalStateException("Malformed effect result injection cursor");
            }
            ByteBuffer buffer = ByteBuffer.wrap(value);
            return Optional.of(new long[]{buffer.getLong(), buffer.getInt()});
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read effect result injection cursor", e);
        }
    }

    void fxPutResultInjectionCursor(long height, int ordinal) {
        fxPutRuntimeCursor(FX_RESULT_INJECTION_CURSOR, height, ordinal,
                "effect result injection cursor");
    }

    Optional<long[]> fxDispatchCursor() {
        return fxRuntimeCursor(FX_DISPATCH_CURSOR, "effect dispatch cursor");
    }

    void fxPutDispatchCursor(long height, int ordinal) {
        fxPutRuntimeCursor(FX_DISPATCH_CURSOR, height, ordinal, "effect dispatch cursor");
    }

    Optional<long[]> fxClaimCursor() {
        return fxRuntimeCursor(FX_CLAIM_CURSOR, "effect claim cursor");
    }

    void fxPutClaimCursor(long height, int ordinal) {
        fxPutRuntimeCursor(FX_CLAIM_CURSOR, height, ordinal, "effect claim cursor");
    }

    private Optional<long[]> fxRuntimeCursor(byte[] key, String label) {
        try {
            byte[] value = db.get(fxRuntimeCf, key);
            if (value == null) {
                return Optional.empty();
            }
            if (value.length != Long.BYTES + Integer.BYTES) {
                throw new IllegalStateException("Malformed " + label);
            }
            ByteBuffer buffer = ByteBuffer.wrap(value);
            return Optional.of(new long[]{buffer.getLong(), buffer.getInt()});
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read " + label, e);
        }
    }

    private void fxPutRuntimeCursor(byte[] key, long height, int ordinal, String label) {
        byte[] value = ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
                .putLong(height).putInt(ordinal).array();
        try {
            db.put(fxRuntimeCf, key, value);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to write " + label, e);
        }
    }

    boolean fxResultReadyExists(long height, int ordinal) {
        try {
            return db.get(fxRuntimeCf, fxResultReadyKey(height, ordinal)) != null;
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read effect result-ready row", e);
        }
    }

    void fxResultReadyDelete(long height, int ordinal) {
        try {
            db.delete(fxRuntimeCf, fxResultReadyKey(height, ordinal));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to delete effect result-ready row", e);
        }
    }

    /**
     * Prune consensus-tier fx rows of RESOLVED effects below the horizon
     * (ADR-010 F3 retention; FX-M1/M2 review hardening). Prunable iff ALL of:
     * <ul>
     *   <li>resolved: a closure row exists (chain-recorded outcome), the
     *       runtime tier holds a local terminal, or the effect is
     *       {@code ResultPolicy.NONE} with NO runtime status at all (nothing
     *       will ever run it on this node — a live PENDING/RETRY/PARKED/
     *       QUARANTINED status is an obligation and blocks pruning);</li>
     *   <li>expiry-safe: {@code expiryHeight == 0} or {@code expiryHeight <=
     *       tip} — a record referenced by a FUTURE expiry bucket must survive
     *       until the deterministic sweep consumes that bucket, or the sweep
     *       hard-fails on the pruning node only (fork).</li>
     * </ul>
     *
     * @return number of effect records pruned
     */
    int fxPruneBelow(long horizon) {
        long tip = tipHeight();
        int pruned = 0;
        List<byte[]> deletions = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(fxRecordsCf)) {
            for (iterator.seek(new byte[]{'r'}); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != 13 || key[0] != 'r') {
                    break;
                }
                ByteBuffer buffer = ByteBuffer.wrap(key, 1, 12);
                long height = buffer.getLong();
                int ordinal = buffer.getInt();
                if (height >= horizon) {
                    break; // ascending order — nothing further is below the horizon
                }
                Optional<FxStatusRecord> status = fxRuntimeStatus(height, ordinal);
                boolean resolved = fxClosed(height, ordinal)
                        || status.map(FxStatusRecord::locallyTerminal).orElse(false)
                        || (status.isEmpty()
                                && EffectRecord.decode(iterator.value()).result()
                                        == com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy.NONE);
                if (!resolved) {
                    continue;
                }
                long expiryHeight = EffectRecord.decode(iterator.value()).expiryHeight();
                if (expiryHeight > 0 && expiryHeight > tip) {
                    continue; // future expiry bucket still references this record
                }
                deletions.add(key.clone());
                deletions.add(fxClosureKey(height, ordinal));
                deletions.add(fxRuntimeStatusKey(height, ordinal));
                deletions.add(fxQueueKey(height, ordinal));
                deletions.add(fxResultReadyKey(height, ordinal));
                pruned++;
            }
        }
        if (!deletions.isEmpty()) {
            try (WriteBatch batch = new WriteBatch();
                 WriteOptions writeOptions = new WriteOptions()) {
                for (int i = 0; i < deletions.size(); i += 5) {
                    batch.delete(fxRecordsCf, deletions.get(i));
                    batch.delete(fxRecordsCf, deletions.get(i + 1));
                    batch.delete(fxRuntimeCf, deletions.get(i + 2));
                    batch.delete(fxRuntimeCf, deletions.get(i + 3));
                    batch.delete(fxRuntimeCf, deletions.get(i + 4));
                }
                db.write(writeOptions, batch);
            } catch (RocksDBException e) {
                throw new RuntimeException("Failed to prune effect records below " + horizon, e);
            }
        }
        return pruned;
    }

    /** Emission records of exactly one height (prefix-bounded — no tail scan). */
    List<EffectRecord> fxRecordsAt(long height) {
        List<EffectRecord> records = new ArrayList<>();
        ByteBuffer seek = ByteBuffer.allocate(9);
        seek.put((byte) 'r').putLong(height);
        try (RocksIterator iterator = db.newIterator(fxRecordsCf)) {
            for (iterator.seek(seek.array()); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != 13 || key[0] != 'r'
                        || ByteBuffer.wrap(key, 1, 8).getLong() != height) {
                    break;
                }
                records.add(EffectRecord.decode(iterator.value()));
            }
        }
        return records;
    }

    /** Keys (height, ordinal) of OPEN effects up to {@code tip} — no value decode. */
    List<long[]> fxOpenRecordKeysUpTo(long tip) {
        List<long[]> keys = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(fxRecordsCf)) {
            for (iterator.seek(new byte[]{'r'}); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != 13 || key[0] != 'r') {
                    break;
                }
                ByteBuffer buffer = ByteBuffer.wrap(key, 1, 12);
                long height = buffer.getLong();
                int ordinal = buffer.getInt();
                if (height > tip) {
                    break;
                }
                if (!fxClosed(height, ordinal)) {
                    keys.add(new long[]{height, ordinal});
                }
            }
        }
        return keys;
    }

    boolean fxQueueExists(long height, int ordinal) {
        try {
            return db.get(fxRuntimeCf, fxQueueKey(height, ordinal)) != null;
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read effect queue row", e);
        }
    }

    /** Runtime status rows in (height, ordinal) order: {@code [height, ordinal, record]}. */
    List<Object[]> fxRuntimeStatusScan(int limit) {
        List<Object[]> entries = new ArrayList<>(Math.min(limit, 256));
        try (RocksIterator iterator = db.newIterator(fxRuntimeCf)) {
            for (iterator.seek(new byte[]{'s'}); iterator.isValid() && entries.size() < limit;
                 iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != 13 || key[0] != 's') {
                    break;
                }
                ByteBuffer buffer = ByteBuffer.wrap(key, 1, 12);
                entries.add(new Object[]{buffer.getLong(), buffer.getInt(),
                        FxStatusRecord.decode(iterator.value())});
            }
        }
        return entries;
    }

    /** Every status row; intended for memoized operational snapshots. */
    List<Object[]> fxRuntimeStatusScanAll() {
        return fxRuntimeStatusScan(Integer.MAX_VALUE);
    }

    private static byte[] fxRuntimeStatusKey(long height, int ordinal) {
        return ByteBuffer.allocate(13).put((byte) 's').putLong(height).putInt(ordinal).array();
    }

    private static byte[] fxQueueKey(long height, int ordinal) {
        return ByteBuffer.allocate(13).put((byte) 'q').putLong(height).putInt(ordinal).array();
    }

    private static byte[] fxResultReadyKey(long height, int ordinal) {
        return ByteBuffer.allocate(13).put((byte) 'i').putLong(height).putInt(ordinal).array();
    }

    // ------------------------------------------------------------------
    // Query surface (ADR 006 E3.3): topic/sender secondary indexes
    // ------------------------------------------------------------------

    /** {@code 't' + topicUtf8 + 0x00 + height(8BE) + index(4BE)} → messageId */
    private static byte[] topicIndexKey(String topic, long height, int index) {
        byte[] topicBytes = (topic != null ? topic : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + topicBytes.length + 1 + 8 + 4);
        buffer.put((byte) 't').put(topicBytes).put((byte) 0).putLong(height).putInt(index);
        return buffer.array();
    }

    /** {@code 's' + sender(32B) + height(8BE) + index(4BE)} → messageId */
    private static byte[] senderIndexKey(byte[] sender, long height, int index) {
        byte[] senderBytes = sender != null ? sender : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(1 + senderBytes.length + 8 + 4);
        buffer.put((byte) 's').put(senderBytes).putLong(height).putInt(index);
        return buffer.array();
    }

    List<com.bloxbean.cardano.yano.api.appchain.MessageRef> messagesByTopic(
            String topic, long fromHeight, int limit) {
        byte[] topicBytes = (topic != null ? topic : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer prefixBuffer = ByteBuffer.allocate(1 + topicBytes.length + 1);
        prefixBuffer.put((byte) 't').put(topicBytes).put((byte) 0);
        return scanIndex(prefixBuffer.array(), fromHeight, limit);
    }

    List<com.bloxbean.cardano.yano.api.appchain.MessageRef> messagesBySender(
            byte[] sender, long fromHeight, int limit) {
        ByteBuffer prefixBuffer = ByteBuffer.allocate(1 + sender.length);
        prefixBuffer.put((byte) 's').put(sender);
        return scanIndex(prefixBuffer.array(), fromHeight, limit);
    }

    private List<com.bloxbean.cardano.yano.api.appchain.MessageRef> scanIndex(
            byte[] prefix, long fromHeight, int limit) {
        List<com.bloxbean.cardano.yano.api.appchain.MessageRef> refs = new ArrayList<>();
        // Seek directly to (prefix, fromHeight) — height is big-endian, so
        // iteration order is ascending height/index.
        ByteBuffer seekBuffer = ByteBuffer.allocate(prefix.length + 8);
        seekBuffer.put(prefix).putLong(Math.max(0, fromHeight));
        try (RocksIterator iterator = db.newIterator(queryIndexCf)) {
            for (iterator.seek(seekBuffer.array()); iterator.isValid() && refs.size() < limit; iterator.next()) {
                byte[] key = iterator.key();
                if (!hasPrefix(key, prefix)) {
                    break;
                }
                ByteBuffer tail = ByteBuffer.wrap(key, prefix.length, 12);
                long height = tail.getLong();
                int index = tail.getInt();
                refs.add(new com.bloxbean.cardano.yano.api.appchain.MessageRef(height, index,
                        com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(iterator.value())));
            }
        }
        return refs;
    }

    private static boolean hasPrefix(byte[] key, byte[] prefix) {
        if (key.length < prefix.length + 12) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** Generic long-valued meta entry (anchor markers etc.). */
    long metaLong(String key, long defaultValue) {
        byte[] value = getMeta(key.getBytes(StandardCharsets.UTF_8));
        return value != null ? ByteBuffer.wrap(value).getLong() : defaultValue;
    }

    void metaPutLong(String key, long value) {
        try {
            db.put(metaCf, key.getBytes(StandardCharsets.UTF_8), longBytes(value));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to write app ledger meta " + key, e);
        }
    }

    /** Atomically initialize a group of long-valued metadata entries. */
    void metaPutLongs(Map<String, Long> values) {
        metaPutAll(values, Map.of());
    }

    /** Atomically write a mixed group of long and byte-valued metadata. */
    void metaPutAll(Map<String, Long> longValues, Map<String, byte[]> byteValues) {
        Objects.requireNonNull(longValues, "longValues");
        Objects.requireNonNull(byteValues, "byteValues");
        if (longValues.isEmpty() && byteValues.isEmpty()) {
            return;
        }
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            for (var entry : longValues.entrySet()) {
                batch.put(metaCf, entry.getKey().getBytes(StandardCharsets.UTF_8),
                        longBytes(entry.getValue()));
            }
            for (var entry : byteValues.entrySet()) {
                batch.put(metaCf, entry.getKey().getBytes(StandardCharsets.UTF_8),
                        Objects.requireNonNull(entry.getValue(), "metadata value"));
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to write app ledger metadata", e);
        }
    }

    /** Generic byte[] / UTF-8 string meta entries (anchor records etc.). */
    byte[] metaBytes(String key) {
        return getMeta(key.getBytes(StandardCharsets.UTF_8));
    }

    void metaPutBytes(String key, byte[] value) {
        try {
            db.put(metaCf, key.getBytes(StandardCharsets.UTF_8), value);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to write app ledger meta " + key, e);
        }
    }

    String metaString(String key) {
        byte[] value = metaBytes(key);
        return value != null ? new String(value, StandardCharsets.UTF_8) : null;
    }

    void metaPutString(String key, String value) {
        metaPutBytes(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] getMeta(byte[] key) {
        try {
            return db.get(metaCf, key);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read app ledger meta", e);
        }
    }

    private static byte[] voteLockKey(long height) {
        return (KEY_VOTE_LOCK_PREFIX + height).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] heightKey(long height) {
        return longBytes(height);
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    @Override
    public void close() {
        Throwable closeFailure = null;
        for (ColumnFamilyHandle handle : cfHandles) {
            try {
                handle.close();
            } catch (Throwable failure) {
                closeFailure = mergeCloseFailure(closeFailure, failure);
            }
        }
        try {
            db.close();
        } catch (Throwable failure) {
            closeFailure = mergeCloseFailure(closeFailure, failure);
        }
        try {
            dbOptions.close();
        } catch (Throwable failure) {
            closeFailure = mergeCloseFailure(closeFailure, failure);
        }
        if (closeFailure instanceof Error error) {
            throw error;
        }
        if (closeFailure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (closeFailure != null) {
            throw new IllegalStateException("App ledger close failed", closeFailure);
        }
    }

    /** Close every native resource while preserving the strongest failure. */
    private static Throwable mergeCloseFailure(Throwable aggregate, Throwable next) {
        return LifecycleFailures.merge(aggregate, next);
    }
}
