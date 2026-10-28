package org.yanoproject.appchain.proof;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AppChainProofVerifierSecurityTest {

    @Test
    @Timeout(5)
    void deeplyNestedCborIsRejectedWithoutEnteringRecursiveDecoder() {
        byte[] nested = new byte[100_002];
        Arrays.fill(nested, 0, nested.length - 1, (byte) 0x81);
        nested[nested.length - 1] = (byte) 0x80;

        assertThatCode(() -> {
            assertThat(AppChainProofVerifier.verifyMpfInclusion(
                    new byte[32], new byte[]{1}, new byte[]{2}, nested)).isFalse();
            assertThat(AppChainProofVerifier.verifyMpfExclusion(
                    new byte[32], new byte[]{1}, nested)).isFalse();
        }).doesNotThrowAnyException();
    }

    @Test
    @Timeout(5)
    void flatProofThatWouldOverflowRecursiveVerifierIsRejectedByPathBound() {
        byte[] proof = repeatedBranches(7_800);
        assertThat(proof).hasSizeLessThan(1024 * 1024 + 1);

        assertThatCode(() -> assertThat(AppChainProofVerifier.verifyMpfExclusion(
                new byte[32], new byte[]{1}, proof)).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void proofShapesAndCumulativePathLengthAreValidatedBeforeDecode() {
        assertThat(AppChainProofVerifier.verifyMpfExclusion(
                new byte[32], new byte[]{1}, branch(64, 128))).isFalse();
        assertThat(AppChainProofVerifier.verifyMpfExclusion(
                new byte[32], new byte[]{1}, branch(0, 127))).isFalse();
    }

    @Test
    void malformedPublicProofAndOversizedInputsFailClosed() {
        assertThat(AppChainProofVerifier.verifyMpfInclusion(
                new byte[31], new byte[]{1}, new byte[]{2}, new byte[]{(byte) 0x80}))
                .isFalse();
        assertThat(AppChainProofVerifier.verifyMpfExclusion(
                new byte[32], new byte[257], new byte[]{(byte) 0x80}))
                .isFalse();
        assertThat(AppChainProofVerifier.verifyMpfExclusion(
                new byte[32], new byte[]{1}, new byte[1024 * 1024 + 1]))
                .isFalse();
    }

    @Test
    void everyCanonicalProofVerifiesIncludingLeafStepsInsideThePath() {
        int midPathLeaves = 0;
        for (int size : new int[] {1, 2, 3, 30, 300, 1000}) {
            MpfTrie trie = new MpfTrie(new MapNodeStore());
            for (int i = 0; i < size; i++) {
                trie.put(bytes("key-" + i), bytes("value-" + i));
            }
            byte[] root = trie.getRootHash();
            for (int i = 0; i < size; i++) {
                byte[] proof = trie.getProofWire(bytes("key-" + i)).orElseThrow();
                midPathLeaves += hasLeafBeforeTheEnd(proof) ? 1 : 0;

                assertThat(AppChainProofVerifier.verifyMpfInclusion(
                        root, bytes("key-" + i), bytes("value-" + i), proof)).isTrue();
            }
            for (int i = 0; i < 300; i++) {
                byte[] proof = trie.getProofWire(bytes("missing-" + i)).orElseThrow();
                midPathLeaves += hasLeafBeforeTheEnd(proof) ? 1 : 0;

                assertThat(AppChainProofVerifier.verifyMpfExclusion(root, bytes("missing-" + i), proof)).isTrue();
            }
        }
        assertThat(midPathLeaves).isPositive();
    }

    private static boolean hasLeafBeforeTheEnd(byte[] proof) {
        try {
            List<DataItem> steps = ((Array) new CborDecoder(new ByteArrayInputStream(proof)).decode().getFirst())
                    .getDataItems();
            for (int index = 0; index < steps.size() - 1; index++) {
                if (steps.get(index).getTag() != null && steps.get(index).getTag().getValue() == 123) {
                    return true;
                }
            }
            return false;
        } catch (CborException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }

        @Override
        public void put(byte[] hash, byte[] node) {
            nodes.put(HexFormat.of().formatHex(hash), node);
        }

        @Override
        public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }

    private static byte[] repeatedBranches(int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x99);
        out.write((count >>> 8) & 0xff);
        out.write(count & 0xff);
        for (int index = 0; index < count; index++) {
            writeBranch(out, 0, 128);
        }
        return out.toByteArray();
    }

    private static byte[] branch(int skip, int neighborBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        writeBranch(out, skip, neighborBytes);
        return out.toByteArray();
    }

    private static void writeBranch(ByteArrayOutputStream out, int skip, int neighborBytes) {
        out.write(0xd8);
        out.write(0x79);
        out.write(0x82);
        if (skip < 24) {
            out.write(skip);
        } else {
            out.write(0x18);
            out.write(skip);
        }
        out.write(0x58);
        out.write(neighborBytes);
        out.writeBytes(new byte[neighborBytes]);
    }
}
