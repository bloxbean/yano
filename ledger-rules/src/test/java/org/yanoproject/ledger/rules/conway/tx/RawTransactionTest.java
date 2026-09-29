package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.fixtures.tx.BuiltTx;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The raw transaction model: original-bytes slices, Haskell's size, and Haskell's decoding rules. */
class RawTransactionTest {

    private static final String INPUT = "825820" + "00".repeat(32) + "00";
    /** Indefinite body map, tag-258 indefinite input set, indefinite output array of one legacy output. */
    private static String body(String extraEntries) {
        return BODY.substring(0, BODY.length() - 2) + extraEntries + "ff";
    }

    private static String withOutput(String output) {
        return "84" + BODY.replace("019f82581d60" + "11".repeat(28) + "1a000f4240ff", "0181" + output) + "a0f5f6";
    }

    private static void assertDecodingFailure(String txHex, String message) {
        assertThatThrownBy(() -> RawTransaction.parse(HexUtil.decodeHexString(txHex), null))
                .isInstanceOf(TxDecodingException.class).hasMessageContaining(message);
    }

    private static final String BODY = "bf"
            + "00d901029f" + INPUT + "ff"
            + "019f82581d60" + "11".repeat(28) + "1a000f4240ff"
            + "021a00030d40"
            + "ff";

    @Test
    void slicesTheOriginalBytesAndSizesAsHaskell() {
        BuiltTx built = EngineTestSupport.build(MutationWorld.scriptSpec());
        RawTransaction raw = RawTransaction.parse(built.cbor(), built.tx());
        assertThat(raw.txId()).isEqualTo(TxIdentity.txId(built.cbor()));
        // toCBORForSizeComputation drops the one-byte is_valid flag (Alonzo/Tx.hs:432-443)
        assertThat(raw.size()).isEqualTo(built.cbor().length - 1);
        assertThat(raw.inputs()).hasSize(2);
        assertThat(raw.redeemers()).singleElement().satisfies(r -> {
            assertThat(r.tag()).isZero();
            assertThat(r.index()).isEqualTo(1);
            assertThat(r.mem()).isEqualTo(BigInteger.valueOf(100_000));
        });
        assertThat(raw.isValid()).isTrue();
        assertThat(raw.auxData()).isNull();
    }

    @Test
    void readsIndefiniteLengthsTagged258SetsAndMapFormRedeemers() {
        String witnesses = "a105a1820000820082" + "1903e8" + "1a000186a0";
        byte[] tx = HexUtil.decodeHexString("84" + BODY + witnesses + "f5f6");
        RawTransaction raw = RawTransaction.parse(tx, null);

        byte[] body = HexUtil.decodeHexString(BODY);
        assertThat(raw.txId()).isEqualTo(Blake2bUtil.blake2bHash256(body));
        assertThat(raw.size()).isEqualTo(1 + body.length + witnesses.length() / 2 + 1);
        assertThat(raw.inputs()).containsExactly(new TxInRef("00".repeat(32), 0));
        assertThat(raw.outputs()).singleElement().satisfies(out -> {
            assertThat(out.size()).isEqualTo(37);
            assertThat(out.value()).isEqualTo(LedgerValue.ofCoin(BigInteger.valueOf(1_000_000)));
            assertThat(AddressBytes.network(out.address())).isZero();
        });
        assertThat(raw.fee()).isEqualTo(BigInteger.valueOf(200_000));
        assertThat(raw.ttl()).isNull();
        assertThat(raw.validityStart()).isNull();
        assertThat(raw.redeemers()).containsExactly(new RawRedeemer(0, 0, BigInteger.valueOf(1000),
                BigInteger.valueOf(100_000)));
    }

    @Test
    void listFormRedeemersKeepTheLastDuplicateAsHaskellsMapFromList() {
        String witnesses = "a105" + "82" + "8400000082" + "0101" + "8400000082" + "0202";
        RawTransaction raw = RawTransaction.parse(HexUtil.decodeHexString("84" + BODY + witnesses + "f5f6"), null);
        assertThat(raw.redeemers()).containsExactly(new RawRedeemer(0, 0, BigInteger.TWO, BigInteger.TWO));
    }

