package org.yanoproject.tx.gate;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.function.TxSigner;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV1Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigInteger;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 7c native-parity workload: a deterministic sequence of transactions submitted over REST to a running
 * devnet producer (the JVM jar or the native binary, started by {@code qa/harness/ledger-rules-native-parity.sh}).
 * Every step writes one observation line (the verdict and, for a rejection, the node's rule names and messages); the
 * harness runs the same workload against a JVM node and a native node with the same genesis and configuration, and
 * the two observation files must be identical.
 *
 * <p>Coverage: payments and chains admitted while their parents are pending (ledger-state mempool, block
 * selection), a phase-1 failure per rule family ({@code MEMPOOL}, {@code UTXO}, {@code UTXOW}, {@code DELEG},
 * {@code POOL}, {@code GOVCERT}, {@code GOV}, {@code LEDGER}, {@code CERTS}), certificates and governance, PlutusV1,
 * V2 (also through a reference script) and V3 spends evaluated by the node (the engine's phase-2 evaluator) and an
 * always-failing V3 script, a V3 script on the crypto builtins backed by native libraries (BLS12-381 through blst,
 * secp256k1), the node's evaluate endpoint, a rollback that removes a pending child's parent (mempool rebuild), and
 * transactions after an epoch boundary (ticked views, {@code currentTreasuryValue}).</p>
 *
 * <p>Runs only with {@code -Dyano.parity.remote-url=http://host:port}; {@code -Dyano.parity.report=<file>} writes
 * the observations. Every transaction is paid from the devnet genesis funds and built offline, so for the same
 * genesis the transactions are byte-identical between runs.</p>
 */
@EnabledIfSystemProperty(named = "yano.parity.remote-url", matches = ".+")
class NativeParityWorkloadTest {

    private static final Anchor ANCHOR = new Anchor("https://example.com/proposal.json", new byte[32]);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long EPOCH_LENGTH = Long.getLong("yano.parity.epoch-length", 100);
    /**
     * Whether certificate and governance children are submitted while their parents are pending. The legacy
     * validator ({@code engine: scalus}, no engines) does not see pending certificates, so the harness turns this off
     * for it and each such transaction is confirmed before the next.
     */
    private static final boolean CHAIN_CERTIFICATES = Boolean.parseBoolean(
            System.getProperty("yano.parity.chain-certificates", "true"));
    private static final ExUnits BUDGET = new ExUnits(BigInteger.valueOf(500_000), BigInteger.valueOf(1_000_000_000));
    private static final PlutusData DATUM = BigIntPlutusData.of(42);
    private static final PlutusData REDEEMER = BigIntPlutusData.of(7);

    /** An always-succeeding PlutusV1 validator (three-argument lambda returning unit). */
    private static final PlutusV1Script V1 = PlutusV1Script.builder().type("PlutusScriptV1")
            .cborHex("4e4d01000033222220051200120011").build();
    /** An always-succeeding PlutusV2 validator. */
    private static final PlutusV2Script V2 = PlutusV2Script.builder().type("PlutusScriptV2")
            .cborHex("49480100002221200101").build();
    /** {@code (program 1.1.0 (lam ctx (con unit ())))}. */
    private static final PlutusV3Script V3 = PlutusV3Script.builder().type("PlutusScriptV3")
            .cborHex("46450101002499").build();
    /** {@code (program 1.1.0 (error))}. */
    private static final PlutusV3Script V3_FAILS = PlutusV3Script.builder().type("PlutusScriptV3")
            .cborHex("454401010061").build();
    /**
     * A V3 validator that ignores its context and succeeds only when every crypto builtin backed by a native library
     * returns the value of the Plutus conformance vectors: {@code bls12_381_G1_compress} and {@code _G2_compress} of
     * {@code bls12_381_G1_hashToGroup} and {@code _G2_hashToGroup} of {@code #8e} with DST {@code #0a},
     * {@code bls12_381_G1_equal} with {@code bls12_381_G1_uncompress}, {@code verifyEcdsaSecp256k1Signature} (test
     * vector 01) and {@code verifySchnorrSecp256k1Signature} (BIP-340 test vector 0). About 420M CPU steps. Decode with
     * {@code julc uplc decode}.
     */
    private static final PlutusV3Script V3_CRYPTO = PlutusV3Script.builder().type("PlutusScriptV3")
            .cborHex("59023d59023a010100253335734666ae68cdc79bba33778911018e004881010a00488130a45ddef02cdd86039be4b0"
                    + "a863cba70ea903194ea0489ce619c6276175839d62eea72b095d6566067f4a44b85614f19900333573466e3cde099b"
                    + "c34881018e004881010a00488160abdb064dbaa986d9609796d7a80ef07f719f99fa5d9876e01f9298793d4c7e7ba9"
                    + "b2c55da6896f90693ad76a093d280118a4c24df9a387eaf85b15927365a110fe5256f53ddf8bef4069fe761d8215d4"
                    + "a73ec980f1a801dbaba25146b6ca7e0700333573466ee4cdde2441018e004881010a003776910130a45ddef02cdd86"
                    + "039be4b0a863cba70ea903194ea0489ce619c6276175839d62eea72b095d6566067f4a44b85614f199003335734666"
                    + "ed122121032e433589dce61863199171f4d1e3fa946a5832621fcd29559940a0950f96fb6f00488120e3b0c44298fc"
                    + "1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855004881404941155e2303988a1be97a021fbaf9fe60"
                    + "64d05ea694bc5e89328f297154e5c63a2f3e7b5f509294a4c2e22feb697a16b792fabfebe9d0f38403b1c929836b5a"
                    + "0033376a910120f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f90048812000000000"
                    + "0000000000000000000000000000000000000000000000000000000000488140e907831f80848d1069a5371b402410"
                    + "364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d3105"
                    + "36c0004a0941282501498581")
            .build();

    private final RemoteYanoNode node = new RemoteYanoNode(System.getProperty("yano.parity.remote-url"));
    private final List<String> observations = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private final Account payer = account(0);
    private final Account payer2 = account(1);
    private final GateWallet wallet = new GateWallet(Set.of(payer.baseAddress()));
    private final GateWallet wallet2 = new GateWallet(Set.of(payer2.baseAddress()));
    private final Map<String, PlutusScript> scripts = new HashMap<>();
    private ProtocolParams params;

    @Test
    void workloadObservationsForTheParityDiff() throws Exception {
        try {
            run();
        } finally {
            String report = System.getProperty("yano.parity.report");
            if (report != null && !report.isBlank()) {
                List<String> lines = new ArrayList<>(observations);
                problems.forEach(p -> lines.add("PROBLEM " + p));
                Files.write(Path.of(report), lines);
            }
        }
        assertThat(problems).isEmpty();
    }

    private void run() throws Exception {
        await("the first blocks", 60_000, () -> node.tipBlock() >= 2);
        params = node.protocolParams();
        observe("env protocol-version " + params.getProtocolMajorVer() + "." + params.getProtocolMinorVer());
        wallet.add(node.genesisUtxo(payer.baseAddress()));
        wallet2.add(node.genesisUtxo(payer2.baseAddress()));
        for (PlutusScript script : List.of(V1, V2, V3, V3_FAILS, V3_CRYPTO)) {
            scripts.put(HexUtil.encodeHexString(script.getScriptHash()), script);
        }

        // ---------------------------------------------------------------- payments and mempool chains
        Tx split = new Tx().from(payer.baseAddress());
        for (int i = 0; i < 4; i++) {
            split.payToAddress(payer.baseAddress(), Amount.ada(1_000));
        }
        String splitHash = accept("pay.split", build(wallet, split, signer()));
        awaitConfirmed("pay.split", List.of(splitHash));
        List<String> chain = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            chain.add(accept("pay.chain-" + i, build(wallet, new Tx().payToAddress(payer2.baseAddress(),
                    Amount.ada(10)).from(payer.baseAddress()), signer())));
        }
        awaitConfirmed("pay.chain", chain);

        // ---------------------------------------------------------------- phase-1 failures, one per family
        rejectMutated("reject.bad-inputs", tx -> tx.getBody().getInputs().add(fakeInput(1)));
        rejectMutated("reject.all-inputs-spent", tx -> {
            tx.getBody().getInputs().clear();
            tx.getBody().getInputs().add(fakeInput(2));
        });
        rejectMutated("reject.value-not-conserved", tx -> addCoin(tx.getBody().getOutputs().get(0), 1_000_000));
        rejectMutated("reject.fee-too-small", tx -> {
            tx.getBody().setFee(tx.getBody().getFee().subtract(BigInteger.valueOf(50_000)));
            addCoin(changeOutput(tx), 50_000);
        });
        rejectMutated("reject.expired", tx -> tx.getBody().setTtl(1));
        rejectMutated("reject.wrong-network", tx -> tx.getBody().getOutputs().get(0).setAddress(
                Account.createFromMnemonic(Networks.mainnet(), LedgerRulesDevnetMatrix.DEVNET_MNEMONIC, 1, 0)
                        .baseAddress()));
        rejectMutated("reject.output-too-small", tx -> {
            TransactionOutput output = tx.getBody().getOutputs().get(0);
            BigInteger coin = output.getValue().getCoin();
            output.getValue().setCoin(BigInteger.valueOf(100_000));
            addCoin(changeOutput(tx), coin.longValueExact() - 100_000);
        });
        rejectRaw("reject.missing-vkey", unsigned(template()));
        Transaction staleSignature = sign(template());
        staleSignature.getBody().setTtl(1_000_000_000L);
        rejectRaw("reject.invalid-signature", serialize(staleSignature));
        // Two transactions spending the same inputs: the second is rejected by the MEMPOOL rule.
        GateTxFactory.Built first = build(wallet, new Tx().payToAddress(payer2.baseAddress(), Amount.ada(5))
                .from(payer.baseAddress()), signer());
        GateTxFactory.Built conflicting = build(wallet, new Tx().payToAddress(payer2.baseAddress(), Amount.ada(6))
                .from(payer.baseAddress()), signer());
        String firstHash = accept("mempool.first-spend", first);
        rejectRaw("reject.double-spend", conflicting.cbor());
        awaitConfirmed("mempool.first-spend", List.of(firstHash));

        // ---------------------------------------------------------------- certificates and governance
        Account staker = account(101);
        Account drep = account(102);
        Account unregistered = account(103);
        Account poolKey = account(104);
        List<String> gov = new ArrayList<>();
        gov.add(certificate("cert.stake-register", build(wallet, new Tx().registerStakeAddress(staker.baseAddress())
                .from(payer.baseAddress()), signer())));
        gov.add(certificate("cert.stake-delegate", build(wallet, new Tx().delegateTo(staker.baseAddress(),
                LedgerRulesDevnetMatrix.DEVNET_POOL).from(payer.baseAddress()), signer(stake(staker)))));
        gov.add(certificate("cert.drep-register", build(wallet, new Tx().registerDRep(drep.drepCredential(), ANCHOR)
                .from(payer.baseAddress()), signer(SignerProviders.drepKeySignerFrom(drep)))));
        gov.add(certificate("cert.vote-delegate", build(wallet, new Tx().delegateVotingPowerTo(staker.baseAddress(),
                DRep.addrKeyHash(drep.drepCredential().getBytes())).from(payer.baseAddress()),
                signer(stake(staker)))));
        String proposal = certificate("gov.proposal", build(wallet, new Tx().createProposal(new InfoAction(),
                staker.stakeAddress(), ANCHOR).from(payer.baseAddress()), signer()));
        gov.add(proposal);
        // Observed, not required: which DRep votes are allowed depends on the protocol version (Conway bootstrap).
        Optional<String> vote = submitOptional("gov.vote", build(wallet, new Tx().createVote(drepVoter(drep),
                new GovActionId(proposal, 0), Vote.YES).from(payer.baseAddress()),
                signer(SignerProviders.drepKeySignerFrom(drep))));
        vote.ifPresent(gov::add);
        awaitConfirmed("gov", gov);

        rejectBuilt("reject.deleg-unregistered", new Tx().delegateTo(unregistered.baseAddress(),
                LedgerRulesDevnetMatrix.DEVNET_POOL).from(payer.baseAddress()), signer(stake(unregistered)));
        rejectBuilt("reject.stake-already-registered", new Tx().registerStakeAddress(staker.baseAddress())
                .from(payer.baseAddress()), signer());
        rejectBuilt("reject.drep-already-registered", new Tx().registerDRep(drep.drepCredential(), ANCHOR)
                .from(payer.baseAddress()), signer(SignerProviders.drepKeySignerFrom(drep)));
        rejectBuilt("reject.vote-missing-action", new Tx().createVote(drepVoter(drep),
                new GovActionId(HexUtil.encodeHexString(new byte[32]), 3), Vote.NO).from(payer.baseAddress()),
                signer(SignerProviders.drepKeySignerFrom(drep)));
        rejectBuilt("reject.withdrawal-wrong-amount", new Tx().withdraw(staker.stakeAddress(), BigInteger.ONE)
                .from(payer.baseAddress()), signer(stake(staker)));
        byte[] poolKeyHash = Blake2bUtil.blake2bHash224(poolKey.stakeHdKeyPair().getPublicKey().getKeyData());
        rejectBuilt("reject.pool-retire-unregistered", new Tx().retirePool(Bech32.encode(poolKeyHash, "pool"),
                (int) (epochOf(node.tipSlot()) + 1)).from(payer.baseAddress()),
                signer(SignerProviders.signerFrom(poolKey.stakeHdKeyPair())));
        rejectTreasury("reject.treasury-mismatch", BigInteger.valueOf(123_456));

        // ---------------------------------------------------------------- Plutus V1, V2, V3 (engine phase 2)
        String v1Address = scriptAddress(V1);
        String v2Address = scriptAddress(V2);
        String v3Address = scriptAddress(V3);
        String failAddress = scriptAddress(V3_FAILS);
        String cryptoAddress = scriptAddress(V3_CRYPTO);
        String datumHash = DATUM.getDatumHash();
        Tx lock = new Tx()
                .payToContract(v1Address, Amount.ada(20), datumHash)
                .payToContract(v2Address, Amount.ada(20), DATUM)
                .payToContract(v2Address, Amount.ada(21), DATUM)
                .payToContract(v3Address, Amount.ada(20), DATUM)
                .payToContract(failAddress, Amount.ada(20), DATUM)
                .payToAddress(payer2.baseAddress(), Amount.ada(30), V2)
                .payToContract(cryptoAddress, Amount.ada(20), DATUM)
                .from(payer.baseAddress());
        GateTxFactory.Built locked = build(wallet, lock, signer());
        String lockHash = accept("plutus.lock", locked);
        awaitConfirmed("plutus.lock", List.of(lockHash));
        List<TransactionOutput> outs = locked.transaction().getBody().getOutputs();
        Utxo v1Utxo = utxoOf(lockHash, 0, outs.get(0));
        Utxo v2Utxo = utxoOf(lockHash, 1, outs.get(1));
        Utxo v2RefSpendUtxo = utxoOf(lockHash, 2, outs.get(2));
        Utxo v3Utxo = utxoOf(lockHash, 3, outs.get(3));
        Utxo failUtxo = utxoOf(lockHash, 4, outs.get(4));
        Utxo refScriptUtxo = utxoOf(lockHash, 5, outs.get(5));
        Utxo cryptoUtxo = utxoOf(lockHash, 6, outs.get(6));

        GateTxFactory.Built v3Spend = buildScript(new ScriptTx().collectFrom(v3Utxo, REDEEMER)
                .payToAddress(payer.baseAddress(), Amount.ada(19)).attachSpendingValidator(V3));
        observe("plutus.v3-evaluate " + evaluate(v3Spend.cbor()));
        List<String> plutus = new ArrayList<>();
        plutus.add(accept("plutus.v3-spend", v3Spend));
        plutus.add(accept("plutus.v1-spend", buildScript(new ScriptTx().collectFrom(v1Utxo, REDEEMER, DATUM)
                .payToAddress(payer.baseAddress(), Amount.ada(19)).attachSpendingValidator(V1))));
        plutus.add(accept("plutus.v2-spend", buildScript(new ScriptTx().collectFrom(v2Utxo, REDEEMER)
                .payToAddress(payer.baseAddress(), Amount.ada(19)).attachSpendingValidator(V2))));
        plutus.add(accept("plutus.v2-reference-script-spend", buildScript(new ScriptTx()
                .readFrom(refScriptUtxo).collectFrom(v2RefSpendUtxo, REDEEMER)
                .payToAddress(payer.baseAddress(), Amount.ada(20)), V2)));
        GateTxFactory.Built cryptoSpend = buildScript(new ScriptTx().collectFrom(cryptoUtxo, REDEEMER)
                .payToAddress(payer.baseAddress(), Amount.ada(19)).attachSpendingValidator(V3_CRYPTO));
        observe("plutus.v3-crypto-evaluate " + evaluate(cryptoSpend.cbor()));
        plutus.add(accept("plutus.v3-crypto-spend", cryptoSpend));
        rejectRaw("reject.plutus-v3-fails", buildScript(new ScriptTx().collectFrom(failUtxo, REDEEMER)
                .payToAddress(payer.baseAddress(), Amount.ada(19)).attachSpendingValidator(V3_FAILS)).cbor());
        awaitConfirmed("plutus", plutus);

        // ---------------------------------------------------------------- rollback: the rebuild drops the child
        var beforeRollback = wallet2.snapshot();
        String parent = accept("rollback.parent", build(wallet2, new Tx().payToAddress(payer2.baseAddress(),
                Amount.ada(50)).from(payer2.baseAddress()), signer(payer2)));
        awaitConfirmed("rollback.parent", List.of(parent));
        long parentSlot = node.txSlot(parent);
        long tip = node.tipBlock();
        await("a fresh block", 30_000, () -> node.tipBlock() > tip);
        String child = accept("rollback.child", build(wallet2, new Tx().payToAddress(payer.baseAddress(),
                Amount.ada(3)).from(payer2.baseAddress()), signer(payer2)));
        String independent = accept("rollback.independent", build(wallet, new Tx().payToAddress(
                payer.baseAddress(), Amount.ada(4)).from(payer.baseAddress()), signer()));
        observe("rollback.child-before " + node.txStatus(child));
        JsonNode rolledBack = node.post("/api/v1/devnet/rollback", "{\"slot\": " + (parentSlot - 1) + "}");
        observe("rollback.response-ok " + rolledBack.has("slot"));
        RemoteYanoNode.poll(20_000, () -> !"pending".equals(node.txStatus(child)));
        observe("rollback.child-after " + node.txStatus(child));
        awaitConfirmed("rollback.independent", List.of(independent));
        observe("rollback.parent-after " + node.txStatus(parent));
        wallet2.restore(beforeRollback);

        // ---------------------------------------------------------------- after an epoch boundary
        long nextEpoch = epochOf(node.tipSlot()) + 1;
        await("epoch " + nextEpoch, EPOCH_LENGTH * 2_000 + 60_000, () -> epochOf(node.tipSlot()) >= nextEpoch);
        long epoch = epochOf(node.tipSlot());
        observe("epoch.crossed " + (epoch >= nextEpoch));
        List<String> afterBoundary = new ArrayList<>();
        afterBoundary.add(accept("epoch.payment", build(wallet, new Tx().payToAddress(payer2.baseAddress(),
                Amount.ada(2)).from(payer.baseAddress()), signer())));
        BigInteger treasury = treasuryOrNull(epoch);
        if (treasury != null) {
            afterBoundary.add(accept("epoch.treasury-value", factory().build(wallet,
                    new Tx().payToAddress(payer.baseAddress(), Amount.ada(2)).from(payer.baseAddress()), signer(),
                    (ctx, txn) -> txn.getBody().setCurrentTreasuryValue(treasury))));
        } else {
            observe("epoch.treasury-value unavailable");
        }
        afterBoundary.add(accept("epoch.withdraw-zero", build(wallet, new Tx().withdraw(staker.stakeAddress(),
                BigInteger.ZERO).from(payer.baseAddress()), signer(stake(staker)))));
        awaitConfirmed("epoch", afterBoundary);
        observe("done");
    }

    // ------------------------------------------------------------------ steps

    private String accept(String label, GateTxFactory.Built built) throws Exception {
        Verdict verdict = submit(built.cbor());
        observe(label + " " + verdict.describe() + " " + built.hash());
        if (!verdict.accepted()) {
            problems.add(label + " was rejected: " + verdict.describe());
            throw new AssertionError(label + " rejected: " + verdict.describe());
        }
        built.commit();
        return built.hash();
    }

    /** Accepts a certificate or governance transaction; without chaining, waits for its confirmation. */
    private String certificate(String label, GateTxFactory.Built built) throws Exception {
        String hash = accept(label, built);
        if (!CHAIN_CERTIFICATES) {
            awaitConfirmed(label, List.of(hash));
        }
        return hash;
    }

    private Optional<String> submitOptional(String label, GateTxFactory.Built built) throws Exception {
        Verdict verdict = submit(built.cbor());
        observe(label + " " + verdict.describe() + " " + built.hash());
        if (verdict.accepted()) {
            built.commit();
            return Optional.of(built.hash());
        }
        return Optional.empty();
    }

    private boolean rejectRaw(String label, byte[] cbor) throws Exception {
        Verdict verdict = submit(cbor);
        observe(label + " " + verdict.describe() + " " + TransactionUtil.getTxHash(cbor));
        if (verdict.accepted()) {
            problems.add(label + " was accepted");
        }
        return verdict.accepted();
    }

    /**
     * A well-formed transaction that must be rejected. If a validator admits it anyway (the legacy path has no GOV
     * rules), it is committed to the wallet so later transactions do not conflict with it in the mempool.
     */
    private void rejectBuilt(String label, GateTxFactory.Built built) throws Exception {
        if (rejectRaw(label, built.cbor())) {
            built.commit();
        }
    }

    private void rejectBuilt(String label, Tx tx, TxSigner signer) throws Exception {
        rejectBuilt(label, build(wallet, tx, signer));
    }

    private void rejectTreasury(String label, BigInteger treasury) throws Exception {
        rejectBuilt(label, factory().build(wallet, new Tx().payToAddress(payer.baseAddress(), Amount.ada(2))
                .from(payer.baseAddress()), signer(), (ctx, txn) -> txn.getBody().setCurrentTreasuryValue(treasury)));
    }

    /** A valid, signed payment (not committed), mutated, re-signed and submitted: it must be rejected. */
    private void rejectMutated(String label, Consumer<Transaction> mutation) throws Exception {
        Transaction tx = template();
        mutation.accept(tx);
        rejectRaw(label, serialize(sign(tx)));
    }

    private Transaction template() throws Exception {
        GateTxFactory.Built built = build(wallet, new Tx().payToAddress(payer2.baseAddress(), Amount.ada(7))
                .from(payer.baseAddress()), signer());
        return Transaction.deserialize(built.cbor());
    }

    private Transaction sign(Transaction tx) {
        tx.getWitnessSet().setVkeyWitnesses(new ArrayList<>());
        return payer.sign(tx);
    }

    private byte[] unsigned(Transaction tx) throws Exception {
        tx.getWitnessSet().setVkeyWitnesses(new ArrayList<>());
        return serialize(tx);
    }

    private static byte[] serialize(Transaction tx) throws Exception {
        return tx.serialize();
    }

    private TransactionOutput changeOutput(Transaction tx) {
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        return outputs.get(outputs.size() - 1);
    }

    private static void addCoin(TransactionOutput output, long lovelace) {
        output.getValue().setCoin(output.getValue().getCoin().add(BigInteger.valueOf(lovelace)));
    }

    private static TransactionInput fakeInput(int index) {
        byte[] hash = new byte[32];
        hash[0] = (byte) 0xfa;
        hash[31] = (byte) index;
        return new TransactionInput(HexUtil.encodeHexString(hash), index);
    }

    // ------------------------------------------------------------------ building

    private GateTxFactory factory() {
        return new GateTxFactory(() -> params);
    }

    private GateTxFactory.Built build(GateWallet from, Tx tx, TxSigner signer) {
        return factory().build(from, tx, signer);
    }

    /**
     * A script spend paid (fee and collateral) by the payer, with a fixed budget per redeemer; {@code referenceScripts}
     * are the scripts its reference inputs carry (for the script integrity hash's language views).
     */
    private GateTxFactory.Built buildScript(ScriptTx scriptTx, PlutusScript... referenceScripts) {
        ProtocolParams current = params;
        ScriptSupplier scriptSupplier = hash -> Optional.ofNullable(scripts.get(hash));
        QuickTxBuilder builder = new QuickTxBuilder(wallet, () -> current, scriptSupplier, null);
        QuickTxBuilder.TxContext context = builder.compose(scriptTx);
        if (referenceScripts.length > 0) {
            context = context.withReferenceScripts(referenceScripts);
        }
        Transaction tx = context
                .feePayer(payer.baseAddress())
                .collateralPayer(payer.baseAddress())
                .withTxEvaluator(fixedBudget())
                .withSigner(signer())
                .buildAndSign();
        return factory().wrap(wallet, tx);
    }

    private static TransactionEvaluator fixedBudget() {
        return new TransactionEvaluator() {
            @Override
            public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
                try {
                    Transaction tx = Transaction.deserialize(cbor);
                    List<EvaluationResult> results = new ArrayList<>();
                    for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
                        results.add(new EvaluationResult(redeemer.getTag(), redeemer.getIndex().intValue(),
                                BUDGET));
                    }
                    return Result.<List<EvaluationResult>>success("fixed budget").withValue(results);
                } catch (Exception e) {
                    return Result.error(e.getMessage());
                }
            }
        };
    }

    private TxSigner signer(TxSigner... more) {
        return signer(payer, more);
    }

    private static TxSigner signer(Account account, TxSigner... more) {
        TxSigner signer = SignerProviders.signerFrom(account);
        for (TxSigner extra : more) {
            signer = signer.andThen(extra);
        }
        return signer;
    }

    private static TxSigner stake(Account account) {
        return SignerProviders.stakeKeySignerFrom(account);
    }

    private static Voter drepVoter(Account account) {
        return new Voter(VoterType.DREP_KEY_HASH, account.drepCredential());
    }

    private static Account account(int index) {
        return Account.createFromMnemonic(Networks.testnet(), LedgerRulesDevnetMatrix.DEVNET_MNEMONIC, index, 0);
    }

    private static String scriptAddress(PlutusScript script) {
        return AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
    }

    private static Utxo utxoOf(String txHash, int index, TransactionOutput output) throws Exception {
        String dataHash = output.getDatumHash() != null ? HexUtil.encodeHexString(output.getDatumHash()) : null;
        String inline = output.getInlineDatum() != null ? output.getInlineDatum().serializeToHex() : null;
        String refScript = null;
        if (output.getScriptRef() != null) {
            refScript = HexUtil.encodeHexString(V2.getScriptHash());
        }
        return new Utxo(txHash, index, output.getAddress(),
                List.of(Amount.lovelace(output.getValue().getCoin())), dataHash, inline, refScript);
    }

    // ------------------------------------------------------------------ node

    /** The node's answer to one submission. */
    private record Verdict(boolean accepted, String detail) {
        String describe() {
            return accepted ? "ACCEPTED" : "REJECTED " + detail;
        }
    }

    /**
     * Submits, retrying after the next block what the node says to retry (503: the mempool is catching up, or the
     * ledger state it needs is unavailable, for example across the Conway bootstrap boundary). That is not a
     * verdict, and when it happens depends on timing only.
     */
    private Verdict submit(byte[] cbor) throws Exception {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = node.submit(cbor);
            if (response.statusCode() == 200) {
                return new Verdict(true, "");
            }
            if (response.statusCode() == 503 && attempt < 20) {
                long tip = node.tipBlock();
                RemoteYanoNode.poll(10_000, () -> node.tipBlock() > tip);
                continue;
            }
            return new Verdict(false, response.statusCode() + " " + rejection(response.body()));
        }
    }

    /** The sorted rule names of a validation failure, then the messages (or the error text). */
    private static String rejection(String body) {
        try {
            JsonNode json = JSON.readTree(body);
            JsonNode errors = json.path("validationErrors");
            if (errors.isArray() && !errors.isEmpty()) {
                TreeSet<String> rules = new TreeSet<>();
                List<String> messages = new ArrayList<>();
                for (JsonNode error : errors) {
                    rules.add(error.path("rule").asText() + "/" + error.path("phase").asText());
                    messages.add(error.path("message").asText());
                }
                return rules + " " + messages;
            }
            return json.path("error").asText(body);
        } catch (Exception e) {
            return body;
        }
    }

    private String evaluate(byte[] cbor) throws Exception {
        HttpResponse<String> response = node.postBytes("/api/v1/utils/txs/evaluate", cbor);
        return response.statusCode() + " " + response.body();
    }

    private static long epochOf(long slot) {
        return slot / EPOCH_LENGTH;
    }

    private BigInteger treasuryOrNull(long epoch) {
        try {
            return node.treasury(epoch);
        } catch (Exception e) {
            return null;
        }
    }

    private void awaitConfirmed(String what, List<String> txHashes) throws InterruptedException {
        await("confirmation of " + what, 60_000, () -> txHashes.stream().allMatch(node::confirmed));
        observe(what + " confirmed " + txHashes.size());
    }

    private void await(String what, long timeoutMillis, BooleanSupplier condition) throws InterruptedException {
        if (!RemoteYanoNode.poll(timeoutMillis, condition)) {
            problems.add("timed out waiting for " + what);
            throw new AssertionError("timed out waiting for " + what);
        }
    }

    /**
     * Masks the parts of an observation that depend on timing rather than on the engine: the current slot in a
     * validity-interval failure, the ticked treasury and the hash of the transaction that carries it, and the hash of
     * the pool retirement (its epoch is the current one plus one).
     */
    static String normalize(String line) {
        String masked = line
                .replaceAll("\\s*\\R\\s*", " ")
                .replaceAll("\\} \\(SlotNo \\d+\\)", "} (SlotNo <current>)")
                .replaceAll("(outside the validity interval .* for slot )\\d+", "$1<current>")
                .replaceAll("(current slot )\\d+( not within)", "$1<current>$2")
                .replaceAll("(current treasury value mismatch: provided \\d+, expected )\\d+", "$1<treasury>")
                .replaceAll("(ConwayTreasuryValueMismatch: Mismatch \\{mismatchSupplied = Coin \\d+, "
                        + "mismatchExpected = Coin )\\d+", "$1<treasury>");
        if (masked.startsWith("epoch.treasury-value ACCEPTED ")) {
            return "epoch.treasury-value ACCEPTED <hash>";
        }
        return masked.startsWith("reject.pool-retire-unregistered ")
                ? masked.replaceAll("[0-9a-f]{64}", "<hash>") : masked;
    }

    private void observe(String raw) {
        String line = normalize(raw);
        observations.add(line);
        System.out.println("PARITY " + line);
    }
}
