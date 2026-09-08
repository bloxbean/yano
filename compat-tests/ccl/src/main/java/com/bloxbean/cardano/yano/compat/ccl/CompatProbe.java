package com.bloxbean.cardano.yano.compat.ccl;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Walks the {@link BackendService} surface an ordinary cardano-client-lib
 * application uses and reports, step by step, whether Yano satisfies it.
 *
 * <p>This is the CCL counterpart of {@code mesh/src/compat.mjs} and
 * {@code evolution/src/compat.mjs}, and it deliberately goes through the CCL
 * <em>provider</em> APIs rather than raw REST: a payment that only works when the
 * harness talks to {@code /api/v1} by hand proves nothing about the SDK. {@link
 * YanoClient} is used only for the devnet faucet and for canonical-state polling,
 * neither of which CCL models.</p>
 *
 * <p>Deterministic pass/fail. Exit code is 0 only when every step passes.</p>
 */
public final class CompatProbe {

    private record StepResult(String name, boolean ok, long millis, String detail) {}

    private static final List<StepResult> RESULTS = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        YanoClient client = new YanoClient(System.getProperty("yano.url", "http://localhost:7070/api/v1"));
        System.out.println("=== cardano-client-lib compatibility probe against " + client.apiBase() + " ===\n");

        if (!client.waitUntilReady(Duration.ofSeconds(120))) {
            System.out.println("node not ready at " + client.apiBase());
            System.exit(2);
        }

        BackendService backend = new BFBackendService(client.apiBase() + "/", "yano-devnet");
        QuickTxBuilder qtx = new QuickTxBuilder(backend);

        step("epochService.getProtocolParameters()", () -> {
            ProtocolParams pp = unwrap(backend.getEpochService().getProtocolParameters());
            // The fields a builder cannot construct a transaction without.
            requireNonNull("minFeeA", pp.getMinFeeA());
            requireNonNull("minFeeB", pp.getMinFeeB());
            requireNonNull("maxTxSize", pp.getMaxTxSize());
            requireNonNull("coinsPerUtxoSize", pp.getCoinsPerUtxoSize());
            requireNonNull("priceMem", pp.getPriceMem());
            requireNonNull("priceStep", pp.getPriceStep());
            return "minFeeA=" + pp.getMinFeeA() + " maxTxSize=" + pp.getMaxTxSize()
                    + " coinsPerUtxoSize=" + pp.getCoinsPerUtxoSize();
        });

        Account sender = new Account(Networks.testnet());
        Account receiver = new Account(Networks.testnet());
        System.out.println("\n  sender address: " + sender.baseAddress().substring(0, 32) + "...\n");

        step("devnet faucet funds the CCL account", () -> client.fund(sender.baseAddress(), 5000));

        step("utxoService.getUtxos() on the funded address", () -> {
            List<Utxo> utxos = unwrap(backend.getUtxoService().getUtxos(sender.baseAddress(), 100, 1));
            if (utxos.isEmpty()) {
                throw new IllegalStateException("no utxos returned for a funded address");
            }
            Utxo u = utxos.get(0);
            if (u.getTxHash() == null || u.getAmount() == null || u.getAmount().isEmpty()) {
                throw new IllegalStateException("utxo shape mismatch: " + u);
            }
            return utxos.size() + " utxo(s), first=" + u.getTxHash().substring(0, 12) + "...#" + u.getOutputIndex();
        });

        // Built through QuickTxBuilder with no caller-supplied inputs, so coin
        // selection, fee estimation and protocol-param resolution all run against
        // Yano rather than being short-circuited by the harness.
        Transaction signed = qtx.compose(new Tx()
                        .from(sender.baseAddress())
                        .payToAddress(receiver.baseAddress(), Amount.ada(3)))
                .feePayer(sender.baseAddress())
                .withSigner(SignerProviders.signerFrom(sender))
                .buildAndSign();
        String txHash = TransactionUtil.getTxHash(signed.serialize());

        boolean submitted = step("build + sign + submit a simple payment", () -> {
            YanoClient.SubmitResult r = client.submit(signed.serialize());
            if (!r.accepted()) {
                throw new IllegalStateException("submit rejected: " + r.status() + " " + r.category());
            }
            return txHash;
        }) != null;

        if (submitted) {
            step("submitted payment reaches canonical state", () -> {
                long waited = awaitCanonical(client, txHash, 90_000);
                if (waited < 0) {
                    throw new IllegalStateException("not confirmed within 90s");
                }
                return "confirmed in " + waited + " ms";
            });

            step("transactionService.getTransaction() on the confirmed tx", () -> {
                TransactionContent info = unwrap(backend.getTransactionService().getTransaction(txHash));
                if (info.getHash() == null) {
                    throw new IllegalStateException("no tx info");
                }
                return "block=" + info.getBlock() + " fees=" + info.getFees();
            });

            step("utxoService.getTxOutput() by tx hash", () -> {
                Utxo out = unwrap(backend.getUtxoService().getTxOutput(txHash, 0));
                if (out == null || out.getAmount() == null) {
                    throw new IllegalStateException("no output 0 for " + txHash);
                }
                return out.getAmount().toString();
            });
        }

        System.out.println("\n=== summary ===");
        long passed = RESULTS.stream().filter(StepResult::ok).count();
        System.out.println(passed + "/" + RESULTS.size() + " checks passed");
        RESULTS.stream().filter(r -> !r.ok())
                .forEach(r -> System.out.println("  FAILED: " + r.name() + " :: " + r.detail()));

        boolean pass = RESULTS.stream().allMatch(StepResult::ok);
        System.out.println("\nRESULT: " + (pass ? "PASS" : "FAIL"));
        System.exit(pass ? 0 : 1);
    }

    /** Runs one named check, recording pass/fail rather than aborting the probe. */
    private static String step(String name, Callable<String> body) {
        long t0 = System.currentTimeMillis();
        try {
            String detail = body.call();
            RESULTS.add(new StepResult(name, true, System.currentTimeMillis() - t0, detail == null ? "" : detail));
            System.out.println("  PASS  " + name + (detail == null || detail.isEmpty() ? "" : " - " + detail));
            return detail == null ? "" : detail;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
            if (msg.length() > 150) {
                msg = msg.substring(0, 150);
            }
            RESULTS.add(new StepResult(name, false, System.currentTimeMillis() - t0, msg));
            System.out.println("  FAIL  " + name + " - " + msg);
            return null;
        }
    }

    private static <T> T unwrap(Result<T> result) {
        if (result == null || !result.isSuccessful()) {
            throw new IllegalStateException("backend call failed: "
                    + (result == null ? "null result" : result.getResponse()));
        }
        return result.getValue();
    }

    private static void requireNonNull(String field, Object value) {
        if (value == null) {
            throw new IllegalStateException("missing protocol parameter: " + field);
        }
    }

    /**
     * Polls canonical state for output 0 of {@code txHash}. Returns -1 on timeout.
     *
     * <p>{@link YanoClient#utxoAt} throws on a 404 rather than returning null — for an
     * outpoint that is not yet canonical that is the normal case, not an error, so the
     * exception is what we poll on.</p>
     */
    private static long awaitCanonical(YanoClient client, String txHash, long timeoutMs) throws InterruptedException {
        long started = System.currentTimeMillis();
        while (System.currentTimeMillis() - started < timeoutMs) {
            try {
                if (client.utxoAt(txHash, 0) != null) {
                    return System.currentTimeMillis() - started;
                }
            } catch (RuntimeException notYetVisible) {
                // 404 until the transaction is in a block.
            }
            Thread.sleep(300);
        }
        return -1;
    }

    private CompatProbe() {}
}
