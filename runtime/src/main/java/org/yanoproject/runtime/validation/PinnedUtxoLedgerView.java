package org.yanoproject.runtime.validation;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.Map;

/**
 * A frozen view for a shadow engine (ADR-056 §7): the transaction's inputs as the admission view resolved
 * them (mempool outputs included), captured while the mempool resolver was still valid; every other read,
 * UTxOs included, goes to the base view, which holds its own reference to the admission's snapshot.
 */
final class PinnedUtxoLedgerView extends ForwardingLedgerView {

    private final Map<Outpoint, Lookup<UtxoEntry>> pinned;

    /** @param pinned normalized outpoints and the admission view's answers for them */
    PinnedUtxoLedgerView(LedgerView base, Map<Outpoint, Lookup<UtxoEntry>> pinned) {
        super(base);
        this.pinned = Map.copyOf(pinned);
    }

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        Lookup<UtxoEntry> answer = pinned.get(Outpoints.normalize(outpoint));
        return answer != null ? answer : base.utxo(outpoint);
    }
}
