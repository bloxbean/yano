package com.bloxbean.cardano.yano.compat.ccl;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load test for the Yano devnet mempool (ADR-NET-009 chained-transaction overlay).
 *
 * <p>Two workloads run concurrently against a live devnet:
 * <ul>
 *   <li><b>regular</b> - independent payments, each consuming a distinct pre-funded
 *       UTXO so parallel submissions never conflict with one another;</li>
 *   <li><b>chained</b> - depth-N parent/child chains submitted back-to-back without
 *       waiting for confirmation, which only succeed if the mempool UTXO overlay
 *       resolves an unconfirmed parent's output.</li>
 * </ul>
 */
public final class LoadTest {

    // ---- configuration (system properties) ----
    static final String API = prop("yano.url", "http://localhost:7070/api/v1");
    static final int DURATION_MIN = intProp("load.duration.minutes", 15);
    static final int WORKERS = intProp("load.workers", 8);
    static final int UTXOS_PER_WORKER = intProp("load.utxos.per.worker", 40);
    static final int CHAIN_WORKERS = intProp("load.chain.workers", 4);
    static final int CHAIN_DEPTH = intProp("load.chain.depth", 6);
    static final long FUND_ADA = longProp("load.fund.ada", 200);
    static final long CHAIN_ROOT_ADA = longProp("load.chain.root.ada", 500);
    static final long PAY_LOVELACE = longProp("load.pay.lovelace", 2_000_000);
    /**
     * Value burned per chain link. Each link forwards (input - step) to itself, so
     * the change output stays above min-ada and the next input is always large
     * enough to cover the following payment plus its fee.
     */
    static final long CHAIN_STEP_LOVELACE = longProp("load.chain.step.lovelace", 3_000_000);
    static final int THROTTLE_MS = intProp("load.throttle.ms", 0);
    /** Top-up batch size. Small batches keep refill stalls short mid-run. */
    static final int REFILL_BATCH = intProp("load.refill.batch", 25);
    static final int CHAIN_PAUSE_MS = intProp("load.chain.pause.ms", 500);
    static final int SAMPLE_SEC = intProp("load.sample.seconds", 5);
    static final String REPORT_DIR = prop("load.report.dir", "report");

    static final AtomicBoolean RUNNING = new AtomicBoolean(true);
    static final AtomicLong REFILLS = new AtomicLong();
    static final List<String> ANOMALIES = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        YanoClient client = new YanoClient(API);
        System.out.println("=== Yano mempool load test ===");
        System.out.println("API                : " + client.apiBase());
        System.out.println("duration (min)     : " + DURATION_MIN);
        System.out.println("regular workers    : " + WORKERS + " x " + UTXOS_PER_WORKER + " utxos");
        System.out.println("chain workers      : " + CHAIN_WORKERS + " depth " + CHAIN_DEPTH);

        if (!client.waitUntilReady(Duration.ofMinutes(2))) {
            throw new IllegalStateException("Yano did not become ready at " + API);
        }
        var pp = client.protocolParams();
        System.out.println("protocol params    : maxTxSize=" + pp.get("max_tx_size").asInt()
                + " maxBlockSize=" + pp.get("max_block_size").asInt()
                + " minFeeA=" + pp.get("min_fee_a").asInt()
                + " minFeeB=" + pp.get("min_fee_b").asInt());

        BackendService backend = new BFBackendService(client.apiBase() + "/", "yano-devnet");
        QuickTxBuilder qtx = new QuickTxBuilder(backend);

        // ---------- phase 0: sanity ----------
        System.out.println("\n--- phase 0: sanity check ---");
        Sanity sanity = runSanity(client, qtx);
        System.out.println("single payment     : " + sanity.singlePayment());
        System.out.println("chain depth 3      : " + sanity.chainResult());
        if (!sanity.ok()) {
            System.out.println("!! sanity failed - continuing to collect evidence anyway");
        }

