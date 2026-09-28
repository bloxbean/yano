package org.yanoproject.ledger.rules;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TxIdentityTest {

    /**
     * A body CCL would re-encode differently: keys out of order (fee first), a non-minimal 8-byte
     * fee, and the inputs as a tag-258 set holding an indefinite-length array.
     */
    private static final String NON_CANONICAL_BODY = "a3"
            + "02" + "1b00000000000000c8"
            + "00" + "d90102" + "9f" + "82" + "5820" + "11".repeat(32) + "00" + "ff"
            + "01" + "81" + "82" + "581d" + "61" + "22".repeat(28) + "1a000f4240";

    private static final String TAIL = "a0" + "f5" + "f6";

    @Test
    void txIdHashesTheOriginalBodyBytes() {
        byte[] tx = HexUtil.decodeHexString("84" + NON_CANONICAL_BODY + TAIL);

        assertThat(HexUtil.encodeHexString(TxIdentity.bodyBytes(tx))).isEqualTo(NON_CANONICAL_BODY);
        assertThat(TxIdentity.txId(tx)).isEqualTo(Blake2bUtil.blake2bHash256(
                HexUtil.decodeHexString(NON_CANONICAL_BODY)));
        assertThat(TxIdentity.txIdHex(tx)).isEqualTo(TransactionUtil.getTxHash(tx));
    }

    @Test
    void reserialisingWithCclWouldChangeTheId() throws Exception {
        byte[] tx = HexUtil.decodeHexString("84" + NON_CANONICAL_BODY + TAIL);
        Transaction decoded = Transaction.deserialize(tx);

        String reserialisedId = TransactionUtil.getTxHash(decoded);

        assertThat(reserialisedId).isNotEqualTo(TxIdentity.txIdHex(tx));
    }

    @Test
    void nonMinimalAndIndefiniteTopLevelArraysSliceTheSameBody() {
        byte[] canonical = HexUtil.decodeHexString("84" + NON_CANONICAL_BODY + TAIL);
        byte[] nonMinimalHeader = HexUtil.decodeHexString("9804" + NON_CANONICAL_BODY + TAIL);
        byte[] indefinite = HexUtil.decodeHexString("9f" + NON_CANONICAL_BODY + TAIL + "ff");

        assertThat(TxIdentity.txId(nonMinimalHeader)).isEqualTo(TxIdentity.txId(canonical));
        assertThat(TxIdentity.txId(indefinite)).isEqualTo(TxIdentity.txId(canonical));
    }

    @Test
    void indefiniteLengthBodyMapIsSlicedWhole() {
        String body = "bf" + "00" + "80" + "01" + "80" + "02" + "00" + "ff";
        byte[] tx = HexUtil.decodeHexString("84" + body + TAIL);

        assertThat(HexUtil.encodeHexString(TxIdentity.bodyBytes(tx))).isEqualTo(body);
    }

    @Test
    void rejectsNonTransactions() {
        assertThatThrownBy(() -> TxIdentity.bodyBytes(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TxIdentity.bodyBytes(HexUtil.decodeHexString("a0")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("array");
        assertThatThrownBy(() -> TxIdentity.bodyBytes(HexUtil.decodeHexString("8480")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("map");
        assertThatThrownBy(() -> TxIdentity.bodyBytes(HexUtil.decodeHexString("80a0")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> TxIdentity.bodyBytes(HexUtil.decodeHexString("9800a0")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> TxIdentity.bodyBytes(HexUtil.decodeHexString("84a20001")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Malformed CBOR");
    }
}
