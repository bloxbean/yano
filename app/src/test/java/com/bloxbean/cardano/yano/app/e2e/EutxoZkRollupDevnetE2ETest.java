package com.bloxbean.cardano.yano.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.cbor.PlutusDataCborEncoder;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Authorization;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Domain;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStagingDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoClient;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.DepositStagingValidator;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoFinalizedProofWitness;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchManifest;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProof;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchSettlement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain.EutxoProofWithdrawalVaultValidator;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain.EutxoValidityOnChainAbi;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain.EutxoValidityRootValidator;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoJubjubBatchDevelopmentSetup;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.ZerojPoseidonValidityEngine;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scalus.cardano.ledger.Builtins;
import scalus.cardano.ledger.Language;
import scalus.cardano.ledger.MajorProtocolVersion;
import scalus.cardano.ledger.Script;
import scalus.uplc.DeBruijnedProgram;
import scalus.uplc.builtin.ByteString;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live disposable-devnet settlement smoke test for the preview EUTxO validity
 * rollup.
 *
 * <p>The test stages and accepts a real L1 deposit, waits for the stable
 * observer to mint its mirrored L2 outpoint, spends that outpoint into an
 * irrevocable withdrawal claim, proves the finalized transition, advances the
 * root on L1, and atomically spends the accepted deposit vault together with
 * the proof controller to pay the user. The stable withdrawal observer then
 * reconciles the same claim on L2.</p>
 */
@io.quarkus.test.junit.QuarkusTest
@io.quarkus.test.junit.TestProfile(EutxoZkDevnetTestProfile.class)
class EutxoZkRollupDevnetE2ETest extends BaseE2ETest {
    private static final Logger log =
            LoggerFactory.getLogger(EutxoZkRollupDevnetE2ETest.class);
    private static final String CHAIN_ID = "payments";
    private static final String NETWORK = "devnet";
    private static final String ROOT_TOKEN = "YanoZkRoot";
    private static final String VAULT_TOKEN = "YanoZkVault";
    private static final long ROOT_LOVELACE = 10_000_000L;
    private static final long CONTROLLER_LOVELACE = 2_000_000L;
    private static final long WITHDRAWAL_LOVELACE = 3_000_000L;
    private static final long REFERENCE_SCRIPT_LOVELACE = 20_000_000L;

    @TempDir
    Path keyDirectory;

    private Account operator;
    private Account payout;
    private Account referenceOwner;

    @Override
    protected int getAccountBaseIndex() {
        return 180;
    }

    @BeforeAll
    void fundOperator() throws Exception {
        operator = getAccount(0);
        payout = getAccount(1);
        referenceOwner = getAccount(2);
        fundAddress(operator.enterpriseAddress(), 50_000);
    }

