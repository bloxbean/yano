package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only operational preflight for the node-local federated settlement plane. */
public final class BridgeDoctor {
    public static final String TRUST_LABEL = "FEDERATED_EXPERIMENTAL";

    private BridgeDoctor() {
    }

    public static Report inspect(Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        List<Check> checks = new ArrayList<>();
        URI endpoint = configuration.signerEndpoint();
        boolean endpointSafe = endpoint != null
                && endpoint.getHost() != null
                && endpoint.getUserInfo() == null
                && endpoint.getFragment() == null
                && ("https".equalsIgnoreCase(endpoint.getScheme())
                || isLoopback(endpoint.getHost()));
        checks.add(new Check(
                "external-signer-endpoint",
                endpointSafe ? Status.PASS : Status.FAIL,
                endpointSafe ? "credential-free HTTPS or loopback endpoint"
                        : "requires a credential-free HTTPS endpoint except on loopback"));

        Path journal = configuration.journalDirectory();
        boolean journalReady = journal != null
                && Files.isDirectory(journal)
                && Files.isReadable(journal)
                && Files.isWritable(journal);
        checks.add(new Check(
                "settlement-journal",
                journalReady ? Status.PASS : Status.FAIL,
                journalReady ? "durable directory is readable and writable"
                        : "durable journal directory is missing or inaccessible"));

        boolean limits = configuration.bridgeEpoch() >= 0
                && configuration.maximumWithdrawalLovelace() != null
                && configuration.maximumWithdrawalLovelace().signum() > 0
                && configuration.maximumPendingWithdrawals() > 0
                && configuration.maximumPendingWithdrawals() <= 100_000;
        checks.add(new Check(
                "consensus-limits",
                limits ? Status.PASS : Status.FAIL,
                limits ? "bridge epoch and withdrawal limits are bounded"
                        : "bridge epoch or withdrawal limits are invalid"));
        checks.add(new Check(
                "withdrawal-intake",
                configuration.withdrawalsPaused() ? Status.WARN : Status.PASS,
                configuration.withdrawalsPaused()
                        ? "new claims are paused; reconciliation may continue"
                        : "new claim intake is enabled"));
        checks.add(new Check(
                "trust-label",
                Status.WARN,
                TRUST_LABEL + ": independent audit and recovery gates remain external"));
        return new Report(List.copyOf(checks));
    }

    public record Configuration(
            URI signerEndpoint,
            Path journalDirectory,
            boolean withdrawalsPaused,
            long bridgeEpoch,
            BigInteger maximumWithdrawalLovelace,
            int maximumPendingWithdrawals
    ) {
    }

    public enum Status {
        PASS,
        WARN,
        FAIL
    }

    public record Check(String id, Status status, String detail) {
        public Check {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("doctor check id is required");
            }
            Objects.requireNonNull(status, "status");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record Report(List<Check> checks) {
        public Report {
            checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        }

        public boolean ready() {
            return checks.stream().noneMatch(check -> check.status() == Status.FAIL);
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }
}