    @Test
    void mapFormRedeemersAlsoKeepTheLastDuplicate() {
        // decodeMapRedeemers reverses its accumulator before Map.fromList (Alonzo/TxWits.hs:571-577)
        String witnesses = "a105" + "a2" + "8200008200" + "820101" + "8200008200" + "820202";
        RawTransaction raw = RawTransaction.parse(HexUtil.decodeHexString("84" + BODY + witnesses + "f5f6"), null);
        assertThat(raw.redeemers()).containsExactly(new RawRedeemer(0, 0, BigInteger.TWO, BigInteger.TWO));
    }

    @Test
    void emptyRedeemersAndOversizedIndicesDoNotDecode() {
        assertDecodingFailure("84" + BODY + "a10580" + "f5f6", "non-empty");
        assertDecodingFailure("84" + BODY + "a105a0" + "f5f6", "non-empty");
        assertDecodingFailure("84" + BODY + "a105" + "81" + "84001b00000001000000000082" + "0101" + "f5f6",
                "Word32");
        assertDecodingFailure("84" + BODY + "a105" + "81" + "84060000" + "820101" + "f5f6", "tag");
    }

    @Test
    void rejectsWhatHaskellsDecoderRejects() {
        String shortSignature = "a100818258" + "20" + "22".repeat(32) + "583f" + "33".repeat(63);
        assertThatThrownBy(() -> RawTransaction.parse(HexUtil.decodeHexString("84" + BODY + shortSignature + "f5f6"),
                null)).isInstanceOf(TxDecodingException.class).hasMessageContaining("signature");

        String duplicateInput = BODY.replace("00d901029f" + INPUT + "ff", "00d901029f" + INPUT + INPUT + "ff");
        assertThatThrownBy(() -> RawTransaction.parse(HexUtil.decodeHexString("84" + duplicateInput + "a0f5f6"),
                null)).isInstanceOf(TxDecodingException.class).hasMessageContaining("duplicate input");

        assertThatThrownBy(() -> RawTransaction.parse(HexUtil.decodeHexString("83" + BODY + "a0f5"), null))
                .isInstanceOf(TxDecodingException.class);

        String noFee = "a200d9010280" + "0180";
        assertThatThrownBy(() -> RawTransaction.parse(HexUtil.decodeHexString("84" + noFee + "a0f5f6"), null))
                .isInstanceOf(TxDecodingException.class).hasMessageContaining("no key 2");
    }

    @Test
    void conwayRejectsUnknownKeysAndDuplicatesAtDecoding() {
        assertDecodingFailure("84" + BODY.replace("021a00030d40", "021a00030d40" + "0601") + "a0f5f6",
                "unknown transaction body key 6");
        assertDecodingFailure("84" + BODY + "a10801" + "f5f6", "unknown witness set key 8");
        String reward = "581de0" + "44".repeat(28);
        assertDecodingFailure("84" + body("05a2" + reward + "01" + reward + "02") + "a0f5f6", "duplicate withdrawal");
        assertDecodingFailure("84" + body("05a1" + "581d62" + "44".repeat(28) + "01") + "a0f5f6", "account address");
        assertDecodingFailure("84" + body("05a0") + "a0f5f6", "Withdrawals");
        assertDecodingFailure("84" + body("0480") + "a0f5f6", "Certificates");
        assertDecodingFailure("84" + body("0d80") + "a0f5f6", "non-empty");
        assertDecodingFailure("84" + body("1600") + "a0f5f6", "Treasury Donation");
        assertDecodingFailure("84" + BODY + "a10080" + "f5f6", "empty list");
    }

