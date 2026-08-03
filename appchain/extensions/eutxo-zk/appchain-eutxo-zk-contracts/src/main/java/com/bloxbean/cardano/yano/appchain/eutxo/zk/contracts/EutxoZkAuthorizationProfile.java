package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable security identity for an EUTxO validity authorization circuit. */
public record EutxoZkAuthorizationProfile(
        String id,
        String maturity,
        String zerojVersion,
        boolean trustedProverRequired,
        boolean hardenedPointChecks,
        List<String> supportedNetworks,
        String fundsPolicy
) {
    public static final EutxoZkAuthorizationProfile JUBJUB_DEVELOPMENT_V1 =
            new EutxoZkAuthorizationProfile(
                    "zeroj-jubjub-dev-v1",
                    "experimental",
                    "0.1.0-pre10",
                    true,
                    false,
                    List.of("devnet", "preview", "preprod"),
                    "disposable-test-funds-only");

    public EutxoZkAuthorizationProfile {
        id = text(id, "id");
        maturity = text(maturity, "maturity");
        zerojVersion = text(zerojVersion, "zerojVersion");
        supportedNetworks = List.copyOf(Objects.requireNonNull(
                supportedNetworks, "supportedNetworks"));
        if (supportedNetworks.isEmpty()
                || supportedNetworks.stream().anyMatch(
                network -> !List.of("devnet", "preview", "preprod")
                        .contains(network))) {
            throw new IllegalArgumentException(
                    "authorization profile supports test networks only");
        }
        fundsPolicy = text(fundsPolicy, "fundsPolicy");
        if (!trustedProverRequired || hardenedPointChecks
                || !"experimental".equals(maturity)) {
            throw new IllegalArgumentException(
                    "current authorization profile must remain experimental "
                            + "and trusted-prover only");
        }
    }

    public String digestHex() {
        String canonical = id + '\n' + maturity + '\n' + zerojVersion + '\n'
                + trustedProverRequired + '\n' + hardenedPointChecks + '\n'
                + String.join(",", supportedNetworks) + '\n' + fundsPolicy;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String text(String value, String label) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > 96) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }
}
