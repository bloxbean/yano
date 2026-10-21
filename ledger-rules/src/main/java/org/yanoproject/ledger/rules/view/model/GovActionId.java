package org.yanoproject.ledger.rules.view.model;

import java.util.Objects;

/**
 * A governance action id: the id of the proposing transaction and the proposal's index in that
 * transaction's {@code proposal_procedures} (Haskell {@code GovActionId txid idx},
 * {@code Conway/Rules/Gov.hs:486}).
 *
 * @param txHashHex lowercase hex transaction id
 * @param index     zero-based index in {@code proposal_procedures}
 */
public record GovActionId(String txHashHex, int index) {

    public GovActionId {
        txHashHex = HexStrings.normalize(txHashHex, "gov action tx id", HexStrings.HASH32);
        if (index < 0) {
            throw new IllegalArgumentException("gov action index must be >= 0: " + index);
        }
    }

    /** Converts a CCL governance action id; returns {@code null} for {@code null}. */
    public static GovActionId of(com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId id) {
        // Fully qualified: the CCL type has the same simple name as this record.
        if (id == null) {
            return null;
        }
        return new GovActionId(Objects.requireNonNull(id.getTransactionId(), "transactionId"),
                id.getGovActionIndex());
    }

    @Override
    public String toString() {
        return txHashHex + "#" + index;
    }
}