    @Test
    void mintAndValueMultiAssetsFollowConwaysDecoder() {
        String policy = "581c" + "ab".repeat(28);
        assertDecodingFailure("84" + body("09a1" + policy + "a14100" + "00") + "a0f5f6", "zeros");
        assertDecodingFailure("84" + body("09a1" + policy + "a0") + "a0f5f6", "Empty Assets");
        assertDecodingFailure("84" + body("09a0") + "a0f5f6", "Mint");
        assertDecodingFailure("84" + body("09a2" + policy + "a1410001" + policy + "a1410101") + "a0f5f6",
                "duplicate policy");
        assertDecodingFailure("84" + body("09a1" + policy + "a2410001410001") + "a0f5f6", "duplicate asset name");
        RawTransaction minted = RawTransaction.parse(HexUtil.decodeHexString("84"
                + body("09a1" + policy + "a1410021") + "a0f5f6"), null);
        assertThat(minted.mint().get("ab".repeat(28))).containsEntry("00", BigInteger.valueOf(-2));

        String address = "581d60" + "11".repeat(28);
        String zeroTokenOutput = "82" + address + "821a000f4240a1" + policy + "a1410000";
        assertDecodingFailure("84" + BODY.replace("019f82581d60" + "11".repeat(28) + "1a000f4240ff",
                "0181" + zeroTokenOutput) + "a0f5f6", "zeros");
    }

    @Test
    void outputsFollowBabbagesTxOutDecoder() {
        String address = "581d60" + "11".repeat(28);
        assertDecodingFailure(withOutput("a3" + "00" + address + "011a000f4240" + "0401"), "unknown output key 4");
        assertDecodingFailure(withOutput("a3" + "00" + address + "011a000f4240" + "011a000f4240"),
                "duplicate output key 1");
        assertDecodingFailure(withOutput("a1" + "00" + address), "no address or no value");
        assertDecodingFailure(withOutput("82" + "581d80" + "11".repeat(28) + "1a000f4240"), "unused bits");
        assertDecodingFailure(withOutput("82" + "581d64" + "11".repeat(28) + "1a000f4240"), "unused bits");
        // Only bit 0 is the network (headerNetworkId, Address.hs:556-559); bit 1 is not checked: 0x62 is testnet.
        RawTransaction bit1 = RawTransaction.parse(HexUtil.decodeHexString(withOutput("82" + "581d62"
                + "11".repeat(28) + "1a000f4240")), null);
        assertThat(AddressBytes.network(bit1.outputs().getFirst().address())).isZero();
        assertDecodingFailure(withOutput("82" + "581e60" + "11".repeat(29) + "1a000f4240"), "Left over");
        assertDecodingFailure(withOutput("82" + "581c60" + "11".repeat(27) + "1a000f4240"), "too short");
        // pointer address: slot 0x8f 0xff 0xff 0xff 0x7f is 2^32 - 1 in five bytes; 0x90… exceeds Word32
        String pointer = "40" + "11".repeat(28);
        RawTransaction ok = RawTransaction.parse(HexUtil.decodeHexString(withOutput("82" + "5824" + pointer
                + "8fffffff7f" + "00" + "00" + "1a000f4240")), null);
        assertThat(ok.outputs()).hasSize(1);
        assertDecodingFailure(withOutput("82" + "5824" + pointer + "90ffffff7f" + "00" + "00" + "1a000f4240"),
                "SlotNo");
        assertDecodingFailure(withOutput("82" + "581f" + pointer + "0000" + "1a000f4240"), "CertIx");
    }

    @Test
    void validityBoundsAreWord64() {
        RawTransaction raw = RawTransaction.parse(HexUtil.decodeHexString("84"
                + body("031bffffffffffffffff") + "a0f5f6"), null);
        assertThat(raw.ttl()).isEqualTo(new BigInteger("18446744073709551615"));
    }

