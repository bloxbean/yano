package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveAddressParserTest {

    private static final String CREDENTIAL = "77".repeat(28);

    @Test
    void parsesShelleyBaseAddress() {
        String address = "00" + "11".repeat(28) + "22".repeat(28);

        var parts = ArchiveAddressParser.parse(address, Era.Babbage.getValue(),
                new AddressKeyCodec(), ignored -> null);

        assertThat(parts.raw()).containsExactly(HexUtil.decodeHexString(address));
        assertThat(parts.paymentCredential()).containsExactly(HexUtil.decodeHexString("11".repeat(28)));
        assertThat(parts.stakeCredential()).containsExactly(HexUtil.decodeHexString("22".repeat(28)));
        assertThat(parts.stakeCredentialType()).isEqualTo("key");
    }

    @Test
    void parsesEnterpriseAndRewardAddressesWithoutInventingCredentials() {
        var enterprise = ArchiveAddressParser.parse("60" + "33".repeat(28),
                Era.Babbage.getValue(), new AddressKeyCodec(), ignored -> null);
        var reward = ArchiveAddressParser.parse("e0" + "44".repeat(28),
                Era.Babbage.getValue(), new AddressKeyCodec(), ignored -> null);

        assertThat(enterprise.paymentCredential())
                .containsExactly(HexUtil.decodeHexString("33".repeat(28)));
        assertThat(enterprise.stakeCredential()).isNull();
        assertThat(reward.paymentCredential()).isNull();
        assertThat(reward.stakeCredential())
                .containsExactly(HexUtil.decodeHexString("44".repeat(28)));
    }

    @Test
    void resolvesPreConwayPointerWithProjectionNeutralTypes() {
        String address = "40" + "66".repeat(28) + "0a0000";
        AtomicReference<ArchiveAddressParser.PointerCoordinate> requested = new AtomicReference<>();

        var parts = ArchiveAddressParser.parse(address, Era.Babbage.getValue(),
                new AddressKeyCodec(), coordinate -> {
                    requested.set(coordinate);
                    return new ArchiveAddressParser.ResolvedStakeCredential(
                            "key", HexUtil.decodeHexString(CREDENTIAL));
                });

        assertThat(requested.get()).isEqualTo(new ArchiveAddressParser.PointerCoordinate(10, 0, 0));
        assertThat(parts.stakeCredentialType()).isEqualTo("key");
        assertThat(parts.stakeCredential()).containsExactly(HexUtil.decodeHexString(CREDENTIAL));
    }

    @Test
    void conwayPointerIsNotEffectiveAndDoesNotConsultResolver() {
        String address = "40" + "66".repeat(28) + "0a0000";

        var parts = ArchiveAddressParser.parse(address, Era.Conway.getValue(),
                new AddressKeyCodec(), ignored -> {
                    throw new AssertionError("Conway pointer must not be resolved");
                });

        assertThat(parts.stakeCredentialType()).isEqualTo("key");
        assertThat(parts.stakeCredential()).isNull();
    }

    @Test
    void parsesByronAddressWithoutShelleyCredentials() {
        String address = "FHnt4NL7yPXhCzCHVywZLqVsvwuG3HvwmjKXQJBrXh3h2aigv6uxkePbpzRNV8q";
        AddressKeyCodec codec = new AddressKeyCodec();

        var parts = ArchiveAddressParser.parse(address, Era.Byron.getValue(), codec, ignored -> null);

        assertThat(parts.raw()).isNotEmpty();
        assertThat(parts.addressKey()).containsExactly(codec.key(parts.raw()));
        assertThat(parts.paymentCredential()).isNull();
        assertThat(parts.stakeCredential()).isNull();
    }

    @Test
    void rejectsMalformedAddress() {
        assertThatThrownBy(() -> ArchiveAddressParser.parse("not-an-address",
                Era.Babbage.getValue(), new AddressKeyCodec(), ignored -> null))
                .isInstanceOf(ArchiveStoreException.class);
    }
}
