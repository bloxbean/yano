package com.bloxbean.cardano.yano.compat.ccl;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.math.BigInteger;
import java.util.List;

/**
 * Establishes which Plutus flows this devnet supports, before those flows are
 * built into the load scenarios.
 *
 * <p>T1 baseline inline validator, T2 baseline reference script, T3 reference
 * script published by a transaction that is still in the mempool (the
 * ADR-NET-009 / ScriptReferenceResolverScope case).</p>
 */
public final class PlutusProbe {

    /** Always-succeeds PlutusV2 validator (datum, redeemer, context). */
    static final String ALWAYS_SUCCEEDS_V2 = "49480100002221200101";

    static final PlutusV2Script SCRIPT = PlutusV2Script.builder().cborHex(ALWAYS_SUCCEEDS_V2).build();

    public static void main(String[] args) throws Exception {
        YanoClient client = new YanoClient(System.getProperty("yano.url", "http://localhost:7070/api/v1"));
        BackendService backend = new BFBackendService(client.apiBase() + "/", "yano-devnet");
        QuickTxBuilder qtx = new QuickTxBuilder(backend);

        String scriptAddr = AddressProvider.getEntAddress(SCRIPT, Networks.testnet()).toBech32();
        System.out.println("script address : " + scriptAddr);

        // ---------- T1: inline validator, parent confirmed ----------
        System.out.println("\n--- T1: lock, await confirmation, spend with inlined validator ---");
        Account p1 = new Account(Networks.testnet());
        Utxo f1 = fund(client, p1.baseAddress(), 2000);
        Transaction lock1 = qtx.compose(new Tx().from(p1.baseAddress()).collectFrom(List.of(f1))
                        .payToContract(scriptAddr, Amount.ada(100), BigIntPlutusData.of(42)))
                .feePayer(p1.baseAddress()).withSigner(SignerProviders.signerFrom(p1)).buildAndSign();
        String lock1Hash = TransactionUtil.getTxHash(lock1.serialize());
        System.out.println("lock submit    : " + client.submit(lock1.serialize()).status());
        Utxo su1 = findOutput(lock1, lock1Hash, scriptAddr, 100_000_000L);
        System.out.println("await canonical: " + awaitCanonical(client, lock1Hash, 0, 20_000));

        Account c1 = new Account(Networks.testnet());
        fund(client, c1.baseAddress(), 200);
        System.out.println("T1 result      : " + trySpend(client, qtx, c1,
                new ScriptTx().collectFrom(su1, BigIntPlutusData.of(1))
                        .attachSpendingValidator(SCRIPT)
                        .payToAddress(p1.baseAddress(), Amount.ada(95)), 0));

        // ---------- T2: reference script, everything confirmed ----------
        System.out.println("\n--- T2: publish ref script + lock, await confirmation, spend via readFrom ---");
        Account p2 = new Account(Networks.testnet());
        Utxo f2 = fund(client, p2.baseAddress(), 2000);
        Transaction pub2 = qtx.compose(new Tx().from(p2.baseAddress()).collectFrom(List.of(f2))
                        .payToAddress(p2.baseAddress(), List.of(Amount.ada(50)), SCRIPT)
                        .payToContract(scriptAddr, Amount.ada(100), BigIntPlutusData.of(7)))
                .feePayer(p2.baseAddress()).withSigner(SignerProviders.signerFrom(p2)).buildAndSign();
        String pub2Hash = TransactionUtil.getTxHash(pub2.serialize());
        System.out.println("publish submit : " + client.submit(pub2.serialize()).status());
        Utxo ref2 = findOutput(pub2, pub2Hash, p2.baseAddress(), 50_000_000L);
        Utxo locked2 = findOutput(pub2, pub2Hash, scriptAddr, 100_000_000L);
        System.out.println("await canonical: " + awaitCanonical(client, pub2Hash, 1, 20_000));

        Account c2 = new Account(Networks.testnet());
        fund(client, c2.baseAddress(), 200);
        System.out.println("T2 result      : " + trySpend(client, qtx, c2,
                new ScriptTx().collectFrom(locked2, BigIntPlutusData.of(1))
                        .readFrom(ref2)
                        .payToAddress(p2.baseAddress(), Amount.ada(95)), 0));

        // ---------- T3: reference script still in the mempool ----------
        System.out.println("\n--- T3: publish ref script + lock, spend IMMEDIATELY (parent unconfirmed) ---");
        Account p3 = new Account(Networks.testnet());
        Utxo f3 = fund(client, p3.baseAddress(), 2000);
        Transaction pub3 = qtx.compose(new Tx().from(p3.baseAddress()).collectFrom(List.of(f3))
                        .payToAddress(p3.baseAddress(), List.of(Amount.ada(50)), SCRIPT)
                        .payToContract(scriptAddr, Amount.ada(100), BigIntPlutusData.of(7)))
                .feePayer(p3.baseAddress()).withSigner(SignerProviders.signerFrom(p3)).buildAndSign();
        String pub3Hash = TransactionUtil.getTxHash(pub3.serialize());
        System.out.println("publish submit : " + client.submit(pub3.serialize()).status() + " " + pub3Hash);
        Utxo ref3 = findOutput(pub3, pub3Hash, p3.baseAddress(), 50_000_000L);
        Utxo locked3 = findOutput(pub3, pub3Hash, scriptAddr, 100_000_000L);

        Account c3 = new Account(Networks.testnet());
        fund(client, c3.baseAddress(), 200);
        System.out.println("T3 (node eval) : " + trySpend(client, qtx, c3,
                new ScriptTx().collectFrom(locked3, BigIntPlutusData.of(1))
                        .readFrom(ref3)
                        .payToAddress(p3.baseAddress(), Amount.ada(95)), 0));
        System.out.println("T3 (fixed eval): " + trySpend(client, qtx, c3,
                new ScriptTx().collectFrom(locked3, BigIntPlutusData.of(1))
                        .readFrom(ref3)
                        .payToAddress(p3.baseAddress(), Amount.ada(95)), 1));
        System.out.println("T3 (ignore err): " + trySpend(client, qtx, c3,
                new ScriptTx().collectFrom(locked3, BigIntPlutusData.of(1))
                        .readFrom(ref3)
                        .payToAddress(p3.baseAddress(), Amount.ada(95)), 2));
    }

