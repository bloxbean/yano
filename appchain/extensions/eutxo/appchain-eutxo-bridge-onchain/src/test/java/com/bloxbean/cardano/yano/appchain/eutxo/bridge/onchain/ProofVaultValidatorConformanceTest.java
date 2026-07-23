package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoMpfProofConverter;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoMpfProof;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalCommitment;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProofVaultValidatorConformanceTest extends ContractTest {
    private static final long MAX_TX_CPU = 10_000_000_000L;
    private static final long MAX_TX_MEM = 14_000_000L;
    private static final byte[] ROOT_POLICY = filled(0x51, 28);
    private static final byte[] NULLIFIER_POLICY = filled(0x52, 28);
    private static final byte[] VAULT_SCRIPT = filled(0x53, 28);
    private static final byte[] ROOT_SCRIPT = filled(0x54, 28);
    private static final byte[] NULLIFIER_SCRIPT = filled(0x55, 28);
    private static final String DESTINATION =
            "addr_test1wzn5ee2qaqvly3hx7e0nk3vhm240n5muq3plhjcnvx9ppjgf62u6a";
    private static final BigInteger CLAIM_LOVELACE =
            BigInteger.valueOf(30_000_000);
    private static final BigInteger FEE = BigInteger.valueOf(1_000_000);
    private static final BigInteger VAULT_INPUT =
            BigInteger.valueOf(100_000_000);
    private static Program program;

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    @Test
    void realMpfClaimSettlesPermissionlesslyWithinBudget() {
        Fixture fixture = fixture();
        var result = evaluate(
                program(),
                context(fixture, false, false, false));

        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
        assertThat(fixture.withdrawal().encode().length)
                .as("normalized proof redeemer bytes")
                .isLessThan(16 * 1024);
        System.out.println("[ProofVaultValidator] folds="
                + fixture.proof().folds().size()
                + ", redeemerBytes=" + fixture.withdrawal().encode().length
                + ", budget=" + result.budgetConsumed());
    }

    @Test
    void wrongRootReplayOldEpochAndDuplicatePayoutFail() {
        Fixture fixture = fixture();

        assertFailure(evaluate(
                program(), context(fixture, true, false, false)));
        assertFailure(evaluate(
                program(), context(fixture, false, true, false)));
        assertFailure(evaluate(
                program(), context(fixture, false, false, true)));
        assertFailure(evaluate(
                program(), context(fixture, false, false, false, true)));
        assertFailure(evaluate(
                program(),
                context(
                        fixture,
                        false, false, false, false,
                        false, false, true, false, false)));
        assertFailure(evaluate(
                program(),
                context(
                        fixture,
                        false, false, false, false,
                        false, false, false, true, false)));
        assertFailure(evaluate(
                program(),
                context(
                        fixture,
                        false, false, false, false,
                        false, false, false, false, true)));
    }

    @Test
    void rootAndNullifierThreadsMustUseConfiguredScripts() {
        Fixture fixture = fixture();

        assertFailure(evaluate(
                program(),
                context(
                        fixture,
                        false, false, false, false,
                        true, false, false, false, false)));
        assertFailure(evaluate(
                program(),
                context(
                        fixture,
                        false, false, false, false,
                        false, true, false, false, false)));
    }

    private Program program() {
        if (program == null) {
            program = compileValidator(ProofVaultValidator.class)
                    .program()
                    .applyParams(
                            PlutusData.bytes(ROOT_POLICY),
                            PlutusData.bytes(new byte[0]),
                            PlutusData.bytes(ROOT_SCRIPT),
                            PlutusData.bytes(NULLIFIER_POLICY),
                            PlutusData.bytes(new byte[0]),
                            PlutusData.bytes(NULLIFIER_SCRIPT),
                            PlutusData.bytes(
                                    EutxoWithdrawalCommitment.DOMAIN),
                            PlutusData.bytes(
                                    EutxoStateKeys
                                            .withdrawalCommitmentPrefix()));
        }
        return program;
    }

    private PlutusData context(
            Fixture fixture,
            boolean wrongRoot,
            boolean replay,
            boolean oldEpoch
    ) {
        return context(fixture, wrongRoot, replay, oldEpoch, false);
    }

    private PlutusData context(
            Fixture fixture,
            boolean wrongRoot,
            boolean replay,
            boolean oldEpoch,
            boolean duplicatePayout
    ) {
        return context(
                fixture,
                wrongRoot,
                replay,
                oldEpoch,
                duplicatePayout,
                false,
                false,
                false,
                false,
                false);
    }

    private PlutusData context(
            Fixture fixture,
            boolean wrongRoot,
            boolean replay,
            boolean oldEpoch,
            boolean duplicatePayout,
            boolean rootAtWrongScript,
            boolean nullifierAtWrongScript,
            boolean mismatchedSettlementDestination,
            boolean proofUnderUnrelatedKey,
            boolean vaultPaysRelayerFee
    ) {
        Address vaultAddress = scriptAddress(VAULT_SCRIPT);
        Address rootAddress = scriptAddress(
                rootAtWrongScript ? filled(0x56, 28) : ROOT_SCRIPT);
        Address nullifierAddress = scriptAddress(
                nullifierAtWrongScript
                        ? filled(0x57, 28) : NULLIFIER_SCRIPT);
        BigInteger epoch = oldEpoch ? BigInteger.valueOf(6) : BigInteger.valueOf(7);
        BigInteger sequence = replay ? BigInteger.valueOf(4) : BigInteger.valueOf(3);
        byte[] stateRoot = wrongRoot ? new byte[32] : fixture.proof().stateRoot();

        PlutusData rootDatum = rootDatum(epoch, stateRoot);
        PlutusData currentNullifier = nullifierDatum(epoch, sequence);
        PlutusData nextNullifier = nullifierDatum(
                epoch, sequence.add(BigInteger.ONE));
        PlutusData settlement = PlutusData.constr(
                2,
                PlutusData.integer(1),
                PlutusData.bytes("payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(7),
                PlutusData.bytes(fixture.commitment().claimId()),
                mismatchedSettlementDestination
                        ? scriptAddress(filled(0x58, 28))
                        .toPlutusData()
                        : fixture.destination().toPlutusData(),
                PlutusData.integer(CLAIM_LOVELACE));

        TxOutRef vaultRef = new TxOutRef(
                new TxId(filled(0x61, 32)), BigInteger.ZERO);
        TxOutRef nullifierRef = new TxOutRef(
                new TxId(filled(0x62, 32)), BigInteger.ZERO);
        TxOutRef rootRef = new TxOutRef(
                new TxId(filled(0x63, 32)), BigInteger.ZERO);
        Value nullifierValue = threadedValue(
                BigInteger.valueOf(2_000_000), NULLIFIER_POLICY);
        Value rootValue = threadedValue(
                BigInteger.valueOf(2_000_000), ROOT_POLICY);
        TxOut payout = new TxOut(
                fixture.destination(),
                Value.lovelace(CLAIM_LOVELACE),
                new OutputDatum.NoOutputDatum(),
                Optional.empty());

        var builder = spendingContext(vaultRef, PlutusData.UNIT)
                .redeemer(proofUnderUnrelatedKey
                        ? fixture.unrelatedKeyProofData()
                        : fixture.proofData())
                .fee(FEE)
                .input(new TxInInfo(
                        vaultRef,
                        new TxOut(
                                vaultAddress,
                                Value.lovelace(VAULT_INPUT),
                                new OutputDatum.OutputDatumInline(
                                        PlutusData.UNIT),
                                Optional.empty())))
                .input(new TxInInfo(
                        nullifierRef,
                        new TxOut(
                                nullifierAddress,
                                nullifierValue,
                                new OutputDatum.OutputDatumInline(
                                        currentNullifier),
                                Optional.empty())))
                .referenceInput(new TxInInfo(
                        rootRef,
                        new TxOut(
                                rootAddress,
                                rootValue,
                                new OutputDatum.OutputDatumInline(rootDatum),
                                Optional.empty())))
                .output(payout)
                .output(new TxOut(
                        vaultAddress,
                        Value.lovelace(
                                VAULT_INPUT.subtract(
                                        CLAIM_LOVELACE).subtract(
                                        vaultPaysRelayerFee
                                                ? FEE
                                                : BigInteger.ZERO)),
                        new OutputDatum.OutputDatumInline(settlement),
                        Optional.empty()))
                .output(new TxOut(
                        nullifierAddress,
                        nullifierValue,
                        new OutputDatum.OutputDatumInline(nextNullifier),
                        Optional.empty()));
        if (duplicatePayout) {
            builder.output(payout);
        }
        return builder.buildPlutusData();
    }

    private static Fixture fixture() {
        EutxoWithdrawalClaim claim = new EutxoWithdrawalClaim(
                1,
                "payments",
                7,
                new EutxoOutpoint("71".repeat(32), 0),
                DESTINATION,
                CLAIM_LOVELACE,
                filled(0x72, 32),
                3,
                42);
        EutxoWithdrawalCommitment commitment =
                EutxoWithdrawalCommitment.fromClaim(claim);
        byte[] key = EutxoStateKeys.withdrawalCommitment(claim.claimId());
        byte[] value = commitment.encode();
        byte[] unrelatedKey =
                "unrelated-state-key".getBytes(StandardCharsets.US_ASCII);
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        trie.put(key, value);
        trie.put(unrelatedKey, value);
        for (int i = 0; i < 3; i++) {
            trie.put(
                    ("neighbor-" + i).getBytes(StandardCharsets.US_ASCII),
                    ("value-" + i).getBytes(StandardCharsets.US_ASCII));
        }
        byte[] root = trie.getRootHash();
        AppChainClient.Proof wire = new AppChainClient.Proof(
                HexFormat.of().formatHex(key),
                "payments",
                HexFormat.of().formatHex(root),
                HexFormat.of().formatHex(
                        trie.getProofWire(key).orElseThrow()),
                HexFormat.of().formatHex(value),
                42L,
                42L);
        EutxoMpfProof proof = EutxoMpfProofConverter.convert(wire);
        AppChainClient.Proof unrelatedWire = new AppChainClient.Proof(
                HexFormat.of().formatHex(unrelatedKey),
                "payments",
                HexFormat.of().formatHex(root),
                HexFormat.of().formatHex(
                        trie.getProofWire(unrelatedKey).orElseThrow()),
                HexFormat.of().formatHex(value),
                42L,
                42L);
        EutxoMpfProof unrelatedProof =
                EutxoMpfProofConverter.convert(unrelatedWire);
        com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProofWithdrawal withdrawal =
                new com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProofWithdrawal(
                        1, commitment, proof);
        Address destination = destinationAddress();
        PlutusData claimData = PlutusData.constr(
                3,
                PlutusData.integer(1),
                PlutusData.bytes("payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(7),
                PlutusData.integer(3),
                PlutusData.bytes(commitment.claimId()),
                destination.toPlutusData(),
                PlutusData.integer(CLAIM_LOVELACE));
        PlutusData proofData = proofData(claimData, proof);
        PlutusData unrelatedProofData =
                proofData(claimData, unrelatedProof);
        return new Fixture(
                commitment,
                withdrawal,
                proof,
                destination,
                proofData,
                unrelatedProofData);
    }

    private static PlutusData proofData(
            PlutusData claimData,
            EutxoMpfProof proof
    ) {
        PlutusData folds = PlutusData.list(proof.folds().stream()
                .map(ProofVaultValidatorConformanceTest::foldData)
                .toArray(PlutusData[]::new));
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                claimData,
                PlutusData.bytes(proof.key()),
                PlutusData.bytes(proof.value()),
                PlutusData.bytes(proof.leafSuffix()),
                folds);
    }

    private static PlutusData foldData(EutxoMpfProof.FoldStep fold) {
        List<byte[]> neighbors = fold.neighbors();
        return PlutusData.constr(
                0,
                PlutusData.integer(fold.cursor()),
                PlutusData.bytes(fold.prefix()),
                PlutusData.integer(fold.nibble()),
                PlutusData.bytes(neighbors.get(0)),
                PlutusData.bytes(neighbors.get(1)),
                PlutusData.bytes(neighbors.get(2)),
                PlutusData.bytes(neighbors.get(3)),
                PlutusData.bytes(fold.branchValueHash()));
    }

    private static PlutusData rootDatum(
            BigInteger epoch,
            byte[] root
    ) {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes("payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(epoch),
                PlutusData.integer(42),
                PlutusData.bytes(root),
                PlutusData.list(PlutusData.bytes(filled(1, 32))),
                PlutusData.integer(1),
                PlutusData.integer(0));
    }

    private static PlutusData nullifierDatum(
            BigInteger epoch,
            BigInteger sequence
    ) {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes("payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(epoch),
                PlutusData.integer(sequence),
                PlutusData.integer(0));
    }

    private static Address destinationAddress() {
        byte[] raw = Bech32.decode(DESTINATION).data;
        assertThat((raw[0] & 0xF0) >>> 4).isEqualTo(7);
        return new Address(
                new Credential.ScriptCredential(
                        new ScriptHash(Arrays.copyOfRange(raw, 1, 29))),
                Optional.empty());
    }

    private static Address scriptAddress(byte[] hash) {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(hash)),
                Optional.empty());
    }

    private static Value threadedValue(
            BigInteger lovelace,
            byte[] policy
    ) {
        return Value.lovelace(lovelace).merge(Value.singleton(
                new PolicyId(policy),
                new TokenName(new byte[0]),
                BigInteger.ONE));
    }

    private static byte[] filled(int value, int length) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private record Fixture(
            EutxoWithdrawalCommitment commitment,
            com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProofWithdrawal withdrawal,
            EutxoMpfProof proof,
            Address destination,
            PlutusData proofData,
            PlutusData unrelatedKeyProofData
    ) {
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
