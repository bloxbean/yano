package com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
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
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Authorization;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Domain;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchManifest;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchSettlement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoJubjubBatchDevelopmentSetup;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.validator.Groth16BLS12381Verifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoJubjubBatchValidityRootTest extends ContractTest {
    private static final byte[] THREAD_POLICY = fill(28, 41);
    private static final byte[] ROOT_SCRIPT = fill(28, 42);
    private static final byte[] DATA_SCRIPT = fill(28, 43);
    private static final byte[] MIGRATION_AUTHORITY = fill(28, 44);
    private static final byte[] MIGRATION_TARGET = fill(28, 45);
    private static final BigInteger WITHDRAWAL =
            BigInteger.valueOf(3_000_000);

    @TempDir
    Path keys;

    @Test
    void b16ProofAdvancesTheActualSettlementBoundValidator() throws Exception {
        System.setProperty("zeroj.allowInsecureTrustedSetup", "true");
        EutxoZkBatchProfile profile =
                EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
        EutxoL2Transaction transaction = transaction();
        var compiledValidator = compileValidator(
                EutxoValidityRootValidator.class).program();
        try (EutxoJubjubBatchDevelopmentSetup setup =
                     EutxoJubjubBatchDevelopmentSetup.create(
                             profile, keys)) {
            EutxoZkBatchManifest manifest =
                    new EutxoZkBatchManifest(
                            List.of(transaction.transactionId()));
            EutxoZkBatchSettlement settlement =
                    EutxoZkBatchSettlement.forTransactions(
                            profile,
                            setup.verificationKey().digestHex(),
                            List.of(transaction),
                            WITHDRAWAL);
            var proof = setup.prove(
                    new byte[32],
                    List.of(transaction),
                    settlement);
            var inputs = proof.settlementInputs();
            var keyParams =
                    EutxoValidityOnChainAbi.verificationKeyParameters(
                            setup.verificationKey());
            var verifierOutput = JulcScriptLoader.loadOutput(
                    Groth16BLS12381Verifier.class);
            var verifier = JulcScriptAdapter.toProgram(
                            verifierOutput.cborHex())
                    .applyParams(
                            keyParams.toArray(PlutusData[]::new));
            var verifierContext = spendingContext(
                            TestDataBuilder.randomTxOutRef_typed(),
                            EutxoValidityOnChainAbi.publicInputs(inputs))
                    .redeemer(PlutusData.constr(
                            0,
                            PlutusData.bytes(proof.piA()),
                            PlutusData.bytes(proof.piB()),
                            PlutusData.bytes(proof.piC())))
                    .buildPlutusData();
            assertSuccess(evaluate(verifier, verifierContext));
            var program = compiledValidator
                    .applyParams(
                            PlutusData.bytes(THREAD_POLICY),
                            PlutusData.bytes(new byte[0]),
                            PlutusData.integer(
                                    inputs.settlementContext()),
                            PlutusData.integer(
                                    profile.maximumTransactions()),
                            PlutusData.integer(
                                    EutxoZkBatchManifest.CANONICAL_BYTES),
                            keyParams.get(0),
                            keyParams.get(1),
                            keyParams.get(2),
                            keyParams.get(3),
                            keyParams.get(4),
                            PlutusData.bytes(DATA_SCRIPT),
                            PlutusData.bytes(MIGRATION_AUTHORITY),
                            PlutusData.bytes(MIGRATION_TARGET));

            PlutusData current = rootDatum(
                    6,
                    inputs.previousRoot(),
                    inputs.settlementContext(),
                    BigInteger.ZERO,
                    BigInteger.ZERO);
            PlutusData next = rootDatum(
                    7,
                    inputs.nextRoot(),
                    inputs.settlementContext(),
                    inputs.batchDataCommitment(),
                    inputs.withdrawalCommitment());
            TxOutRef ownRef = ref(1);
            Address rootAddress = scriptAddress(ROOT_SCRIPT);
            PlutusData context = spendingContext(ownRef, current)
                    .redeemer(
                            EutxoValidityOnChainAbi.advanceRedeemer(
                                    proof, manifest))
                    .input(new TxInInfo(
                            ownRef,
                            rootOutput(rootAddress, current)))
                    .output(rootOutput(rootAddress, next))
                    .output(dataOutput(manifest.canonicalBytes()))
                    .buildPlutusData();

            var result = evaluate(program, context);
            assertSuccess(result);
            assertThat(result.budgetConsumed()).isNotNull();
            System.out.printf(
                    "DEVNET_B16_L1_PARITY constraints=%d wires=%d "
                            + "setupMillis=%d proofMillis=%d budget=%s%n",
                    setup.constraintCount(),
                    setup.wireCount(),
                    setup.setupMillis(),
                    proof.proofMillis(),
                    result.budgetConsumed());
        }
    }

    private static EutxoL2Transaction transaction() throws Exception {
        String address =
                "addr_test1vp8mg8c5950hhrj3mkfr9ggseae2aj24ya2rndegwzuuyrg77ht6p";
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder()
                        .transactionId("11".repeat(32))
                        .index(0)
                        .build()))
                .outputs(List.of(TransactionOutput.builder()
                        .address(address)
                        .value(Value.fromCoin(
                                BigInteger.valueOf(10_000_000)))
                        .build()))
                .fee(BigInteger.ZERO)
                .ttl(100)
                .networkId(NetworkId.TESTNET)
                .build();
        var authorizationProfile =
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        EutxoL2Domain domain = new EutxoL2Domain(
                "payments",
                "devnet",
                EutxoProfile.V1.digestHex(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                authorizationProfile.id(),
                authorizationProfile.digestHex(),
                fill(32, 5),
                100);
        BigInteger secret = BigInteger.valueOf(424242);
        var keypair = EdDSAJubjub.keypairFromSecret(secret);
        EutxoL2Authorization unsigned =
                new EutxoL2Authorization(
                        "22".repeat(28),
                        1,
                        keypair.pk().toBytes(),
                        new byte[32],
                        new byte[32],
                        List.of(0));
        EutxoL2Transaction template = new EutxoL2Transaction(
                domain,
                CborSerializationUtil.serialize(body.serialize()),
                List.of(unsigned));
        BigInteger message = new BigInteger(
                1, template.signingCommitment())
                .mod(JubjubCurve.BASE_FIELD_PRIME);
        var signature = EdDSAJubjub.sign(secret, message);
        return new EutxoL2Transaction(
                domain,
                template.transactionBody(),
                List.of(new EutxoL2Authorization(
                        unsigned.paymentCredential(),
                        unsigned.keyEpoch(),
                        unsigned.publicKey(),
                        signature.r().toBytes(),
                        littleEndian(signature.s()),
                        unsigned.inputIndexes())));
    }

    private static byte[] littleEndian(BigInteger value) {
        byte[] fixed = new byte[32];
        byte[] encoded = value.toByteArray();
        int offset = encoded.length == 33 && encoded[0] == 0 ? 1 : 0;
        for (int index = offset; index < encoded.length; index++) {
            fixed[encoded.length - 1 - index] = encoded[index];
        }
        return fixed;
    }

    private static PlutusData rootDatum(
            long height,
            BigInteger root,
            BigInteger context,
            BigInteger batch,
            BigInteger withdrawal
    ) {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes(
                        "payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(0),
                PlutusData.integer(height),
                PlutusData.integer(root),
                PlutusData.integer(context),
                PlutusData.integer(batch),
                PlutusData.integer(withdrawal),
                PlutusData.integer(0));
    }

    private static TxOut rootOutput(
            Address address,
            PlutusData datum
    ) {
        return new TxOut(
                address,
                com.bloxbean.cardano.julc.ledger.Value
                        .lovelace(BigInteger.valueOf(2_000_000))
                        .merge(com.bloxbean.cardano.julc.ledger.Value
                                .singleton(
                                        new PolicyId(THREAD_POLICY),
                                        new TokenName(new byte[0]),
                                        BigInteger.ONE)),
                new OutputDatum.OutputDatumInline(datum),
                Optional.empty());
    }

    private static TxOut dataOutput(byte[] manifest) {
        return new TxOut(
                scriptAddress(DATA_SCRIPT),
                com.bloxbean.cardano.julc.ledger.Value
                        .lovelace(BigInteger.valueOf(2_000_000)),
                new OutputDatum.OutputDatumInline(
                        PlutusData.bytes(manifest)),
                Optional.empty());
    }

    private static Address scriptAddress(byte[] hash) {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(hash)),
                Optional.empty());
    }

    private static TxOutRef ref(int value) {
        return new TxOutRef(
                new TxId(fill(32, value)), BigInteger.ZERO);
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
