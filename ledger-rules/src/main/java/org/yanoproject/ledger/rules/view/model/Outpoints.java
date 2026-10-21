package org.yanoproject.ledger.rules.view.model;

import org.yanoproject.api.utxo.model.Outpoint;

import java.util.Objects;

/**
 * Helpers for {@link Outpoint} keys.
 *
 * <p>{@code Outpoint} is a {@code core-api} record that does not normalise its hash. Ledger views and
 * overlays key on the lowercase form produced by {@link #normalize(Outpoint)}.</p>
 */
public final class Outpoints {

    private Outpoints() {
    }

    public static Outpoint of(String txHashHex, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("output index must be >= 0: " + index);
        }
        return new Outpoint(HexStrings.normalize(txHashHex, "outpoint tx hash", HexStrings.HASH32), index);
    }

    public static Outpoint normalize(Outpoint outpoint) {
        Objects.requireNonNull(outpoint, "outpoint");
        String normalized = HexStrings.normalize(outpoint.txHash(), "outpoint tx hash", HexStrings.HASH32);
        if (outpoint.index() < 0) {
            throw new IllegalArgumentException("output index must be >= 0: " + outpoint.index());
        }
        return normalized.equals(outpoint.txHash()) ? outpoint : new Outpoint(normalized, outpoint.index());
    }
}
