package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2ParameterSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable Cardano-shaped L2 parameter snapshot; never valid for L1 fees. */
public record EutxoL2ProtocolParameters(
        String chainId,
        String ledgerProfileDigest,
        String validityProfileDigest,
        String authorizationProfile,
        String authorizationProfileDigest,
        int maxTransactionBytes,
        int maxInputs,
        int maxOutputs,
        String digest
) implements ProtocolParamsSupplier {
    public EutxoL2ProtocolParameters {
        chainId = required(chainId, "chainId");
        ledgerProfileDigest = digest(
                ledgerProfileDigest, "ledgerProfileDigest");
        validityProfileDigest = digest(
                validityProfileDigest, "validityProfileDigest");
        authorizationProfile = required(
                authorizationProfile, "authorizationProfile");
        authorizationProfileDigest = digest(
                authorizationProfileDigest, "authorizationProfileDigest");
        if (maxTransactionBytes < 1 || maxInputs < 1 || maxOutputs < 1) {
            throw new IllegalArgumentException(
                    "L2 parameter bounds must be positive");
        }
        String computed = computeDigest(
                chainId, ledgerProfileDigest, validityProfileDigest,
                authorizationProfile,
                authorizationProfileDigest, maxTransactionBytes,
                maxInputs, maxOutputs);
        if (digest == null || digest.isBlank()) {
            digest = computed;
        } else if (!computed.equals(digest)) {
            throw new IllegalArgumentException(
                    "L2 parameter digest mismatch");
        }
    }

    public static EutxoL2ProtocolParameters create(
            String chainId,
            EutxoProfile profile,
            String validityProfileDigest,
            String authorizationProfile,
            String authorizationProfileDigest
    ) {
        Objects.requireNonNull(profile, "profile");
        return new EutxoL2ProtocolParameters(
                chainId,
                profile.digestHex(),
                validityProfileDigest,
                authorizationProfile,
                authorizationProfileDigest,
                profile.maxTransactionBytes(),
                profile.maxInputs(),
                profile.maxOutputs(),
                "");
    }

    public static EutxoL2ProtocolParameters from(
            EutxoL2ParameterSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new EutxoL2ProtocolParameters(
                snapshot.chainId(),
                snapshot.ledgerProfileDigest(),
                snapshot.validityProfileDigest(),
                snapshot.authorizationProfile(),
                snapshot.authorizationProfileDigest(),
                snapshot.maxTransactionBytes(),
                snapshot.maxInputs(),
                snapshot.maxOutputs(),
                snapshot.digest());
    }

    @Override
    public ProtocolParams getProtocolParams() {
        return ProtocolParams.builder()
                .minFeeA(0)
                .minFeeB(0)
                .maxTxSize(maxTransactionBytes)
                .maxBlockSize(Math.multiplyExact(maxTransactionBytes, 16))
                .maxBlockHeaderSize(0)
                .keyDeposit("0")
                .poolDeposit("0")
                .minUtxo("0")
                .minPoolCost("0")
                .coinsPerUtxoSize("0")
                .collateralPercent(java.math.BigDecimal.ZERO)
                .maxCollateralInputs(0)
                .protocolMajorVer(9)
                .protocolMinorVer(0)
                .build();
    }

    private static String computeDigest(
            String chainId,
            String ledger,
            String validity,
            String authorizationProfile,
            String authorization,
            int maxBytes,
            int maxInputs,
            int maxOutputs
    ) {
        String canonical = String.join("\n",
                "yano:eutxo:l2-parameters:v1",
                chainId,
                ledger,
                validity,
                authorizationProfile,
                authorization,
                "minFeeA=0",
                "minFeeB=0",
                "maxTransactionBytes=" + maxBytes,
                "maxInputs=" + maxInputs,
                "maxOutputs=" + maxOutputs,
                "coinsPerUtxoByte=0",
                "collateral=0");
        return HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(
                canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String required(String value, String label) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > 63) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static String digest(String value, String label) {
        value = Objects.requireNonNull(value, label);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    label + " must be lowercase 32-byte hex");
        }
        return value;
    }
}
