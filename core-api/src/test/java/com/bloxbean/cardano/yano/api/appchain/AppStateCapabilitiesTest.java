package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotPlanCollector;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSeriesDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSeriesHandle;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppStateCapabilitiesTest {
    @Test
    void componentScopeCannotResolveAnotherComponentsSeries() {
        var collector = new AuthenticatedSnapshotPlanCollector(10, 1000);
        var orders = descriptor("orders.daily");
        var documents = descriptor("documents.daily");
        AppStateCapabilities root = AppStateCapabilities.enabled(Map.of(
                orders.seriesId(), new SnapshotSeriesHandle(orders, collector),
                documents.seriesId(), new SnapshotSeriesHandle(documents, collector)), collector);

        AppStateCapabilities scoped = root.scope("orders");
        assertThat(scoped.snapshotSeries("daily")).isPresent();
        assertThat(scoped.snapshotSeries("documents.daily")).isEmpty();
    }

    @Test
    void disabledRegistryIsStableAndEmpty() {
        assertThat(AppStateCapabilities.empty()).isSameAs(AppStateCapabilities.empty());
        assertThat(AppStateCapabilities.empty().authenticatedSnapshotsEnabled()).isFalse();
        assertThat(AppStateCapabilities.empty().scope("orders"))
                .isSameAs(AppStateCapabilities.empty());
    }

    private static AuthenticatedSnapshotSeriesDescriptorV1 descriptor(String id) {
        return new AuthenticatedSnapshotSeriesDescriptorV1(id, "schema-v1",
                AuthenticatedSnapshotSeriesDescriptorV1.Trigger.APPLICATION_MESSAGE,
                StateCommitmentProfiles.MPF.id(), StateCommitmentProfiles.MPF.formatFingerprint(),
                StateCommitmentProfiles.MPF.proofEncodingId(),
                AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.ON_CHAIN,
                AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC,
                "blake2b256", "source-v1", 10, 1000, 256, 8192, 100,
                AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET);
    }
}
