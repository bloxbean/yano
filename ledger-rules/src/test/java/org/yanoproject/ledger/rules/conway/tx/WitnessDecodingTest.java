package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Native scripts ({@code evalTimelock}), auxiliary data ({@code AlonzoTxAuxData} and {@code decodeMetadatum} at
 * protocol versions 9–11) and the witness-set decoding rules the witness checks rely on.
 */
class WitnessDecodingTest {

    private static final String KEY = "11".repeat(28);

    private static Timelock script(String hex) {
        return Timelock.decode(HexUtil.decodeHexString(hex));
    }

    @Test
    void timelocksEvaluateAsEvalTimelock() {
        Set<String> signed = Set.of(KEY);
        assertThat(Timelock.evaluate(script("8200581c" + KEY), signed, null, null)).isTrue();
        assertThat(Timelock.evaluate(script("8200581c" + KEY), Set.of(), null, null)).isFalse();
        assertThat(Timelock.evaluate(script("820180"), Set.of(), null, null)).as("all of nothing").isTrue();
        assertThat(Timelock.evaluate(script("820280"), Set.of(), null, null)).as("any of nothing").isFalse();
        assertThat(Timelock.evaluate(script("83030080"), Set.of(), null, null)).as("0 of nothing").isTrue();
        assertThat(Timelock.evaluate(script("830320" + "80"), Set.of(), null, null)).as("-1 of nothing").isTrue();
        assertThat(Timelock.evaluate(script("83030281" + "8200581c" + KEY), signed, null, null))
                .as("2 of 1").isFalse();

        // [4, 100]: holds when the transaction's lower bound is present and at least 100
        Timelock start = script("82041864");
        assertThat(Timelock.evaluate(start, Set.of(), null, null)).isFalse();
        assertThat(Timelock.evaluate(start, Set.of(), BigInteger.valueOf(100), null)).isTrue();
        assertThat(Timelock.evaluate(start, Set.of(), BigInteger.valueOf(99), null)).isFalse();
        // [5, 100]: holds when the transaction's upper bound is present and at most 100
        Timelock expire = script("82051864");
        assertThat(Timelock.evaluate(expire, Set.of(), null, null)).isFalse();
        assertThat(Timelock.evaluate(expire, Set.of(), null, BigInteger.valueOf(100))).isTrue();
        assertThat(Timelock.evaluate(expire, Set.of(), null, BigInteger.valueOf(101))).isFalse();
    }

    @Test
    void malformedTimelocksDoNotDecode() {
        assertThatThrownBy(() -> script("820658" + "1c" + KEY)).isInstanceOf(TxDecodingException.class);
        assertThatThrownBy(() -> script("8300581c" + KEY + "00")).isInstanceOf(TxDecodingException.class);
        assertThatThrownBy(() -> script("82005801" + "00")).isInstanceOf(TxDecodingException.class);
    }

    @Test
    void auxiliaryDataDecodesAsConway() {
        // Shelley map, Allegra array, Alonzo tag 259 with PlutusV3 scripts
        assertThat(RawAuxData.decode(HexUtil.decodeHexString("a1186763616263")).plutusScripts()).isEmpty();
        assertThat(RawAuxData.decode(HexUtil.decodeHexString("82a080")).plutusScripts()).isEmpty();
        RawAuxData alonzo = RawAuxData.decode(HexUtil.decodeHexString("d90103a2" + "00a0" + "0481" + "4401020304"));
        assertThat(alonzo.plutusScripts()).singleElement()
                .satisfies(s -> assertThat(s.language()).isEqualTo(RawScript.PLUTUS_V3));

        // decodeMetadatum: at most 64 bytes of bytes or text (the concatenation for indefinite strings)
        assertThat(RawAuxData.decode(HexUtil.decodeHexString("a1015840" + "00".repeat(64)))).isNotNull();
        assertDecodingFailure("a1015841" + "00".repeat(65), "64 bytes");
        assertDecodingFailure("a1017841" + "61".repeat(65), "64 bytes");
        assertDecodingFailure("a1017f7820" + "61".repeat(32) + "7821" + "61".repeat(33) + "ff", "64 bytes");
        assertDecodingFailure("a10162c328", "UTF-8");
        // integers of at most 64 bits; no big-number tags
        assertThat(RawAuxData.decode(HexUtil.decodeHexString("a1013bffffffffffffffff"))).isNotNull();
        assertDecodingFailure("a101c249010000000000000000", "Unsupported");
        // no duplicate label; no PlutusV4 (key 5) or unknown key; Allegra form has exactly two fields
        assertDecodingFailure("a2010101", "duplicate metadata label");
        assertDecodingFailure("d90103a1" + "0580", "PlutusV4");
        assertDecodingFailure("d90103a1" + "0680", "unknown auxiliary data key");
        assertDecodingFailure("d90103a2" + "00a0" + "00a0", "Duplicate key");
        assertDecodingFailure("83a08080", "2 elements");
    }

    private static void assertDecodingFailure(String hex, String message) {
        assertThatThrownBy(() -> RawAuxData.decode(HexUtil.decodeHexString(hex)))
                .isInstanceOf(TxDecodingException.class).hasMessageContaining(message);
    }

    @Test
    void scriptsHashWithTheirLanguageTag() {
        // MutationWorld.ALWAYS_SUCCEEDS: PlutusV3 binary 450101002499, hash 186e32fa…
        RawScript v3 = new RawScript(RawScript.PLUTUS_V3, HexUtil.decodeHexString("450101002499"));
        assertThat(v3.hashHex()).isEqualTo("186e32faa80a26810392fda6d559c7ed4721a65ce1c9d4ef3e1c87b4");
        // Amaru's corpus PlutusV2 script from a reference script [2, h'46010000222499']
        RawScript v2 = RawScript.fromScriptRef(HexUtil.decodeHexString("d8184a82024746010000222499"));
        assertThat(v2.hashHex()).isEqualTo("52c6af0c9b744b4eecce838538a52ceb155038b3de68e2bb2fa8fc37");
        assertThatThrownBy(() -> RawScript.fromScriptRef(HexUtil.decodeHexString("820441ff")))
                .isInstanceOf(TxDecodingException.class);
    }
}
