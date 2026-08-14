package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSeriesDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedSnapshotSettingsTest {

    @Test
    void capabilityIdentityBindsSelectedDeclarationsButNotArchiveLocation() {
        AuthenticatedSnapshotSettings first = settings(Path.of("archive-a"));
        AuthenticatedSnapshotSettings second = settings(Path.of("archive-b"));
        var declaration = declaration("daily", StateCommitmentProfiles.MPF.id());
        byte[] firstDigest = first.capabilityIdentityDigest(List.of(declaration));
        assertThat(second.capabilityIdentityDigest(List.of(declaration))).isEqualTo(firstDigest);
        assertThat(first.capabilityIdentityDigest(List.of(declaration(
                "weekly", StateCommitmentProfiles.MPF.id())))).isNotEqualTo(firstDigest);
    }

    @Test
    void selectedSeriesAndOnchainProfileConstraintsFailClosed() {
        var settings = settings(Path.of("archive"));
        assertThat(settings.select(List.of(declaration("daily", StateCommitmentProfiles.MPF.id())),
                StateCommitmentProfiles.MPF, true)).extracting(
                        AuthenticatedSnapshotSeriesDescriptorV1::seriesId).containsExactly("daily");
        assertThatThrownBy(() -> settings.select(
                List.of(declaration("daily", StateCommitmentProfiles.MPF.id())),
                StateCommitmentProfiles.CLASSIC_JMT, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires MPF primary");
    }

    private static AuthenticatedSnapshotSettings settings(Path archive) {
        return new AuthenticatedSnapshotSettings(true, Set.of("daily"), 100, 4096,
                repeated(7), 32, false, 10, true, 300, false,
                archive, 1_000, 10_000_000);
    }

    private static AuthenticatedSnapshotSeriesDescriptorV1 declaration(String id, String profileId) {
        var profile = StateCommitmentProfiles.require(profileId);
        return new AuthenticatedSnapshotSeriesDescriptorV1(id, "balances-v1",
                AuthenticatedSnapshotSeriesDescriptorV1.Trigger.APPLICATION_MESSAGE,
                profile.id(), profile.formatFingerprint(), profile.proofEncodingId(),
                profile.equals(StateCommitmentProfiles.MPF)
                        ? AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.ON_CHAIN
                        : AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.OFF_CHAIN,
                AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC,
                "blake2b256", "balances-source-v1", 10, 4096, 256, 8192, 100,
                AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET);
    }

    private static byte[] repeated(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
