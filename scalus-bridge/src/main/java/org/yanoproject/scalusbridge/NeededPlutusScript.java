package org.yanoproject.scalusbridge;

/**
 * A Plutus script the transaction needs (Haskell {@code scriptsNeeded} restricted to scripts provided as
 * Plutus, {@code resolveNeededPlutusScriptsWithPurpose}), as computed by Scalus.
 *
 * @param purpose     the redeemer tag in lowercase ({@code spend}, {@code mint}, {@code cert},
 *                    {@code reward}, {@code voting}, {@code proposing})
 * @param index       the redeemer index within its purpose
 * @param scriptHash  the script hash, hex
 * @param language    1, 2 or 3 for Plutus V1, V2, V3 (4 for V4)
 * @param hasRedeemer whether the transaction carries a redeemer for it
 */
public record NeededPlutusScript(String purpose, int index, String scriptHash, int language, boolean hasRedeemer) {

    /** @return {@code PlutusV1}, {@code PlutusV2}, … as CCL and Haskell name the language */
    public String languageName() {
        return "PlutusV" + language;
    }
}
