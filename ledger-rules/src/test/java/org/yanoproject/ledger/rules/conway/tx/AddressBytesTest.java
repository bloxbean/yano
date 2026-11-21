package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.address.ByronAddress;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link AddressBytes}' reading of a Byron address, which decides as CCL's {@link ByronAddress} does. */
class AddressBytesTest {

    private static final String ROOT = "581c" + "11".repeat(28);

    /** {@code [#6.24(payload), crc32(payload)]}. */
    private static byte[] byron(String payload) {
        byte[] bytes = HexUtil.decodeHexString(payload);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return HexUtil.decodeHexString("82d818" + bytesHead(bytes.length) + payload
                + "1a%08x".formatted(crc.getValue()));
    }

    private static String bytesHead(int length) {
        if (length < 24) {
            return "%02x".formatted(0x40 + length);
        }
        return length < 0x100 ? "58%02x".formatted(length) : "59%04x".formatted(length);
    }

    /** The payload {@code [root, attributes, type]}. */
    private static String payload(String attributes, String type) {
        return "83" + ROOT + attributes + type;
    }

    /** Both readers accept or both reject, and read the same root, network and attribute size. */
    private static void decidesAsCcl(byte[] address) {
        ByronAddress ccl;
        try {
            ccl = new ByronAddress(address);
        } catch (RuntimeException rejected) {
            assertThatThrownBy(() -> AddressBytes.validate(address)).as(HexUtil.encodeHexString(address))
                    .isInstanceOf(TxDecodingException.class);
            return;
        }
        AddressBytes.validate(address);
        assertThat(AddressBytes.bootstrapRoot(address)).isEqualTo(ccl.getRoot());
        assertThat(AddressBytes.network(address)).isEqualTo(ccl.getProtocolMagic().isPresent() ? 0 : 1);
        int size = ccl.getDerivationPath().map(path -> path.length).orElse(0)
                + ccl.getUnknownAttributes().values().stream().mapToInt(value -> value.length).sum();
        assertThat(AddressBytes.bootstrapAttrsSize(address)).isEqualTo(size);
    }

    @Test
    void aByronAddressIsReadAsCclReadsIt() {
        String path = "4443" + "424344";
        String magic = "45" + "1a2d964a09";
        String[] payloads = {
            payload("a0", "00"),
            payload("a0", "02"),
            payload("a101" + path, "00"),
            payload("a102" + magic, "00"),
            payload("a3" + "01" + path + "02" + magic + "07" + "43010203", "00"),
            payload("a1" + "1807" + "43010203", "00"),
            // a non-canonical derivation path, protocol magic or type, a value after one, an indefinite one
            payload("a101" + "455803424344", "00"),
            payload("a102" + "451b00000001", "00"),
            payload("a102" + "4300" + "00", "00"),
            payload("a101" + "47" + "5f43424344ff", "00"),
            payload("a0", "1800"),
            // a protocol magic beyond Word32, type 1, a type beyond Word8
            payload("a102" + "491b0000000100000000", "00"),
            payload("a0", "01"),
            payload("a0", "190100"),
            // attributes indefinite, keys repeated or descending, a key beyond Word8, a value that is no byte string
            payload("bf" + "07" + "4101" + "ff", "00"),
            payload("a2" + "07" + "4101" + "07" + "4101", "00"),
            payload("a2" + "08" + "4101" + "07" + "4101", "00"),
            payload("a1" + "190100" + "4101", "00"),
            payload("a1" + "07" + "01", "00"),
            payload("a1" + "07" + "5f4101ff", "00"),
            // a root of 27 bytes or in chunks, a payload of 2 or 4 items, indefinite, or with a byte after it
            "83" + "581b" + "11".repeat(27) + "a0" + "00",
            "83" + "5f" + ROOT + "ff" + "a0" + "00",
            "82" + ROOT + "a0",
            "84" + ROOT + "a0" + "00" + "00",
            "9f" + ROOT + "a0" + "00" + "ff",
            payload("a0", "00") + "00",
            "9803" + ROOT + "a0" + "00",
        };
        for (String payload : payloads) {
            decidesAsCcl(byron(payload));
        }

        byte[] address = byron(payload("a0", "00"));
        String valid = HexUtil.encodeHexString(address);
        String crc = valid.substring(valid.length() - 10);
        String content = valid.substring(6, valid.length() - 10);
        String[] envelopes = {
            valid.substring(0, valid.length() - 2) + "00",
            "82d818" + content + "1b00000000" + crc.substring(2),
            "82d818" + content + "1b0000000100000000",
            "82d819" + content + crc,
            "82d90018" + content + crc,
            "82" + content + crc,
            "9802d818" + content + crc,
            "83d818" + content + crc + "00",
            valid + "00",
            "8000",
        };
        for (String envelope : envelopes) {
            decidesAsCcl(HexUtil.decodeHexString(envelope));
        }
    }

    /** {@code [#6.24([root, {3: attribute}, 0]), crc]}, an address whose one unknown attribute has the given size. */
    private static byte[] withAttribute(int size) {
        return byron(payload("a1" + "03" + bytesHead(size) + "5a".repeat(size), "00"));
    }

    /** An output to a Byron address of 16 KB of attributes is read in linear time. */
    @Test
    void anOutputWithLargeByronAttributesIsReadQuickly() {
        byte[] address = withAttribute(16_000);
        String output = "82" + "59%04x".formatted(address.length) + HexUtil.encodeHexString(address) + "00";
        byte[] tx = HexUtil.decodeHexString("84" + "a3" + "0081825820" + "00".repeat(32) + "00" + "0181" + output
                + "0200" + "a0f5f6");
        RawTransaction.parse(tx, null);

        long start = System.nanoTime();
        RawTransaction raw = RawTransaction.parse(tx, null);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).as("parse in ms").isLessThan(50);
        assertThat(AddressBytes.bootstrapAttrsSize(raw.outputs().getFirst().address())).isEqualTo(16_000);
        assertThat(AddressBytes.network(raw.outputs().getFirst().address())).isOne();
    }

    /**
     * AddressBytes reads a Byron address itself while CCL's {@link ByronAddress} encodes it in Base58 when built, in
     * quadratic time. When this fails, CCL builds it lazily: read the address with {@link ByronAddress} again.
     */
    @Test
    void cclsByronAddressStillEncodesBase58WhenBuilt() {
        byte[] address = withAttribute(16_000);
        new ByronAddress(address);
        long start = System.nanoTime();
        new ByronAddress(address);
        assertThat((System.nanoTime() - start) / 1_000_000).as("ByronAddress(byte[]) in ms").isGreaterThan(20);
    }
}