    @Test
    void depositFinalizeProveSettleAndWithdrawOnDevnet()
            throws Exception {
        CompilerHarness compiler = new CompilerHarness();
        ScriptPubkey fundsVaultScript =
                EutxoZkDevnetTestProfile.fundsVaultScript(operator);
        byte[] fundsVaultScriptHash = HexFormat.of().parseHex(
                fundsVaultScript.getPolicyId());
        String fundsVaultAddress = AddressProvider.getEntAddress(
                fundsVaultScript, Networks.testnet()).toBech32();
        Program stagingProgram = compiler.compileBridge(
                DepositStagingValidator.class).applyParams(
                com.bloxbean.cardano.julc.core.PlutusData.bytes(
                        fundsVaultScriptHash));
        PlutusV3Script stagingScript =
                JulcScriptAdapter.fromProgram(stagingProgram);
        String stagingAddress = AddressProvider.getEntAddress(
                stagingScript, Networks.testnet()).toBech32();
        Program unparameterizedRoot = compiler.compile(
                EutxoValidityRootValidator.class);
        Program unparameterizedVault = compiler.compile(
                EutxoProofWithdrawalVaultValidator.class);
        EutxoZkBatchProfile profile =
                EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
        AppChainClient appChainClient = AppChainClient.builder(baseUrl)
                .chainId(CHAIN_ID)
                .build();
        EutxoClient eutxoClient = new EutxoClient(appChainClient);
        Utxo stagingReference = deployReference(
                stagingScript);
        Utxo acceptedVault = acceptDeposit(
                stagingScript,
                stagingReference,
                stagingAddress,
                fundsVaultAddress);
        EutxoOutpoint acceptedOutpoint = outpoint(acceptedVault);
        EutxoDepositRecord deposit = waitForDeposit(
                eutxoClient, acceptedOutpoint);
        assertEquals(
                EutxoZkDevnetTestProfile.DEPOSIT_LOVELACE,
                lovelace(acceptedVault).longValueExact());
        EutxoL2Transaction l2Transaction =
                l2Withdrawal(deposit.mirroredOutpoint());
        var submitted = eutxoClient.submit(
                l2Transaction.canonicalBytes());
        assertEquals(CHAIN_ID, submitted.chainId());
        EutxoReceipt receipt = waitForReceipt(
                eutxoClient, l2Transaction.transactionId());
        assertEquals(EutxoReceipt.Status.ACCEPTED, receipt.status());
        EutxoValidityTransition transition = waitForTransition(
                eutxoClient, receipt.appHeight(), receipt.ordinal());
        EutxoFinalizedProofWitness finalized =
                EutxoFinalizedProofWitness.derive(transition);
        assertEquals(
                l2Transaction,
                EutxoL2Transaction.decode(
                        transition.canonicalTransaction()));
        assertTrue(eutxoClient.utxos(
                        operator.enterpriseAddress()).stream()
                .anyMatch(record -> record.outpoint().transactionId()
                        .equals(l2Transaction.transactionId())));
        EutxoWithdrawalClaim withdrawal =
                transition.withdrawals().getFirst();
        assertEquals(
                BigInteger.valueOf(WITHDRAWAL_LOVELACE),
                transition.withdrawalLovelace());
        long rootHeight = receipt.appHeight();

        try (EutxoJubjubBatchDevelopmentSetup setup =
                     EutxoJubjubBatchDevelopmentSetup.create(
                             profile, keyDirectory)) {
            EutxoZkBatchManifest manifest = new EutxoZkBatchManifest(
                    List.of(l2Transaction.transactionId()));
            EutxoZkBatchSettlement settlement =
                    EutxoZkBatchSettlement.forFinalized(
                            profile,
                            setup.verificationKey().digestHex(),
                            List.of(finalized));
            EutxoZkBatchProof proof = setup.proveFinalized(
                    transition.previousRoot(),
                    List.of(finalized),
                    settlement);
            assertTrue(EutxoJubjubBatchDevelopmentSetup.verify(
                    proof, setup.verificationKey()));
            var runtimeCommitment = new ZerojPoseidonValidityEngine(
                    CHAIN_ID, EutxoProfile.V1).commit(transition);
            assertEquals(
                    new BigInteger(1, runtimeCommitment.root()),
                    proof.settlementInputs().nextRoot());

            ScriptPubkey threadPolicy = ScriptPubkey.create(
                    VerificationKey.create(operator.publicKeyBytes()));
            byte[] threadPolicyId =
                    HexFormat.of().parseHex(threadPolicy.getPolicyId());
            byte[] rootTokenName =
                    ROOT_TOKEN.getBytes(StandardCharsets.UTF_8);
            byte[] vaultTokenName =
                    VAULT_TOKEN.getBytes(StandardCharsets.UTF_8);

            PlutusV3Script dataScript = PlutusV3Script.builder()
                    .cborHex("46450101002499")
                    .build();
            byte[] dataScriptHash = dataScript.getScriptHash();
            byte[] migrationAuthority = paymentKeyHash(operator);
            var keyParameters =
                    EutxoValidityOnChainAbi.verificationKeyParameters(
                            setup.verificationKey());
            Program rootProgram = unparameterizedRoot.applyParams(
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            threadPolicyId),
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            rootTokenName),
                    com.bloxbean.cardano.julc.core.PlutusData.integer(
                            settlement.settlementContext()),
                    com.bloxbean.cardano.julc.core.PlutusData.integer(
                            profile.maximumTransactions()),
                    com.bloxbean.cardano.julc.core.PlutusData.integer(
                            EutxoZkBatchManifest.CANONICAL_BYTES),
                    keyParameters.get(0),
                    keyParameters.get(1),
                    keyParameters.get(2),
                    keyParameters.get(3),
                    keyParameters.get(4),
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            dataScriptHash),
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            migrationAuthority),
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            dataScriptHash));
            PlutusV3Script rootScript =
                    JulcScriptAdapter.fromProgram(rootProgram);

            com.bloxbean.cardano.julc.core.PlutusData payoutAddress =
                    new com.bloxbean.cardano.julc.ledger.Address(
                            new Credential.PubKeyCredential(
                                    new PubKeyHash(paymentKeyHash(payout))),
                            Optional.empty()).toPlutusData();
            Program vaultProgram = unparameterizedVault.applyParams(
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            threadPolicyId),
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            vaultTokenName),
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            threadPolicyId),
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            rootTokenName),
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            rootScript.getScriptHash()),
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            fundsVaultScriptHash),
                    payoutAddress,
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            migrationAuthority),
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            dataScriptHash));
            PlutusV3Script vaultScript =
                    JulcScriptAdapter.fromProgram(vaultProgram);

            String rootAddress = AddressProvider.getEntAddress(
                    rootScript, Networks.testnet()).toBech32();
            String vaultAddress = AddressProvider.getEntAddress(
                    vaultScript, Networks.testnet()).toBech32();
            String dataAddress = AddressProvider.getEntAddress(
                    dataScript, Networks.testnet()).toBech32();
            log.info(
                    "Compiled validators: root={} bytes, vault={} bytes",
                    rootScript.serializeScriptBody().length,
                    vaultScript.serializeScriptBody().length);
            log.info(
                    "Scalus validator diagnostics: root={}, vault={}",
                    scalusDiagnostics(rootScript),
                    scalusDiagnostics(vaultScript));

            String rootReferenceTx = submit(new Tx()
                    .payToAddress(
                            referenceOwner.enterpriseAddress(),
                            Amount.lovelace(BigInteger.valueOf(
                                    REFERENCE_SCRIPT_LOVELACE)),
                            rootScript)
                    .from(operator.enterpriseAddress()));
            Utxo rootReference = referenceScriptUtxo(
                    rootReferenceTx, rootScript);

            String vaultReferenceTx = submit(new Tx()
                    .payToAddress(
                            referenceOwner.enterpriseAddress(),
                            Amount.lovelace(BigInteger.valueOf(
                                    REFERENCE_SCRIPT_LOVELACE)),
                            vaultScript)
                    .from(operator.enterpriseAddress()));
            Utxo vaultReference = referenceScriptUtxo(
                    vaultReferenceTx, vaultScript);

            String mintTx = submit(new Tx()
                    .mintAssets(
                            threadPolicy,
                            List.of(
                                    new Asset(
                                            ROOT_TOKEN, BigInteger.ONE),
                                    new Asset(
                                            VAULT_TOKEN, BigInteger.ONE)),
                            operator.enterpriseAddress())
                    .from(operator.enterpriseAddress()));
            checkIfUtxoAvailable(
                    mintTx, operator.enterpriseAddress());

            String rootUnit = threadPolicy.getPolicyId()
                    + HexUtil.encodeHexString(rootTokenName);
            String vaultUnit = threadPolicy.getPolicyId()
                    + HexUtil.encodeHexString(vaultTokenName);
            PlutusData initialRootDatum = ccl(rootDatum(
                    0,
                    proof.settlementInputs().previousRoot(),
                    settlement.settlementContext(),
                    BigInteger.ZERO,
                    BigInteger.ZERO));
            PlutusData initialVaultDatum = ccl(vaultDatum(0, 0));
            String bootstrapTx = submit(new Tx()
                    .payToContract(
                            rootAddress,
                            List.of(
                                    Amount.lovelace(BigInteger.valueOf(
                                            ROOT_LOVELACE)),
                                    new Amount(rootUnit, BigInteger.ONE)),
                            initialRootDatum)
                    .payToContract(
                            vaultAddress,
                            List.of(
                                    Amount.lovelace(BigInteger.valueOf(
                                            CONTROLLER_LOVELACE)),
                                    new Amount(vaultUnit, BigInteger.ONE)),
                            initialVaultDatum)
                    .from(operator.enterpriseAddress()));
            checkIfUtxoAvailable(bootstrapTx, rootAddress);
            checkIfUtxoAvailable(bootstrapTx, vaultAddress);

            Utxo currentRoot = tokenUtxo(rootAddress, rootUnit);
            PlutusData nextRootDatum = ccl(rootDatum(
                    rootHeight,
                    proof.settlementInputs().nextRoot(),
                    settlement.settlementContext(),
                    proof.settlementInputs().batchDataCommitment(),
                    proof.settlementInputs().withdrawalCommitment()));
            PlutusData rootRedeemer = ccl(
                    EutxoValidityOnChainAbi.advanceRedeemer(
                            proof, manifest));
            PlutusData manifestDatum = ccl(
                    com.bloxbean.cardano.julc.core.PlutusData.bytes(
                            manifest.canonicalBytes()));
            String settleTx = submit(new ScriptTx()
                    .collectFrom(currentRoot, rootRedeemer)
                    .readFrom(rootReference)
                    .payToContract(
                            rootAddress,
                            List.of(
                                    Amount.lovelace(BigInteger.valueOf(
                                            ROOT_LOVELACE)),
                                    new Amount(rootUnit, BigInteger.ONE)),
                            nextRootDatum)
                    .payToContract(
                            dataAddress,
                            Amount.lovelace(
                                    BigInteger.valueOf(2_000_000)),
                            manifestDatum)
                    .withChangeAddress(operator.enterpriseAddress()));
            checkIfUtxoAvailable(settleTx, rootAddress);

            Utxo settledRoot = tokenUtxo(rootAddress, rootUnit);
            Utxo currentVault = tokenUtxo(vaultAddress, vaultUnit);
            PlutusData withdrawRedeemer = ccl(
                    withdrawRedeemer(rootHeight));
            PlutusData nextVaultDatum = ccl(
                    vaultDatum(1, rootHeight));
            PlutusData settlementDatum = PlutusData.deserialize(
                    EutxoSettlementDatum.forAddress(
                            EutxoSettlementDatum.ABI_VERSION,
                            CHAIN_ID,
                            0,
                            withdrawal.claimId(),
                            payout.enterpriseAddress(),
                            BigInteger.valueOf(WITHDRAWAL_LOVELACE))
                            .encode());
            String withdrawalTx = submit(new Tx()
                    .collectFrom(currentVault, withdrawRedeemer)
                    .collectFrom(List.of(acceptedVault))
                    .readFrom(vaultReference, settledRoot)
                    .attachNativeScript(fundsVaultScript)
                    .payToContract(
                            vaultAddress,
                            List.of(
                                    Amount.lovelace(BigInteger.valueOf(
                                            CONTROLLER_LOVELACE)),
                                    new Amount(vaultUnit, BigInteger.ONE)),
                            nextVaultDatum)
                    .payToContract(
                            fundsVaultAddress,
                            Amount.lovelace(BigInteger.valueOf(
                                    EutxoZkDevnetTestProfile
                                            .DEPOSIT_LOVELACE
                                            - WITHDRAWAL_LOVELACE)),
                            settlementDatum)
                    .payToAddress(
                            payout.enterpriseAddress(),
                            Amount.lovelace(BigInteger.valueOf(
                                    WITHDRAWAL_LOVELACE)))
                    .withChangeAddress(operator.enterpriseAddress())
                    .from(operator.enterpriseAddress()));
            checkIfUtxoAvailable(
                    withdrawalTx, payout.enterpriseAddress());

            Utxo nextVault = tokenUtxo(vaultAddress, vaultUnit);
            assertEquals(
                    BigInteger.valueOf(CONTROLLER_LOVELACE),
                    lovelace(nextVault));
            EutxoWithdrawalRecord confirmed = waitForWithdrawal(
                    eutxoClient, withdrawal.claimId());
            assertEquals(
                    EutxoWithdrawalRecord.Status.CONFIRMED,
                    confirmed.status());
            assertEquals(
                    withdrawalTx,
                    confirmed.settlementTransactionId());
            assertTrue(utxoSupplier.getAll(payout.enterpriseAddress())
                    .stream()
                    .filter(utxo -> withdrawalTx.equals(utxo.getTxHash()))
                    .anyMatch(utxo -> lovelace(utxo).equals(
                            BigInteger.valueOf(WITHDRAWAL_LOVELACE))));
            assertFalse(settleTx.isBlank());
            log.info(
                    "EUTXO_ZK_DEVNET_ROUND_TRIP_PASS deposit={} bootstrap={} "
                            + "settlement={} withdrawal={} proof={} profile={} "
                            + "root={} claim={}",
                    acceptedVault.getTxHash(),
                    bootstrapTx,
                    settleTx,
                    withdrawalTx,
                    proof.digestHex(),
                    profile.id(),
                    proof.settlementInputs().nextRoot(),
                    withdrawal.claimId());
        }
    }

    private String submit(Tx tx) throws Exception {
        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(operator))
                .complete();
        assertTrue(
                result.isSuccessful(),
                "Cardano transaction failed: " + result.getResponse());
        waitForTransaction(result);
        return result.getValue();
    }

    private Utxo deployReference(PlutusV3Script script)
            throws Exception {
        String transactionId = submit(new Tx()
                .payToAddress(
                        referenceOwner.enterpriseAddress(),
                        Amount.lovelace(BigInteger.valueOf(
                                REFERENCE_SCRIPT_LOVELACE)),
                        script)
                .from(operator.enterpriseAddress()));
        return referenceScriptUtxo(transactionId, script);
    }

    private Utxo acceptDeposit(
            PlutusV3Script stagingScript,
            Utxo stagingReference,
            String stagingAddress,
            String fundsVaultAddress
    ) throws Exception {
        long refundDeadline = 10_000_000L;
        EutxoStagingDatum stagingDatum = new EutxoStagingDatum(
                EutxoStagingDatum.ABI_VERSION,
                CHAIN_ID,
                operator.enterpriseAddress(),
                HexFormat.of().parseHex("ab".repeat(32)),
                paymentKeyHash(operator),
                refundDeadline);
        String stagingTransaction = submit(new Tx()
                .payToContract(
                        stagingAddress,
                        Amount.lovelace(BigInteger.valueOf(
                                EutxoZkDevnetTestProfile
                                        .DEPOSIT_LOVELACE)),
                        PlutusData.deserialize(stagingDatum.encode()))
                .from(operator.enterpriseAddress()));
        Utxo staged = transactionUtxo(
                stagingTransaction, stagingAddress);
        EutxoOutpoint stagingOutpoint = outpoint(staged);
        EutxoVaultDatum vaultDatum = new EutxoVaultDatum(
                EutxoVaultDatum.ABI_VERSION,
                CHAIN_ID,
                operator.enterpriseAddress(),
                stagingDatum.depositNonce(),
                stagingOutpoint,
                refundDeadline);
        String acceptanceTransaction = submit(new ScriptTx()
                .collectFrom(
                        staged,
                        BigIntPlutusData.of(BigInteger.ZERO))
                .readFrom(stagingReference)
                .payToContract(
                        fundsVaultAddress,
                        Amount.lovelace(BigInteger.valueOf(
                                EutxoZkDevnetTestProfile
                                        .DEPOSIT_LOVELACE)),
                        PlutusData.deserialize(vaultDatum.encode()))
                .withChangeAddress(operator.enterpriseAddress()));
        Utxo accepted = transactionUtxo(
                acceptanceTransaction, fundsVaultAddress);
        assertEquals(
                HexFormat.of().formatHex(stagingScript.getScriptHash()),
                stagingReference.getReferenceScriptHash());
        return accepted;
    }

    private Utxo transactionUtxo(
            String transactionId,
            String address
    ) throws Exception {
        checkIfUtxoAvailable(transactionId, address);
        return utxoSupplier.getAll(address).stream()
                .filter(utxo -> transactionId.equals(utxo.getTxHash()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "transaction output was not indexed at " + address));
    }

    private static EutxoOutpoint outpoint(Utxo utxo) {
        return new EutxoOutpoint(
                utxo.getTxHash(), utxo.getOutputIndex());
    }

    private String submit(ScriptTx tx) throws Exception {
        Result<String> result = quickTxBuilder.compose(tx)
                .feePayer(operator.enterpriseAddress())
                .withSigner(SignerProviders.signerFrom(operator))
                .complete();
        assertTrue(
                result.isSuccessful(),
                "Cardano script transaction failed: "
                        + result.getResponse());
        waitForTransaction(result);
        return result.getValue();
    }

    private Utxo referenceScriptUtxo(
            String transactionId,
            PlutusV3Script script
    ) throws Exception {
        checkIfUtxoAvailable(
                transactionId, referenceOwner.enterpriseAddress());
        String expectedHash =
                HexFormat.of().formatHex(script.getScriptHash());
        return utxoSupplier.getAll(referenceOwner.enterpriseAddress())
                .stream()
                .filter(utxo -> transactionId.equals(utxo.getTxHash()))
                .filter(utxo -> expectedHash.equals(
                        utxo.getReferenceScriptHash()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "reference script UTxO was not indexed"));
    }

    private Utxo tokenUtxo(String address, String unit) {
        return utxoSupplier.getAll(address).stream()
                .filter(utxo -> utxo.getAmount().stream()
                        .anyMatch(amount -> unit.equals(amount.getUnit())
                                && BigInteger.ONE.equals(
                                amount.getQuantity())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "thread-token UTxO was not indexed at " + address));
    }

    private static BigInteger lovelace(Utxo utxo) {
        return utxo.getAmount().stream()
                .filter(amount -> "lovelace".equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .findFirst()
                .orElse(BigInteger.ZERO);
    }

    private static byte[] paymentKeyHash(Account account) {
        return new com.bloxbean.cardano.client.address.Address(
                account.enterpriseAddress())
                .getPaymentCredentialHash()
                .orElseThrow();
    }

    private static String scalusDiagnostics(PlutusV3Script script)
            throws Exception {
        byte[] scriptBody = script.serializeScriptBody();
        try {
            var decoded =
                    DeBruijnedProgram.fromCborWithRemainingBytes(scriptBody);
            var scalusScript = new Script.PlutusV3(
                    ByteString.fromArray(scriptBody));
            var protocolVersion = new MajorProtocolVersion(11);
            var available = Builtins.findBuiltinsIntroducedIn(
                    Language.PlutusV3, protocolVersion);
            var unsupported = decoded.program().term().collectBuiltins()
                    .diff(available);
            return "version=" + decoded.program().version()
                    + ", remainder=" + decoded.remainder().length
                    + ", unsupported=" + unsupported
                    + ", wellFormedPv11="
                    + scalusScript.isWellFormed(protocolVersion);
        } catch (RuntimeException exception) {
            return exception.getClass().getName()
                    + ": " + exception.getMessage();
        }
    }

    private static PlutusData ccl(
            com.bloxbean.cardano.julc.core.PlutusData value
    ) throws Exception {
        return PlutusData.deserialize(
                PlutusDataCborEncoder.encode(value));
    }

    private static com.bloxbean.cardano.julc.core.PlutusData rootDatum(
            long height,
            BigInteger root,
            BigInteger settlementContext,
            BigInteger batchCommitment,
            BigInteger withdrawal
    ) {
        return com.bloxbean.cardano.julc.core.PlutusData.constr(
                0,
                com.bloxbean.cardano.julc.core.PlutusData.integer(1),
                com.bloxbean.cardano.julc.core.PlutusData.bytes(
                        CHAIN_ID.getBytes(StandardCharsets.UTF_8)),
                com.bloxbean.cardano.julc.core.PlutusData.integer(0),
                com.bloxbean.cardano.julc.core.PlutusData.integer(height),
                com.bloxbean.cardano.julc.core.PlutusData.integer(root),
                com.bloxbean.cardano.julc.core.PlutusData.integer(
                        settlementContext),
                com.bloxbean.cardano.julc.core.PlutusData.integer(
                        batchCommitment),
                com.bloxbean.cardano.julc.core.PlutusData.integer(
                        withdrawal),
                com.bloxbean.cardano.julc.core.PlutusData.integer(0));
    }

    private static com.bloxbean.cardano.julc.core.PlutusData vaultDatum(
            long nextSequence,
            long lastRootHeight
    ) {
        return com.bloxbean.cardano.julc.core.PlutusData.constr(
                0,
                com.bloxbean.cardano.julc.core.PlutusData.integer(1),
                com.bloxbean.cardano.julc.core.PlutusData.bytes(
                        CHAIN_ID.getBytes(StandardCharsets.UTF_8)),
                com.bloxbean.cardano.julc.core.PlutusData.integer(0),
                com.bloxbean.cardano.julc.core.PlutusData.integer(
                        nextSequence),
                com.bloxbean.cardano.julc.core.PlutusData.integer(
                        lastRootHeight),
                com.bloxbean.cardano.julc.core.PlutusData.integer(0));
    }

    private static com.bloxbean.cardano.julc.core.PlutusData
    withdrawRedeemer(long rootHeight) {
        return com.bloxbean.cardano.julc.core.PlutusData.constr(
                0,
                com.bloxbean.cardano.julc.core.PlutusData.integer(0),
                com.bloxbean.cardano.julc.core.PlutusData.bytes(
                        CHAIN_ID.getBytes(StandardCharsets.UTF_8)),
                com.bloxbean.cardano.julc.core.PlutusData.integer(0),
                com.bloxbean.cardano.julc.core.PlutusData.integer(0),
                com.bloxbean.cardano.julc.core.PlutusData.integer(
                        rootHeight),
                com.bloxbean.cardano.julc.core.PlutusData.integer(
                        WITHDRAWAL_LOVELACE));
    }

    private EutxoL2Transaction l2Withdrawal(
            EutxoOutpoint deposited
    ) throws Exception {
        long expiry = 10_000_000L;
        EutxoWithdrawalDatum withdrawalDatum =
                new EutxoWithdrawalDatum(
                        EutxoWithdrawalDatum.ABI_VERSION,
                        CHAIN_ID,
                        0,
                        payout.enterpriseAddress(),
                        HexFormat.of().parseHex("cd".repeat(32)));
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder()
                        .transactionId(deposited.transactionId())
                        .index(deposited.index())
                        .build()))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(operator.enterpriseAddress())
                                .value(Value.fromCoin(
                                        BigInteger.valueOf(
                                                EutxoZkDevnetTestProfile
                                                        .DEPOSIT_LOVELACE
                                                        - WITHDRAWAL_LOVELACE)))
                                .build(),
                        TransactionOutput.builder()
                                .address(payout.enterpriseAddress())
                                .value(Value.fromCoin(
                                        BigInteger.valueOf(
                                                WITHDRAWAL_LOVELACE)))
                                .inlineDatum(PlutusData.deserialize(
                                        withdrawalDatum.encode()))
                                .build()))
                .fee(BigInteger.ZERO)
                .ttl(expiry)
                .networkId(NetworkId.TESTNET)
                .build();
        EutxoZkAuthorizationProfile authorizationProfile =
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        EutxoL2Domain domain = new EutxoL2Domain(
                CHAIN_ID,
                NETWORK,
                EutxoProfile.V1.digestHex(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                authorizationProfile.id(),
                authorizationProfile.digestHex(),
                new byte[32],
                expiry);
        BigInteger secret = EutxoZkDevnetTestProfile.L2_SECRET;
        var keypair = EdDSAJubjub.keypairFromSecret(secret);
        EutxoL2Authorization unsigned = new EutxoL2Authorization(
                HexFormat.of().formatHex(paymentKeyHash(operator)),
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

    private static EutxoReceipt waitForReceipt(
            EutxoClient client,
            String transactionId
    ) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            try {
                var receipt = client.transaction(transactionId);
                if (receipt.isPresent()) {
                    return receipt.orElseThrow();
                }
            } catch (RuntimeException notReady) {
                // The app chain may still be finishing its first block.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
                "L2 transaction was not finalized: " + transactionId);
    }

    private static EutxoDepositRecord waitForDeposit(
            EutxoClient client,
            EutxoOutpoint acceptedOutpoint
    ) throws Exception {
        for (int attempt = 0; attempt < 240; attempt++) {
            try {
                var deposit = client.depositSnapshot(
                        acceptedOutpoint).value();
                if (deposit.isPresent()) {
                    return deposit.orElseThrow();
                }
            } catch (RuntimeException notReady) {
                // Stability and app-chain finalization are asynchronous.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
                "stable L1 deposit was not credited: "
                        + acceptedOutpoint);
    }

    private static EutxoWithdrawalRecord waitForWithdrawal(
            EutxoClient client,
            String claimId
    ) throws Exception {
        for (int attempt = 0; attempt < 240; attempt++) {
            try {
                var withdrawal = client.withdrawalSnapshot(
                        claimId).value();
                if (withdrawal.isPresent()
                        && withdrawal.orElseThrow().status()
                        == EutxoWithdrawalRecord.Status.CONFIRMED) {
                    return withdrawal.orElseThrow();
                }
            } catch (RuntimeException notReady) {
                // Stability and app-chain finalization are asynchronous.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
                "stable L1 withdrawal was not reconciled: " + claimId);
    }

    private static EutxoValidityTransition waitForTransition(
            EutxoClient client,
            long appHeight,
            int ordinal
    ) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                var transition = client.finalizedValidityTransition(
                        appHeight, ordinal).value();
                if (transition.isPresent()) {
                    return transition.orElseThrow();
                }
            } catch (RuntimeException notReady) {
                // The root-fixed query becomes available after finalization.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
                "finalized validity transition was not queryable");
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

    private static final class CompilerHarness extends ContractTest {
        private static final Path ONCHAIN_SOURCE_ROOT = Path.of(
                System.getProperty("user.dir"),
                "..",
                "appchain",
                "extensions",
                "eutxo-zk",
                "appchain-eutxo-zk-onchain",
                "src",
                "main",
                "java").normalize();
        private static final Path BRIDGE_SOURCE_ROOT = Path.of(
                System.getProperty("user.dir"),
                "..",
                "appchain",
                "extensions",
                "eutxo",
                "appchain-eutxo-bridge-onchain",
                "src",
                "main",
                "java").normalize();

        private Program compile(Class<?> validator) {
            return compileValidator(
                    validator, ONCHAIN_SOURCE_ROOT).program();
        }

        private Program compileBridge(Class<?> validator) {
            return compileValidator(
                    validator, BRIDGE_SOURCE_ROOT).program();
        }
    }
}
