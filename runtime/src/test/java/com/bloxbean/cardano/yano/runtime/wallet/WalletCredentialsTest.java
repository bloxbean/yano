package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.ByronAddress;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import com.bloxbean.cardano.yano.runtime.genesis.AvvmAddressConverter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletCredentialsTest {
    // Mainnet block 6203514, transaction 369826b2f60b443d27966d222fa5bedc6b900326b77a09f15473d77f5ef2871c#0.
    static final String HISTORICAL_ADDRESS = "addr1q9d66zzs27kppmx8qc8h43q7m4hkxp5d39377lvxefvxd8j7eukjsdqc5c97t2zg5guqadepqqx6rc9m7wtnxy6tajjvk4a0kze4ljyuvvrpexg5up2sqxj33363v35gtew";
    static final String PAYMENT = "5bad085057ac10ecc7060f7ac41edd6f63068d8963ef7d86ca58669e";
    static final String STAKE = "5ecf2d283418a60be5a848a2380eb721000da1e0bbf39733134beca4";

    @Test void historicalTrailingBytesPreserveIdentityAndExtractBothCredentials() {
        byte[] bytes = WalletIndexStore.addressBytes(HISTORICAL_ADDRESS);
        assertThat(bytes).hasSize(78);
        assertThat(HexFormat.of().formatHex(bytes, 57, bytes.length))
                .isEqualTo("cb57afb0b35fc89c63061c9914e055001a518c7516");
        var credentials = new HashSet<WalletCredential>();
        WalletCredentials.address(HISTORICAL_ADDRESS, credentials);
        assertThat(credentials).containsExactlyInAnyOrder(
                new WalletCredential("payment", "key", PAYMENT), new WalletCredential("stake", "key", STAKE));
    }

    @Test void acceptsByronGenesisWithoutShelleyCredentials() {
        String address = AvvmAddressConverter.convertAvvmToByronAddress(
                Base64.getEncoder().encodeToString(new byte[32])).orElseThrow();
        byte[] bytes = new ByronAddress(address).getBytes();
        assertThat(WalletIndexStore.addressBytes(address)).isEqualTo(bytes);
        var credentials = new HashSet<WalletCredential>();
        WalletCredentials.address(address, credentials);
        assertThat(credentials).isEmpty();
    }

    @Test void cclExtractsKeyAndScriptCredentialsForAllShelleyTypes() {
        for (int type : new int[]{0, 1, 2, 3, 6, 7, 14, 15}) {
            int length = type <= 3 ? 57 : 29;
            byte[] raw = new byte[length];
            raw[0] = (byte) ((type << 4) | 1);
            Arrays.fill(raw, 1, 29, (byte) 1);
            if (type <= 3) Arrays.fill(raw, 29, 57, (byte) 2);
            var expected = new HashSet<WalletCredential>();
            if (type <= 7) expected.add(new WalletCredential("payment", type % 2 == 0 ? "key" : "script", "01".repeat(28)));
            if (type <= 3) expected.add(new WalletCredential("stake", type < 2 ? "key" : "script", "02".repeat(28)));
            if (type >= 14) expected.add(new WalletCredential("stake", type == 14 ? "key" : "script", "01".repeat(28)));
            for (byte[] bytes : new byte[][]{raw, Arrays.copyOf(raw, raw.length + 21)}) {
                String encoded = new Address(bytes).toBech32();
                assertThat(WalletIndexStore.addressBytes(encoded)).isEqualTo(bytes);
                var credentials = new HashSet<WalletCredential>();
                WalletCredentials.address(encoded, credentials);
                assertThat(credentials).isEqualTo(expected);
            }
        }
    }

    @Test void pointersExposeOnlyPaymentWithoutReinterpretingHistoricalPointerBytes() {
        for (String header : new String[]{"41", "51"}) {
            for (String suffix : new String[]{"000000", "81000203", "00000000", "80000000", "82ffffffffffffffff7f0000"}) {
                byte[] bytes = HexFormat.of().parseHex(header + "01".repeat(28) + suffix);
                String encoded = new Address(bytes).toBech32();
                assertThat(WalletIndexStore.addressBytes(encoded)).isEqualTo(bytes);
                var credentials = new HashSet<WalletCredential>();
                WalletCredentials.address(encoded, credentials);
                assertThat(credentials).containsExactly(new WalletCredential("payment",
                        header.equals("41") ? "key" : "script", "01".repeat(28)));
            }
        }
    }

    @Test void invalidEncodingIsRejectedByCcl() {
        for (String address : new String[]{null, "", "not-an-address",
                HISTORICAL_ADDRESS.substring(0, HISTORICAL_ADDRESS.length() - 1) + "q"}) {
            assertThatThrownBy(() -> WalletIndexStore.addressBytes(address)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
