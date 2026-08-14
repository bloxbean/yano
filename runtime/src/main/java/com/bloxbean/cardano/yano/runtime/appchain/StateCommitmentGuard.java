package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Retained-state guard for the genesis-selected ADR-025 commitment identity. */
final class StateCommitmentGuard {
    private final StateCommitmentIdentity identity;

    StateCommitmentGuard(StateCommitmentIdentity identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    void verifyRetained(AppLedgerStore ledger, String chainId) {
        Optional<byte[]> observed = ledger.stateGet(StateCommitmentIdentity.markerKey());
        if (ledger.tipHeight() == 0) {
            if (observed.isPresent()) {
                throw incompatible(chainId, 0, "marker exists before height 1");
            }
            return;
        }
        if (observed.isEmpty() || !Arrays.equals(identity.canonicalBytes(), observed.orElseThrow())) {
            throw incompatible(chainId, ledger.tipHeight(), "marker is absent or mismatched");
        }
    }

    void apply(long height, AppStateWriter writer) {
        byte[] key = StateCommitmentIdentity.markerKey();
        byte[] observed = writer.get(key).orElse(null);
        if (height == 1) {
            if (observed != null) {
                throw new IllegalStateException("state commitment marker exists before height-1 initialization");
            }
            writer.put(key, identity.canonicalBytes());
            return;
        }
        if (height < 1) {
            throw new IllegalArgumentException("app block height must be positive");
        }
        if (!Arrays.equals(identity.canonicalBytes(), observed)) {
            throw new IllegalStateException("state commitment marker is absent or mismatched at height "
                    + height + " (expected " + HexUtil.encodeHexString(identity.digest()) + ")");
        }
    }

    private IllegalStateException incompatible(String chainId, long height, String reason) {
        return new IllegalStateException("App-chain '" + chainId
                + "' retained state commitment is incompatible at tip " + height + ": " + reason
                + " (configured profile=" + identity.profile().id() + "). Start with a compatible ledger.");
    }
}
