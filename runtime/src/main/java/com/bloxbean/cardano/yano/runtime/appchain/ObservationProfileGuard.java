package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationKeys;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Retained-state and per-transition guard for ADR-037's independent profile. */
final class ObservationProfileGuard {
    private final ObservationProfileV1 profile;
    private final byte[] canonicalBytes;
    private final byte[] digest;

    ObservationProfileGuard(ObservationProfileV1 profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.canonicalBytes = profile.encode();
        this.digest = profile.digest();
    }

    byte[] digest() {
        return digest.clone();
    }

    ObservationProfileV1 profile() {
        return profile;
    }

    void verifyRetained(AppLedgerStore ledger, String chainId) {
        long tip = ledger.tipHeight();
        Optional<byte[]> observed = ledger.stateGet(ObservationKeys.profile());
        if (tip == 0) {
            if (observed.isPresent()) {
                throw incompatible(chainId, tip, "marker exists before app height 1", observed.get());
            }
            return;
        }
        if (observed.isEmpty()) {
            throw incompatible(chainId, tip, "marker is absent from retained state", null);
        }
        if (!Arrays.equals(canonicalBytes, observed.get())) {
            throw incompatible(chainId, tip, "marker does not match effective local profile", observed.get());
        }
    }

    void apply(long height, AppStateWriter writer) {
        byte[] key = ObservationKeys.profile();
        byte[] observed = writer.get(key).orElse(null);
        if (height == 1) {
            if (observed != null) {
                throw new IllegalStateException(
                        "observation profile marker exists before height-1 initialization");
            }
            writer.put(key, canonicalBytes);
            return;
        }
        if (height < 1) {
            throw new IllegalArgumentException("app block height must be positive");
        }
        if (!Arrays.equals(canonicalBytes, observed)) {
            throw new IllegalStateException("observation profile marker is absent or mismatched at height "
                    + height + " (expected " + HexUtil.encodeHexString(digest) + ")");
        }
    }

    private IllegalStateException incompatible(String chainId, long tip, String reason,
                                                byte[] observed) {
        String actual = "absent";
        if (observed != null) {
            try {
                actual = HexUtil.encodeHexString(
                        ObservationHashes.profileDigest(ObservationProfileV1.decode(observed)));
            } catch (IllegalArgumentException malformed) {
                actual = "malformed(" + observed.length + " bytes)";
            }
        }
        return new IllegalStateException("App-chain '" + chainId
                + "' retained observation profile is incompatible at tip " + tip
                + ": " + reason + " (expected=" + HexUtil.encodeHexString(digest)
                + ", observed=" + actual + "). Start with a fresh app-chain ledger.");
    }
}