    @Test
    void nestedIndefiniteByteStringChunksAreRejected() {
        CborReader reader = new CborReader(HexUtil.decodeHexString("5f5f4100ffff"));
        assertThatThrownBy(reader::readBytes).isInstanceOf(TxDecodingException.class).hasMessageContaining("chunk");
        assertThat(new CborReader(HexUtil.decodeHexString("5f41014102ff")).readBytes())
                .containsExactly(1, 2);
    }

    @Test
    void anEmptyInputSetDecodes() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs.clear();
        spec.outputs.clear();
        spec.changeAdjust = BigInteger.valueOf(5_000_000);
        BuiltTx built = EngineTestSupport.build(spec);
        assertThat(RawTransaction.parse(built.cbor(), built.tx()).inputs()).isEmpty();
    }

    /**
     * Witness-set rules at protocol versions 9–11: Plutus script lists are non-empty and hold no two scripts of the
     * same hash ({@code scriptDecoderV9}); a bootstrap witness's chain code has any length before version 12.
     */
    @Test
    void readsWitnessesAsConwayDecodesThem() {
        String tx = "84" + BODY;
        assertDecodingFailure(tx + "a10780" + "f5f6", "Empty list of scripts");
        assertDecodingFailure(tx + "a107824401020304" + "4401020304" + "f5f6", "duplicate PlutusV3");
        String bootstrap = "a1028184" + "5820" + "01".repeat(32) + "5840" + "02".repeat(64) + "5821" + "03".repeat(33)
                + "41a0";
        RawTransaction raw = RawTransaction.parse(HexUtil.decodeHexString(tx + bootstrap + "f5f6"), null);
        assertThat(raw.bootstrapWitnesses()).singleElement()
                .satisfies(w -> assertThat(w.chainCode()).hasSize(33));
        RawTransaction scripts = RawTransaction.parse(HexUtil.decodeHexString(tx + "a2" + "0781" + "4401020304"
                + "01818200581c" + "11".repeat(28) + "f5f6"), null);
        assertThat(scripts.witnessScripts()).extracting(RawScript::language)
                .containsExactly(RawScript.NATIVE, RawScript.PLUTUS_V3);
    }

    /**
     * Certificates and proposal procedures are {@code OSet}s: {@code decodeOSet} rejects two equal elements
     * ({@code decodeSetLikeEnforceNoDuplicates}), equal as decoded values, so a different encoding of the same
     * certificate is a duplicate too.
     */
    @Test
    void duplicateCertificatesAndProposalsDoNotDecode() {
        String cred = "8200581c" + "11".repeat(28);
        String reg = "8200" + cred;
        String regIndefinite = "9f00" + cred + "ff";
        String other = "8201" + cred;
        String tail = "a0f5f6";
        assertThat(RawTransaction.parse(HexUtil.decodeHexString("84" + body("0482" + reg + other) + tail), null)
                .certificates()).hasSize(2);
        assertDecodingFailure("84" + body("0482" + reg + reg) + tail, "duplicate certificate");
        assertDecodingFailure("84" + body("04d9010282" + reg + regIndefinite) + tail, "duplicate certificate");

        // pool registrations whose owner sets differ only in order are equal
        String pool = "8a03581c" + "22".repeat(28) + "5820" + "33".repeat(32) + "0000d81e820105"
                + "581de0" + "44".repeat(28);
        String owners1 = "82581c" + "55".repeat(28) + "581c" + "66".repeat(28);
        String owners2 = "82581c" + "66".repeat(28) + "581c" + "55".repeat(28);
        String poolTail = "80f6";
        assertDecodingFailure("84" + body("0482" + pool + owners1 + poolTail + pool + owners2 + poolTail) + tail,
                "duplicate certificate");

        String proposal = "841a000f4240581de0" + "44".repeat(28) + "8106" + "826568747470735820" + "00".repeat(32);
        assertThat(RawTransaction.parse(HexUtil.decodeHexString("84" + body("1481" + proposal) + tail), null)
                .proposals()).hasSize(1);
        assertDecodingFailure("84" + body("1482" + proposal + proposal) + tail, "duplicate proposal");
    }
}