    /** mode 0 = node evaluation, 1 = fixed ExUnits, 2 = ignore evaluation error. */
    static String trySpend(YanoClient client, QuickTxBuilder qtx, Account collat,
                           ScriptTx tx, int mode) {
        try {
            var ctx = qtx.compose(tx)
                    .feePayer(collat.baseAddress())
                    .collateralPayer(collat.baseAddress())
                    .withSigner(SignerProviders.signerFrom(collat));
            if (mode == 1) {
                ctx = ctx.withTxEvaluator(new FixedExUnitEvaluator(500_000L, 200_000_000L));
            } else if (mode == 2) {
                ctx = ctx.ignoreScriptCostEvaluationError(true);
            }
            Transaction signed = ctx.buildAndSign();
            var r = client.submit(signed.serialize());
            String detail = r.accepted() ? r.txHash() : firstError(r.body());
            return r.status() + " " + r.category() + " " + detail;
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
            return "BUILD_FAILED " + m.substring(0, Math.min(180, m.length()));
        }
    }

    static String firstError(String body) {
        if (body == null) return "";
        int i = body.indexOf("\"rule\"");
        return i < 0 ? body.substring(0, Math.min(200, body.length()))
                : body.substring(i, Math.min(i + 200, body.length()));
    }

    /** Poll until the given outpoint is visible in canonical UTXO state. */
    static String awaitCanonical(YanoClient client, String txHash, int index, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                var n = client.utxoAt(txHash, index);
                if (n != null) return "confirmed after "
                        + (timeoutMillis - (deadline - System.currentTimeMillis())) + " ms";
            } catch (Exception ignored) {
                // not yet
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "TIMEOUT";
    }

    static Utxo fund(YanoClient client, String addr, long ada) {
        String[] p = client.fund(addr, ada).split("#");
        return Utxo.builder().txHash(p[0]).outputIndex(Integer.parseInt(p[1])).address(addr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(ada).multiply(BigInteger.valueOf(1_000_000)))))
                .build();
    }

    static Utxo findOutput(Transaction tx, String hash, String addr, long lovelace) {
        List<TransactionOutput> outs = tx.getBody().getOutputs();
        for (int i = 0; i < outs.size(); i++) {
            TransactionOutput o = outs.get(i);
            if (addr.equals(o.getAddress()) && o.getValue() != null
                    && BigInteger.valueOf(lovelace).equals(o.getValue().getCoin())) {
                return Utxo.builder().txHash(hash).outputIndex(i).address(addr)
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))))
                        .inlineDatum(o.getInlineDatum() != null
                                ? java.util.HexFormat.of().formatHex(o.getInlineDatum().serializeToBytes()) : null)
                        .build();
            }
        }
        return null;
    }
}
