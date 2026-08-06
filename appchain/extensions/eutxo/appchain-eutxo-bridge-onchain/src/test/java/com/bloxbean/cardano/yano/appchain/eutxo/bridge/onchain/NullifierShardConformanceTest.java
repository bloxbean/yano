package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ADR-UTXO-009 SP-M2: nullifier shard — chained non-membership + insert
 * proofs against a real off-chain trie, paired with a vault spend.
 */
class NullifierShardConformanceTest extends ContractTest {
    private static final byte[] SHARD_POLICY = filled(0x71, 28);
    private static final byte[] VAULT_SCRIPT = filled(0x54, 28);
    private static final byte[] SHARD_SCRIPT = filled(0x53, 28);
    private static final byte[] CHAIN_ID = "payments".getBytes(StandardCharsets.UTF_8);
    private static Program program;

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    private Program program() {
        if (program == null) {
            program = compileValidator(NullifierShardValidator.class)
                    .program()
                    .applyParams(
                            PlutusData.bytes(SHARD_POLICY),
                            PlutusData.bytes(VAULT_SCRIPT));
        }
        return program;
    }

    @Test
    void chainedInsertsAdvanceTheShardRoot() {
        for (int n : new int[] {1, 2, 3}) {
            var result = evaluate(program(), context(n, false, false));
            assertSuccess(result);
            System.out.println("[NullifierShard] inserts=" + n
                    + " cpu=" + result.budgetConsumed().cpuSteps()
                    + " mem=" + result.budgetConsumed().memoryUnits());
        }
    }

    @Test
    void adversarialCasesFail() {
        // Continuing datum carries a root that does not match the computed one.
        assertFailure(evaluate(program(), context(2, true, false)));
        // No paired vault spend.
        assertFailure(evaluate(program(), context(2, false, true)));
    }

    private PlutusData context(int inserts, boolean wrongNextRoot, boolean noVault) {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        // Seed the shard trie with some settled ids.
        for (int i = 0; i < 4; i++) {
            trie.put(shardKey(0x10 + i), shardKey(0x10 + i));
        }
        byte[] currentRoot = trie.getRootHash();
        List<PlutusData> insertItems = new ArrayList<>();
        for (int i = 0; i < inserts; i++) {
            byte[] claimId = shardZeroClaimId(0x40 + i);
            byte[] proofWire = trie.getProofWire(claimId).orElseThrow();
            insertItems.add(PlutusData.constr(0,
                    PlutusData.bytes(claimId), convertProofSteps(proofWire)));
            trie.put(claimId, claimId);
        }
        byte[] finalRoot = wrongNextRoot ? filled(0x00, 32) : trie.getRootHash();

        PlutusData currentDatum = shardDatum(currentRoot);
        PlutusData nextDatum = shardDatum(finalRoot);
        Address shardAddress = scriptAddress(SHARD_SCRIPT);
        Address vaultAddress = scriptAddress(VAULT_SCRIPT);
        TxOutRef shardRef = new TxOutRef(new TxId(filled(0x62, 32)), BigInteger.ZERO);
        TxOutRef vaultRef = new TxOutRef(new TxId(filled(0x61, 32)), BigInteger.ZERO);
        Value shardValue = threadedValue(BigInteger.valueOf(2_000_000),
                SHARD_POLICY, new byte[] {0x00});

        var builder = spendingContext(shardRef, currentDatum)
                .redeemer(PlutusData.constr(0, PlutusData.list(insertItems.toArray(new PlutusData[0]))))
                .fee(BigInteger.valueOf(300_000))
                .input(new TxInInfo(shardRef, new TxOut(
                        shardAddress, shardValue,
                        new OutputDatum.OutputDatumInline(currentDatum),
                        Optional.empty())))
                .output(new TxOut(
                        shardAddress, shardValue,
                        new OutputDatum.OutputDatumInline(nextDatum),
                        Optional.empty()));
        if (!noVault) {
            builder.input(new TxInInfo(vaultRef, new TxOut(
                    vaultAddress, Value.lovelace(BigInteger.valueOf(20_000_000)),
                    new OutputDatum.OutputDatumInline(PlutusData.UNIT),
                    Optional.empty())));
        }
        return builder.buildPlutusData();
    }

    private static PlutusData shardDatum(byte[] root) {
        return PlutusData.constr(0,
                PlutusData.integer(1),
                PlutusData.bytes(CHAIN_ID),
                PlutusData.integer(7),
                PlutusData.integer(0),
                PlutusData.bytes(root));
    }

    private static byte[] shardZeroClaimId(int fill) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) fill);
        id[31] = (byte) (id[31] & 0xF0);
        return id;
    }

    private static byte[] shardKey(int fill) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) fill);
        id[31] = (byte) (id[31] & 0xF0);
        return id;
    }

    private static PlutusData convertProofSteps(byte[] proofWire) {
        try {
            var data = com.bloxbean.cardano.client.plutus.spec.PlutusData
                    .deserialize(proofWire);
            var list = (com.bloxbean.cardano.client.plutus.spec.ListPlutusData) data;
            var steps = new ArrayList<PlutusData>();
            for (var item : list.getPlutusDataList()) {
                var c = (com.bloxbean.cardano.client.plutus.spec
                        .ConstrPlutusData) item;
                var f = c.getData().getPlutusDataList();
                long tag = c.getAlternative();
                if (tag == 0) {
                    steps.add(PlutusData.constr(0, intData(f.get(0)), bytesData(f.get(1))));
                } else if (tag == 1) {
                    var nc = (com.bloxbean.cardano.client.plutus.spec
                            .ConstrPlutusData) f.get(1);
                    var nf = nc.getData().getPlutusDataList();
                    steps.add(PlutusData.constr(1, intData(f.get(0)),
                            PlutusData.constr(0, intData(nf.get(0)),
                                    bytesData(nf.get(1)), bytesData(nf.get(2)))));
                } else {
                    steps.add(PlutusData.constr(2, intData(f.get(0)),
                            bytesData(f.get(1)), bytesData(f.get(2))));
                }
            }
            return PlutusData.list(steps.toArray(new PlutusData[0]));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static PlutusData intData(
            com.bloxbean.cardano.client.plutus.spec.PlutusData data) {
        return PlutusData.integer(((com.bloxbean.cardano.client.plutus.spec
                .BigIntPlutusData) data).getValue());
    }

    private static PlutusData bytesData(
            com.bloxbean.cardano.client.plutus.spec.PlutusData data) {
        return PlutusData.bytes(((com.bloxbean.cardano.client.plutus.spec
                .BytesPlutusData) data).getValue());
    }

    private static Address scriptAddress(byte[] hash) {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(hash)),
                Optional.empty());
    }

    private static Value threadedValue(
            BigInteger lovelace, byte[] policy, byte[] tokenName) {
        return Value.lovelace(lovelace).merge(Value.singleton(
                new PolicyId(policy), new TokenName(tokenName), BigInteger.ONE));
    }

    private static byte[] filled(int value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

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
