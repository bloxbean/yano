package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionStartupGuardTest {

    private static final ArchiveNetworkIdentity PREPROD = new ArchiveNetworkIdentity(1, "162d29c4");
    private static final ArchiveNetworkIdentity MAINNET = new ArchiveNetworkIdentity(764824073, "5f20df93");

    private static final Set<ProjectionSectionType> REQUIRED =
            Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY);

    private static ProjectionIdentity identity(ArchiveNetworkIdentity network, String engine) {
        return new ProjectionIdentity(network, engine, 1, REQUIRED);
    }

    private static ProjectionIdentity expected() {
        return identity(PREPROD, "ducklake");
    }

    private static ProjectionCoordinate coordinateAt(long blockNumber) {
        return ProjectionCoordinate.of(new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.SHELLEY_PLUS,
                blockNumber, new byte[]{1}, new byte[]{0}, blockNumber * 20, 3, 1L, 1, List.of(), List.of()));
    }

    private static ProjectionStartupGuard.Observed freshEverything() {
        return new ProjectionStartupGuard.Observed(0, false, Optional.empty(), true, Optional.empty(),
                ProjectionCoordinate.NONE, REQUIRED);
    }

    // --- the supported start ----------------------------------------------------

    @Test
    void genesisChainWithAnEmptyArchiveIsTheSupportedStart() {
        assertThatCode(() -> ProjectionStartupGuard.verify(expected(), freshEverything()))
                .doesNotThrowAnyException();
    }

    @Test
    void anAdvancedChainWithMatchingProjectionIdentityResumesNormally() {
        var observed = new ProjectionStartupGuard.Observed(500_000, true, Optional.of(expected()),
                false, Optional.of(expected()), coordinateAt(497_000), REQUIRED);
        assertThatCode(() -> ProjectionStartupGuard.verify(expected(), observed)).doesNotThrowAnyException();
    }

    // --- mid-chain activation ---------------------------------------------------

    @Test
    void enablingHistoryMidChainIsRejected() {
        var observed = new ProjectionStartupGuard.Observed(1_234_567, false, Optional.empty(),
                true, Optional.empty(), ProjectionCoordinate.NONE, REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("cannot be enabled mid-chain")
                .hasMessageContaining("1234567");
    }

    @Test
    void anExistingChainstatePointedAtAnEmptyArchiveIsRejected() {
        var observed = new ProjectionStartupGuard.Observed(900_000, false, Optional.empty(),
                true, Optional.empty(), ProjectionCoordinate.NONE, REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class);
    }

    @Test
    void aFreshChainstatePointedAtAPopulatedArchiveIsRejected() {
        var observed = new ProjectionStartupGuard.Observed(0, false, Optional.empty(),
                false, Optional.of(expected()), coordinateAt(400_000), REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("cannot adopt an archive that already covers");
    }

    // --- identity mismatches ----------------------------------------------------

    @Test
    void aWrongNetworkArchiveIsRejected() {
        var observed = new ProjectionStartupGuard.Observed(100, true, Optional.of(expected()),
                false, Optional.of(identity(MAINNET, "ducklake")), coordinateAt(50), REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("archive identity mismatch");
    }

    @Test
    void aWrongGenesisArchiveIsRejectedEvenOnTheSameMagic() {
        var otherGenesis = identity(new ArchiveNetworkIdentity(1, "deadbeef"), "ducklake");
        var observed = new ProjectionStartupGuard.Observed(100, true, Optional.of(expected()),
                false, Optional.of(otherGenesis), coordinateAt(50), REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("archive identity mismatch");
    }

    @Test
    void aMismatchedSinkEngineIsRejected() {
        var observed = new ProjectionStartupGuard.Observed(100, true, Optional.of(expected()),
                false, Optional.of(identity(PREPROD, "sqlite")), coordinateAt(50), REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("archive identity mismatch");
    }

    @Test
    void aMismatchedOutboxIdentityIsRejectedBeforeTheArchiveIsConsidered() {
        var observed = new ProjectionStartupGuard.Observed(100, true,
                Optional.of(new ProjectionIdentity(PREPROD, "ducklake", 2, REQUIRED)),
                false, Optional.of(expected()), coordinateAt(50), REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("projection identity mismatch");
    }

    @Test
    void aDifferentRequiredSectionSetIsAnIdentityMismatch() {
        var narrower = new ProjectionIdentity(PREPROD, "ducklake", 1, Set.of(ProjectionSectionType.TRANSACTION));
        var observed = new ProjectionStartupGuard.Observed(100, true, Optional.of(narrower),
                false, Optional.of(expected()), coordinateAt(50), REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("projection identity mismatch");
    }

    // --- required sections the sink cannot serve --------------------------------

    @Test
    void aSinkThatCannotServeARequiredSectionFailsAtStartupNotAtFirstUse() {
        var observed = new ProjectionStartupGuard.Observed(0, false, Optional.empty(), true, Optional.empty(),
                ProjectionCoordinate.NONE, Set.of(ProjectionSectionType.TRANSACTION));
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("cannot serve required projection section")
                .hasMessageContaining("utxo-history:v5");
    }

    // --- incoherent observations ------------------------------------------------

    @Test
    void anEmptySinkThatReportsACoordinateIsRejected() {
        var observed = new ProjectionStartupGuard.Observed(100, true, Optional.of(expected()),
                true, Optional.empty(), coordinateAt(50), REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("reports itself empty but exposes a committed coordinate");
    }

    @Test
    void aNonEmptySinkWithoutIdentityIsRejected() {
        var observed = new ProjectionStartupGuard.Observed(100, true, Optional.of(expected()),
                false, Optional.empty(), coordinateAt(50), REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("exposes no projection identity");
    }

    @Test
    void anOutboxClaimingIdentityItCannotProduceIsRejected() {
        var observed = new ProjectionStartupGuard.Observed(100, true, Optional.empty(),
                false, Optional.of(expected()), coordinateAt(50), REQUIRED);
        assertThatThrownBy(() -> ProjectionStartupGuard.verify(expected(), observed))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("none could be read");
    }
}
