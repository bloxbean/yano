# Pre-existing UTXO bug: a collateral return spent in its own block leaks a phantom unspent output

**Found 2026-08-20 during ADR-039 Phase 4b live validation. Not caused by ADR-039, and not
fixed here — it changes core UTXO accounting, so the fix is a decision, not a detail.**

**Tracked as [bloxbean/yano#74](https://github.com/bloxbean/yano/issues/74); to be fixed in a
separate PR.**

## What happens

`DefaultUtxoStore.applyBlock` resolves a spent input in two steps:

1. `ctx.getUnspent(key)` — a prefetched read of the **committed** database;
2. failing that, `intraBlockOutputs` — outputs created earlier in the *same* block, which the
   committed read cannot see because they are still in the open `WriteBatch`.

The ordinary output-creation path populates `intraBlockOutputs`. **The collateral-return path
does not.** It writes the return to `cfUnspent` through the batch and stops there.

So when an invalid transaction's collateral return is spent later in the same block, both
lookups miss, `prev` is null, and the whole spend is skipped by the surrounding
`if (prev != null)` — silently, because an unresolvable input is not an error to this code.

## Consequences

For that outpoint:

- it stays in `utxo_unspent` **permanently** — a phantom unspent output;
- it is never written to `utxo_spent`;
- its `utxo_addr` index entry is never deleted;
- `addStakeBalanceDelta` is never applied, so the owner's stake balance stays overstated by
  the return's lovelace.

## Evidence

Preprod block **1,809,762** (slot 49,425,634): transaction `ef4a3c84ae917f6305c418647a504d1b1b74a03b651474dabb356f94049d03ba`
is invalid (`valid=false`), its collateral return is output index 1 (7,000,000 lovelace), and
that output is consumed inside the same block. Both facts come from the archive of a node
synced to tip.

Probing that node's live chainstate for the outpoint, at block 5,077,000+, long after the
spend:

```
utxo_unspent   present, 169 bytes          <- still unspent, ~3.27M blocks later
utxo_spent     ABSENT
utxo_addr      2 entries at slot 49425634  <- address hash + payment credential, both stale
```

An earlier revision of this report showed `utxo_addr ABSENT`, which contradicted its own text. The
probe was wrong, not the analysis: it used the 34-byte outpoint key against a column family keyed
by `addrHash28 ‖ slot ‖ txHash ‖ index` (70 bytes). Re-probed by scanning for entries whose
trailing 34 bytes match the outpoint: 8,750,751 entries scanned, **2 matching** — both stale.

## Why it stayed invisible

Nothing ever asked. The store treats an unresolvable input as a no-op, and no downstream
consumer needed the spent output's identity — until ADR-039's address-transaction projection
needed the address of every consumed output and **failed closed** rather than dropping the
participation. The node halted on that block with a clear diagnostic, which is how the bug
surfaced.

The ADR-039 side is fixed independently: addresses are now captured when an output is
**created** as well as when it is spent, so the projection resolves intra-block spends without
depending on the store's intra-block map.

## The fix, and why it is not applied here

One line, in the collateral-return branch:

```java
intraBlockOutputs.put(tx.getTxHash() + ":" + outIdx, val);
```

It is not applied in this branch because it changes what the UTXO set contains and what stake
balances report, on every network, for every node — well outside "minimum necessary changes to
runtime/core" for ADR-039, and it deserves its own change with its own validation. Two further
questions belong with it:

1. **Existing chainstates are already wrong.** Every node that has passed such a block carries
   phantom unspent outputs. Correcting them needs either a resync or a repair pass; the
   population is small (one preprod occurrence found in 5.07M blocks) but non-zero.
2. **The silent skip itself is questionable.** `if (prev != null)` treats an input the store
   cannot resolve as nothing to do. That is what turned a one-line omission into a
   permanently wrong UTXO set rather than a loud failure at the first occurrence.
