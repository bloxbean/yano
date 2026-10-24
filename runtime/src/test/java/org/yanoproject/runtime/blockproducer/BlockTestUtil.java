package org.yanoproject.runtime.blockproducer;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawBlock;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared test utilities for block builder tests.
 */
final class BlockTestUtil {

    private static final String BODY = "a3" + "0080" + "0180" + "0200"; // {0: [], 1: [], 2: 0}

    private BlockTestUtil() {
        // utility class
    }

    /**
     * Valid transactions in encodings a re-serialisation would change, each of which a block must carry as submitted:
     * <ul>
     *     <li>chunked metadata strings, each chunk at most 64 bytes: merged into one string the ledger's decoder rejects
     *     the block ({@code ByteString exceeds 64 bytes}), and the aux data hash no longer matches;</li>
     *     <li>integers not in their shortest form in the body: re-encoded, the transaction id changes and the witnesses
     *     no longer verify;</li>
     *     <li>indefinite-length arrays and maps, set tags (258) and tagged (259) aux data;</li>
     *     <li>map keys out of order; an indefinite-length transaction array; a transaction without aux data.</li>
     * </ul>
     */
    static List<byte[]> transactionsInNonCanonicalEncodings() {
        String chunkedBytes = "5f" + "5840" + "00".repeat(64) + "5824" + "00".repeat(36) + "ff";
        String chunkedText = "7f" + "7840" + "61".repeat(64) + "6461616161" + "ff";
        String nonShortestBody = "a3" + "0080" + "0181" + "82" + "581d61" + "00".repeat(28) + "1b0000000000000001"
                + "02" + "1a00000005";
        String input = "82" + "5820" + "11".repeat(32) + "00";
        String indefiniteBody = "bf" + "00" + "d90102" + "9f" + input + "ff" + "01" + "9f" + "ff" + "02" + "00" + "ff";
        String indefiniteWitnesses = "bf" + "01" + "9f" + "8200" + "581c" + "22".repeat(28) + "ff" + "ff";
        String taggedAux = "d90103" + "bf" + "00" + "bf" + "01" + "9f" + "01" + "02" + "ff" + "ff" + "ff";
        return List.of(
                tx(BODY, "a0", "a2" + "01" + chunkedBytes + "02" + chunkedText),
                tx(nonShortestBody, "a0", null),
                tx(indefiniteBody, indefiniteWitnesses, taggedAux),
                tx("a3" + "0200" + "0180" + "0080", "a0", "a2" + "02" + "00" + "01" + "00"),
                HexUtil.decodeHexString("9f" + BODY + "a0" + "f5" + "f6" + "ff"),
                tx(BODY, "a0", null));
    }

    private static byte[] tx(String body, String witnesses, String aux) {
        return HexUtil.decodeHexString("84" + body + witnesses + "f5" + (aux == null ? "f6" : aux));
    }

    /**
     * Asserts that a built Conway block carries each transaction as submitted (its body, witness set and aux data byte
     * for byte, so its id too) and that its header declares the body it carries, read back with CCL's
     * {@link RawBlock}.
     */
    static void assertCarriesTransactionsAsSubmitted(byte[] blockCbor, List<byte[]> transactions) {
        RawBlock block = RawBlock.of(blockCbor);
        assertThat(block.era()).isEqualTo(Era.Conway);
        assertThat(block.txCount()).isEqualTo(transactions.size());
        for (int i = 0; i < transactions.size(); i++) {
            RawTx submitted = RawTx.of(transactions.get(i));
            RawTx inBlock = block.tx(i);
            assertThat(inBlock.body().bytes()).as("body %d", i).isEqualTo(submitted.body().bytes());
            assertThat(inBlock.witnessSet().bytes()).as("witnesses %d", i).isEqualTo(submitted.witnessSet().bytes());
            assertThat(inBlock.auxData().map(aux -> HexUtil.encodeHexString(aux.bytes()))).as("aux data %d", i)
                    .isEqualTo(submitted.auxData().map(aux -> HexUtil.encodeHexString(aux.bytes())));
            assertThat(inBlock.txId()).as("tx id %d", i).isEqualTo(submitted.txId());
            assertThat(inBlock.isValid()).as("isValid %d", i).isTrue();
        }
        // the Praos header body declares the block body size (field 6) and hash (field 7)
        CborSpan headerBody = block.header().get(0);
        assertThat(headerBody.get(7).byteString()).isEqualTo(block.bodyHash());
        long bodySize = block.transactionBodies().length() + block.transactionWitnessSets().length()
                + block.auxiliaryDataSet().length() + block.invalidTransactions().orElseThrow().length();
        assertThat(headerBody.get(6).asLong()).isEqualTo(bodySize);
    }

    /**
     * Unwrap tag-24 (wrapCBORinCBOR) block content from a parsed block array.
     * Block format: [era, 24(h'<inner_bytes>')] or [era, [content...]]
     * Returns the deserialized inner 5-element array: [header, txBodies, witnesses, auxData, invalidTxs]
     */
    public static Array unwrapTag24BlockContent(Array blockArray) {
        DataItem innerItem = blockArray.getDataItems().get(1);
        if (innerItem instanceof ByteString) {
            return (Array) CborSerializationUtil.deserializeOne(((ByteString) innerItem).getBytes());
        } else {
            return (Array) innerItem;
        }
    }

    public static ProtocolVersion protocolVersionFromBlockCbor(byte[] blockCbor) {
        Array blockArray = (Array) CborSerializationUtil.deserializeOne(blockCbor);
        Array blockContent = unwrapTag24BlockContent(blockArray);
        Array header = (Array) blockContent.getDataItems().get(0);
        Array headerBody = (Array) header.getDataItems().get(0);
        Array protocolVersion = (Array) headerBody.getDataItems().get(9);
        long major = ((UnsignedInteger) protocolVersion.getDataItems().get(0)).getValue().longValue();
        long minor = ((UnsignedInteger) protocolVersion.getDataItems().get(1)).getValue().longValue();
        return new ProtocolVersion(major, minor);
    }
}
