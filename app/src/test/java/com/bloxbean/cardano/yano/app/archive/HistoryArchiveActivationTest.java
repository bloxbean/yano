package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.ArchiveCoverage;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveStartMode;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryArchiveActivationTest {
    @TempDir Path temp;

    @Test
    void fullRequiredAcceptsBlockOneAsTheByronOriginOnRestart() {
        assertThat(HistoryArchiveService.firstCanonicalBlockNumber(true)).isEqualTo(1);
        assertThat(HistoryArchiveService.resolveBlockActivationStart(
                ArchiveStartMode.FULL_REQUIRED, 1, 317_482, OptionalLong.of(1)))
                .isEqualTo(1);
    }

    @Test
    void fullRequiredUsesTheByronOriginBeforeTheFirstBodyArrives() {
        assertThat(HistoryArchiveService.resolveBlockActivationStart(
                ArchiveStartMode.FULL_REQUIRED, 1, -1, OptionalLong.empty()))
                .isEqualTo(1);
    }

    @Test
    void fullRequiredStillRejectsActuallyPrunedOriginBodies() {
        assertThatThrownBy(() -> HistoryArchiveService.resolveBlockActivationStart(
                ArchiveStartMode.FULL_REQUIRED, 1, 317_482, OptionalLong.of(2)))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("expected first canonical block 1");
    }

    @Test
    void fullRequiredRejectsPersistedTipWithoutAnyRetainedBody() {
        assertThatThrownBy(() -> HistoryArchiveService.resolveBlockActivationStart(
                ArchiveStartMode.FULL_REQUIRED, 1, 317_482, OptionalLong.empty()))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("no retained block bodies");
    }

    @Test
    void shelleyOnlyAndDevnetChainsStillBeginAtBlockZero() {
        assertThat(HistoryArchiveService.firstCanonicalBlockNumber(false)).isZero();
        assertThat(HistoryArchiveService.resolveBlockActivationStart(
                ArchiveStartMode.FULL_REQUIRED, 0, 10, OptionalLong.of(0)))
                .isZero();
    }

    @Test
    void tipModeUsesGenesisSeedUntilTheFirstCanonicalBlockExists() {
        assertThat(HistoryArchiveService.tipStartsAtGenesis(-1, 1)).isTrue();
        assertThat(HistoryArchiveService.tipStartsAtGenesis(0, 1)).isTrue();
        assertThat(HistoryArchiveService.tipStartsAtGenesis(1, 1)).isFalse();
        assertThat(HistoryArchiveService.tipStartsAtGenesis(-1, 0)).isTrue();
        assertThat(HistoryArchiveService.tipStartsAtGenesis(0, 0)).isFalse();
    }

    @Test
    void tableEnabledWithFreshDatasetSharesBackfillStart() {
        var activations = new ActivationStore(temp.resolve("fresh-table.properties"));

        assertThat(activations.configureTable(
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.UTXO_HISTORY,
                "transaction_datums", true, false, 1, -1, 1)).hasValue(1);
        assertThat(activations.configureTable(
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.UTXO_HISTORY,
                "transaction_datums", true, true, 1, 500, 1)).hasValue(1);
    }

    @Test
    void tableEnabledLaterStartsAfterCanonicalTipAndNeverBackfillsDisabledGap() {
        var activations = new ActivationStore(temp.resolve("later-table.properties"));
        var dataset = com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.UTXO_HISTORY;

        assertThat(activations.configureTable(dataset, "transaction_redeemers",
                false, true, 1, 100, 1)).isEmpty();
        assertThat(activations.configureTable(dataset, "transaction_redeemers",
                true, true, 1, 100, 1)).hasValue(101);
        assertThat(activations.configureTable(dataset, "transaction_redeemers",
                false, true, 1, 150, 1)).isEmpty();
        assertThat(activations.configureTable(dataset, "transaction_redeemers",
                true, true, 1, 150, 1)).hasValue(151);
    }

    @Test
    void addressSubjectSelectionIsPinnedAfterDatasetActivation() {
        Path path = temp.resolve("address-subjects.properties");
        var activations = new ActivationStore(path);
        activations.configureAddressSubjects("stake_credential", false);
        activations.putIfAbsent(ArchiveDatasetId.ADDRESS_TRANSACTION, 1);

        var reopened = new ActivationStore(path);
        reopened.configureAddressSubjects("stake_credential", true);
        assertThatThrownBy(() -> reopened.configureAddressSubjects(
                "address,payment_credential,stake_credential", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("new history directory or rebuild");
    }

    @Test
    void legacyAddressArchiveIsTreatedAsAllSubjects() {
        var activations = new ActivationStore(temp.resolve("legacy-address-subjects.properties"));
        activations.putIfAbsent(ArchiveDatasetId.ADDRESS_TRANSACTION, 1);

        assertThatThrownBy(() -> activations.configureAddressSubjects("stake_credential", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("address,payment_credential,stake_credential");
    }

    @Test
    void reanchorsAStaleLiveTrackOnlyAfterCoreReachesItsUpstreamTarget() {
        assertThat(HistoryArchiveService.shouldReanchorLive(
                5_000_000, 5_000_010, 500_000, 100, 4_320)).isTrue();
        assertThat(HistoryArchiveService.shouldReanchorLive(
                4_000_000, 5_000_000, 500_000, 100, 4_320)).isFalse();
        assertThat(HistoryArchiveService.shouldReanchorLive(
                5_000_000, 5_000_010, 4_998_000, 100, 4_320)).isFalse();
    }

    @Test
    void resolvesAutomaticAndExplicitProjectionParallelismConservatively() {
        assertThat(HistoryArchiveService.resolveProjectionParallelism("auto", 2, 4)).isEqualTo(1);
        assertThat(HistoryArchiveService.resolveProjectionParallelism("auto", 8, 4)).isEqualTo(4);
        assertThat(HistoryArchiveService.resolveProjectionParallelism("8", 8, 3)).isEqualTo(3);
        assertThatThrownBy(() -> HistoryArchiveService.resolveProjectionParallelism("0", 8, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handoffRequiresCompleteFinalizedCoverageAndUsesOneCursor() {
        byte[] hash = new byte[32];
        ArchiveProgress behind = new ArchiveProgress(ArchiveDatasetId.TRANSACTION,
                ArchiveTrack.BACKFILL, 9, 90, hash, 1);
        ArchiveProgress caught = new ArchiveProgress(ArchiveDatasetId.TRANSACTION,
                ArchiveTrack.BACKFILL, 10, 100, hash, 1);
        ArchiveCoverage complete = new ArchiveCoverage(ArchiveDatasetId.TRANSACTION, 1, 1,
                java.util.List.of(new BlockRange(1, 10)));
        ArchiveCoverage gap = new ArchiveCoverage(ArchiveDatasetId.TRANSACTION, 1, 1,
                java.util.List.of(new BlockRange(1, 4), new BlockRange(6, 10)));

        assertThat(HistoryArchiveService.handoffBaseline(1, 10, behind, complete)).isEmpty();
        assertThat(HistoryArchiveService.handoffBaseline(1, 10, caught, gap)).isEmpty();
        assertThat(HistoryArchiveService.handoffBaseline(1, 10, caught, complete)).hasValue(10);
        assertThat(HistoryArchiveService.handoffBaseline(101, 90, null, complete)).hasValue(100);
    }

    @Test
    void hotStartIsPhaseMetadataNotASecondHistoryActivation() {
        Path path = temp.resolve("single-track.properties");
        var activations = new ActivationStore(path);
        activations.putIfAbsent(ArchiveDatasetId.TRANSACTION, 1);
        activations.setHotStart(ArchiveDatasetId.TRANSACTION, 101);

        var reopened = new ActivationStore(path);
        assertThat(reopened.start(ArchiveDatasetId.TRANSACTION)).hasValue(1);
        assertThat(reopened.hotStart(ArchiveDatasetId.TRANSACTION)).hasValue(101);
    }

}
