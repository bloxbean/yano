package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Native scripts ({@code evalTimelock}), auxiliary data ({@code AlonzoTxAuxData} and {@code decodeMetadatum} at
 * protocol versions 9–11) and the witness-set decoding rules the witness checks rely on.
 */
class WitnessDecodingTest {

    private static final String KEY = "11".repeat(28);

    private static RawScript script(String hex) {
        return RawScript.of(RawScript.NATIVE, StrictCbor.span(HexUtil.decodeHexString(hex)));
    }

    private static boolean holds(String hex, Set<String> signed, BigInteger start, BigInteger expire) {
        return script(hex).nativeScriptHolds(signed, start, expire);
    }

    @Test
    void timelocksEvaluateAsEvalTimelock() {
        Set<String> signed = Set.of(KEY);
        assertThat(holds("8200581c" + KEY, signed, null, null)).isTrue();
        assertThat(holds("8200581c" + KEY, Set.of(), null, null)).isFalse();
        assertThat(holds("820180", Set.of(), null, null)).as("all of nothing").isTrue();
        assertThat(holds("820280", Set.of(), null, null)).as("any of nothing").isFalse();
        assertThat(holds("83030080", Set.of(), null, null)).as("0 of nothing").isTrue();
        assertThat(holds("830320" + "80", Set.of(), null, null)).as("-1 of nothing").isTrue();
        assertThat(holds("83030281" + "8200581c" + KEY, signed, null, null)).as("2 of 1").isFalse();
        assertThat(holds("9f00581c" + KEY + "ff", signed, null, null)).as("indefinite-length arrays").isTrue();
        assertThat(holds("9f019f9f00581c" + KEY + "ffffff", signed, null, null)).isTrue();

        // [4, 100]: holds when the transaction's lower bound is present and at least 100
        String start = "82041864";
        assertThat(holds(start, Set.of(), null, null)).isFalse();
        assertThat(holds(start, Set.of(), BigInteger.valueOf(100), null)).isTrue();
        assertThat(holds(start, Set.of(), BigInteger.valueOf(99), null)).isFalse();
        // [5, 100]: holds when the transaction's upper bound is present and at most 100
        String expire = "82051864";
        assertThat(holds(expire, Set.of(), null, null)).isFalse();
        assertThat(holds(expire, Set.of(), null, BigInteger.valueOf(100))).isTrue();
        assertThat(holds(expire, Set.of(), null, BigInteger.valueOf(101))).isFalse();
        // Slots are Word64: a bound above 2^63 compares unsigned.
        assertThat(holds("82051bffffffffffffffff", Set.of(), null, new BigInteger("18446744073709551615")))
                .isTrue();
    }

    @Test
    void malformedTimelocksDoNotDecode() {
        assertThatThrownBy(() -> script("820658" + "1c" + KEY)).isInstanceOf(TxDecodingException.class);
        assertThatThrownBy(() -> script("8300581c" + KEY + "00")).isInstanceOf(TxDecodingException.class);
        assertThatThrownBy(() -> script("82005801" + "00")).isInstanceOf(TxDecodingException.class);
        // A key hash is a definite-length byte string below decoder version 12, although CCL joins the chunks.
        assertThatThrownBy(() -> script("82005f" + "580e" + KEY.substring(0, 28) + "580e" + KEY.substring(28) + "ff"))
                .isInstanceOf(TxDecodingException.class).hasMessageContaining("indefinite-length byte string");
        assertThatThrownBy(() -> script("8201815f" + "580e" + KEY.substring(0, 28) + "580e" + KEY.substring(28)
                + "ff")).isInstanceOf(TxDecodingException.class);
    }

