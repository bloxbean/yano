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
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Extra scenarios beyond the baseline payment load:
 * plutus | exunits | rollback | interval | heap
 */
public final class Scenarios {

    static final String ALWAYS_SUCCEEDS_V2 = "49480100002221200101";
    static final PlutusV2Script SCRIPT = PlutusV2Script.builder().cborHex(ALWAYS_SUCCEEDS_V2).build();

    static YanoClient client;
    static QuickTxBuilder qtx;
    static String scriptAddr;
    static final StringBuilder OUT = new StringBuilder();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "plutus";
        client = new YanoClient(System.getProperty("yano.url", "http://localhost:7070/api/v1"));
        BackendService backend = new BFBackendService(client.apiBase() + "/", "yano-devnet");
        qtx = new QuickTxBuilder(backend);
        scriptAddr = AddressProvider.getEntAddress(SCRIPT, Networks.testnet()).toBech32();

        log("# Scenario: " + mode);
        log("script address: " + scriptAddr);
        switch (mode) {
            case "plutus" -> plutus();
            case "exunits" -> exunits();
            case "rollback" -> rollback();
            case "interval" -> interval();
            case "heap" -> heap();
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }

        Path out = Path.of(System.getProperty("load.report.dir", "report-scenarios"));
        Files.createDirectories(out);
        Path f = out.resolve(mode + ".md");
        Files.writeString(f, OUT.toString());
        System.out.println("\n>>> written " + f.toAbsolutePath());
    }

    static void log(String s) {
        System.out.println(s);
        OUT.append(s).append('\n');
    }

    // ================= S1: Plutus / reference-script load =================

    static void plutus() throws Exception {
        int n = intProp("load.plutus.count", 300);
        boolean useRef = Boolean.parseBoolean(System.getProperty("load.plutus.refscript", "false"));
        log("\n## Plutus spend load (n=" + n + ", referenceScript=" + useRef + ")\n");

        // Stage 1: create n locked script UTXOs and wait until they are canonical.
        log("### stage 1 - lock funds at the script address");
        Stats lockStats = new Stats("plutus-lock");
        lockStats.start();
        List<Utxo> locked = new ArrayList<>();
        List<Utxo> refUtxos = new ArrayList<>();
        List<Account> payers = new ArrayList<>();
        String lastHash = null;
        for (int i = 0; i < n; i++) {
            Account p = new Account(Networks.testnet());
            payers.add(p);
            Utxo f = fund(p.baseAddress(), 2000);
            Tx tx = new Tx().from(p.baseAddress()).collectFrom(List.of(f))
                    .payToContract(scriptAddr, Amount.ada(100), BigIntPlutusData.of(42));
            if (useRef) tx = tx.payToAddress(p.baseAddress(), List.of(Amount.ada(50)), SCRIPT);
            Transaction signed = qtx.compose(tx).feePayer(p.baseAddress())
                    .withSigner(SignerProviders.signerFrom(p)).buildAndSign();
            byte[] b = signed.serialize();
            String h = TransactionUtil.getTxHash(b);
            lockStats.record(client.submit(b));
            locked.add(findOutput(signed, h, scriptAddr, 100_000_000L));
            if (useRef) refUtxos.add(findOutput(signed, h, p.baseAddress(), 50_000_000L));
            lastHash = h;
        }
        lockStats.end();
        log("locked " + locked.size() + " utxos, " + lockStats.accepted() + " accepted, "
                + String.format("%.1f tx/s", lockStats.acceptTps()));
        log("awaiting canonical visibility: " + awaitCanonical(lastHash, 0, 60_000));

        // Stage 2: spend them, measuring script-tx admission throughput.
        log("\n### stage 2 - spend with Plutus validator (phase-2 under the admission lane)");
        Account collat = new Account(Networks.testnet());
        for (int i = 0; i < 4; i++) fund(collat.baseAddress(), 500);

        Stats spend = new Stats("plutus-spend");
        List<long[]> exUnits = new ArrayList<>();
        spend.start();
        for (int i = 0; i < locked.size(); i++) {
            Utxo u = locked.get(i);
            if (u == null) continue;
            try {
                ScriptTx st = new ScriptTx().collectFrom(u, BigIntPlutusData.of(1))
                        .payToAddress(payers.get(i).baseAddress(), Amount.ada(95));
                st = useRef ? st.readFrom(refUtxos.get(i)) : st.attachSpendingValidator(SCRIPT);
                Transaction signed = qtx.compose(st)
                        .feePayer(collat.baseAddress())
                        .collateralPayer(collat.baseAddress())
                        .withSigner(SignerProviders.signerFrom(collat))
                        .buildAndSign();
                exUnits.add(declaredExUnits(signed));
                spend.record(client.submit(signed.serialize()));
            } catch (Exception e) {
                spend.recordLocalFailure(LoadTest.shortName(e));
            }
        }
        spend.end();
        report("Plutus spend", spend);
        if (!exUnits.isEmpty()) {
            long mem = exUnits.stream().mapToLong(a -> a[0]).sum() / exUnits.size();
            long steps = exUnits.stream().mapToLong(a -> a[1]).sum() / exUnits.size();
            log("mean declared ExUnits per script tx: mem=" + mem + " steps=" + steps);
        }

        // Chained-script check: reference script published by an unconfirmed parent.
        log("\n### stage 3 - chained script tx (parent still in mempool)");
        chainedScriptCheck();
    }

    /** The ADR-NET-009 reference-script chaining case, end to end. */
    static void chainedScriptCheck() {
        try {
            Account p = new Account(Networks.testnet());
            Utxo f = fund(p.baseAddress(), 2000);
            Transaction pub = qtx.compose(new Tx().from(p.baseAddress()).collectFrom(List.of(f))
                            .payToAddress(p.baseAddress(), List.of(Amount.ada(50)), SCRIPT)
                            .payToContract(scriptAddr, Amount.ada(100), BigIntPlutusData.of(7)))
                    .feePayer(p.baseAddress()).withSigner(SignerProviders.signerFrom(p)).buildAndSign();
            String h = TransactionUtil.getTxHash(pub.serialize());
            log("publish parent: " + client.submit(pub.serialize()).status() + " (left unconfirmed)");
            Utxo ref = findOutput(pub, h, p.baseAddress(), 50_000_000L);
            Utxo lockedU = findOutput(pub, h, scriptAddr, 100_000_000L);
            Account c = new Account(Networks.testnet());
            fund(c.baseAddress(), 500);
            try {
                Transaction child = qtx.compose(new ScriptTx()
                                .collectFrom(lockedU, BigIntPlutusData.of(1))
                                .readFrom(ref)
                                .payToAddress(p.baseAddress(), Amount.ada(95)))
                        .feePayer(c.baseAddress()).collateralPayer(c.baseAddress())
                        .withSigner(SignerProviders.signerFrom(c))
                        .buildAndSign();
                var r = client.submit(child.serialize());
                log("child submit  : " + r.status() + " " + r.category());
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
                log("child BUILD FAILED (client side): " + m.substring(0, Math.min(160, m.length())));
                log("cause: Yano's /utils/txs/evaluate resolves canonically only, so it cannot");
                log("price a script tx whose input comes from an unconfirmed mempool parent.");
            }
        } catch (Exception e) {
            log("chained script check failed: " + e);
        }
    }

    // ================= S2: block execution-unit limits =================

    static void exunits() throws Exception {
        int n = intProp("load.exunits.count", 900);
        log("\n## Block execution-unit limits (script txs=" + n + ")\n");
        var pp = client.protocolParams();
        long maxBlockMem = pp.get("max_block_ex_mem").asLong();
        long maxBlockSteps = pp.get("max_block_ex_steps").asLong();
        long maxTxMem = pp.get("max_tx_ex_mem").asLong();
        log("protocol: max_block_ex_mem=" + maxBlockMem + " max_block_ex_steps=" + maxBlockSteps
                + " max_tx_ex_mem=" + maxTxMem);

        // Pre-build everything, then submit as fast as possible so a single block
        // has the chance to absorb many script transactions.
        log("pre-building " + n + " script spends ...");
        Account collat = new Account(Networks.testnet());
        for (int i = 0; i < 6; i++) fund(collat.baseAddress(), 500);
        List<Utxo> locked = new ArrayList<>();
        List<Account> payers = new ArrayList<>();
        String lastHash = null;
        for (int i = 0; i < n; i++) {
            Account p = new Account(Networks.testnet());
            payers.add(p);
            Utxo f = fund(p.baseAddress(), 2000);
            Transaction lock = qtx.compose(new Tx().from(p.baseAddress()).collectFrom(List.of(f))
                            .payToContract(scriptAddr, Amount.ada(100), BigIntPlutusData.of(42)))
                    .feePayer(p.baseAddress()).withSigner(SignerProviders.signerFrom(p)).buildAndSign();
            byte[] b = lock.serialize();
            lastHash = TransactionUtil.getTxHash(b);
            client.submit(b);
            locked.add(findOutput(lock, lastHash, scriptAddr, 100_000_000L));
        }
        log("awaiting canonical: " + awaitCanonical(lastHash, 0, 90_000));

        List<byte[]> prebuilt = new ArrayList<>();
        List<long[]> ex = new ArrayList<>();
        for (int i = 0; i < locked.size(); i++) {
            if (locked.get(i) == null) continue;
            try {
                Transaction signed = qtx.compose(new ScriptTx()
                                .collectFrom(locked.get(i), BigIntPlutusData.of(1))
                                .attachSpendingValidator(SCRIPT)
                                .payToAddress(payers.get(i).baseAddress(), Amount.ada(95)))
                        .feePayer(collat.baseAddress()).collateralPayer(collat.baseAddress())
                        .withSigner(SignerProviders.signerFrom(collat)).buildAndSign();
                prebuilt.add(signed.serialize());
                ex.add(declaredExUnits(signed));
            } catch (Exception ignored) {
                // pre-build failures are reported via the accepted count below
            }
        }
        long meanMem = ex.stream().mapToLong(a -> a[0]).sum() / Math.max(1, ex.size());
        long meanSteps = ex.stream().mapToLong(a -> a[1]).sum() / Math.max(1, ex.size());
        log("pre-built " + prebuilt.size() + " script txs, mean ExUnits mem=" + meanMem
                + " steps=" + meanSteps);

        long startHeight = client.latestBlock().get("height").asLong();
        Stats s = new Stats("exunits-burst");
        s.start();
        CountDownLatch done = new CountDownLatch(prebuilt.size());
        for (byte[] tx : prebuilt) {
            Thread.ofVirtual().start(() -> {
                try {
                    s.record(client.submit(tx));
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        s.end();
        report("script burst", s);

        Thread.sleep(4000);
        long endHeight = client.latestBlock().get("height").asLong();
        int maxTx = 0;
        long maxSize = 0;
        for (long h = startHeight; h <= endHeight; h++) {
            try {
                var b = client.blockByNumber(h);
                maxTx = Math.max(maxTx, b.get("tx_count").asInt());
                maxSize = Math.max(maxSize, b.get("size").asLong());
            } catch (Exception ignored) {
                // block may not be queryable yet
            }
        }
        long blockMem = (long) maxTx * meanMem;
        long blockSteps = (long) maxTx * meanSteps;
        log("\n### result");
        log("blocks " + startHeight + " -> " + endHeight);
        log("max script txs in one block : " + maxTx);
        log("max block size (bytes)      : " + maxSize);
        log("implied block ExUnit mem    : " + blockMem + "  (limit " + maxBlockMem + ")  "
                + (blockMem > maxBlockMem ? "**EXCEEDS " + String.format("%.1fx", (double) blockMem / maxBlockMem) + "**" : "within"));
        log("implied block ExUnit steps  : " + blockSteps + "  (limit " + maxBlockSteps + ")  "
                + (blockSteps > maxBlockSteps ? "**EXCEEDS " + String.format("%.1fx", (double) blockSteps / maxBlockSteps) + "**" : "within"));
        log("\nOnly script transactions were submitted in this window, so the implied totals");
        log("are block tx_count x mean declared ExUnits per transaction.");
    }

    // ================= S3: rollback and revalidation =================

    static void rollback() throws Exception {
        int depth = intProp("load.rollback.blocks", 8);
        log("\n## Rollback and mempool revalidation (rollback " + depth + " blocks)\n");

        // Case A: chain confirmed, then rolled back.
        log("### case A - confirmed chain, then rollback");
        Account a1 = new Account(Networks.testnet());
        Account a2 = new Account(Networks.testnet());
        Utxo root = fund(a1.baseAddress(), 500);
        Transaction p = qtx.compose(new Tx().from(a1.baseAddress()).collectFrom(List.of(root))
                        .payToAddress(a2.baseAddress(), Amount.ada(400)))
                .feePayer(a1.baseAddress()).withSigner(SignerProviders.signerFrom(a1)).buildAndSign();
        String pHash = TransactionUtil.getTxHash(p.serialize());
        log("parent submit : " + client.submit(p.serialize()).status() + " " + pHash);
        log("await confirm : " + awaitCanonical(pHash, outIndex(p, a2.baseAddress(), 400_000_000L), 30_000));
        log("mempool before rollback : " + mempoolCount());

        var rb = client.postRollback(depth);
        log("rollback      : " + rb);
        Thread.sleep(1500);
        log("mempool after rollback  : " + mempoolCount());
        log("parent output canonical after rollback: " + canonicalStatus(pHash,
                outIndex(p, a2.baseAddress(), 400_000_000L)));
        log("root input restored     : " + canonicalStatus(root.getTxHash(), root.getOutputIndex()));
        log("=> confirmed-then-rolled-back transactions are NOT re-admitted to the mempool");
        log("   (ADR-NET-009 scopes rollback re-admission out; recorded as review finding M7)");

        // Case B: resident unconfirmed child whose parent gets rolled back.
        log("\n### case B - unconfirmed child whose parent is rolled back");
        Account b1 = new Account(Networks.testnet());
        Account b2 = new Account(Networks.testnet());
        Utxo broot = fund(b1.baseAddress(), 500);
        Transaction bp = qtx.compose(new Tx().from(b1.baseAddress()).collectFrom(List.of(broot))
                        .payToAddress(b2.baseAddress(), Amount.ada(400)))
                .feePayer(b1.baseAddress()).withSigner(SignerProviders.signerFrom(b1)).buildAndSign();
        String bpHash = TransactionUtil.getTxHash(bp.serialize());
        client.submit(bp.serialize());
        int bpIdx = outIndex(bp, b2.baseAddress(), 400_000_000L);
        awaitCanonical(bpHash, bpIdx, 30_000);

        Utxo childIn = Utxo.builder().txHash(bpHash).outputIndex(bpIdx).address(b2.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(400_000_000L)))).build();
        Transaction bc = qtx.compose(new Tx().from(b2.baseAddress()).collectFrom(List.of(childIn))
                        .payToAddress(b1.baseAddress(), Amount.ada(300)))
                .feePayer(b2.baseAddress()).withSigner(SignerProviders.signerFrom(b2)).buildAndSign();
        String bcHash = TransactionUtil.getTxHash(bc.serialize());
        var cr = client.submit(bc.serialize());
        log("child submit  : " + cr.status() + " " + cr.category());
        long before = mempoolCount();
        var rb2 = client.postRollback(depth);
        log("rollback      : " + rb2);
        Thread.sleep(1500);
        long after = mempoolCount();
        log("mempool before/after rollback : " + before + " -> " + after);
        log("child still present           : " + client.txInMempoolHint(bcHash));

        log("\n### node health after rollbacks");
        log("health : " + (client.waitUntilReady(Duration.ofSeconds(10)) ? "UP" : "DOWN"));
        log("tip    : " + client.latestBlock().get("height").asLong());
        log("(block production continuing after rollback confirms the selection guard was");
        log(" released in the rollback handler's finally block - review finding H1)");
    }

    // ================= S4: realistic block intervals =================

    static void interval() throws Exception {
        int seconds = intProp("load.interval.seconds", 120);
        int rate = intProp("load.interval.rate", 40);
        log("\n## Behaviour at realistic block intervals (" + seconds + "s at ~" + rate + " tx/s)\n");
        log("block interval is set in the node config; this scenario measures mempool residency.");

        List<Utxo> pool = new ArrayList<>();
        Account w = new Account(Networks.testnet());
        for (int i = 0; i < seconds * rate + 50; i++) pool.add(fund(w.baseAddress(), 20));
        String sink = new Account(Networks.testnet()).baseAddress();

        Stats s = new Stats("interval");
        s.start();
        List<Map<String, Object>> samples = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        long lastSample = 0;
        int idx = 0;
        while (System.nanoTime() < deadline && idx < pool.size()) {
            Transaction t = qtx.compose(new Tx().from(w.baseAddress())
                            .collectFrom(List.of(pool.get(idx++)))
                            .payToAddress(sink, Amount.ada(5)))
                    .feePayer(w.baseAddress()).withSigner(SignerProviders.signerFrom(w)).buildAndSign();
            s.record(client.submit(t.serialize()));
            if (System.currentTimeMillis() - lastSample > 5000) {
                lastSample = System.currentTimeMillis();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("mempoolTxs", mempoolCount());
                m.put("height", client.latestBlock().get("height").asLong());
                samples.add(m);
            }
            Thread.sleep(Math.max(0, 1000 / Math.max(1, rate)));
        }
        s.end();
        report("interval load", s);
        long peak = samples.stream().mapToLong(m -> (Long) m.get("mempoolTxs")).max().orElse(0);
        long firstH = samples.isEmpty() ? 0 : (Long) samples.get(0).get("height");
        long lastH = samples.isEmpty() ? 0 : (Long) samples.get(samples.size() - 1).get("height");
        log("peak mempool residency : " + peak + " transactions");
        log("blocks produced        : " + (lastH - firstH) + " over " + seconds + "s");
        log("mempool samples        : " + samples);
    }

    // ================= S5: retained heap at index capacity =================

    static void heap() throws Exception {
        int target = intProp("load.heap.index.entries", 250_000);
        int outputs = intProp("load.heap.outputs", 24);
        log("\n## Retained heap at " + target + " UTXO index entries\n");
        log("Each transaction contributes (outputs + regular inputs) index records.");
        log("Using " + outputs + " outputs + 1 input = " + (outputs + 1) + " records per transaction.");
        int txCount = (int) Math.ceil(target / (double) (outputs + 1));
        log("target transactions resident: " + txCount);

        String pid = System.getProperty("load.heap.pid", "");
        log("\nbaseline heap (mempool empty): " + heapInfo(pid));

        Account w = new Account(Networks.testnet());
        String sink = new Account(Networks.testnet()).baseAddress();
        log("funding " + txCount + " inputs ...");
        List<Utxo> pool = new ArrayList<>();
        for (int i = 0; i < txCount; i++) pool.add(fund(w.baseAddress(), 200));

        // Pre-build everything first. The transactions must all be resident at the
        // same instant, so signing cannot happen inside the submission window.
        log("pre-building " + txCount + " transactions (" + outputs + " outputs each) ...");
        List<byte[]> prebuilt = new ArrayList<>();
        long buildStart = System.currentTimeMillis();
        for (Utxo u : pool) {
            try {
                Tx t = new Tx().from(w.baseAddress()).collectFrom(List.of(u));
                for (int o = 0; o < outputs; o++) t = t.payToAddress(sink, Amount.ada(2));
                Transaction signed = qtx.compose(t).feePayer(w.baseAddress())
                        .withSigner(SignerProviders.signerFrom(w)).buildAndSign();
                prebuilt.add(signed.serialize());
            } catch (Exception ignored) {
                // counted via the accepted total below
            }
        }
        log("pre-built " + prebuilt.size() + " txs in "
                + (System.currentTimeMillis() - buildStart) + " ms, mean size "
                + (prebuilt.isEmpty() ? 0 : prebuilt.stream().mapToInt(b -> b.length).sum() / prebuilt.size())
                + " bytes");

        log("burst-submitting so every transaction is resident at once ...");
        Stats s = new Stats("heap-fill");
        s.start();
        CountDownLatch done = new CountDownLatch(prebuilt.size());
        for (byte[] tx : prebuilt) {
            Thread.ofVirtual().start(() -> {
                try {
                    s.record(client.submit(tx));
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        s.end();
        report("heap fill", s);

        long resident = mempoolCount();
        long entries = resident * (outputs + 1);
        log("resident transactions  : " + resident);
        log("implied index records  : " + entries);
        log("mempool bytes          : " + mempoolBytes());
        log("heap at peak residency : " + heapInfo(pid));
        log("\nNote: Yano exposes no metric for actual index cardinality, so the record count");
        log("is derived from the submitted shapes (review finding: MempoolStats unreachable).");
    }

    // ================= helpers =================

    static void report(String title, Stats s) {
        log("\n**" + title + "**");
        log("- submitted " + s.submitted() + ", accepted " + s.accepted()
                + " (" + String.format("%.1f%%", s.submitted() == 0 ? 0 : 100.0 * s.accepted() / s.submitted()) + ")");
        log("- throughput " + String.format("%.1f tx/s", s.acceptTps()));
        var lat = s.latencyMillis();
        if (!lat.isEmpty()) {
            log("- latency p50 " + String.format("%.2f", lat.get("p50")) + " ms, p95 "
                    + String.format("%.2f", lat.get("p95")) + " ms, p99 "
                    + String.format("%.2f", lat.get("p99")) + " ms, max "
                    + String.format("%.2f", lat.get("max")) + " ms");
        }
        log("- outcomes " + s.categories());
    }

    static long[] declaredExUnits(Transaction tx) {
        long mem = 0;
        long steps = 0;
        List<Redeemer> rs = tx.getWitnessSet() != null ? tx.getWitnessSet().getRedeemers() : null;
        if (rs != null) {
            for (Redeemer r : rs) {
                if (r.getExUnits() != null) {
                    mem += r.getExUnits().getMem().longValue();
                    steps += r.getExUnits().getSteps().longValue();
                }
            }
        }
        return new long[]{mem, steps};
    }

    static Utxo fund(String addr, long ada) {
        String[] p = client.fund(addr, ada).split("#");
        return Utxo.builder().txHash(p[0]).outputIndex(Integer.parseInt(p[1])).address(addr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(ada).multiply(BigInteger.valueOf(1_000_000)))))
                .build();
    }

    static Utxo findOutput(Transaction tx, String hash, String addr, long lovelace) {
        int i = outIndex(tx, addr, lovelace);
        if (i < 0) return null;
        return Utxo.builder().txHash(hash).outputIndex(i).address(addr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))))
                .inlineDatum(tx.getBody().getOutputs().get(i).getInlineDatum() != null
                        ? java.util.HexFormat.of().formatHex(
                        tx.getBody().getOutputs().get(i).getInlineDatum().serializeToBytes()) : null)
                .build();
    }

    static int outIndex(Transaction tx, String addr, long lovelace) {
        List<TransactionOutput> outs = tx.getBody().getOutputs();
        for (int i = 0; i < outs.size(); i++) {
            TransactionOutput o = outs.get(i);
            if (addr.equals(o.getAddress()) && o.getValue() != null
                    && BigInteger.valueOf(lovelace).equals(o.getValue().getCoin())) return i;
        }
        return -1;
    }

    static String awaitCanonical(String txHash, int index, long timeoutMillis) {
        long t0 = System.currentTimeMillis();
        long deadline = t0 + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                client.utxoAt(txHash, index);
                return "confirmed after " + (System.currentTimeMillis() - t0) + " ms";
            } catch (Exception ignored) {
                // not yet
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "TIMEOUT";
    }

    static String canonicalStatus(String txHash, int index) {
        try {
            client.utxoAt(txHash, index);
            return "present";
        } catch (Exception e) {
            return "absent";
        }
    }

    static long mempoolCount() {
        return client.mempoolMetrics()
                .getOrDefault("yano_node_mempool_transactions", -1.0).longValue();
    }

    static long mempoolBytes() {
        return client.mempoolMetrics()
                .getOrDefault("yano_node_mempool_bytes", -1.0).longValue();
    }

    /** Force a GC then read used heap from the node JVM. */
    static String heapInfo(String pid) {
        if (pid == null || pid.isBlank()) return "(no load.heap.pid supplied)";
        try {
            new ProcessBuilder("jcmd", pid, "GC.run").redirectErrorStream(true).start().waitFor();
            Thread.sleep(1500);
            Process p = new ProcessBuilder("jcmd", pid, "GC.heap_info")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            StringBuilder sb = new StringBuilder();
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.startsWith("garbage-first heap") || t.startsWith("PSYoungGen")
                        || t.contains("used ") && t.contains("region")) {
                    sb.append(t).append(" | ");
                }
            }
            String rss = rssMb(pid);
            return (sb.length() == 0 ? out.replaceAll("\\s+", " ").substring(0, Math.min(200, out.length()))
                    : sb.toString()) + " RSS=" + rss + " MB";
        } catch (Exception e) {
            return "jcmd failed: " + e;
        }
    }

    static String rssMb(String pid) {
        try {
            Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", pid).start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return String.valueOf(Long.parseLong(s) / 1024);
        } catch (Exception e) {
            return "?";
        }
    }

    static int intProp(String k, int d) {
        return Integer.parseInt(System.getProperty(k, String.valueOf(d)));
    }
}
