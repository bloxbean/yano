package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;
import com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EpochParamsStateMachineTest {

    @Test
    void replaysOnlySequencedObservationBytesAcrossMembersRestartAndSnapshot() {
        StateMachineConformance.Result result = StateMachineConformance.builder(
                        new StdlibStateMachineProviders.EpochParamsProvider())
                .settings(Map.of("machines.epoch-params.observer-id", "params-source"))
                .blocks(12)
                .messagesPerBlock(1)
                .runs(3)
                .restartAtHeight(5)
                .snapshotAtHeight(8)
                .messageGenerator((height, index, random) -> {
                    byte[] params = new byte[]{(byte) 0x82, 0x01, (byte) height};
                    byte[] claim = EpochParamsContract.encodeClaim(
                            new EpochParamsContract.Claim(height, params));
                    L1Observation observation = L1Observation.epoch(
                            "params-source", height, 0, new byte[32], claim);
                    return new StateMachineConformance.CorpusMessage(
                            observation.topic(), observation.encode());
                })
                .stateProbe("first-epoch", EpochParamsContract.stateKey(1))
                .stateProbe("latest", EpochParamsContract.latestKey())
                .run();

        assertThat(result.outcomesPerRun()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.outcomesPerRun().getFirst().get(12L).stateValues().get("latest"))
                .isEqualTo(java.util.HexFormat.of().formatHex(
                        EpochParamsContract.encodeEpoch(12)));
        assertThat(result.deterministic()).isTrue();
    }

    @Test
    void claimCodecAndKeysAreCanonical() {
        EpochParamsContract.Claim claim = new EpochParamsContract.Claim(
                42, new byte[]{(byte) 0x81, 0x01});
        assertThat(EpochParamsContract.decodeClaim(
                EpochParamsContract.encodeClaim(claim))).isEqualTo(claim);
        assertThat(new String(EpochParamsContract.stateKey(42),
                java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("params/42");
    }
}
