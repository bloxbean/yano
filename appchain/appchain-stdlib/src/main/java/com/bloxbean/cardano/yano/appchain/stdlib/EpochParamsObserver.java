package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.l1view.EpochObservationManifest;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObservationSink;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochState;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;

import java.util.Map;

/** Deterministic one-record protocol-parameter observer. */
public final class EpochParamsObserver implements L1EpochObserver {
    private final String observerId;

    public EpochParamsObserver(String observerId) {
        if (observerId == null || observerId.isBlank()) {
            throw new IllegalArgumentException("epoch-params observer id is required");
        }
        this.observerId = observerId;
    }

    @Override public String observerId() { return observerId; }

    @Override
    public EpochObservationManifest prepare(L1EpochBoundary boundary, L1EpochState state) {
        byte[] claim = claim(boundary.newEpoch(), state);
        return new EpochObservationManifest(EpochObservationManifest.VERSION, observerId,
                boundary.previousEpoch(), boundary.newEpoch(), boundary.newEpoch(),
                1, 1, 0, Blake2bUtil.blake2bHash256(claim));
    }

    @Override
    public void writeObservations(EpochObservationManifest manifest,
                                  L1EpochState state,
                                  L1EpochObservationSink sink) {
        byte[] claim = claim(manifest.newEpoch(), state);
        if (!java.util.Arrays.equals(Blake2bUtil.blake2bHash256(claim),
                manifest.snapshotRoot())) {
            throw new IllegalStateException("Protocol parameters changed between observer passes");
        }
        sink.write(0, claim);
    }

    @Override public Map<String, Object> status() {
        return Map.of("dataset", "protocol-parameters", "wireVersion", 1);
    }

    private static byte[] claim(long newEpoch, L1EpochState state) {
        var params = state.protocolParams(newEpoch);
        if (params.effectiveEpoch() != newEpoch) {
            throw new IllegalStateException("Protocol parameter view has the wrong effective epoch");
        }
        return EpochParamsContract.encodeClaim(new EpochParamsContract.Claim(
                params.effectiveEpoch(), params.canonicalCbor()));
    }
}
