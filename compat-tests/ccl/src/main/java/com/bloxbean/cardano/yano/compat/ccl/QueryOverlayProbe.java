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
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.util.List;

/**
 * Which read paths see an unconfirmed mempool output?
 *
 * <p>/utils/txs/evaluate was made mempool-aware. This probe checks whether the
 * UTXO query endpoints a client also needs during transaction construction were
 * given the same view.</p>
 */
public final class QueryOverlayProbe {

    public static void main(String[] args) throws Exception {
        YanoClient client = new YanoClient(System.getProperty("yano.url", "http://localhost:7070/api/v1"));
        BackendService backend = new BFBackendService(client.apiBase() + "/", "yano-devnet");
        QuickTxBuilder qtx = new QuickTxBuilder(backend);
        String scriptAddr = AddressProvider.getEntAddress(PlutusProbe.SCRIPT, Networks.testnet()).toBech32();

        Account p = new Account(Networks.testnet());
        Utxo funded = PlutusProbe.fund(client, p.baseAddress(), 2000);

        Transaction pub = qtx.compose(new Tx().from(p.baseAddress()).collectFrom(List.of(funded))
                        .payToAddress(p.baseAddress(), List.of(Amount.ada(50)), PlutusProbe.SCRIPT)
                        .payToContract(scriptAddr, Amount.ada(100), BigIntPlutusData.of(7)))
                .feePayer(p.baseAddress()).withSigner(SignerProviders.signerFrom(p)).buildAndSign();
        byte[] bytes = pub.serialize();
        String hash = TransactionUtil.getTxHash(bytes);
        int refIdx = PlutusProbe.findOutput(pub, hash, p.baseAddress(), 50_000_000L).getOutputIndex();
        int lockIdx = PlutusProbe.findOutput(pub, hash, scriptAddr, 100_000_000L).getOutputIndex();

        System.out.println("submit parent (left unconfirmed): " + client.submit(bytes).status());
        System.out.println("tx hash: " + hash);
        System.out.println();

        // Query immediately, while the producing transaction is still in the mempool.
        System.out.println("GET /utxos/" + hash.substring(0, 12) + ".../" + refIdx
                + " (script-ref output) : " + probe(client, hash, refIdx));
        System.out.println("GET /utxos/" + hash.substring(0, 12) + ".../" + lockIdx
                + " (locked output)     : " + probe(client, hash, lockIdx));
        System.out.println("GET /utxos/.../" + refIdx + "?include_mempool=true : " + probeFlag(client, hash, refIdx));
        System.out.println("GET /utxos/.../" + lockIdx + "?include_mempool=true : " + probeFlag(client, hash, lockIdx));
        System.out.println("GET /addresses/{payer}/utxos      : "
                + countUtxos(client, p.baseAddress()) + " utxo(s) visible");
        System.out.println("GET /addresses/{script}/utxos     : "
                + countUtxos(client, scriptAddr) + " utxo(s) visible");
        System.out.println("child, bare ref utxo (no script info)   : "
                + evaluateChild(client, qtx, p, scriptAddr, hash, refIdx, lockIdx, 0));
        System.out.println("child, ref utxo + referenceScriptHash   : "
                + evaluateChild(client, qtx, p, scriptAddr, hash, refIdx, lockIdx, 1));
        System.out.println("child, language supplied via attach     : "
                + evaluateChild(client, qtx, p, scriptAddr, hash, refIdx, lockIdx, 2));
        System.out.println("child, inlined validator, NO readFrom   : "
                + evaluateChild(client, qtx, p, scriptAddr, hash, refIdx, lockIdx, 3));

        Thread.sleep(3000);
        System.out.println("\n-- after confirmation --");
        System.out.println("GET /utxos/.../" + refIdx + " : " + probe(client, hash, refIdx));
        System.out.println("GET /addresses/{payer}/utxos : " + countUtxos(client, p.baseAddress()));
    }

    static String probe(YanoClient c, String hash, int idx) {
        try {
            c.utxoAt(hash, idx);
            return "200 VISIBLE";
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            return m.contains("404") ? "404 NOT VISIBLE" : m.substring(0, Math.min(60, m.length()));
        }
    }

    static String probeFlag(YanoClient c, String hash, int idx) {
        try {
            c.utxoAtIncludingMempool(hash, idx);
            return "200 VISIBLE";
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            return m.contains("404") ? "404 NOT VISIBLE" : m.substring(0, Math.min(60, m.length()));
        }
    }

    static int countUtxos(YanoClient c, String addr) {
        try {
            return c.utxos(addr).size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** variant 0 = bare ref utxo, 1 = ref utxo carrying the script hash, 2 = script attached. */
    static String evaluateChild(YanoClient c, QuickTxBuilder qtx, Account p, String scriptAddr,
                                String hash, int refIdx, int lockIdx, int variant) {
        try {
            Utxo locked = Utxo.builder().txHash(hash).outputIndex(lockIdx).address(scriptAddr)
                    .amount(List.of(Amount.lovelace(java.math.BigInteger.valueOf(100_000_000L))))
                    .inlineDatum(java.util.HexFormat.of().formatHex(
                            BigIntPlutusData.of(7).serializeToBytes()))
                    .build();
            var refBuilder = Utxo.builder().txHash(hash).outputIndex(refIdx).address(p.baseAddress())
                    .amount(List.of(Amount.lovelace(java.math.BigInteger.valueOf(50_000_000L))));
            if (variant >= 1) {
                refBuilder.referenceScriptHash(
                        java.util.HexFormat.of().formatHex(PlutusProbe.SCRIPT.getScriptHash()));
            }
            Utxo ref = refBuilder.build();

            Account collat = new Account(Networks.testnet());
            PlutusProbe.fund(c, collat.baseAddress(), 500);
            var st = new com.bloxbean.cardano.client.quicktx.ScriptTx()
                    .collectFrom(locked, BigIntPlutusData.of(1))
                    .readFrom(ref)
                    .payToAddress(p.baseAddress(), Amount.ada(95));
            if (variant == 2) st = st.attachSpendingValidator(PlutusProbe.SCRIPT);
            if (variant == 3) {
                // Spend a mempool-produced script UTXO with the validator inlined and
                // no reference input at all: isolates overlay resolution of the INPUT.
                st = new com.bloxbean.cardano.client.quicktx.ScriptTx()
                        .collectFrom(locked, BigIntPlutusData.of(1))
                        .attachSpendingValidator(PlutusProbe.SCRIPT)
                        .payToAddress(p.baseAddress(), Amount.ada(95));
            }
            Transaction child = qtx.compose(st)
                    .feePayer(collat.baseAddress()).collateralPayer(collat.baseAddress())
                    .withSigner(SignerProviders.signerFrom(collat))
                    .buildAndSign();
            var r = c.submit(child.serialize());
            return "built OK -> submit " + r.status() + " " + r.category()
                    + (r.accepted() ? "" : " " + firstRule(r.body()));
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
            return "BUILD FAILED " + m.substring(0, Math.min(90, m.length()));
        }
    }

    static String firstRule(String body) {
        if (body == null) return "";
        int i = body.indexOf("\"rule\"");
        return i < 0 ? "" : body.substring(i, Math.min(i + 45, body.length()));
    }
}
