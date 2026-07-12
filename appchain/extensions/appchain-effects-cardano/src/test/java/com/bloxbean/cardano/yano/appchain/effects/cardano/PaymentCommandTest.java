package com.bloxbean.cardano.yano.appchain.effects.cardano;

import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** FX-M4: cardano.payment payload decoding — CBOR map, JSON fallback, malformation. */
class PaymentCommandTest {

    @Test
    void decodesCborMap() {
        co.nstant.in.cbor.model.Map map = new co.nstant.in.cbor.model.Map();
        map.put(new UnicodeString("to"), new UnicodeString("addr_test1qz..."));
        map.put(new UnicodeString("lovelace"), new UnsignedInteger(1_500_000));
        map.put(new UnicodeString("memo"), new UnicodeString("rel-42"));
        PaymentCommand command = PaymentCommand.decode(CborSerializationUtil.serialize(map));
        assertThat(command).isNotNull();
        assertThat(command.to()).isEqualTo("addr_test1qz...");
        assertThat(command.lovelace()).isEqualTo(1_500_000);
        assertThat(command.memo()).isEqualTo("rel-42");
    }

    @Test
    void decodesJsonFallback() {
        PaymentCommand command = PaymentCommand.decode(
                "{\"to\":\"addr_test1qq..\",\"lovelace\":100,\"memo\":\"x\"}"
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(command).isNotNull();
        assertThat(command.to()).isEqualTo("addr_test1qq..");
        assertThat(command.lovelace()).isEqualTo(100);
    }

    @Test
    void rejectsMalformation() {
        assertThat(PaymentCommand.decode(null)).isNull();
        assertThat(PaymentCommand.decode(new byte[0])).isNull();
        assertThat(PaymentCommand.decode("garbage".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(PaymentCommand.decode(
                "{\"to\":\"addr\",\"lovelace\":0}".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(PaymentCommand.decode(
                "{\"lovelace\":5}".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(PaymentCommand.decode(new byte[5000])).isNull(); // size cap
    }
}
