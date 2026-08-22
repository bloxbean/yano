package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The artifact contract exists so an archive can never claim an epoch artifact it did not capture
 * and cannot reconstruct. These pin the four comparison rules and the reasoning behind each.
 */
class ProjectionArtifactIdentityTest {

    private static ProjectionArtifactContract stake() {
        return new ProjectionArtifactContract(ArchiveDatasetId.EPOCH_STAKE, 2, 1,
                ProjectionArtifactRepresentation.IMMUTABLE_GENERATION,
                ProjectionArtifactReconstructibility.RECONSTRUCTIBLE);
    }

    private static ProjectionArtifactContract rewards() {
        return new ProjectionArtifactContract(ArchiveDatasetId.REWARD, 2, 1,
                ProjectionArtifactRepresentation.STAGED_FILE,
                ProjectionArtifactReconstructibility.IRREPRODUCIBLE);
    }

    private static ProjectionArtifactContract adaPots() {
        // Verified against the source: the computed pot is written under adaPotKey(epoch) and no
        // code path ever deletes it, so the value survives indefinitely and the artifact is
        // reconstructible from what the node keeps.
        return new ProjectionArtifactContract(ArchiveDatasetId.ADA_POT, 2, 1,
                ProjectionArtifactRepresentation.ATOMIC_EVIDENCE,
                ProjectionArtifactReconstructibility.RECONSTRUCTIBLE);
    }

    @Test
    void anIdenticalContractOpens() {
        var identity = ProjectionArtifactIdentity.of(List.of(stake(), rewards()));
        assertThat(identity.refuseToOpen(ProjectionArtifactIdentity.of(List.of(stake(), rewards())),
                ProjectionArtifactCoverage.COMPLETE, 0, 500)).isEmpty();
    }

    @Test
    void aReconstructibleArtifactMayBeAddedToAnExistingArchive() {
        // Epoch stake is a function of retained state, so an archive that lacks it can be
        // backfilled rather than rebuilt.
        var configured = ProjectionArtifactIdentity.of(List.of(stake()));
        var stored = ProjectionArtifactIdentity.NONE;

        assertThat(configured.refuseToOpen(stored, ProjectionArtifactCoverage.COMPLETE, 0, 500))
                .isEmpty();
        assertThat(configured.backfillRequired(stored))
                .extracting(ProjectionArtifactContract::dataset)
                .containsExactly(ArchiveDatasetId.EPOCH_STAKE);
    }

    @Test
    void anIrreproducibleArtifactMayNotBeAddedToAnExistingArchive() {
        // Rewards depend on the calculation's own version as well as its inputs. An archive that
        // did not capture them at the boundary can never produce them, so opening it while
        // claiming to serve them would be a promise the node cannot keep.
        var configured = ProjectionArtifactIdentity.of(List.of(rewards()));

        assertThat(configured.refuseToOpen(ProjectionArtifactIdentity.NONE,
                ProjectionArtifactCoverage.COMPLETE, 0, 500))
                .get().asString()
                .contains("reward")
                .contains("IRREPRODUCIBLE")
                .contains("can never be complete");
    }

    @Test
    void anArtifactNeedingBoundaryInputsAlsoMayNotBeAdded() {
        // Ada pots are cheap to capture but not recoverable afterwards: recomputing needs the
        // pre-transition pots and that epoch's fees, which the node does not keep.
        // Ada pots are reconstructible, so with retained coverage they may be added.
        assertThat(ProjectionArtifactIdentity.of(List.of(adaPots()))
                .refuseToOpen(ProjectionArtifactIdentity.NONE,
                        ProjectionArtifactCoverage.COMPLETE, 0, 500))
                .isEmpty();
    }

    @Test
    void anArchiveHoldingMoreThanTheNodeMaintainsIsRefused() {
        // Otherwise the node silently stops updating an artifact the archive keeps reporting.
        var configured = ProjectionArtifactIdentity.of(List.of(stake()));
        var stored = ProjectionArtifactIdentity.of(List.of(stake(), rewards()));

        assertThat(configured.refuseToOpen(stored, ProjectionArtifactCoverage.COMPLETE, 0, 500))
                .get().asString()
                .contains("reward")
                .contains("not configured to maintain it");
    }

    @Test
    void changingRepresentationOrCodecRequiresARebuild() {
        var stored = ProjectionArtifactIdentity.of(List.of(stake()));
        var reRepresented = ProjectionArtifactIdentity.of(List.of(
                new ProjectionArtifactContract(ArchiveDatasetId.EPOCH_STAKE, 2, 1,
                        ProjectionArtifactRepresentation.STAGED_FILE,
                        ProjectionArtifactReconstructibility.RECONSTRUCTIBLE)));
        var reCoded = ProjectionArtifactIdentity.of(List.of(
                new ProjectionArtifactContract(ArchiveDatasetId.EPOCH_STAKE, 2, 2,
                        ProjectionArtifactRepresentation.IMMUTABLE_GENERATION,
                        ProjectionArtifactReconstructibility.RECONSTRUCTIBLE)));

        assertThat(reRepresented.refuseToOpen(stored, ProjectionArtifactCoverage.COMPLETE, 0, 500))
                .get().asString().contains("require a rebuild");
        assertThat(reCoded.refuseToOpen(stored, ProjectionArtifactCoverage.COMPLETE, 0, 500))
                .get().asString().contains("require a rebuild");
    }

