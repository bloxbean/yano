package com.bloxbean.cardano.yano.api.utxo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StakeCredentialExtractorTest {
    private static final String BASE_ADDRESS =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";
    private static final String POINTER_ADDRESS =
            "addr1gxrgsz5tkx0vsapdhyrk09w9zplhllr94zy70vycpll2egsvpsxqgnmy5k";

    @Test
    void bech32AndHexRepresentationsProduceIdenticalValueSemanticCredential() {
        Address parsed = new Address(BASE_ADDRESS);
        String hex = HexUtil.encodeHexString(parsed.getBytes());

        StakeCredentialId bech32 = StakeCredentialExtractor.extractNonPointer(BASE_ADDRESS);
        StakeCredentialId rawHex = StakeCredentialExtractor.extractNonPointer(hex);

        assertThat(bech32).isEqualTo(rawHex);
        assertThat(bech32.credentialHash()).hasSize(StakeCredentialId.HASH_LENGTH);
        assertThat(bech32.credentialHash()).isNotSameAs(bech32.credentialHash());
    }

    @Test
    void nonStakeLegacyAddressesAreSkippedAndMalformedShelleyFailsClosed() {
        assertThat(StakeCredentialExtractor.extractNonPointer(
                "DdzFFzCqrhsxabc-not-a-valid-byron-checksum")).isNull();
        assertThatThrownBy(() -> StakeCredentialExtractor.extractNonPointer(
                "addr1notavalidchecksum"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed Shelley payment address");
        assertThat(StakeCredentialExtractor.extractNonPointer((String) null)).isNull();
    }

    @Test
    void pointerBech32AndHexRepresentationsProduceIdenticalTuple() {
        Address parsed = new Address(POINTER_ADDRESS);
        String hex = HexUtil.encodeHexString(parsed.getBytes());

        assertThat(StakeCredentialExtractor.extractPointer(POINTER_ADDRESS))
                .isEqualTo(StakeCredentialExtractor.extractPointer(hex));
    }
}
