package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.mpf.rocksdb.RocksDbNodeStore;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import org.rocksdb.*;
import org.slf4j.Logger;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

    /** MPF inclusion proof (wire format) for a key against the committed root. */
    Optional<byte[]> stateProofWire(byte[] key) {
        byte[] root = stateRoot();
        if (root == null) {
            return Optional.empty();
        }
        return new MpfTrie(mpfNodeStore, root).getProofWire(key);
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
            }
            if (fx.consumedExpiryBucket() >= 0) {
                batch.delete(fxRecordsCf, fxExpiryKey(fx.consumedExpiryBucket()));
            }
            batch.put(metaCf, FX_OPEN_COUNT.getBytes(StandardCharsets.UTF_8),
                    longBytes(fx.newOpenCount()));
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

    /** Committed count of open CHAIN effects. */
    long fxOpenCount() {
        return metaLong(FX_OPEN_COUNT, 0L);
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
    // Key tags: 's'+h(8BE)+ord(4BE) → FxStatusRecord CBOR;
    //           'q'+h(8BE)+ord(4BE) → (empty) pending-queue row.
    // Wall clock is FINE here — this is the execution plane.
    // ------------------------------------------------------------------

    private static final String FX_INTAKE_CURSOR = "fx_intake_cursor";

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
        List<long[]> entries = new ArrayList<>(Math.min(limit, 256));
        try (RocksIterator iterator = db.newIterator(fxRuntimeCf)) {
            for (iterator.seek(new byte[]{'q'}); iterator.isValid() && entries.size() < limit;
                 iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != 13 || key[0] != 'q') {
                    break;
                }
                ByteBuffer buffer = ByteBuffer.wrap(key, 1, 12);
                entries.add(new long[]{buffer.getLong(), buffer.getInt()});
            }
        }
        return entries;
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
                pruned++;
            }
        }
        if (!deletions.isEmpty()) {
            try (WriteBatch batch = new WriteBatch();
                 WriteOptions writeOptions = new WriteOptions()) {
                for (int i = 0; i < deletions.size(); i += 4) {
                    batch.delete(fxRecordsCf, deletions.get(i));
                    batch.delete(fxRecordsCf, deletions.get(i + 1));
                    batch.delete(fxRuntimeCf, deletions.get(i + 2));
                    batch.delete(fxRuntimeCf, deletions.get(i + 3));
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

    private static byte[] fxRuntimeStatusKey(long height, int ordinal) {
        return ByteBuffer.allocate(13).put((byte) 's').putLong(height).putInt(ordinal).array();
    }

    private static byte[] fxQueueKey(long height, int ordinal) {
        return ByteBuffer.allocate(13).put((byte) 'q').putLong(height).putInt(ordinal).array();
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
        for (ColumnFamilyHandle handle : cfHandles) {
            try {
                handle.close();
            } catch (Exception ignored) {
            }
        }
        try {
            db.close();
        } catch (Exception ignored) {
        }
        try {
            dbOptions.close();
        } catch (Exception ignored) {
        }
    }
}
