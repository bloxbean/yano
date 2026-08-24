package com.bloxbean.cardano.yano.archive.core.address;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StakeAddressCodecTest {
    private final byte[] credential = new byte[28];

    @Test
    void usesMainnetPrefixOnlyForMainnetMagic() {
        assertThat(StakeAddressCodec.encode(764_824_073L, "key", credential)).startsWith("stake1");
        assertThat(StakeAddressCodec.encode(1, "key", credential)).startsWith("stake_test1");
        assertThat(StakeAddressCodec.encode(42, "script", credential)).startsWith("stake_test1");
    }

    @Test
    void absentCredentialHasNoStakeAddress() {
        assertThat(StakeAddressCodec.encode(1, null, null)).isNull();
    }
}
