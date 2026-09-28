package org.yanoproject.runtime.validation;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.Objects;
import java.util.function.Function;

/**
 * A temporary admission view (ADR-056 step 1d, until the Phase 6 {@code OverlayLedgerView} mempool state):
 * UTxO reads go through the mempool's admission resolver, which lets a transaction spend outputs of earlier
 * mempool transactions and treats outputs they spent as gone; every other read is the base (ticked
 * canonical) view's.
 *
 * <p>So chained UTxOs behave as the legacy path does, but certificate and governance effects of earlier
 * mempool transactions are not visible yet (Phase 6).</p>
 *
 * <p>The mempool resolver is only valid during the synchronous admission callback; a read after that is
 * {@link Lookup.Unavailable}. Shadow engines therefore get a {@link PinnedUtxoLedgerView} that captured the
 * transaction's inputs during admission.</p>
 */
final class MempoolUtxoOverlayView extends ForwardingLedgerView {

    private final Function<Outpoint, Utxo> mempoolResolver;
    private final AdmissionCanonicalResolver canonical;

    /**
     * @param base            the ticked canonical view
     * @param mempoolResolver the mempool's admission resolver (mempool outputs first, then {@code canonical})
     * @param canonical       the canonical resolver the mempool was given, to recover exact view entries
     */
    MempoolUtxoOverlayView(LedgerView base, Function<Outpoint, Utxo> mempoolResolver,
                           AdmissionCanonicalResolver canonical) {
        super(base);
        this.mempoolResolver = Objects.requireNonNull(mempoolResolver, "mempoolResolver");
        this.canonical = canonical;
    }

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        Outpoint key = Outpoints.normalize(outpoint);
        Utxo resolved;
        try {
            resolved = mempoolResolver.apply(key);
        } catch (LedgerStateUnavailableException e) {
            return Lookup.unavailable(e.getMessage());
        } catch (RuntimeException e) {
            return Lookup.unavailable("mempool UTxO resolver: " + e.getMessage());
        }
        if (resolved == null) {
            return Lookup.absent();
        }
        UtxoEntry exact = canonical != null ? canonical.entryFor(resolved) : null;
        if (exact != null) {
            return Lookup.present(exact);
        }
        try {
            return Lookup.present(UtxoConversions.toEntry(key, resolved));
        } catch (RuntimeException e) {
            return Lookup.unavailable("cannot convert mempool output " + key.txHash() + "#" + key.index() + ": "
                    + e.getMessage());
        }
    }
}
