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
import com.bloxbean.cardano.yano.appchain.proofs.MpfNormalizedNonMembershipProof;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
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
    void amountPoolCombinedAndExactPredicatesVerifyAtOneRootWithinCardanoBudget() {
        Fixture fixture = fixture();
        for (int mode = 0; mode <= 3; mode++) {
            byte[] pool = mode == 0 ? new byte[0] : fixture.poolHash();
            var result = evaluate(program(), spendingContext(
                            new TxOutRef(new TxId(filled(0x61 + mode, 32)),
                                    java.math.BigInteger.ZERO),
                            PlutusData.bytes(fixture.root()))
                    .redeemer(pairData(fixture, mode, 1_012_345L, pool))
                    .buildPlutusData());

            assertSuccess(result);
            assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
            int bytes = PlutusDataCborEncoder.encode(pairData(
                    fixture, mode, 1_012_345L, pool)).length;
            assertThat(bytes).isLessThan(16 * 1024);
            System.out.println("[MpfPairOnChainVerifier] predicate=" + mode + ", folds="
                    + (fixture.fact().folds().size() + fixture.complete().folds().size())
                    + ", redeemerBytes=" + bytes + ", budget=" + result.budgetConsumed());
        }
    }

    @Test
    void rejectsProofsFromDifferentRoots() {
        Fixture fixture = fixture();
        byte[] wrong = fixture.root().clone();
        wrong[0] ^= 1;
        var result = evaluate(program(), spendingContext(
                        new TxOutRef(new TxId(filled(0x62, 32)), java.math.BigInteger.ZERO),
                        PlutusData.bytes(wrong))
                .redeemer(pairData(fixture, 0, 1_012_345L, new byte[0]))
                .buildPlutusData());

        assertFailure(result);
    }

    @Test
    void absenceAndCompletenessVerifyAtOneRootWithinCardanoBudget() {
        Fixture fixture = fixture();
        var result = evaluate(program(), spendingContext(
                        new TxOutRef(new TxId(filled(0x69, 32)), java.math.BigInteger.ZERO),
                        PlutusData.bytes(fixture.root()))
                .redeemer(absencePairData(fixture))
                .buildPlutusData());

        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
        assertThat(PlutusDataCborEncoder.encode(absencePairData(fixture)).length)
                .isLessThan(16 * 1024);
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
            byte[] value = stakeValue(1_000_000L + index, filled(index, 28));
            trie.put(key, value);
            if (index == 12_345) {
                factKey = key;
                factValue = value;
            }
        }
        byte[] completeKey = "stake/500/meta".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] completeValue = completenessValue();
        trie.put(completeKey, completeValue);
        byte[] root = trie.getRootHash();
        byte[] absentKey = key(99_999);
        return new Fixture(root, factKey, absentKey, completeKey, filled(12_345, 28),
                normalized(trie, root, factKey, factValue),
                absent(trie, root, absentKey),
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

    private static MpfNormalizedNonMembershipProof absent(MpfTrie trie, byte[] root,
                                                           byte[] key) {
        ProofVerifier.ProfileMetadata metadata = ProofVerifier.profileMetadata(
                ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
        AppChainClient.Proof wire = new AppChainClient.Proof(
                HexFormat.of().formatHex(key), "cardano-history",
                HexFormat.of().formatHex(root),
                HexFormat.of().formatHex(trie.getProofWire(key).orElseThrow()),
                null, 42L, 42L, 1, ProofVerifier.MPF_BLAKE2B256_V1,
                metadata.backend(), metadata.commitmentFormatId(),
                metadata.formatFingerprintHex(), "11".repeat(32),
                metadata.proofEncodingId(), metadata.nativeVersioning(),
                metadata.physicalDelete(), 42L, AppChainClient.ProofPresence.ABSENT,
                null, null);
        return MpfProofConverter.convertAbsence(wire);
    }

    private static PlutusData pairData(Fixture fixture, long predicate,
                                       long coin, byte[] poolHash) {
        return PlutusData.constr(0, proofData(fixture.fact()), proofData(fixture.complete()),
                PlutusData.bytes(fixture.factKey()), PlutusData.bytes(fixture.completeKey()),
                PlutusData.integer(predicate), PlutusData.integer(coin),
                PlutusData.bytes(poolHash));
    }

    private static PlutusData proofData(MpfNormalizedProof proof) {
        return PlutusData.constr(0,
                PlutusData.bytes(proof.key()), PlutusData.bytes(proof.value()),
                PlutusData.bytes(proof.leafSuffix()),
                PlutusData.list(proof.folds().stream()
                        .map(MpfPairOnChainVerifierTest::foldData)
                        .toArray(PlutusData[]::new)),
                PlutusData.integer(0), PlutusData.bytes(new byte[0]),
                PlutusData.bytes(new byte[0]));
    }

    private static PlutusData absencePairData(Fixture fixture) {
        MpfNormalizedNonMembershipProof absent = fixture.absence();
        PlutusData proof = PlutusData.constr(0,
                PlutusData.bytes(absent.key()), PlutusData.bytes(new byte[0]),
                PlutusData.bytes(absent.conflictingLeafSuffix()),
                PlutusData.list(absent.folds().stream()
                        .map(MpfPairOnChainVerifierTest::foldData)
                        .toArray(PlutusData[]::new)),
                PlutusData.integer(absent.terminalCursor()),
                PlutusData.bytes(absent.conflictingKeyHash()),
                PlutusData.bytes(absent.conflictingValueHash()));
        return PlutusData.constr(0, proof, proofData(fixture.complete()),
                PlutusData.bytes(fixture.absentKey()),
                PlutusData.bytes(fixture.completeKey()), PlutusData.integer(4),
                PlutusData.integer(0), PlutusData.bytes(new byte[0]));
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

    private static byte[] stakeValue(long coin, byte[] poolHash) {
        return ByteBuffer.allocate(36).put((byte) 0x82).put((byte) 0x1a)
                .putInt(Math.toIntExact(coin)).put((byte) 0x58).put((byte) 0x1c)
                .put(poolHash).array();
    }

    private static byte[] completenessValue() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 0x01, 0x19, 0x01, (byte) 0xf4,
                0x00, 0x19, 0x4e, 0x20, 0x19, 0x61, (byte) 0xa8, 0x01,
                0x58, 0x20});
        out.writeBytes(filled(0x44, 32));
        out.writeBytes(new byte[]{0x01, 0x01});
        return out.toByteArray();
    }

    private record Fixture(byte[] root, byte[] factKey, byte[] absentKey,
                           byte[] completeKey, byte[] poolHash, MpfNormalizedProof fact,
                           MpfNormalizedNonMembershipProof absence,
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
