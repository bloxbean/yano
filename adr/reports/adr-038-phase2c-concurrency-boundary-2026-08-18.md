# ADR-038 Phase 2c — UTXO Parallel Decoding: Concurrency Boundary Inspection

Date: 2026-08-18
Branch: `feat/adr-038-archive-throughput`
Scope: inspection only — **no code changed, no deployment touched.**

**Verdict: a safe concurrency boundary exists.** Pure per-block decoding can run in
parallel; stateful UTXO derivation must stay strictly sequential, and the existing
code already enforces that. Proceeding to a bounded prototype is justified.

---

## 1. Where immutable serialized block bytes are obtained

`ChainBlockArchiveSource.readCanonical` (`archive-modules/archive-core/.../source/ChainBlockArchiveSource.java:31-44`):

```java
return reader.getCanonicalBlockReference(blockNumber).flatMap(reference -> {
    byte[] body = reader.getBlockByNumber(blockNumber);
    if (body == null) return Optional.empty();
    BlockSourceContext<B> decoded = decoder.decode(blockNumber, reference, body);
    ... verifies decoded number/slot/hash against the canonical reference ...
});
```

Bytes come from `ChainBlockReader.getBlockByNumber(long)` as a freshly allocated
`byte[]`. `CanonicalBlockReference` is obtained separately and does not decode the
body (`core-api/.../ChainBlockReader.java:20-25`).

## 2. Fetching can be separated from deserialization — yes

They are already two distinct calls in the snippet above: `getBlockByNumber` returns
bytes, `decoder.decode(blockNumber, reference, body)` consumes them. The `byte[]` is
not retained or mutated by the source, so a fetch stage and a decode stage can be
split with no contract change.

## 3. Concurrent chain-store reads are safe

`DirectRocksDBChainState.getBlockByNumber` is three independent `db.get(...)` calls
with locally allocated keys, returning fresh arrays:

```java
byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNumber));
long slot = bytesToLong(slotBytes);
byte[] blockHash = db.get(slotToHashHandle, longToBytes(slot));
return db.get(blocksHandle, blockHash);
```

No shared mutable buffers, iterators or reusable state in the class. RocksDB `get`
is safe for concurrent readers. Reads occur inside the batch's source lease (§8).

## 4. Serializer thread safety

- `BlockSerializer` is an **enum singleton** whose only static state is a logger
  (`javap`: `INSTANCE`, `log`, `$VALUES`). `deserialize(byte[])` builds a fresh
  `Block`; no instance state.
- `YaciBlockDecoder` holds only `final` function references (`slotToEpoch`,
  `slotToUnixTime`, `storedEra`).
- `YaciUtxoHistoryDecoder` holds only `final` fields: `blockDecoder`, `addressKeys`,
  `genesisOutputs`, `genesisBlockNumber`, `projection`.

**One shared mutable collaborator exists and it is already guarded.**
`AddressKeyCodec.key(byte[])` is **`synchronized`** and mutates a 4,096-entry
access-ordered `LinkedHashMap` used as a bounded fail-closed collision detector
(`archive-modules/archive-core/.../address/AddressKeyCodec.java:17-33`).

Two consequences, neither fatal:

- **Output is unaffected.** The returned key is always
  `Blake2bUtil.blake2bHash256(canonicalAddressBytes)` — a pure function of the
  input. The map only *detects* a key collision and throws; it never changes the
  result. Determinism of emitted rows is therefore preserved under any interleaving.
- **Collision-detection *coverage* becomes nondeterministic.** LRU eviction order
  varies with thread interleaving, so which addresses remain in the 4,096-entry
  window differs run to run. Detection is explicitly bounded and best-effort
  already, and the code notes that "backend address dimensions provide durable
  key/raw validation", so a genuine collision is still caught durably. This must be
  stated in the prototype's design rather than glossed.

**The lock is not on the hot path.** In 124 thread samples of the four projection
threads, `AddressKeyCodec` appeared **0** times and `Blake2b` **8**, against
`BlockSerializer` **416** and `HexUtil.encodeHexString` **338**. Contention on the
synchronized codec is therefore not expected to cap parallel decode; CBOR
deserialization dominates and is lock-free.

Per-task decoder instances are **not** required, and are in fact undesirable: a
per-task `AddressKeyCodec` would shrink each collision window and further weaken
detection coverage for no benefit.

## 5. Where pure decoding ends and stateful derivation begins

The boundary is clean and explicit.

**Pure (parallelisable):**
`YaciUtxoHistoryDecoder.decode(blockNumber, reference, body)` →
`BlockSourceContext<UtxoHistoryFact>`. Documented as *"Canonical,
resolver-independent normalized UTXO-history decoder"*
(`YaciUtxoHistoryDecoder.java:27,70`). It calls `blockDecoder.decode(...)` then a
private `derive(Block, slot, includeGenesis, blockNumber)` that produces flat facts.

