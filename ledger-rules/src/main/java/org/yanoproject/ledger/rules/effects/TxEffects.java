package org.yanoproject.ledger.rules.effects;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.model.HexStrings;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.List;
import java.util.Objects;

/**
 * What one valid transaction changes that later rules can observe (ADR-056 §3, invariant 4).
 *
 * <p>Immutable: the lists are unmodifiable copies. The CCL objects inside ({@code TransactionOutput},
 * {@code PoolRegistration}, {@code GovAction}) are mutable beans and must be treated as read-only.</p>
 *
 * @param txId        the transaction id, lowercase hex
 * @param phase2Valid the phase-2 verdict these effects were derived for; when false the effects
 *                    are collateral consumption and collateral return only
 * @param consumed    spent outpoints, in body order
 * @param produced    created outputs, in index order
 * @param changes     certificate, withdrawal and governance changes, in Haskell application order
 */
public record TxEffects(String txId, boolean phase2Valid, List<Outpoint> consumed, List<UtxoEntry> produced,
                        List<LedgerChange> changes) {

    public TxEffects {
        txId = HexStrings.normalize(txId, "txId", HexStrings.HASH32);
        consumed = Objects.requireNonNull(consumed, "consumed").stream().map(Outpoints::normalize).toList();
        produced = List.copyOf(Objects.requireNonNull(produced, "produced"));
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        if (!phase2Valid && !changes.isEmpty()) {
            throw new IllegalArgumentException(
                    "A phase-2-invalid transaction has no certificate or governance effects");
        }
    }

    /** Effects with only ledger changes, used for the in-transaction certificate fold. */
    public static TxEffects ofChanges(String txId, List<LedgerChange> changes) {
        return new TxEffects(txId, true, List.of(), List.of(), changes);
    }
}
