package org.yanoproject.ledger.rules.view.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Normalisation of hex-encoded hashes used as ledger-view keys.
 *
 * <p>Every key type in this package stores lowercase hex, so a lookup never misses because one
 * producer wrote uppercase and another lowercase.</p>
 */
public final class HexStrings {

    /** Length of a blake2b-224 hash: key, script and pool key hashes. */
    public static final int HASH28 = 28;
    /** Length of a blake2b-256 hash: transaction ids and VRF key hashes. */
    public static final int HASH32 = 32;

    private HexStrings() {
    }

    /**
     * Returns {@code hex} in lowercase after checking it is a non-empty, even-length hex string.
     *
     * @param hex  the hex string
     * @param what a short name for error messages
     * @return the lowercase form
     * @throws IllegalArgumentException if {@code hex} is not valid hex
     */
    public static String normalize(String hex, String what) {
        Objects.requireNonNull(hex, what);
        if (hex.isEmpty() || (hex.length() & 1) != 0) {
            throw new IllegalArgumentException(what + " is not an even-length hex string: '" + hex + "'");
        }
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) {
                throw new IllegalArgumentException(what + " is not hex: '" + hex + "'");
            }
        }
        return hex.toLowerCase(Locale.ROOT);
    }

    /**
     * Like {@link #normalize(String, String)} and also checks the decoded length.
     *
     * @param expectedBytes the required length in bytes (28 for key/script/pool hashes, 32 for
     *                      transaction ids and VRF key hashes)
     */
    public static String normalize(String hex, String what, int expectedBytes) {
        String normalized = normalize(hex, what);
        if (normalized.length() != expectedBytes * 2) {
            throw new IllegalArgumentException(what + " must be " + expectedBytes + " bytes, was "
                    + normalized.length() / 2 + ": '" + hex + "'");
        }
        return normalized;
    }
}
