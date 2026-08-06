package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009 SP-M2: proves the on-chain MPF arithmetic ({@link MpfLib})
 * byte-equal to the off-chain library on the julc VM — exclusion,
 * post-insert roots, chained inserts, and inclusion, plus adversarial
 * mismatches.
 */
class MpfLibConformanceTest extends ContractTest {
    private static Program program;

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    private Program program() {
        if (program == null) {
            program = compileValidator(MpfProbeValidator.class).program();
        }
        return program;
    }

    @Test
    void exclusionInsertChainAndInclusionMatchTheOffChainTrie() throws Exception {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        for (int i = 0; i < 8; i++) {
            trie.put(key("seed-" + i), value("seed-value-" + i));
        }
        // Chain three inserts, each proven against the PREVIOUS root — the
        // §7.1 nullifier pattern.
        for (int i = 0; i < 3; i++) {
            byte[] insertKey = key("nullifier-" + i);
            byte[] insertValue = value("marker-" + i);
            byte[] rootBefore = trie.getRootHash();
            byte[] wire = trie.getProofWire(insertKey).orElseThrow();
            // Exclusion against the current root.
            assertSuccess(evaluate(program(), probeContext(
                    wire, insertKey, new byte[0], 0, rootBefore)));
            // Post-insert root equality.
            trie.put(insertKey, insertValue);
            assertSuccess(evaluate(program(), probeContext(
                    wire, insertKey, Blake2bUtil.blake2bHash256(insertValue),
                    1, trie.getRootHash())));
        }
        // Inclusion of a present key.
        byte[] presentKey = key("seed-3");
        byte[] wire = trie.getProofWire(presentKey).orElseThrow();
        var inclusion = evaluate(program(), probeContext(
                wire, presentKey,
                Blake2bUtil.blake2bHash256(value("seed-value-3")),
                1, trie.getRootHash()));
        assertSuccess(inclusion);
        System.out.println("[MpfLib] inclusion budget=" + inclusion.budgetConsumed());
    }

    @Test
    void adversarialMismatchesFail() throws Exception {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        for (int i = 0; i < 8; i++) {
            trie.put(key("seed-" + i), value("seed-value-" + i));
        }
        byte[] absent = key("absent");
        byte[] wire = trie.getProofWire(absent).orElseThrow();
        byte[] root = trie.getRootHash();
        // Wrong expected root.
        assertFailure(evaluate(program(), probeContext(
                wire, absent, new byte[0], 0, key("wrong-root-32-bytes-pad"))));
        // Exclusion proof replayed for a DIFFERENT absent key.
        assertFailure(evaluate(program(), probeContext(
                wire, key("other-absent"), new byte[0], 0, root)));
        // Insert claiming a different value than proven.
        trie.put(absent, value("real"));
        assertFailure(evaluate(program(), probeContext(
                wire, absent, Blake2bUtil.blake2bHash256(value("forged")),
                1, trie.getRootHash())));
        // Exclusion for a PRESENT key must not verify.
        byte[] presentWire = trie.getProofWire(key("seed-1")).orElseThrow();
        assertFailure(evaluate(program(), probeContext(
                presentWire, key("seed-1"), new byte[0], 0, trie.getRootHash())));
    }

    // ------------------------------------------------------------------

    private PlutusData probeContext(
            byte[] proofWire, byte[] rawKey, byte[] valueHash,
            int including, byte[] expectedRoot) throws Exception {
        com.bloxbean.cardano.client.plutus.spec.PlutusData steps =
                com.bloxbean.cardano.client.plutus.spec.PlutusData
                        .deserialize(proofWire);
        PlutusData redeemer = PlutusData.constr(0,
                toJulc(steps),
                PlutusData.bytes(Blake2bUtil.blake2bHash256(rawKey)),
                PlutusData.bytes(valueHash),
                PlutusData.integer(including),
                PlutusData.bytes(pad32(expectedRoot)));
        return spendingContext(new com.bloxbean.cardano.julc.ledger.TxOutRef(
                        new com.bloxbean.cardano.julc.ledger.TxId(new byte[32]),
                        java.math.BigInteger.ONE), PlutusData.UNIT)
                .redeemer(redeemer)
                .buildPlutusData();
    }

    private static byte[] pad32(byte[] value) {
        if (value.length == 32) {
            return value;
        }
        byte[] out = new byte[32];
        System.arraycopy(value, 0, out, 0, Math.min(32, value.length));
        return out;
    }

    /** CCL PlutusData -> julc PlutusData (constr/bytes/int/list). */
    private static PlutusData toJulc(
            com.bloxbean.cardano.client.plutus.spec.PlutusData data)
            throws Exception {
        if (data instanceof ConstrPlutusData constr) {
            var fields = constr.getData().getPlutusDataList();
            PlutusData[] converted = new PlutusData[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                converted[i] = toJulc(fields.get(i));
            }
            return PlutusData.constr((int) constr.getAlternative(), converted);
        }
        if (data instanceof BytesPlutusData bytes) {
            return PlutusData.bytes(bytes.getValue());
        }
        if (data instanceof BigIntPlutusData integer) {
            return PlutusData.integer(integer.getValue());
        }
        if (data instanceof ListPlutusData list) {
            var items = list.getPlutusDataList();
            PlutusData[] converted = new PlutusData[items.size()];
            for (int i = 0; i < items.size(); i++) {
                converted[i] = toJulc(items.get(i));
            }
            return PlutusData.list(converted);
        }
        throw new IllegalArgumentException(
                "unsupported plutus data: " + data.getClass());
    }

    private static byte[] key(String text) {
        return ("mpf-key-" + text).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String text) {
        return ("mpf-value-" + text).getBytes(StandardCharsets.UTF_8);
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }
}