    @Test
    void theFingerprintIsStableAndCarriesCaptureSemantics() {
        var a = ProjectionArtifactIdentity.of(List.of(stake(), rewards()));
        var b = ProjectionArtifactIdentity.of(List.of(rewards(), stake()));
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        // Names alone are not enough: representation and reconstructibility change what the
        // archive can promise after a crash, so they are part of the identity.
        assertThat(a.fingerprint())
                .contains("epoch-stake:s2:c1:IMMUTABLE_GENERATION:RECONSTRUCTIBLE")
                .contains("reward:s2:c1:STAGED_FILE:IRREPRODUCIBLE");
        assertThat(ProjectionArtifactIdentity.NONE.fingerprint()).isEqualTo("artifacts:none");
    }

    @Test
    void aBlockDatasetIsRejectedAsAnArtifact() {
        // Block datasets belong in the section set, which has different comparison rules.
        assertThatThrownBy(() -> new ProjectionArtifactContract(ArchiveDatasetId.TRANSACTION, 2, 1,
                ProjectionArtifactRepresentation.STAGED_FILE,
                ProjectionArtifactReconstructibility.RECONSTRUCTIBLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs in the section set");
    }

    @Test
    void aReconstructibleArtifactStillNeedsRetainedSourcesToBeAdded() {
        // The correction that matters: RECONSTRUCTIBLE describes the kind, not this node.
        // Epoch stake's delegation generations are pruned unless a lease protected them since
        // genesis, so on a normal node the artifact is derivable in principle and unavailable in
        // practice. Deciding from the class alone would produce an archive claiming epochs it
        // can never fill.
        var configured = ProjectionArtifactIdentity.of(List.of(stake()));

        assertThat(configured.refuseToOpen(ProjectionArtifactIdentity.NONE,
                ProjectionArtifactCoverage.NONE, 0, 500))
                .get().asString()
                .contains("epoch-stake")
                .contains("no longer retained")
                .contains("requires a rebuild");
    }

    @Test
    void coverageIsOnlyConsultedForTheEpochsActuallyMissing() {
        var configured = ProjectionArtifactIdentity.of(List.of(stake()));
        var asked = new java.util.ArrayList<String>();
        ProjectionArtifactCoverage recording = (dataset, from, through) -> {
            asked.add(dataset + ":" + from + ".." + through);
            return true;
        };

        assertThat(configured.refuseToOpen(ProjectionArtifactIdentity.NONE, recording, 120, 340))
                .isEmpty();
        assertThat(asked).containsExactly("EPOCH_STAKE:120..340");
    }

    @Test
    void anEmptyArchiveNeedsNoCoverageProof() {
        // Nothing has passed yet, so there is nothing to backfill and nothing to prove.
        assertThat(ProjectionArtifactIdentity.of(List.of(stake()))
                .refuseToOpen(ProjectionArtifactIdentity.NONE, ProjectionArtifactCoverage.NONE, 0, -1))
                .isEmpty();
    }

    @Test
    void theContractSetRoundTripsThroughItsPersistedForm() {
        // The set is persisted beside the section fingerprint and compared on every open, so a
        // parse that lost a field would silently accept an archive built under different terms.
        var shipped = ProjectionArtifactContracts.shipped();

        var reparsed = ProjectionArtifactIdentity.parse(shipped.wireForm());

        assertThat(reparsed).isEqualTo(shipped);
        assertThat(reparsed.refuseToOpen(shipped, ProjectionArtifactCoverage.NONE, 0, 300)).isEmpty();
    }

    @Test
    void anEmptyPersistedFormIsAnArchiveHoldingNoArtifacts() {
        assertThat(ProjectionArtifactIdentity.parse("")).isEqualTo(ProjectionArtifactIdentity.NONE);
        assertThat(ProjectionArtifactIdentity.parse(null)).isEqualTo(ProjectionArtifactIdentity.NONE);
    }

    @Test
    void theShippedSetCannotBeAddedToAPopulatedArchiveWithoutRetainedSources() {
        // Reconstructible in kind, but the default coverage proves nothing is still retained.
        var reason = ProjectionArtifactContracts.shipped()
                .refuseToOpen(ProjectionArtifactIdentity.NONE, ProjectionArtifactCoverage.NONE, 0, 300);

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("no longer retained");
    }
}
