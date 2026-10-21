package org.yanoproject.runtime.mempool;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.ledger.rules.util.PersistentMap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The UTxO indexes and dependency edges of one {@link MempoolLedgerState} (the indexes {@code DefaultMemPool}
 * keeps in mutable maps), as persistent maps so that every state version owns a consistent set and appends cost
 * {@code O(log n)}. Immutable.
 *
 * @param byId        entries by transaction id
 * @param spentBy     regular inputs claimed by a mempool transaction, to its id
 * @param producedBy  outputs of mempool transactions
 * @param refScripts  reference scripts produced by mempool transactions, by script hash
 * @param byteSize    total stored transaction bytes
 * @param edgeCount   parent-to-child dependency edges
 */
record MempoolIndexes(PersistentMap<String, MempoolEntry> byId,
                      PersistentMap<Outpoint, String> spentBy,
                      PersistentMap<Outpoint, Produced> producedBy,
                      PersistentMap<String, RefScript> refScripts,
                      long byteSize,
                      int edgeCount) {

    static final MempoolIndexes EMPTY = new MempoolIndexes(PersistentMap.empty(), PersistentMap.empty(),
            PersistentMap.empty(), PersistentMap.empty(), 0, 0);

    /** An output of a mempool transaction. */
    record Produced(String owner, Utxo utxo, TxProjection.SubjectKey subject) {
    }

    /** Reference-script bytes and how many live outputs carry them. */
    record RefScript(byte[] bytes, int references) {
    }

    /** Thrown when two outputs carry different bytes under one script hash. */
    static final class ReferenceScriptConflict extends IllegalStateException {
        ReferenceScriptConflict(String hash) {
            super("different reference-script bytes share hash " + hash);
        }
    }

    /** @return the ids of mempool transactions whose outputs {@code projection} spends or references */
    Set<String> parentsOf(TxProjection projection) {
        Set<String> parents = new HashSet<>();
        for (Outpoint input : projection.allInputs()) {
            Produced produced = producedBy.get(input);
            if (produced != null) {
                parents.add(produced.owner());
            }
        }
        return parents;
    }

    /** Index records, counted as {@code DefaultMemPool} counts them (each edge twice). */
    int entryCount() {
        return producedBy.size() + spentBy.size() + refScripts.size() + edgeCount * 2;
    }

    /**
     * @return the indexes with {@code entry} added (its regular inputs must be unclaimed)
     * @throws ReferenceScriptConflict when a reference script's bytes disagree with a live one
     */
    MempoolIndexes add(MempoolEntry entry) {
        TxProjection p = entry.projection();
        String id = entry.txHash();
        PersistentMap<Outpoint, String> spent = spentBy;
        for (Outpoint input : p.regularInputs()) {
            spent = spent.plus(input, id);
        }
        PersistentMap<Outpoint, Produced> produced = producedBy;
        PersistentMap<String, RefScript> scripts = refScripts;
        for (var e : p.outputs().entrySet()) {
            Utxo utxo = e.getValue();
            produced = produced.plus(e.getKey(), new Produced(id, utxo, p.subjects().get(utxo.address())));
            String hash = normalizeScriptHash(utxo.referenceScriptHash());
            if (hash != null && utxo.scriptRef() != null) {
                byte[] bytes = HexUtil.decodeHexString(utxo.scriptRef());
                RefScript existing = scripts.get(hash);
                if (existing == null) {
                    scripts = scripts.plus(hash, new RefScript(bytes, 1));
                } else if (!Arrays.equals(existing.bytes(), bytes)) {
                    throw new ReferenceScriptConflict(hash);
                } else {
                    scripts = scripts.plus(hash, new RefScript(existing.bytes(), existing.references() + 1));
                }
            }
        }
        return new MempoolIndexes(byId.plus(id, entry), spent, produced, scripts, byteSize + entry.size(),
                edgeCount + entry.parents().size());
    }

    /** @return the indexes without {@code entry} */
    MempoolIndexes remove(MempoolEntry entry) {
        TxProjection p = entry.projection();
        String id = entry.txHash();
        if (!byId.containsKey(id)) {
            return this;
        }
        PersistentMap<Outpoint, String> spent = spentBy;
        for (Outpoint input : p.regularInputs()) {
            if (id.equals(spent.get(input))) {
                spent = spent.minus(input);
            }
        }
        PersistentMap<Outpoint, Produced> produced = producedBy;
        PersistentMap<String, RefScript> scripts = refScripts;
        for (var e : p.outputs().entrySet()) {
            produced = produced.minus(e.getKey());
            Utxo utxo = e.getValue();
            String hash = normalizeScriptHash(utxo.referenceScriptHash());
            if (hash != null && utxo.scriptRef() != null) {
                RefScript existing = scripts.get(hash);
                if (existing != null) {
                    scripts = existing.references() <= 1 ? scripts.minus(hash)
                            : scripts.plus(hash, new RefScript(existing.bytes(), existing.references() - 1));
                }
            }
        }
        return new MempoolIndexes(byId.minus(id), spent, produced, scripts, byteSize - entry.size(),
                edgeCount - entry.parents().size());
    }

    static String normalizeScriptHash(String scriptHash) {
        if (scriptHash == null || scriptHash.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = HexUtil.decodeHexString(scriptHash);
            return decoded.length > 0 ? HexUtil.encodeHexString(decoded) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
