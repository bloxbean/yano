package com.bloxbean.cardano.yano.testkit.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Lightweight AdaPot comparison helpers for external validation runs.
 */
public final class YanoAdaPotComparator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private YanoAdaPotComparator() {
    }

    /**
     * AdaPot values returned by Yano or reference tooling.
     */
    public record AdaPotSnapshot(int epoch,
                                 BigInteger treasury,
                                 BigInteger reserves,
                                 BigInteger deposits,
                                 BigInteger fees,
                                 BigInteger distributedRewards,
                                 BigInteger undistributedRewards,
                                 BigInteger rewardsPot,
                                 BigInteger poolRewardsPot) {
        public AdaPotSnapshot {
            treasury = zeroIfNull(treasury);
            reserves = zeroIfNull(reserves);
            deposits = zeroIfNull(deposits);
            fees = zeroIfNull(fees);
            distributedRewards = zeroIfNull(distributedRewards);
            undistributedRewards = zeroIfNull(undistributedRewards);
            rewardsPot = zeroIfNull(rewardsPot);
            poolRewardsPot = zeroIfNull(poolRewardsPot);
        }

        /**
         * Creates a reference snapshot with treasury and reserves only.
         *
         * @param epoch epoch
         * @param treasury treasury amount
         * @param reserves reserves amount
         * @return snapshot
         */
        public static AdaPotSnapshot treasuryReserves(int epoch, BigInteger treasury, BigInteger reserves) {
            return new AdaPotSnapshot(epoch, treasury, reserves,
                    BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                    BigInteger.ZERO, BigInteger.ZERO);
        }
    }

    /**
     * AdaPot mismatch for treasury/reserves comparison.
     */
    public record AdaPotMismatch(int epoch,
                                 BigInteger expectedTreasury,
                                 BigInteger actualTreasury,
                                 BigInteger expectedReserves,
                                 BigInteger actualReserves) {
    }

    /**
     * Treasury/reserves comparison result.
     */
    public record AdaPotComparisonResult(int checked, List<AdaPotMismatch> mismatches) {
        public AdaPotComparisonResult {
            mismatches = List.copyOf(mismatches);
        }

        /**
         * Whether all checked epochs match.
         *
         * @return true when there are no mismatches
         */
        public boolean matches() {
            return mismatches.isEmpty();
        }
    }

    /**
     * Fetches Yano AdaPot snapshots from {@code /epochs/{epoch}/adapot}.
     *
     * @param apiBaseUrl Yano {@code /api/v1/} base URL
     * @param fromEpoch first epoch, inclusive
     * @param toEpoch last epoch, inclusive
     * @return snapshots keyed by epoch
     * @throws IOException if HTTP or JSON parsing fails
     * @throws InterruptedException if interrupted
     */
    public static Map<Integer, AdaPotSnapshot> fetchYanoAdaPots(URI apiBaseUrl,
                                                                 int fromEpoch,
                                                                 int toEpoch)
            throws IOException, InterruptedException {
        validateRange(fromEpoch, toEpoch);
        URI base = ensureTrailingSlash(Objects.requireNonNull(apiBaseUrl, "apiBaseUrl"));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Map<Integer, AdaPotSnapshot> snapshots = new LinkedHashMap<>();

        for (int epoch = fromEpoch; epoch <= toEpoch; epoch++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(base.resolve("epochs/" + epoch + "/adapot"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("GET " + request.uri() + " failed: "
                        + response.statusCode() + " " + response.body());
            }
            snapshots.put(epoch, fromJson(MAPPER.readTree(response.body())));
        }

        return snapshots;
    }

    /**
     * Reads reference AdaPot data in {@code epoch|treasury|reserves} or
     * {@code epoch,treasury,reserves} format.
     *
     * @param file reference file
     * @return snapshots keyed by epoch
     * @throws IOException if the file cannot be read
     */
    public static Map<Integer, AdaPotSnapshot> readTreasuryReserveReference(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Map<Integer, AdaPotSnapshot> snapshots = new TreeMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(file)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.contains("|") ? trimmed.split("\\|") : trimmed.split(",");
            if (parts.length < 3) {
                throw new IOException("Invalid AdaPot reference line " + lineNumber
                        + " in " + file + ": " + line);
            }
            int epoch;
            BigInteger treasury;
            BigInteger reserves;
            try {
                epoch = Integer.parseInt(parts[0].trim());
                treasury = new BigInteger(parts[1].trim());
                reserves = new BigInteger(parts[2].trim());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid AdaPot reference number on line " + lineNumber
                        + " in " + file + ": " + line, e);
            }
            snapshots.put(epoch, AdaPotSnapshot.treasuryReserves(epoch, treasury, reserves));
        }
        return snapshots;
    }

    /**
     * Compares actual Yano snapshots against reference treasury/reserves values.
     *
     * @param actual actual Yano snapshots
     * @param reference reference snapshots
     * @return comparison result
     */
    public static AdaPotComparisonResult compareTreasuryAndReserves(
            Map<Integer, AdaPotSnapshot> actual,
            Map<Integer, AdaPotSnapshot> reference) {
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(reference, "reference");

        List<AdaPotMismatch> mismatches = new ArrayList<>();
        for (var entry : new TreeMap<>(reference).entrySet()) {
            int epoch = entry.getKey();
            AdaPotSnapshot expected = entry.getValue();
            AdaPotSnapshot observed = actual.get(epoch);
            if (observed == null) {
                mismatches.add(new AdaPotMismatch(epoch, expected.treasury(), null,
                        expected.reserves(), null));
                continue;
            }
            if (!expected.treasury().equals(observed.treasury())
                    || !expected.reserves().equals(observed.reserves())) {
                mismatches.add(new AdaPotMismatch(epoch, expected.treasury(), observed.treasury(),
                        expected.reserves(), observed.reserves()));
            }
        }
        return new AdaPotComparisonResult(reference.size(), mismatches);
    }

    /**
     * Asserts actual Yano snapshots match reference treasury/reserves values.
     *
     * @param actual actual Yano snapshots
     * @param reference reference snapshots
     */
    public static void assertTreasuryAndReservesMatch(Map<Integer, AdaPotSnapshot> actual,
                                                       Map<Integer, AdaPotSnapshot> reference) {
        AdaPotComparisonResult result = compareTreasuryAndReserves(actual, reference);
        if (result.matches()) {
            return;
        }

        StringBuilder message = new StringBuilder("AdaPot treasury/reserves mismatch: ")
                .append(result.mismatches().size())
                .append(" mismatches across ")
                .append(result.checked())
                .append(" checked epochs");
        result.mismatches().stream().limit(5).forEach(mismatch ->
                message.append("\n  epoch ")
                        .append(mismatch.epoch())
                        .append(": expected treasury=")
                        .append(mismatch.expectedTreasury())
                        .append(", actual treasury=")
                        .append(mismatch.actualTreasury())
                        .append(", expected reserves=")
                        .append(mismatch.expectedReserves())
                        .append(", actual reserves=")
                        .append(mismatch.actualReserves()));
        throw new AssertionError(message.toString());
    }

    static AdaPotSnapshot fromJson(JsonNode json) {
        return new AdaPotSnapshot(
                requiredInt(json, "epoch"),
                bigInteger(json, "treasury"),
                bigInteger(json, "reserves"),
                bigInteger(json, "deposits"),
                bigInteger(json, "fees"),
                bigInteger(json, "distributed_rewards"),
                bigInteger(json, "undistributed_rewards"),
                bigInteger(json, "rewards_pot"),
                bigInteger(json, "pool_rewards_pot")
        );
    }

    private static void validateRange(int fromEpoch, int toEpoch) {
        if (fromEpoch < 0) {
            throw new IllegalArgumentException("fromEpoch must be greater than or equal to 0");
        }
        if (toEpoch < fromEpoch) {
            throw new IllegalArgumentException("toEpoch must be greater than or equal to fromEpoch");
        }
    }

    private static URI ensureTrailingSlash(URI uri) {
        String raw = uri.toString();
        return raw.endsWith("/") ? uri : URI.create(raw + "/");
    }

    private static int requiredInt(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Missing AdaPot JSON field: " + field);
        }
        return node.asInt();
    }

    private static BigInteger bigInteger(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) {
            return BigInteger.ZERO;
        }
        return new BigInteger(node.asText());
    }

    private static BigInteger zeroIfNull(BigInteger value) {
        return value != null ? value : BigInteger.ZERO;
    }
}
