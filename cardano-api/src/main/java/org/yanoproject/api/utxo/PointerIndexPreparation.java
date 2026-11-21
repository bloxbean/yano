package org.yanoproject.api.utxo;

/** Result of checking the optional pointer UTXO index for a boundary read. */
public record PointerIndexPreparation(boolean ready) {
    public static PointerIndexPreparation unavailable() {
        return new PointerIndexPreparation(false);
    }

    public static PointerIndexPreparation available() {
        return new PointerIndexPreparation(true);
    }
}
