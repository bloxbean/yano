package com.bloxbean.cardano.yano.compat.ccl;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proves that BLS12-381 Plutus builtins work on the node under test — the case a
 * GraalVM native image gets wrong when the {@code blst} JNI library is not registered
 * as an image resource.
 *
 * <p>Flow: fund and lock both cases first, then price each spend through
 * {@code /utils/txs/evaluate}, rebuild with the ExUnits the node returned, and submit.
 * Evaluation is where a missing blst library surfaces; submission proves the returned
 * budget is actually usable. Setup runs before any evaluation deliberately — a node
 * that cannot load blst can wedge its own faucet after the failure (observed on
 * 0.1.0-pre8), and that must not be mistaken for a funding problem.</p>
 *
 * <p>Two cases run. The happy path doubles the G1 generator and expects 2G. The
 * negative control expects 3G, so the script must fail validation — that is what
 * separates "the builtins really ran" from "the validator returned true without doing
 * the work". A node with no blst fails both, and fails them differently: a JNI/linkage
 * error (or a bare HTTP 500) rather than a script validation failure.</p>
 *
 * <p>Run: {@code ./gradlew blsProbe -Dyano.url=http://localhost:7070/api/v1}.
 * Exit code is 0 only when both cases behave.</p>
 */
public final class BlsProbe {

    /**
     * {@code BlsDoublingValidator} compiled by julc 0.1.0-pre16 — source lives in
     * {@code compat-tests/contracts/bls/}, so the smoke test itself needs no julc
     * toolchain. Rebuild with {@code cd compat-tests/contracts/bls && ../../ccl/gradlew build}
     * and copy {@code cborHex} from
     * {@code build/classes/java/main/META-INF/plutus/BlsDoublingValidator.plutus.json}.
     */
    static final String SCRIPT_CBOR_HEX =
            "58635861010100232323232323232533357340022930b191919bb900137766eb8d5d09aab9e375400666ed80040"
                    + "04ddd9bae357426aae78dd50029aba135573c6ea8004d5d09aba200135573c6ea8004d5d09aba2357440"
                    + "046ae84d5d10009aab9e3754003";

    static final PlutusV3Script SCRIPT = PlutusV3Script.builder().cborHex(SCRIPT_CBOR_HEX).build();

    /** Compressed BLS12-381 G1 generator. */
    static final String G1 =
            "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb";
    /** Compressed 2G — what the validator must see when it doubles the generator. */
    static final String TWO_G =
            "a572cbea904d67468808c8eb50a9450c9721db309128012543902d0ac358a62ae28f75bb8f1c7c42c39a8c5529bf0f4e";
    /** Compressed 3G — the wrong answer, used by the negative control. */
    static final String THREE_G =
            "89ece308f9d1f0131765212deca99697b112d61f9be9a5f1f3780a51335b3ff981747a0b2ca2179b96d2c0c9024e5224";

    /** Generous placeholder budget, used only to build the draft handed to the evaluator. */
    private static final long PLACEHOLDER_MEM = 2_000_000L;
    private static final long PLACEHOLDER_STEPS = 800_000_000L;

    public static void main(String[] args) throws Exception {
        YanoClient client = new YanoClient(System.getProperty("yano.url", "http://localhost:7070/api/v1"));
        if (!client.waitUntilReady(Duration.ofSeconds(120))) {
            System.out.println("node not ready at " + client.apiBase());
            System.exit(2);
        }
        BackendService backend = new BFBackendService(client.apiBase() + "/", "yano-devnet");
        QuickTxBuilder qtx = new QuickTxBuilder(backend);

        String scriptAddr = AddressProvider.getEntAddress(SCRIPT, Networks.testnet()).toBech32();
        System.out.println("node           : " + client.apiBase());
        System.out.println("script hash    : " + HexFormat.of().formatHex(SCRIPT.getScriptHash()));
        System.out.println("script address : " + scriptAddr);

        System.out.println("\n--- setup: lock one UTxO per case (before any evaluation) ---");
        Locked happyLock = lock(client, qtx, scriptAddr, TWO_G, "case1-expects-2G");
        Locked negativeLock = lock(client, qtx, scriptAddr, THREE_G, "case2-expects-3G");

        System.out.println("\n=== case 1: doubling the generator must verify (datum 2G, redeemer G) ===");
        Outcome happy = spend(client, qtx, happyLock, G1);
        System.out.println("case 1 result  : " + happy);

        System.out.println("\n=== case 2 (negative control): wrong expected point must fail (datum 3G, redeemer G) ===");
        Outcome negative = spend(client, qtx, negativeLock, G1);
        System.out.println("case 2 result  : " + negative);

        boolean happyOk = happy.evaluated && happy.submitted && happy.confirmed;
        // The control must be rejected, and rejected for the right reason: a validation
        // failure — not an unavailable BLS library, and not an unmapped server error.
        boolean negativeOk = !negative.evaluated && !blsUnavailable(negative.detail)
                && !unmappedServerError(negative.detail);

        System.out.println("\n---------------- summary ----------------");
        System.out.println("BLS builtins evaluated : "
                + (happy.evaluated ? "PASS " + happy.exUnits : "FAIL " + happy.detail));
        System.out.println("BLS spend submitted    : "
                + (happy.submitted ? "PASS " + happy.txHash : "FAIL " + happy.detail));
        System.out.println("BLS spend confirmed    : " + (happy.confirmed ? "PASS" : "FAIL"));
        System.out.println("negative control       : "
                + (negativeOk ? "PASS rejected: " + trim(negative.detail) : "FAIL " + trim(negative.detail)));
        if (blsUnavailable(happy.detail) || blsUnavailable(negative.detail)
                || unmappedServerError(happy.detail) || unmappedServerError(negative.detail)) {
            System.out.println("\nDIAGNOSIS: this node could not evaluate a BLS12-381 script. In a native image");
            System.out.println("that means the blst JNI library was not registered as an image resource — check");
            System.out.println("the node log for 'supranational.blst' / 'blstJNI' initialisation failures, and");
            System.out.println("see META-INF/native-image/.../resource-config.json in the scalus bridge module.");
        }
        boolean pass = happyOk && negativeOk;
        System.out.println("\nRESULT: " + (pass ? "PASS" : "FAIL"));
        System.exit(pass ? 0 : 1);
    }

    /** A script UTxO waiting to be spent, plus the account that will spend it. */
    record Locked(String label, Utxo utxo, Account spender, String setupError) {}

    /** Everything observed for one evaluate-and-submit round. */
    record Outcome(boolean evaluated, boolean submitted, boolean confirmed,
                   String exUnits, String txHash, String detail) {
        @Override
        public String toString() {
            return (evaluated ? "evaluated " + exUnits : "not evaluated")
                    + (submitted ? ", submitted " + txHash : "")
                    + (confirmed ? ", confirmed" : "")
                    + (detail == null || detail.isBlank() ? "" : ", detail=" + trim(detail));
        }
    }

    /** Fund a fresh account, lock 100 ADA at the script with the expected point inline. */
    private static Locked lock(YanoClient client, QuickTxBuilder qtx, String scriptAddr,
                               String expectedPoint, String label) {
        try {
            Account locker = new Account(Networks.testnet());
            Utxo funding = PlutusProbe.fund(client, locker.baseAddress(), 2000);
            Transaction lock = qtx.compose(new Tx().from(locker.baseAddress()).collectFrom(List.of(funding))
                            .payToContract(scriptAddr, Amount.ada(100), pointData(expectedPoint)))
                    .feePayer(locker.baseAddress())
                    .withSigner(SignerProviders.signerFrom(locker))
                    .buildAndSign();
            String lockHash = TransactionUtil.getTxHash(lock.serialize());
            var submit = client.submit(lock.serialize());
            System.out.println(label + " lock  : " + submit.status() + " " + submit.category());
            if (!submit.accepted()) {
                return new Locked(label, null, null, "lock rejected: " + trim(submit.body()));
            }
            Utxo locked = PlutusProbe.findOutput(lock, lockHash, scriptAddr, 100_000_000L);
            // The evaluate endpoint resolves inputs from canonical state only, so the
            // spend cannot be priced until this lock is confirmed.
            System.out.println(label + " await : " + PlutusProbe.awaitCanonical(client, lockHash, 0, 30_000));

            Account spender = new Account(Networks.testnet());
            PlutusProbe.fund(client, spender.baseAddress(), 500);
            return new Locked(label, locked, spender, null);
        } catch (Exception e) {
            System.out.println(label + " setup FAILED: " + e);
            return new Locked(label, null, null, "setup failed: " + e);
        }
    }

    /** Price the spend through the node, then submit it with the node's own ExUnits. */
    private static Outcome spend(YanoClient client, QuickTxBuilder qtx, Locked locked, String redeemerPoint) {
        if (locked.setupError != null) {
            return new Outcome(false, false, false, null, null, locked.setupError);
        }
        try {
            Transaction draft = spendTx(qtx, locked, redeemerPoint, PLACEHOLDER_MEM, PLACEHOLDER_STEPS);

            YanoClient.EvaluateResult evaluation = client.evaluate(draft.serialize());
            if (!evaluation.evaluated()) {
                System.out.println("evaluate       : FAILED " + trim(evaluation.failure()));
                return new Outcome(false, false, false, null, null, evaluation.failure());
            }
            Map.Entry<String, long[]> priced = evaluation.exUnits().entrySet().iterator().next();
            long mem = priced.getValue()[0];
            long steps = priced.getValue()[1];
            String exUnits = priced.getKey() + " mem=" + mem + " steps=" + steps;
            System.out.println("evaluate       : OK " + exUnits);

            // Re-build with the node's own numbers: if they are wrong, submission fails.
            Transaction spend = spendTx(qtx, locked, redeemerPoint, mem, steps);
            String spendHash = TransactionUtil.getTxHash(spend.serialize());
            var submit = client.submit(spend.serialize());
            System.out.println("spend submit   : " + submit.status() + " " + submit.category());
            if (!submit.accepted()) {
                return new Outcome(true, false, false, exUnits, null, PlutusProbe.firstError(submit.body()));
            }
            String confirmation = PlutusProbe.awaitCanonical(client, spendHash, 0, 30_000);
            System.out.println("spend confirmed: " + confirmation);
            return new Outcome(true, true, !confirmation.equals("TIMEOUT"), exUnits, spendHash, confirmation);
        } catch (Exception e) {
            System.out.println("spend          : FAILED " + e);
            return new Outcome(false, false, false, null, null, String.valueOf(e));
        }
    }

    private static Transaction spendTx(QuickTxBuilder qtx, Locked locked, String redeemerPoint,
                                       long mem, long steps) throws Exception {
        ScriptTx script = new ScriptTx()
                .collectFrom(locked.utxo, pointData(redeemerPoint))
                .attachSpendingValidator(SCRIPT)
                .payToAddress(locked.spender.baseAddress(), Amount.ada(95));
        return qtx.compose(script)
                .feePayer(locked.spender.baseAddress())
                .collateralPayer(locked.spender.baseAddress())
                .withSigner(SignerProviders.signerFrom(locked.spender))
                .withTxEvaluator(new FixedExUnitEvaluator(mem, steps))
                .buildAndSign();
    }

    /** {@code Constr 0 [bytes]} — the shape julc generates for a single-field record. */
    static PlutusData pointData(String compressedHex) {
        return ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(BytesPlutusData.of(HexFormat.of().parseHex(compressedHex))))
                .build();
    }

    /** True when a failure message points at the blst JNI library rather than at the script. */
    static boolean blsUnavailable(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("blst") || m.contains("bls12_381builtinsunavailable")
                || m.contains("bls12-381 plutus builtins are unavailable");
    }

    /** A bare 500 carries no reason — older nodes report an unmapped blst failure this way. */
    static boolean unmappedServerError(String message) {
        return message != null && message.startsWith("HTTP 500");
    }

    static String trim(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() <= 220 ? flat : flat.substring(0, 220) + "…";
    }
}
