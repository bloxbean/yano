package org.yanoproject.archive.core.source;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.CanonicalBlockReference;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class YaciBlockDecoderTest {
    @Test
    void decodesStoredByronEpochBoundaryWithoutShelleySerializer() {
        byte[] body = byronEpochBoundary(3);
        byte[] blockHash = new byte[32];
        blockHash[0] = 9;
        long slot = 3L * 21_600L;
        var decoder = new YaciBlockDecoder(value -> 0, value -> 1_500_000_000L + value,
                ignored -> Era.Byron);

        var decoded = decoder.decode(0, new CanonicalBlockReference(0, slot, blockHash), body);

        assertThat(decoded.block().getEra()).isEqualTo(Era.Byron);
        assertThat(decoded.block().getTransactionBodies()).isEmpty();
        assertThat(decoded.slot()).isEqualTo(slot);
        assertThat(decoded.blockNumber()).isZero();
        assertThat(decoded.parentHash()).hasSize(32);
    }

    @Test
    void decodesStoredByronMainBlockWithoutShelleySerializer() {
        byte[] body = byronMainBlock(3, 5);
        byte[] blockHash = new byte[32];
        blockHash[0] = 8;
        long slot = 3L * 21_600L + 5;
        var decoder = new YaciBlockDecoder(value -> 0, value -> 1_500_000_000L + value,
                ignored -> Era.Byron);

        var decoded = decoder.decode(1, new CanonicalBlockReference(1, slot, blockHash), body);

        assertThat(decoded.block().getEra()).isEqualTo(Era.Byron);
        assertThat(decoded.block().getTransactionBodies()).isEmpty();
        assertThat(decoded.slot()).isEqualTo(slot);
        assertThat(decoded.blockNumber()).isEqualTo(1);
    }

    @Test
    void projectsByronTransactionWithUnknownFeeInsteadOfHaltingBackfill() {
        byte[] body = byronMainBlock(3, 6, true);
        byte[] blockHash = new byte[32];
        blockHash[0] = 7;
        long slot = 3L * 21_600L + 6;
        var decoder = new YaciBlockArchiveDecoder(value -> 0, value -> value, ignored -> Era.Byron);

        var decoded = decoder.decode(2, new CanonicalBlockReference(2, slot, blockHash), body);

        assertThat(decoded.block().transactions()).singleElement().satisfies(transaction -> {
            assertThat(transaction.fee()).isNull();
            assertThat(transaction.valid()).isTrue();
            assertThat(transaction.txHash()).hasSize(32);
        });
    }

    /**
     * A Dijkstra block under an era index Yaci does not know reaches the decoder with a null era (ADR 0003 I12). The
     * copy that gives it the stored era keeps its Leios certificate, its Peras certificate and its header's Leios
     * fields (ADR 0003 D4b). Synthetic: the Musashi block musashi-dijkstra8 under index 9, with a Peras certificate in
     * place of its nil; Yaci does not check the header's body hash.
     */
    @Test
    void keepsTheLeiosAndPerasFieldsOfABlockWhoseEraIsCopiedIn() throws IOException {
        String hex = resource("/block/musashi-dijkstra8.txt");
        assertThat(hex).startsWith("8208").endsWith("f6");
        byte[] body = HexUtil.decodeHexString("8209" + hex.substring(4, hex.length() - 2) + "43010203");
        Block read = BlockSerializer.INSTANCE.deserialize(body);
        assertThat(read.getEra()).isNull();
        assertThat(read.getLeiosCertificate()).isNotNull();
        assertThat(read.getPerasCertificate()).isEqualTo("010203");
        assertThat(read.getHeader().getHeaderBody().getEbAnnouncement()).isNotNull();
        long slot = read.getHeader().getHeaderBody().getSlot();
        var decoder = new YaciBlockDecoder(value -> 0, value -> value, ignored -> Era.Dijkstra);

        var decoded = decoder.decode(17403, new CanonicalBlockReference(17403, slot, new byte[32]), body);

        Block block = decoded.block();
        assertThat(block.getEra()).isEqualTo(Era.Dijkstra);
        assertThat(block.getLeiosCertificate()).isEqualTo(read.getLeiosCertificate());
        assertThat(block.getPerasCertificate()).isEqualTo("010203");
        assertThat(block.getHeader()).usingRecursiveComparison().isEqualTo(read.getHeader());
        assertThat(block.getHeader().getHeaderBody().getLeiosCertInBody()).isTrue();
        assertThat(block.getHeader().getHeaderBody().getBlockHash())
                .isEqualTo("33a48eee693522320891dd4d1da8ee33c288c854eb25337802dbc4c912571d07");
        assertThat(block.getTransactionBodies()).isEqualTo(read.getTransactionBodies());
        assertThat(block.getInvalidTransactions()).isEqualTo(read.getInvalidTransactions());
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = YaciBlockDecoderTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
    }

    private static byte[] byronEpochBoundary(long epoch) {
        Array consensus = new Array();
        consensus.add(new UnsignedInteger(epoch));
        Array difficulty = new Array();
        difficulty.add(new UnsignedInteger(1));
        consensus.add(difficulty);

        Array header = new Array();
        header.add(new UnsignedInteger(764_824_073L));
        header.add(new ByteString(new byte[32]));
        header.add(new Array());
        header.add(consensus);
        header.add(new Array());

        Array block = new Array();
        block.add(header);
        block.add(new Array());
        Array envelope = new Array();
        envelope.add(new UnsignedInteger(0));
        envelope.add(block);
        return CborSerializationUtil.serialize(envelope);
    }

    private static byte[] byronMainBlock(long epoch, long relativeSlot) {
        return byronMainBlock(epoch, relativeSlot, false);
    }

    private static byte[] byronMainBlock(long epoch, long relativeSlot, boolean includeTransaction) {
        Array txProof = array(new UnsignedInteger(0), new ByteString(new byte[32]),
                new ByteString(new byte[32]));
        Array sscProof = array(new UnsignedInteger(3), new Array());
        Array bodyProof = array(txProof, sscProof, new ByteString(new byte[32]),
                new ByteString(new byte[32]));
        Array slotId = array(new UnsignedInteger(epoch), new UnsignedInteger(relativeSlot));
        Array difficulty = array(new UnsignedInteger(1));
        Array signature = array(new UnsignedInteger(0), array(new ByteString(new byte[64])));
        Array consensus = array(slotId, new ByteString(new byte[32]), difficulty, signature);
        Array version = array(new UnsignedInteger(0), new UnsignedInteger(0), new UnsignedInteger(0));
        Array software = array(new ByteString(new byte[] {1}), new UnsignedInteger(0));
        Array extraData = array(version, software, new co.nstant.in.cbor.model.Map(),
                new ByteString(new byte[32]));
        Array header = array(new UnsignedInteger(764_824_073L), new ByteString(new byte[32]),
                bodyProof, consensus, extraData);
        Array sscPayload = array(new UnsignedInteger(3), new Array());
        Array updatePayload = array(new Array(), new Array());
        Array transactions = new Array();
        if (includeTransaction) {
            Array transactionBody = array(new Array(), new Array(), new co.nstant.in.cbor.model.Map());
            transactions.add(array(transactionBody, new Array()));
        }
        Array blockBody = array(transactions, sscPayload, new Array(), updatePayload);
        Array block = array(header, blockBody, new Array());
        return CborSerializationUtil.serialize(array(new UnsignedInteger(1), block));
    }

    private static Array array(co.nstant.in.cbor.model.DataItem... values) {
        Array result = new Array();
        for (var value : values) result.add(value);
        return result;
    }
}
