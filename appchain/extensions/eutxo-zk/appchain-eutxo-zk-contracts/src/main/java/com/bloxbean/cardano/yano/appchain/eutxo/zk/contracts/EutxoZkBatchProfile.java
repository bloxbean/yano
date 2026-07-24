package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable circuit/artifact identity for one fixed L2 batch bound. */
public record EutxoZkBatchProfile(
        String id,
        int version,
        int maximumTransactions,
        String circuitId,
        String proofSystem,
        String curve,
        String zerojVersion,
        String authorizationProfile,
        String authorizationProfileDigest,
        Status status,
        String digest
) {
    public enum Status {
        MEASURED_DEVELOPMENT_DEFAULT,
        UNMEASURED_CANDIDATE
    }

    public static final EutxoZkBatchProfile CARDANO_PAYMENT_B16 =
            create("cardano-payment-b16", 16,
                    "eutxo-jubjub-batch-dev-b16-v1",
                    Status.MEASURED_DEVELOPMENT_DEFAULT);
    public static final EutxoZkBatchProfile CARDANO_PAYMENT_B32 =
            create("cardano-payment-b32", 32,
                    "eutxo-jubjub-batch-dev-b32-v1",
                    Status.UNMEASURED_CANDIDATE);
    public static final EutxoZkBatchProfile CARDANO_PAYMENT_B64 =
            create("cardano-payment-b64", 64,
                    "eutxo-jubjub-batch-dev-b64-v1",
                    Status.UNMEASURED_CANDIDATE);

    public EutxoZkBatchProfile {
        id = text(id, "id");
        circuitId = text(circuitId, "circuitId");
        proofSystem = text(proofSystem, "proofSystem");
        curve = text(curve, "curve");
        zerojVersion = text(zerojVersion, "zerojVersion");
        authorizationProfile = text(
                authorizationProfile, "authorizationProfile");
        authorizationProfileDigest = digest(
                authorizationProfileDigest, "authorizationProfileDigest");
        Objects.requireNonNull(status, "status");
        if (version < 1 || !List.of(16, 32, 64)
                .contains(maximumTransactions)
                || !"groth16".equals(proofSystem)
                || !"bls12-381".equals(curve)) {
            throw new IllegalArgumentException(
                    "invalid immutable EUTxO batch profile");
        }
        String computed = computeDigest(
                id, version, maximumTransactions, circuitId,
                proofSystem, curve, zerojVersion,
                authorizationProfile, authorizationProfileDigest, status);
        if (digest == null || digest.isBlank()) {
            digest = computed;
        } else if (!computed.equals(digest)) {
            throw new IllegalArgumentException("batch-profile digest mismatch");
        }
    }

    public static List<EutxoZkBatchProfile> values() {
        return List.of(
                CARDANO_PAYMENT_B16,
                CARDANO_PAYMENT_B32,
                CARDANO_PAYMENT_B64);
    }

    private static EutxoZkBatchProfile create(
            String id,
            int maximum,
            String circuitId,
            Status status
    ) {
        var authorization =
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        return new EutxoZkBatchProfile(
                id,
                1,
                maximum,
                circuitId,
                "groth16",
                "bls12-381",
                authorization.zerojVersion(),
                authorization.id(),
                authorization.digestHex(),
                status,
                "");
    }

    private static String computeDigest(
            String id,
            int version,
            int maximum,
            String circuit,
            String proofSystem,
            String curve,
            String zeroj,
            String authorizationProfile,
            String authorizationDigest,
            Status status
    ) {
        String canonical = String.join("\n",
                "yano:eutxo:zk-batch-profile:v1",
                id,
                Integer.toString(version),
                Integer.toString(maximum),
                circuit,
                proofSystem,
                curve,
                zeroj,
                authorizationProfile,
                authorizationDigest,
                status.name());
        return HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(
                canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(String value, String label) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > 96) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static String digest(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    label + " must be lowercase 32-byte hex");
        }
        return value;
    }
}
