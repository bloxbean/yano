package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoContractCodecTest {

    @Test
    void recordReceiptAndQueryListsRoundTripCanonically() {
        EutxoRecord record = new EutxoRecord(
                new EutxoOutpoint("01".repeat(32), 7),
                "addr_test1vr0sample",
                HexFormat.of().parseHex("820102"),
                EutxoRecord.Origin.TRANSACTION);
        EutxoReceipt receipt = new EutxoReceipt(
                EutxoReceipt.Status.ACCEPTED,
                "02".repeat(32),
                HexFormat.of().parseHex("03".repeat(32)),
                9,
                2,
                123,
                "",
                "");

        assertThat(EutxoRecord.decode(record.encode())).isEqualTo(record);
        assertThat(EutxoReceipt.decode(receipt.encode())).isEqualTo(receipt);
        assertThat(EutxoQueryCodec.decodeRecords(
                EutxoQueryCodec.records(List.of(record)))).containsExactly(record);
        assertThat(EutxoQueryCodec.decodeOptionalRecord(
                EutxoQueryCodec.optionalRecord(null))).isNull();
        assertThat(EutxoQueryCodec.decodeOptionalReceipt(
                EutxoQueryCodec.optionalReceipt(null))).isNull();
    }

    @Test
    void stateKeysAndOutpointsAreCanonicalAndBounded() {
        EutxoOutpoint outpoint = EutxoOutpoint.parse("ab".repeat(32) + "#12");

        assertThat(outpoint.transactionId()).isEqualTo("ab".repeat(32));
        assertThat(outpoint.index()).isEqualTo(12);
        assertThat(EutxoStateKeys.utxo(outpoint))
                .asString()
                .isEqualTo("eutxo/v1/u/" + outpoint);
        assertThat(EutxoStateKeys.addressIndex("addr_test1x")).hasSizeLessThan(100);
        assertThatThrownBy(() -> EutxoOutpoint.parse("not-an-outpoint"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileDigestIsStableAndChangesOnlyWithProfileSemantics() {
        assertThat(EutxoProfile.V1.digestHex()).isEqualTo(
                "2499d01ee7cb0d09d0d498040c6351accd9da83df31666cd4463d0b1722d1212");
        assertThat(EutxoProfile.V2.digestHex()).isEqualTo(
                "8cd4adb72def2c31dc8551a02f67429ea468bb2024dbe85a1dc7300590c9d1bf");
    }
}
