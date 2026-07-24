package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentEngine;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;

import java.util.LinkedHashMap;
import java.util.Map;

/** Service-loaded provider for the optional ZeroJ Poseidon commitment. */
public final class ZerojPoseidonValidityProvider
        implements EutxoValidityCommitmentProvider {
    public static final String ID = "zeroj-poseidon-v1";
    public static final String TRANSACTION_FORMAT =
            "yano-eutxo-l2-envelope-v1";
    public static final String ZEROJ_VERSION = "0.1.0-pre10";
    public static final String JULC_VERSION = "0.1.0-pre14";
    private static final String PREFIX = "machines.eutxo.validity.";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EutxoValidityCommitmentEngine create(
            String chainId,
            EutxoProfile profile,
            Map<String, String> settings
    ) {
        if (!EutxoProfile.V1.equals(profile)) {
            throw new IllegalArgumentException(
                    "the Z0 validity profile supports key-controlled EUTxO v1 only");
        }
        String bridgeObserver =
                settings.get("machines.eutxo.bridge.observer-id");
        if (bridgeObserver != null && !bridgeObserver.isBlank()) {
            throw new IllegalArgumentException(
                    "the Z0 validity profile does not cover bridge transitions");
        }
        if (!settings.isEmpty()) {
            requiredIdentitySettings().forEach((key, expected) -> {
                if (!expected.equals(settings.get(key))) {
                    throw new IllegalArgumentException(
                            "EUTxO validity identity mismatch for " + key);
                }
            });
            String network = settings.get("machines.eutxo.network");
            if (network == null || !java.util.Set.of(
                    "devnet", "preview", "preprod").contains(network)) {
                throw new IllegalArgumentException(
                        "EUTxO validity requires an explicit supported test network");
            }
            if (!"devnet".equals(network)
                    && !Boolean.parseBoolean(settings.getOrDefault(
                    PREFIX + "acknowledge-unsafe-jubjub-dev", "false"))) {
                throw new IllegalArgumentException(
                        "Preview/Preprod require explicit acknowledgement of "
                                + "the trusted-prover Jubjub development profile");
            }
        }
        return new ZerojPoseidonValidityEngine(chainId, profile);
    }

    /**
     * Consensus-shared static identities required by the development profile.
     * Deployment-specific ceremony, verification-key, and validator digests
     * are pinned later by the packaged lifecycle.
     */
    public static Map<String, String> requiredIdentitySettings() {
        EutxoZkProfile profile = EutxoZkProfile.Z3_VALIDITY_SETTLEMENT;
        Map<String, String> identities = new LinkedHashMap<>();
        identities.put(PREFIX + "transaction-format", TRANSACTION_FORMAT);
        identities.put(PREFIX + "profile", profile.id());
        identities.put(PREFIX + "expected-profile-digest", profile.digestHex());
        identities.put(PREFIX + "circuit-id", profile.circuitId());
        identities.put(PREFIX + "proof-system", profile.proofSystem());
        identities.put(PREFIX + "curve", profile.curve());
        identities.put(PREFIX + "zeroj-version", ZEROJ_VERSION);
        identities.put(PREFIX + "julc-version", JULC_VERSION);
        EutxoZkAuthorizationProfile authorization =
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        identities.put(PREFIX + "authorization-profile", authorization.id());
        identities.put(PREFIX + "authorization-profile-digest",
                authorization.digestHex());
        identities.put(PREFIX + "authorization-trusted-prover-required",
                Boolean.toString(authorization.trustedProverRequired()));
        identities.put(PREFIX + "authorization-point-checks",
                authorization.hardenedPointChecks()
                        ? "in-circuit-hardened" : "host-only-development");
        identities.put(PREFIX + "funds-policy", authorization.fundsPolicy());
        return Map.copyOf(identities);
    }
}
