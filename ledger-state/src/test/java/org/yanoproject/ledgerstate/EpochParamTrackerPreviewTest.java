package org.yanoproject.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Update;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.era.EraProvider;
import org.yanoproject.api.model.ProtocolParamsSnapshot;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EpochParamTracker#previewEpochParams} (ADR-056 ticked parameters) equals what the real
 * boundary path stores — {@link EpochParamTracker#finalizeEpoch} then
 * {@link EpochParamTracker#applyEnactedParamChange} for each enacted update — and mutates nothing.
 */
class EpochParamTrackerPreviewTest {

    private static final EpochParamProvider BASE = new EpochParamProvider() {
        @Override
        public BigInteger getKeyDeposit(long epoch) {
            return BigInteger.valueOf(2_000_000);
        }

        @Override
        public BigInteger getPoolDeposit(long epoch) {
            return BigInteger.valueOf(500_000_000);
        }

        @Override
        public Integer getMaxTxSize(long epoch) {
            return 16_384;
        }

        @Override
        public Map<String, Object> getCostModels(long epoch) {
            return Map.of("PlutusV1", List.of(1, 2, 3), "PlutusV2", List.of(4, 5));
        }

        @Override
        public Map<String, Object> getConwayCostModels(long epoch) {
            return Map.of("PlutusV3", List.of(7, 8, 9));
        }

        @Override
        public int getProtocolMajor(long epoch) {
            return 10;
        }
    };

    @Test
    void previewEqualsFinalizeThenEnactmentsAndMutatesNothing() {
        EpochParamTracker tracker = new EpochParamTracker(BASE, true);
        tracker.finalizeEpoch(5);
        // Pre-Conway style Update proposed in epoch 5, effective at 6.
        tracker.processTransaction(TransactionBody.builder()
                .update(new Update(Map.of("g1", ProtocolParamUpdate.builder().minFeeA(77).build()), 5))
                .build(), 100, 0, null);
        List<ProtocolParamUpdate> enacted = List.of(
                ProtocolParamUpdate.builder().maxTxSize(20_000).minFeeA(88).build(),
                ProtocolParamUpdate.builder().protocolMajorVer(11).protocolMinorVer(0).build());

        EpochParamTracker.PreviewBase base = tracker.capturePreviewBase(6);
        ProtocolParamUpdate preview = tracker.previewEpochParams(base, enacted);
        Map<String, Object> costModelsBefore = tracker.getCostModels(5);

        // Nothing changed: epoch 6 is not finalized and its pending update is still there.
        assertThat(tracker.getResolvedParams(6)).isNull();
        assertThat(tracker.getCostModels(5)).isEqualTo(costModelsBefore);

        tracker.finalizeEpoch(6);
        assertThat(tracker.getResolvedParams(6).getMinFeeA()).as("pending update consumed by the real path")
                .isEqualTo(77);
        for (ProtocolParamUpdate update : enacted) {
            tracker.applyEnactedParamChange(6, update);
        }

        assertThat(preview).isEqualTo(tracker.getResolvedParams(6));
        assertThat(preview.getMinFeeA()).isEqualTo(88);
        assertThat(preview.getMaxTxSize()).isEqualTo(20_000);
        assertThat(preview.getProtocolMajorVer()).isEqualTo(11);
        ProtocolParamsSnapshot previewed = DefaultAccountStateStore.protocolParamsSnapshot(
                tracker.previewView(base, preview), BASE, 6).orElseThrow();
        assertThat(previewed).isEqualTo(DefaultAccountStateStore.protocolParamsSnapshot(tracker, BASE, 6).orElseThrow());
        assertThat(previewed.costModels()).containsKeys("PlutusV1", "PlutusV2", "PlutusV3");
    }

    @Test
    void previewAppliesTheEraTransitionOverlay() {
        EraProvider conwayAt3 = new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue >= Era.Conway.getValue() ? 3 : 0;
            }
        };
        EpochParamTracker tracker = new EpochParamTracker(BASE, true);
        tracker.setEraProvider(conwayAt3);
        tracker.finalizeEpoch(2);
        assertThat(tracker.getCostModels(2)).doesNotContainKey("PlutusV3");

        EpochParamTracker.PreviewBase base = tracker.capturePreviewBase(3);
        ProtocolParamUpdate preview = tracker.previewEpochParams(base, List.of());
        tracker.finalizeEpoch(3);

        assertThat(preview).isEqualTo(tracker.getResolvedParams(3));
        assertThat(DefaultAccountStateStore.protocolParamsSnapshot(tracker.previewView(base, preview), BASE, 3))
                .isEqualTo(DefaultAccountStateStore.protocolParamsSnapshot(tracker, BASE, 3));
        assertThat(tracker.previewView(base, preview).getCostModels(3)).containsKey("PlutusV3");
    }

    @Test
    void disabledTrackerPreviewsNothing() {
        EpochParamTracker tracker = new EpochParamTracker(BASE, false);
        assertThat(tracker.previewEpochParams(tracker.capturePreviewBase(4), List.of())).isNull();
    }
}