**Stateful (must stay sequential):**
`UtxoHistoryDataset` (`UtxoHistoryDataset.java:19-213`), which is
`LiveStatefulBlockArchiveDataset<UtxoHistoryFact>` and owns
`SequentialPointerResolver pointers`, plus per-batch mutable
`blocks`, `updates`, `pendingPointers`, `pendingDeletedPointers`, `blockIndex`.

Note the generic parameter: the UTXO dataset consumes **`UtxoHistoryFact`, not
`Block`**. Decoding for UTXO therefore already terminates in a flat, immutable
fact record before any resolver work begins — which is precisely the split the
intended concurrency model needs.

## 6. Transaction order is preserved and explicit

`UtxoHistoryFact` is a record whose every collection is a `List`
(`pointerRegistrations`, `pointerDeregistrations`, `newAddresses`, `outputs`,
`assets`, `inputs`, `transactionDatums`, `transactionRedeemers`), so insertion
order is retained. Order is additionally recoverable from data: `Output`, `Input`,
`PointerRegistration` and `PointerDeregistration` all carry `txIndex` (and
`certIndex` where relevant), and `UtxoHistoryDataset.derive` re-sorts pointer
lifecycle events by `(txIndex, certIndex)` before applying them.

## 7. `CycleCachingBlockArchiveSource` concurrency

Backed by `ConcurrentHashMap` with `beginCycle`/`endCycle` guarded by an
`AtomicBoolean`, and `LongAdder` counters — correct under concurrent access.

Caveat for the prototype: `readCanonical` uses **`computeIfAbsent`** with the
delegate decode as the mapping function
(`CycleCachingBlockArchiveSource.java:63-72`). A long-running mapping function
holds the map's bin lock, so parallel decoders colliding on a bin serialise. It is
not a deadlock risk (no recursive update), but the prototype should **not** route
parallel decode through this cache; it should decode directly against the
underlying source and leave the cycle cache for the existing serial path. The cache
is nearly useless here anyway — measured hit rate **4.1%**, because the datasets sit
~882k blocks apart.

## 8. The source lease protects in-flight bodies

`BlockArchiveWorker.runBatch` wraps the whole batch:

```java
try (var lease = source.acquire(start, end, Instant.now().plus(leaseDuration))) { ... }
```

and `ChainBlockArchiveSource.acquire` validates against `earliestRetainedBody()`
before delegating to `leases.acquireBlockBodyLease(startBlock, endBlock, expiresAt)`,
throwing `"required block bodies already pruned"` if the range is already gone. The
lease covers `[start, end]` for the batch's lifetime, so every body a parallel
decoder touches is protected as long as decoding stays inside that try-block.

## 9. Can this stay UTXO-scoped without changing archive contracts — yes

Decode is reached through `BlockArchiveSource.readCanonical`, which
`BlockArchiveWorker` calls for every dataset. Keeping the change UTXO-scoped
therefore means **not** altering `BlockArchiveSource`, `ArchiveBackend`,
`ArchiveWriteSession` or the hot-store contracts, and instead introducing a bounded
parallel-prefetch decorator (or a worker-internal prefetch stage) that is only
installed for the `utxo_history` worker registration and is configuration-gated with
a serial fallback.

Crucially, **the dataset already enforces sequential consumption**:

```java
if (state != null && (blockIndex >= blocks.size()
        || blocks.get(blockIndex).blockNumber() != block.blockNumber())) {
    throw new IllegalStateException("UTXO history dataset batch order changed");
}
```

So an ordering bug in a reorder buffer fails loudly at the dataset boundary rather
than silently corrupting state — a strong safety net for the prototype, and an
existing invariant the prototype must satisfy rather than one it must invent.

## 10. Resulting design constraints for the prototype

1. Parallelise **fetch + decode only**; derivation, resolver and hot-state stay on
   the consuming thread in canonical order.
2. Decode directly against the underlying source, bypassing
   `CycleCachingBlockArchiveSource` (§7).
3. Share one `AddressKeyCodec` (do not create per-task instances) and record that
   collision-detection coverage becomes interleaving-dependent while emitted rows
   stay deterministic (§4).
4. Keep all parallel work inside the existing `source.acquire(start, end, …)`
   try-block so lease protection holds (§8).
5. Preserve `verifyParentChain` and the pre-commit anchor recheck unchanged.
6. Bound the reorder buffer by count **and** estimated bytes, with backpressure.
7. Cancel all outstanding tasks on failure, fork change, interruption or shutdown;
   advance no cursor, coverage, resolver or hot state until the ordered batch commits.
8. Configuration-gated with a retained serial path; UTXO-scoped, no contract change.

## 11. Expected ceiling

Decode is the dominant per-block cost (`BlockSerializer` 416 / `HexUtil` 338 of the
sampled frames), the archive write session is only 4.1% of the UTXO cycle, and the
machine runs at ~4–14% of 16 cores. There is genuine headroom. Whether it converts
to ≥50 blocks/s is exactly what the prototype must measure, not assume.
