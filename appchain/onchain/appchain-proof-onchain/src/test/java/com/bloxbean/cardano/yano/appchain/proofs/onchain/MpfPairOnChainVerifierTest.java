package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.cbor.PlutusDataCborEncoder;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.MpfProofConverter;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.proofs.MpfNormalizedProof;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MpfPairOnChainVerifierTest extends ContractTest {
    private static final long MAX_TX_CPU = 10_000_000_000L;
    private static final long MAX_TX_MEM = 14_000_000L;
    private static Program program;

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    @Test
    void factAndCompletenessProofVerifyAtOneRootWithinCardanoBudget() {
        Fixture fixture = fixture();
        var result = evaluate(program(), spendingContext(
                        new TxOutRef(new TxId(filled(0x61, 32)), java.math.BigInteger.ZERO),
                        PlutusData.bytes(fixture.root()))
                .redeemer(pairData(fixture.fact(), fixture.complete()))
                .buildPlutusData());

        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
        int bytes = PlutusDataCborEncoder.encode(
                pairData(fixture.fact(), fixture.complete())).length;
        assertThat(bytes).isLessThan(16 * 1024);
        System.out.println("[MpfPairOnChainVerifier] folds="
                + (fixture.fact().folds().size() + fixture.complete().folds().size())
                + ", redeemerBytes=" + bytes + ", budget=" + result.budgetConsumed());
    }

    @Test
    void rejectsProofsFromDifferentRoots() {
        Fixture fixture = fixture();
        byte[] wrong = fixture.root().clone();
        wrong[0] ^= 1;
        var result = evaluate(program(), spendingContext(
                        new TxOutRef(new TxId(filled(0x62, 32)), java.math.BigInteger.ZERO),
                        PlutusData.bytes(wrong))
                .redeemer(pairData(fixture.fact(), fixture.complete()))
                .buildPlutusData());

        assertFailure(result);
    }

    private Program program() {
        if (program == null) {
            program = compileValidator(MpfPairOnChainVerifier.class).program();
        }
        return program;
    }

    private static Fixture fixture() {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        byte[] factKey = null;
        byte[] factValue = null;
        for (int index = 0; index < 20_000; index++) {
            byte[] key = key(index);
            byte[] value = ByteBuffer.allocate(8).putLong(1_000_000L + index).array();
            trie.put(key, value);
            if (index == 12_345) {
                factKey = key;
                factValue = value;
            }
        }
        byte[] completeKey = "stake/500/meta".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] completeValue = new byte[]{1};
        trie.put(completeKey, completeValue);
        byte[] root = trie.getRootHash();
        return new Fixture(root,
                normalized(trie, root, factKey, factValue),
                normalized(trie, root, completeKey, completeValue));
    }

    private static MpfNormalizedProof normalized(MpfTrie trie, byte[] root,
                                                  byte[] key, byte[] value) {
        ProofVerifier.ProfileMetadata metadata = ProofVerifier.profileMetadata(
                ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
        AppChainClient.Proof wire = new AppChainClient.Proof(
                HexFormat.of().formatHex(key), "cardano-history",
                HexFormat.of().formatHex(root),
                HexFormat.of().formatHex(trie.getProofWire(key).orElseThrow()),
                HexFormat.of().formatHex(value), 42L, 42L, 1,
                ProofVerifier.MPF_BLAKE2B256_V1, metadata.backend(),
                metadata.commitmentFormatId(), metadata.formatFingerprintHex(),
                "11".repeat(32), metadata.proofEncodingId(), metadata.nativeVersioning(),
                metadata.physicalDelete(), 42L, AppChainClient.ProofPresence.PRESENT,
                null, null);
        return MpfProofConverter.convert(wire);
    }

    private static PlutusData pairData(MpfNormalizedProof fact,
                                       MpfNormalizedProof complete) {
        return PlutusData.constr(0, proofData(fact), proofData(complete));
    }

    private static PlutusData proofData(MpfNormalizedProof proof) {
        return PlutusData.constr(0,
                PlutusData.bytes(proof.key()), PlutusData.bytes(proof.value()),
                PlutusData.bytes(proof.leafSuffix()),
                PlutusData.list(proof.folds().stream()
                        .map(MpfPairOnChainVerifierTest::foldData)
                        .toArray(PlutusData[]::new)));
    }

    private static PlutusData foldData(MpfNormalizedProof.FoldStep fold) {
        List<byte[]> neighbors = fold.neighbors();
        return PlutusData.constr(0, PlutusData.integer(fold.cursor()),
                PlutusData.bytes(fold.prefix()), PlutusData.integer(fold.nibble()),
                PlutusData.bytes(neighbors.get(0)), PlutusData.bytes(neighbors.get(1)),
                PlutusData.bytes(neighbors.get(2)), PlutusData.bytes(neighbors.get(3)),
                PlutusData.bytes(fold.branchValueHash()));
    }

    private static byte[] key(int value) {
        byte[] result = new byte[38];
        byte[] prefix = "stake/500/00".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        ByteBuffer.wrap(result, result.length - 4, 4).putInt(value);
        return result;
    }

    private static byte[] filled(int value, int length) {
        byte[] result = new byte[length];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private record Fixture(byte[] root, MpfNormalizedProof fact,
                           MpfNormalizedProof complete) { }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();
        @Override public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }
        @Override public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }
        @Override public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }
}