    @Test
    void auxiliaryDataDecodesAsConway() {
        // Shelley map, Allegra array, Alonzo tag 259 with PlutusV3 scripts
        assertThatCode(() -> validate("a1186763616263")).doesNotThrowAnyException();
        assertThatCode(() -> validate("82a080")).doesNotThrowAnyException();
        // {0: [], 1: [], 2: 0}, {}, true, aux: the auxiliary data's scripts come from CCL's view
        RawTransaction alonzo = RawTransaction.parse(HexUtil.decodeHexString("84" + "a3008001800200" + "a0" + "f5"
                + "d90103a2" + "00a0" + "0481" + "4401020304"), null);
        assertThat(alonzo.auxScripts()).singleElement()
                .satisfies(s -> assertThat(s.language()).isEqualTo(RawScript.PLUTUS_V3));
        // the auxiliary data's scripts decode as Haskell decodes them: a native script of an unknown type, a chunked
        // Plutus script
        for (String aux : List.of("82a081" + "8206581c" + KEY, "d90103a1" + "0481" + "5f4101ff")) {
            assertThatThrownBy(() -> RawTransaction.parse(HexUtil.decodeHexString("84" + "a3008001800200" + "a0" + "f5"
                    + aux), null)).as(aux).isInstanceOf(TxDecodingException.class);
        }

        // decodeMetadatum: at most 64 bytes of bytes or text (the concatenation for indefinite strings)
        assertThatCode(() -> validate("a1015840" + "00".repeat(64))).doesNotThrowAnyException();
        assertDecodingFailure("a1015841" + "00".repeat(65), "64 bytes");
        assertDecodingFailure("a1017841" + "61".repeat(65), "64 bytes");
        assertDecodingFailure("a1017f7820" + "61".repeat(32) + "7821" + "61".repeat(33) + "ff", "64 bytes");
        assertDecodingFailure("a10162c328", "UTF-8");
        // integers of at most 64 bits; no big-number tags
        assertThatCode(() -> validate("a1013bffffffffffffffff")).doesNotThrowAnyException();
        assertDecodingFailure("a101c249010000000000000000", "Unsupported");
        // no duplicate label; no PlutusV4 (key 5) or unknown key; Allegra form has exactly two fields
        assertDecodingFailure("a201010101", "duplicate metadata label");
        assertDecodingFailure("d90103a1" + "0580", "PlutusV4");
        assertDecodingFailure("d90103a1" + "0680", "unknown auxiliary data key");
        assertDecodingFailure("d90103a2" + "00a0" + "00a0", "Duplicate key");
        assertDecodingFailure("83a08080", "2 elements");
    }

    @Test
    void metadataOfAnyDepthIsCheckedWithoutRecursion() {
        // {1: [[[ ... [0] ... ]]]} nested 100,000 deep, and an indefinite map whose break follows a key
        int depth = 100_000;
        assertThatCode(() -> validate("a101" + "81".repeat(depth) + "00")).doesNotThrowAnyException();
        assertDecodingFailure("a101" + "81".repeat(depth) + "c0" + "00", "Unsupported");
        assertDecodingFailure("a101bf01ff", "BREAK");
    }

    private static void validate(String hex) {
        RawAuxData.validate(StrictCbor.span(HexUtil.decodeHexString(hex)));
    }

    private static void assertDecodingFailure(String hex, String message) {
        assertThatThrownBy(() -> validate(hex)).isInstanceOf(TxDecodingException.class).hasMessageContaining(message);
    }

    @Test
    void scriptsHashWithTheirLanguageTag() {
        // MutationWorld.ALWAYS_SUCCEEDS: PlutusV3 binary 450101002499, hash 186e32fa…
        RawScript v3 = RawScript.of(RawScript.PLUTUS_V3, StrictCbor.span(HexUtil.decodeHexString("46450101002499")));
        assertThat(v3.hashHex()).isEqualTo("186e32faa80a26810392fda6d559c7ed4721a65ce1c9d4ef3e1c87b4");
        // Amaru's corpus PlutusV2 script from a reference script [2, h'46010000222499']
        RawScript v2 = RawScript.fromScriptRef(HexUtil.decodeHexString("d8184a82024746010000222499"));
        assertThat(v2.hashHex()).isEqualTo("52c6af0c9b744b4eecce838538a52ceb155038b3de68e2bb2fa8fc37");
        assertThatThrownBy(() -> RawScript.fromScriptRef(HexUtil.decodeHexString("820441ff")))
                .isInstanceOf(TxDecodingException.class);
    }
}
