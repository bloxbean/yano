package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowEd25519;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/** Reproducible local measurement for the ADR-025.2 consensus crypto-work fence. */
public final class AuthenticatedMapCryptoWorkBenchmark {
    private static final byte[] SEED = HexFormat.of().parseHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    private AuthenticatedMapCryptoWorkBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("expected report path and iteration count");
        }
        int iterations = Integer.parseInt(arguments[1]);
        if (iterations < 10_000) {
            throw new IllegalArgumentException("benchmark needs at least 10000 iterations");
        }
        byte[] message = "adr-025.2-dual-verification-work-unit"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(SEED);
        byte[] signature = RoleWorkflowEd25519.sign(message, SEED);
        for (int index = 0; index < 10_000; index++) {
            if (!RoleWorkflowEd25519.verify(signature, message, publicKey)) {
                throw new IllegalStateException("verification failed during warmup");
            }
        }
        long started = System.nanoTime();
        for (int index = 0; index < iterations; index++) {
            if (!RoleWorkflowEd25519.verify(signature, message, publicKey)) {
                throw new IllegalStateException("verification failed during measurement");
            }
        }
        long elapsed = System.nanoTime() - started;
        double nanosecondsPerUnit = (double) elapsed / iterations;
        int cap = RoleWorkflowLimits.MAX_CRYPTO_WORK_UNITS_PER_BLOCK;
        double cappedMilliseconds = nanosecondsPerUnit * cap / 1_000_000.0;
        double defaultBlockFraction = cappedMilliseconds
                / AppChainConfig.DEFAULT_BLOCK_INTERVAL_MS;
        String report = """
                {
                  "schemaVersion": 1,
                  "runtime": "%s",
                  "iterations": %d,
                  "dualVerificationNanosecondsPerWorkUnit": %.3f,
                  "maximumGovernedCryptoWorkUnitsPerBlock": %d,
                  "estimatedCappedGovernedCryptoMilliseconds": %.3f,
                  "defaultBlockIntervalMilliseconds": %d,
                  "estimatedDefaultBlockIntervalFraction": %.6f,
                  "defaultMaximumBlockMessages": %d,
                  "defaultMaximumBlockBytes": %d
                }
                """.formatted(
                System.getProperty("java.runtime.version"), iterations,
                nanosecondsPerUnit, cap, cappedMilliseconds,
                AppChainConfig.DEFAULT_BLOCK_INTERVAL_MS, defaultBlockFraction,
                AppChainConfig.DEFAULT_MAX_BLOCK_MESSAGES,
                AppChainConfig.DEFAULT_BLOCK_MAX_BYTES);
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.print(report);
    }
}
