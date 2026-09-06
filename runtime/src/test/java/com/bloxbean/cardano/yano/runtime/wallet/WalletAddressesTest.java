package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import com.bloxbean.cardano.yano.runtime.genesis.AvvmAddressConverter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletAddressesTest {
    @Test void acceptsByronGenesisAndRejectsChecksumOrEnvelopeCorruption() {
        String address = AvvmAddressConverter.convertAvvmToByronAddress(
                Base64.getEncoder().encodeToString(new byte[32])).orElseThrow();
        byte[] bytes = Base58.decode(address);
        assertThat(WalletIndexStore.addressBytes(address)).isEqualTo(bytes);
        var credentials = new HashSet<WalletCredential>();
        WalletCredentials.address(address, credentials);
        assertThat(credentials).isEmpty();
        bytes[bytes.length - 1] ^= 1;
        assertThatThrownBy(() -> WalletIndexStore.addressBytes(Base58.encode(bytes)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WalletIndexStore.addressBytes(Base58.encode(new byte[]{(byte) 0x82})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsTruncatedAndExtendedShelleyAddressesEvenWithValidBech32Checksum() {
        for (int type : new int[]{0, 1, 2, 3, 6, 7, 14, 15}) {
            int length = type <= 3 ? 57 : 29;
            byte[] raw = new byte[length];
            raw[0] = (byte) (type << 4);
            assertThat(WalletIndexStore.addressBytes(new Address(raw).toBech32())).isEqualTo(raw);
            for (int malformedLength : new int[]{1, length - 1, length + 1}) {
                String encoded = new Address(Arrays.copyOf(raw, malformedLength)).toBech32();
                assertThatThrownBy(() -> WalletIndexStore.addressBytes(encoded)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test void pointersRequireExactlyThreeCanonicalUnsignedComponents() {
        String payment = "40" + "01".repeat(28);
        for (String suffix : new String[]{"000000", "81000203", "81ffffffffffffffff7f0000"}) {
            byte[] bytes = HexFormat.of().parseHex(payment + suffix);
            assertThat(WalletIndexStore.addressBytes(new Address(bytes).toBech32())).isEqualTo(bytes);
        }
        for (String suffix : new String[]{"0000", "00000000", "80000000", "00800000", "000080", "82ffffffffffffffff7f0000"}) {
            String encoded = new Address(HexFormat.of().parseHex(payment + suffix)).toBech32();
            assertThatThrownBy(() -> WalletIndexStore.addressBytes(encoded)).as(suffix).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
