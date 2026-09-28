package org.yanoproject.runtime.validation;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The canonical UTxO resolver handed to the mempool during engine-mode admission: it answers from the
 * admission snapshot's (ticked) view instead of the live UTxO store, so the mempool's chained-UTxO resolver,
 * every validation listener and the engine all see one canonical generation.
 *
 * <p>Absent → {@code null} (as the live resolver answers); Unavailable → a
 * {@link LedgerStateUnavailableException}, which the engine path turns into
 * {@code ENGINE.LedgerStateUnavailable} (fail closed, never "absent"). Each returned {@link Utxo} remembers the
 * exact {@link UtxoEntry} it came from, so the engine gets the entry with its original inline datum bytes.</p>
 */
final class AdmissionCanonicalResolver implements Function<Outpoint, Utxo> {

    private final LedgerView view;
    private final Map<Utxo, UtxoEntry> entries = Collections.synchronizedMap(new IdentityHashMap<>());

    AdmissionCanonicalResolver(LedgerView view) {
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public Utxo apply(Outpoint outpoint) {
        return switch (view.utxo(outpoint)) {
            case Lookup.Present<UtxoEntry> p -> {
                Utxo utxo = UtxoConversions.toUtxo(p.value());
                entries.put(utxo, p.value());
                yield utxo;
            }
            case Lookup.Absent<UtxoEntry> a -> null;
            case Lookup.Unavailable<UtxoEntry> u -> throw new LedgerStateUnavailableException(u.reason());
        };
    }

    /** @return the view entry a {@link Utxo} returned by {@link #apply} came from, or {@code null} */
    UtxoEntry entryFor(Utxo utxo) {
        return utxo == null ? null : entries.get(utxo);
    }
}
