package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.ui.YanoWalletApplication;
import javafx.application.Application;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletionException;

public final class YanoWalletApp {
    private YanoWalletApp() {
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--native-smoke")) {
            System.out.println("Yano Wallet native smoke OK");
            return;
        }
        if (Arrays.asList(args).contains("--probe-preprod-wallet")) {
            probePreprodWallet(args);
            return;
        }

        YanoWalletApplication.setController(new YanoWalletAppController());
        Application.launch(YanoWalletApplication.class, args);
    }

    private static void probePreprodWallet(String[] args) {
        String mnemonic = System.getenv("YANO_WALLET_TEST_MNEMONIC");
        if (mnemonic == null || mnemonic.isBlank()) {
            throw new IllegalArgumentException("YANO_WALLET_TEST_MNEMONIC is required for the preprod wallet probe");
        }

        try (YanoWalletAppController controller = new YanoWalletAppController()) {
            boolean sync = Arrays.asList(args).contains("--sync")
                    || Boolean.parseBoolean(System.getenv().getOrDefault("YANO_WALLET_PROBE_SYNC", "false"));
            var runtime = (sync
                    ? controller.startPreprodSync(preprodWalletChainstatePath(), resolvePath("config/network/preprod"))
                    : controller.openPreprodChainstate(preprodWalletChainstatePath(), resolvePath("config/network/preprod"))).join();
            var wallet = controller.restorePreprodWallet(mnemonic).join();
            var draft = controller.buildSelfPaymentDraft(BigInteger.valueOf(1_000_000L)).join();

            System.out.println("Runtime: " + runtime.mode());
            System.out.println("Tip slot: " + runtime.tipSlot());
            System.out.println("Tip block: " + runtime.tipBlock());
            System.out.println("Wallet address: " + wallet.address());
            System.out.println("Balance lovelace: " + wallet.lovelace());
            System.out.println("UTXO count: " + wallet.utxoCount());
            System.out.println("Draft tx hash: " + draft.txHash());
            System.out.println("Draft fee lovelace: " + draft.fee());
            System.out.println("Draft status: " + draft.message());
            if (Boolean.parseBoolean(System.getenv().getOrDefault("YANO_WALLET_PROBE_SUBMIT", "false"))) {
                var submit = controller.submitLastDraft().join();
                System.out.println("Submitted tx hash: " + submit.txHash());
                System.out.println("Submit status: " + submit.message());
            }
            int lingerSeconds = Integer.parseInt(System.getenv().getOrDefault("YANO_WALLET_PROBE_LINGER_SECONDS", "0"));
            for (int remaining = lingerSeconds; remaining > 0; remaining -= 10) {
                Thread.sleep(Math.min(10, remaining) * 1000L);
                var refreshed = controller.refreshRuntimeStatus().join();
                System.out.println("Probe sync status: " + refreshed.message()
                        + ", localSlot=" + refreshed.tipSlot()
                        + ", remoteSlot=" + refreshed.remoteTipSlot());
            }
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while probing preprod wallet sync", e);
        }
    }

    private static Path resolvePath(String path) {
        Path direct = Path.of(path);
        if (Files.exists(direct)) {
            return direct;
        }

        Path underNodeApp = Path.of("node-app").resolve(path);
        if (Files.exists(underNodeApp)) {
            return underNodeApp;
        }

        Path siblingNodeApp = Path.of("..", "node-app").resolve(path);
        if (Files.exists(siblingNodeApp)) {
            return siblingNodeApp;
        }

        return direct;
    }

    private static Path preprodWalletChainstatePath() {
        Path walletPath = Path.of(
                System.getProperty("user.home"),
                ".yano-wallet",
                "networks",
                "preprod",
                "yano",
                "chainstate");
        if (Files.exists(walletPath)) {
            return walletPath;
        }
        return resolvePath("chainstate");
    }
}
