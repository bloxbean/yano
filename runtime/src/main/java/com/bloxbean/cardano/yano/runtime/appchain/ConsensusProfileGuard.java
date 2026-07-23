package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfileCommitment;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Runtime-owned ADR-016 retained-state and per-transition profile guard. */
final class ConsensusProfileGuard {
    private final AppChainConsensusProfile profile;
    private final byte[] canonicalBytes;
    private final String digestHex;

    ConsensusProfileGuard(AppChainConsensusProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.canonicalBytes = AppChainConsensusProfileCommitment.encode(profile);
        this.digestHex = HexUtil.encodeHexString(
                AppChainConsensusProfileCommitment.digest(canonicalBytes));
    }

    AppChainConsensusProfile profile() {
        return profile;
    }

    String digestHex() {
        return digestHex;
    }

    void verifyRetained(AppLedgerStore ledger, String chainId) {
        long tip = ledger.tipHeight();
        Optional<byte[]> observed = ledger.stateGet(
                AppChainConsensusProfileCommitment.markerKey());
        if (tip == 0) {
            if (observed.isPresent()) {
                throw incompatible(chainId, tip,
                        "marker exists before app height 1", observed.get());
            }
            return;
        }
        if (observed.isEmpty()) {
            throw incompatible(chainId, tip,
                    "marker is absent from retained state", null);
        }
        if (!Arrays.equals(canonicalBytes, observed.get())) {
            throw incompatible(chainId, tip,
                    "marker does not match effective local profile", observed.get());
        }
    }

    void apply(long height, MpfTrie trie) {
        byte[] key = AppChainConsensusProfileCommitment.markerKey();
        byte[] observed = trie.get(key);
        if (height == 1) {
            if (observed != null) {
                throw new IllegalStateException(
                        "consensus profile marker exists before height-1 initialization");
            }
            trie.put(key, canonicalBytes);
            return;
        }
        if (height < 1) {
            throw new IllegalArgumentException("app block height must be positive");
        }
        if (!Arrays.equals(canonicalBytes, observed)) {
            throw new IllegalStateException("consensus profile marker is absent or mismatched at height "
                    + height + " (expected " + digestHex + ")");
        }
    }

    private IllegalStateException incompatible(
            String chainId,
            long tip,
            String reason,
            byte[] observed
    ) {
        String observedIdentity = "absent";
        if (observed != null) {
            try {
                observedIdentity = HexUtil.encodeHexString(
                        AppChainConsensusProfileCommitment.digest(observed));
            } catch (IllegalArgumentException malformed) {
                observedIdentity = "malformed(" + observed.length + " bytes)";
            }
        }
        return new IllegalStateException("App-chain '" + chainId
                + "' retained consensus profile is incompatible at tip " + tip
                + ": " + reason + " (expected=" + digestHex
                + ", observed=" + observedIdentity + "). Start with a fresh preview ledger.");
    }
}