        // ---------- setup ----------
        System.out.println("\n--- funding workers ---");
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            Worker w = new Worker(i, new Account(Networks.testnet()));
            fillPool(client, w, UTXOS_PER_WORKER);
            workers.add(w);
            System.out.println("worker " + i + " " + w.account.baseAddress().substring(0, 24)
                    + "... utxos=" + w.pool.size());
        }
        List<Account> chainAccounts = new ArrayList<>();
        for (int i = 0; i < CHAIN_WORKERS; i++) chainAccounts.add(new Account(Networks.testnet()));

        Stats regular = new Stats("regular");
        Stats chained = new Stats("chained");
        ConcurrentLinkedQueue<ChainOutcome> chainOutcomes = new ConcurrentLinkedQueue<>();

        // ---------- run ----------
        Path reportDir = Path.of(REPORT_DIR);
        Files.createDirectories(reportDir);
        Path samplePath = reportDir.resolve("samples.jsonl");
        Sampler sampler = new Sampler(client, samplePath, regular, chained);
        Thread samplerThread = Thread.ofVirtual().name("sampler").start(sampler);

        System.out.println("\n--- load run: " + DURATION_MIN + " min ---");
        regular.start();
        chained.start();
        long deadline = System.nanoTime() + Duration.ofMinutes(DURATION_MIN).toNanos();

        List<Thread> threads = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(WORKERS + CHAIN_WORKERS);
        for (Worker w : workers) {
            threads.add(Thread.ofVirtual().name("regular-" + w.id).start(() -> {
                try {
                    regularLoop(client, qtx, w, regular, deadline);
                } finally {
                    done.countDown();
                }
            }));
        }
        for (int i = 0; i < CHAIN_WORKERS; i++) {
            Account acct = chainAccounts.get(i);
            int id = i;
            threads.add(Thread.ofVirtual().name("chain-" + id).start(() -> {
                try {
                    chainLoop(client, qtx, acct, id, chained, chainOutcomes, deadline);
                } finally {
                    done.countDown();
                }
            }));
        }

        Thread progress = Thread.ofVirtual().name("progress").start(() -> {
            while (RUNNING.get() && System.nanoTime() < deadline) {
                sleep(30_000);
                System.out.printf("  [%s] regular sub=%d acc=%d (%.1f tps) | chained sub=%d acc=%d | chains=%d%n",
                        Instant.now().toString().substring(11, 19),
                        regular.submitted(), regular.accepted(), regular.acceptTps(),
                        chained.submitted(), chained.accepted(), chainOutcomes.size());
            }
        });

        done.await();
        RUNNING.set(false);
        regular.end();
        chained.end();
        progress.interrupt();
        sampler.stop();
        samplerThread.join(Duration.ofSeconds(10));

        // ---------- settle ----------
        System.out.println("\n--- draining mempool ---");
        drainWait(client, Duration.ofMinutes(2));

        // ---------- report ----------
        Report report = new Report(client, regular, chained, new ArrayList<>(chainOutcomes),
                sanity, sampler.samples(), reportDir);
        Path out = report.write();
        System.out.println("\n=== report written: " + out.toAbsolutePath() + " ===");
        System.out.println(report.summaryText());
    }

    // ---------------- phases ----------------

    record Sanity(String singlePayment, String chainResult, boolean ok) {
    }

    private static Sanity runSanity(YanoClient client, QuickTxBuilder qtx) {
        String single;
        boolean singleOk = false;
        Account a = new Account(Networks.testnet());
        try {
            Utxo u = fundOne(client, a.baseAddress(), FUND_ADA);
            Transaction signed = build(qtx, a, u, a.baseAddress(), PAY_LOVELACE);
            YanoClient.SubmitResult r = client.submit(signed.serialize());
            single = r.status() + " " + r.category() + " " + (r.txHash() != null ? r.txHash() : r.body());
            singleOk = r.accepted();
        } catch (Exception e) {
            single = "EXCEPTION " + e;
        }

        String chain;
        boolean chainOk = false;
        try {
            Account[] accts = new Account[4];
            for (int i = 0; i < accts.length; i++) accts[i] = new Account(Networks.testnet());
            Utxo cur = fundOne(client, accts[0].baseAddress(), CHAIN_ROOT_ADA);
            long curLovelace = CHAIN_ROOT_ADA * 1_000_000L;
            List<String> steps = new ArrayList<>();
            for (int d = 0; d < 3; d++) {
                long pay = curLovelace - CHAIN_STEP_LOVELACE;
                String next = accts[d + 1].baseAddress();
                Transaction signed = build(qtx, accts[d], cur, next, pay);
                byte[] bytes = signed.serialize();
                String hash = TransactionUtil.getTxHash(bytes);
                YanoClient.SubmitResult r = client.submit(bytes);
                steps.add("d" + d + "=" + r.status() + "/" + r.category());
                if (!r.accepted()) break;
                cur = nextLink(signed, hash, next, pay);
                if (cur == null) {
                    steps.add("d" + d + "=NO_LINK_OUTPUT");
                    break;
                }
                curLovelace = pay;
                if (d == 2) chainOk = true;
            }
            chain = String.join(", ", steps);
        } catch (Exception e) {
            chain = "EXCEPTION " + e;
        }
        return new Sanity(single, chain, singleOk && chainOk);
    }

    private static void regularLoop(YanoClient client, QuickTxBuilder qtx, Worker w,
                                    Stats stats, long deadline) {
        while (RUNNING.get() && System.nanoTime() < deadline) {
            Utxo in = w.pool.poll();
            if (in == null) {
                try {
                    fillPool(client, w, REFILL_BATCH);
                    REFILLS.incrementAndGet();
                } catch (Exception e) {
                    ANOMALIES.add("refill failed for worker " + w.id + ": " + e);
                    sleep(1000);
                }
                continue;
            }
            try {
                // Pay to a fresh throwaway address so the payment output is never
                // recycled as an input; this keeps the regular workload unchained.
                Transaction signed = build(qtx, w.account, in, w.sink, PAY_LOVELACE);
                stats.record(client.submit(signed.serialize()));
            } catch (Exception e) {
                stats.recordLocalFailure(shortName(e));
            }
            if (THROTTLE_MS > 0) sleep(THROTTLE_MS);
        }
    }

    record ChainOutcome(int worker, int requestedDepth, int acceptedDepth, String stopReason,
                        long totalMillis) {
    }

    private static void chainLoop(YanoClient client, QuickTxBuilder qtx, Account ignored, int id,
                                  Stats stats, ConcurrentLinkedQueue<ChainOutcome> outcomes,
                                  long deadline) {
        while (RUNNING.get() && System.nanoTime() < deadline) {
            long t0 = System.currentTimeMillis();
            int depth = 0;
            String stop = "COMPLETE";
            // One fresh account per link. Paying to a *different* address than the
            // sender is essential: paying to self lets CCL merge the payment with
            // the change output, leaving no identifiable link to spend next.
            Account[] accts = new Account[CHAIN_DEPTH + 1];
            for (int i = 0; i < accts.length; i++) accts[i] = new Account(Networks.testnet());
            try {
                Utxo cur = fundOne(client, accts[0].baseAddress(), CHAIN_ROOT_ADA);
                long curLovelace = CHAIN_ROOT_ADA * 1_000_000L;
                for (int d = 0; d < CHAIN_DEPTH; d++) {
                    long pay = curLovelace - CHAIN_STEP_LOVELACE;
                    if (pay < 2_000_000L) {
                        stop = "CHAIN_VALUE_EXHAUSTED";
                        break;
                    }
                    String next = accts[d + 1].baseAddress();
                    Transaction signed = build(qtx, accts[d], cur, next, pay);
                    byte[] bytes = signed.serialize();
                    String hash = TransactionUtil.getTxHash(bytes);
                    YanoClient.SubmitResult r = client.submit(bytes);
                    stats.record(r);
                    if (!r.accepted()) {
                        stop = r.category();
                        break;
                    }
                    depth++;
                    cur = nextLink(signed, hash, next, pay);
                    if (cur == null) {
                        stop = "NO_LINK_OUTPUT";
                        break;
                    }
                    curLovelace = pay;
                }
            } catch (Exception e) {
                stop = "LOCAL_" + shortName(e);
                stats.recordLocalFailure(shortName(e));
            }
            outcomes.add(new ChainOutcome(id, CHAIN_DEPTH, depth, stop,
                    System.currentTimeMillis() - t0));
            if (CHAIN_PAUSE_MS > 0) sleep(CHAIN_PAUSE_MS);
        }
    }

    // ---------------- helpers ----------------

    static final class Worker {
        final int id;
        final Account account;
        final String sink;
        final ConcurrentLinkedQueue<Utxo> pool = new ConcurrentLinkedQueue<>();

        Worker(int id, Account account) {
            this.id = id;
            this.account = account;
            this.sink = new Account(Networks.testnet()).baseAddress();
        }
    }

    /** Build and sign a payment consuming exactly the given input. */
    private static Transaction build(QuickTxBuilder qtx, Account from, Utxo in,
                                     String to, long lovelace) {
        Tx tx = new Tx()
                .from(from.baseAddress())
                .collectFrom(List.of(in))
                .payToAddress(to, Amount.lovelace(BigInteger.valueOf(lovelace)));
        return qtx.compose(tx)
                .feePayer(from.baseAddress())
                .withSigner(SignerProviders.signerFrom(from))
                .buildAndSign();
    }

    /**
     * Locate the output this chain should spend next: the one paying the chain
     * address exactly {@code lovelace}. Change is a different amount, so this is
     * unambiguous and does not assume an output ordering.
     */
    private static Utxo nextLink(Transaction signed, String hash, String address, long lovelace) {
        List<TransactionOutput> outs = signed.getBody().getOutputs();
        for (int i = 0; i < outs.size(); i++) {
            TransactionOutput o = outs.get(i);
            if (address.equals(o.getAddress())
                    && o.getValue() != null
                    && BigInteger.valueOf(lovelace).equals(o.getValue().getCoin())) {
                return Utxo.builder()
                        .txHash(hash)
                        .outputIndex(i)
                        .address(address)
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))))
                        .build();
            }
        }
        return null;
    }

    private static Utxo fundOne(YanoClient client, String address, long ada) {
        String outpoint = client.fund(address, ada);
        String[] parts = outpoint.split("#");
        return Utxo.builder()
                .txHash(parts[0])
                .outputIndex(Integer.parseInt(parts[1]))
                .address(address)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(ada).multiply(BigInteger.valueOf(1_000_000)))))
                .build();
    }

    private static void fillPool(YanoClient client, Worker w, int count) {
        for (int i = 0; i < count; i++) {
            w.pool.add(fundOne(client, w.account.baseAddress(), FUND_ADA));
        }
    }

    /** Wait for the mempool to drain so the report can state the settled state. */
    private static void drainWait(YanoClient client, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        double last = -1;
        while (System.nanoTime() < deadline) {
            Double n = client.mempoolMetrics().get("yano_node_mempool_transactions");
            if (n == null) break;
            if (n != last) {
                System.out.println("  mempool transactions = " + n.longValue());
                last = n;
            }
            if (n <= 0) return;
            sleep(2000);
        }
        System.out.println("  mempool did not reach zero within " + timeout);
        ANOMALIES.add("mempool did not drain to zero within " + timeout);
    }

    static String shortName(Exception e) {
        String m = e.getMessage();
        String cls = e.getClass().getSimpleName();
        if (m == null) return cls;
        String flat = m.replaceAll("\\s+", " ");
        return cls + ":" + flat.substring(0, Math.min(80, flat.length()));
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String prop(String k, String d) {
        return System.getProperty(k, d);
    }

    static int intProp(String k, int d) {
        return Integer.parseInt(System.getProperty(k, String.valueOf(d)));
    }

    static long longProp(String k, long d) {
        return Long.parseLong(System.getProperty(k, String.valueOf(d)));
    }

    /** Periodic sampler of node-side state, written as JSONL and kept for the report. */
    static final class Sampler implements Runnable {
        private final YanoClient client;
        private final Path path;
        private final Stats regular;
        private final Stats chained;
        private final List<Map<String, Object>> samples = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean run = true;

        Sampler(YanoClient client, Path path, Stats regular, Stats chained) {
            this.client = client;
            this.path = path;
            this.regular = regular;
            this.chained = chained;
        }

        void stop() {
            run = false;
        }

        List<Map<String, Object>> samples() {
            return new ArrayList<>(samples);
        }

        @Override
        public void run() {
            try (var writer = Files.newBufferedWriter(path)) {
                while (run) {
                    try {
                        Map<String, Double> m = client.mempoolMetrics();
                        var tip = client.latestBlock();
                        Map<String, Object> s = new java.util.LinkedHashMap<>();
                        s.put("t", Instant.now().toString());
                        s.put("mempoolTxs", m.getOrDefault("yano_node_mempool_transactions", -1.0).longValue());
                        s.put("mempoolBytes", m.getOrDefault("yano_node_mempool_bytes", -1.0).longValue());
                        s.put("blockHeight", tip.get("height").asLong());
                        s.put("blockTxCount", tip.get("tx_count").asInt());
                        s.put("blockSize", tip.get("size").asInt());
                        s.put("regularSubmitted", regular.submitted());
                        s.put("regularAccepted", regular.accepted());
                        s.put("chainedSubmitted", chained.submitted());
                        s.put("chainedAccepted", chained.accepted());
                        samples.add(s);
                        writer.write(toJson(s));
                        writer.newLine();
                        writer.flush();
                    } catch (Exception e) {
                        ANOMALIES.add("sampler: " + e);
                    }
                    for (int i = 0; i < SAMPLE_SEC * 2 && run; i++) sleep(500);
                }
            } catch (IOException e) {
                ANOMALIES.add("sampler writer: " + e);
            }
        }

        private static String toJson(Map<String, Object> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v instanceof Number) sb.append(v);
                else sb.append('"').append(v).append('"');
            }
            return sb.append('}').toString();
        }
    }
}
