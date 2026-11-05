package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HaskellPolicy}: M1's keyed-record proof (ADR 0003 I13), where the walk and {@link RawTransaction}'s reader
 * must agree, and the rules the decoder version decides.
 */
class HaskellPolicyTest {

    private static final String HASH28 = "581c" + "11".repeat(28);
    private static final String HASH32 = "5820" + "22".repeat(32);
    private static final String INPUTS = "0081" + "82" + HASH32 + "00";
    private static final String OUTPUTS = "0180";
    private static final String FEE = "0200";
    private static final String BODY = "a3" + INPUTS + OUTPUTS + FEE;
    private static final String CRED = "8200" + HASH28;
    private static final String ANCHOR = "82" + "60" + HASH32;

    private static byte[] tx(String body, String witnesses) {
        return HexUtil.decodeHexString("84" + body + witnesses + "f5f6");
    }

    private static boolean rejects(Consumer<byte[]> read, byte[] tx) {
        try {
            read.accept(tx);
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The walk and the reader agree, and the walk says why it rejects. */
    private static void bothReject(String body, String witnesses, String walkReason) {
        byte[] tx = tx(body, witnesses);
        assertThat(rejects(raw -> RawTransaction.parse(raw, null), tx)).as("the reader").isTrue();
        assertThatThrownBy(() -> RawTx.walk(tx, Era.Conway, HaskellPolicy.CONWAY))
                .isInstanceOf(TxDecodingException.class).hasMessageContaining(walkReason);
    }

    private static void bothAccept(String body, String witnesses) {
        byte[] tx = tx(body, witnesses);
        assertThatCode(() -> RawTransaction.parse(tx, null)).doesNotThrowAnyException();
        assertThatCode(() -> RawTx.walk(tx, Era.Conway, HaskellPolicy.CONWAY)).doesNotThrowAnyException();
    }

    @Test
    void aBodyWithItsFieldsPasses() {
        bothAccept(BODY, "a0");
        bothAccept("bf" + INPUTS + OUTPUTS + FEE + "ff", "a0");
        bothAccept("a4" + INPUTS + OUTPUTS + FEE + "03" + "00", "a0");
    }

    @Test
    void aBodyWithNoFee() {
        bothReject("a2" + INPUTS + OUTPUTS, "a0", "transaction_body at 1 has no COIN");
    }

    @Test
    void aBodyWithNoInputsOrNoOutputs() {
        bothReject("a2" + OUTPUTS + FEE, "a0", "has no SET");
        bothReject("a2" + INPUTS + FEE, "a0", "has no LIST");
    }

    @Test
    void anUnknownBodyKey() {
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "0c" + "00", "a0", "unknown transaction_body key 0c");
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "17" + "00", "a0", "unknown transaction_body key 17");
    }

    @Test
    void aKeyThatIsNotAnUnsignedInteger() {
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "20" + "00", "a0", "unknown transaction_body key 20");
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "4100" + "00", "a0", "unknown transaction_body key 4100");
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "c203" + "00", "a0", "unknown transaction_body key c203");
    }

    @Test
    void aFieldOutsideItsEra() {
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "06" + "a0", "a0", "transaction_body key 06 is not in Conway");
    }

    @Test
    void anUnknownWitnessKey() {
        bothReject(BODY, "a108" + "80", "unknown transaction_witness_set key 08");
    }

    @Test
    void aRepeatedKey() {
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "02" + "00", "a0", "repeated transaction_body key 02");
        bothReject(BODY, "a2" + "0081" + "82" + "5820" + "33".repeat(32) + "5840" + "44".repeat(64)
                + "0081" + "82" + "5820" + "33".repeat(32) + "5840" + "44".repeat(64),
                "repeated transaction_witness_set key 00");
    }

    @Test
    void equalKeysEncodedDifferently() {
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "1802" + "00", "a0", "repeated transaction_body key 1802");
        bothReject("a4" + INPUTS + OUTPUTS + "1802" + "00" + FEE, "a0", "repeated transaction_body key 02");
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "190002" + "00", "a0", "repeated transaction_body key 190002");
    }

    @Test
    void aNonMinimalKeyIsTheField() {
        bothAccept("a3" + INPUTS + OUTPUTS + "1802" + "00", "a0");
    }

    @Test
    void aTaggedRecord() {
        bothReject("d81e" + BODY, "a0", "transaction_body at 1 is not an untagged map");
        bothReject(BODY, "d81ea0", "transaction_witness_set at");
    }

    @Test
    void aCertificateIsWalkedInTheBody() {
        bothAccept("a4" + INPUTS + OUTPUTS + FEE + "0481" + "8200" + CRED, "a0");
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "0480", "a0", "Certificates");
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "0482" + "8200" + CRED + "8200" + CRED, "a0",
                "duplicate certificate 1");
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "0481" + "8200" + "8200" + "581b" + "11".repeat(27), "a0",
                "key hash of 27 bytes");
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "0481" + "8205" + CRED, "a0", "certificate key 05");
    }

    @Test
    void aValueTooDeepForARecursiveReaderIsRejected() {
        String deep = "81".repeat(200_000) + "00";
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "0481" + "830f" + CRED + deep, "a0", "");
        bothReject("a4" + INPUTS + OUTPUTS + FEE + "0481" + "8200" + deep, "a0", "");
    }

    private static void certificate(HaskellPolicy policy, String certificate) {
        policy.walk(CertKind.RECORD, StrictCbor.span(HexUtil.decodeHexString(certificate)));
    }

    @Test
    void definiteByteStringsAndAnchorsEndBelowDecoderVersion12() {
        String chunked = "5f" + "5801" + "11" + "581b" + "11".repeat(27) + "ff";
        String keyDelegation = "8302" + CRED;
        HaskellPolicy eleven = new HaskellPolicy(11);
        HaskellPolicy twelve = new HaskellPolicy(12);

        assertThatThrownBy(() -> certificate(eleven, keyDelegation + chunked))
                .isInstanceOf(TxDecodingException.class).hasMessageContaining("indefinite-length byte string");
        assertThatCode(() -> certificate(twelve, keyDelegation + chunked)).doesNotThrowAnyException();

        String update = "8312" + CRED;
        assertThatCode(() -> certificate(eleven, update + ANCHOR)).doesNotThrowAnyException();
        assertThatThrownBy(() -> certificate(eleven, update + "9f" + "60" + HASH32 + "ff"))
                .isInstanceOf(TxDecodingException.class).hasMessageContaining("indefinite-length array");
        assertThatCode(() -> certificate(twelve, update + "9f" + "60" + HASH32 + "ff")).doesNotThrowAnyException();
    }

    @Test
    void aMarginOfBignumsEndsAtDecoderVersion12() {
        String pool = "8a03" + HASH28 + HASH32 + "0000" + "d81e82" + "c24101" + "02" + "581de0" + "44".repeat(28)
                + "80" + "80" + "f6";
        assertThatCode(() -> certificate(new HaskellPolicy(11), pool)).doesNotThrowAnyException();
        assertThatThrownBy(() -> certificate(new HaskellPolicy(12), pool)).isInstanceOf(TxDecodingException.class);
    }

    @Test
    void decoderVersionsBelowConwayAreNotKnown() {
        assertThatThrownBy(() -> new HaskellPolicy(8)).isInstanceOf(IllegalArgumentException.class);
    }
}
