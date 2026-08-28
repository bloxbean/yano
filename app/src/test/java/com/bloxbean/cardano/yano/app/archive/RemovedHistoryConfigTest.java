package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.core.config.ArchiveEngine;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Configuration that no longer does anything must say so.
 *
 * <p>The replay-worker pipeline left its keys behind, and a stale one is not harmless: a
 * deployment file carrying {@code worker.projection-parallelism} or
 * {@code maintenance.max-bytes-to-rewrite} reads as "this is tuned" when nothing consumes it.
 * The maintenance keys were the worst of them - unguarded until recently, so an operator capping
 * a rewrite budget to keep compaction out of a window got no effect and no warning.
 *
 * <p>These pin the guard in both directions. Rejecting too little is the original defect;
 * rejecting too much would take out {@code projection.maintenance.*}, which is live
 * configuration that merely shares a word with the dead keys.
 */
class RemovedHistoryConfigTest {

    /** Minimal Config over a fixed map; the guard only reads names and values. */
    private static Config configOf(Map<String, String> values) {
        Config config = mock(Config.class);
        org.mockito.Mockito.when(config.getPropertyNames()).thenReturn(values.keySet());
        org.mockito.Mockito.when(config.getOptionalValue(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(String.class)))
                .thenAnswer(call -> Optional.ofNullable(values.get(call.getArgument(0, String.class))));
        org.mockito.Mockito.when(config.getOptionalValue(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(Boolean.class)))
                .thenAnswer(call -> Optional.ofNullable(values.get(call.getArgument(0, String.class)))
                        .map(Boolean::parseBoolean));
        return config;
    }

    private static void initialize(Map<String, String> values) {
        new HistoryArchiveService(configOf(values)).initialize();
    }

    private static Map<String, String> with(String key, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        return values;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Replay-worker tuning.
            "yano.history.worker.projection-parallelism",
            "yano.history.worker.max-blocks-per-batch",
            "yano.history.worker.pause-backfill-during-core-catchup",
            // The hot store, which no longer exists.
            "yano.history.hot-store.engine",
            "yano.history.hot-store.sqlite.path",
            // Per-dataset selection, replaced by projection.sections for block sections.
            "yano.history.datasets.rewards.enabled",
            "yano.history.datasets.address-transactions.subjects.stake-credential",
            // Coverage mode.
            "yano.history.start-mode",
            // Unguarded until recently: read by nothing, and silently ignored.
            "yano.history.maintenance.interval-seconds",
            "yano.history.maintenance.time-limit-seconds",
            "yano.history.maintenance.max-bytes-to-rewrite",
            // The SQLite archive backend, removed with its module.
            "yano.history.archive.sqlite.path"})
    void removedKeysAreRejectedRatherThanIgnored(String key) {
        assertThatThrownBy(() -> initialize(with(key, "some-value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key);
    }

    @Test
    void theLegacyEnableFlagNamesItsReplacement() {
        // An error that says only "removed" leaves the operator to guess; this one has to point
        // somewhere, and somewhere that is not itself rejected.
        assertThatThrownBy(() -> initialize(with("yano.history.enabled", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yano.history.projection.enabled=true");
    }

    @Test
    void theLegacyEnableFlagIsRejectedEvenWhenFalse() {
        HistoryArchiveService service = new HistoryArchiveService(
                configOf(with("yano.history.enabled", "false")));

        assertThatThrownBy(service::initialize)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yano.history.projection.enabled=true");
        assertThat(service.hasInitializationFailure()).isTrue();
        assertThat(service.status()).containsKey("error");
    }

    @Test
    void removedSynchronousAccountHistoryFlagIsRejectedEvenWhenFalse() {
        assertThatThrownBy(() -> initialize(with("yano.account-history.enabled", "false")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yano.history.projection.enabled=true");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Live projection configuration that merely shares a word with the dead keys. A
            // prefix guard written as "maintenance" rather than "yano.history.maintenance."
            // would take these out, and the archive would stop being tunable at all.
            "yano.history.projection.maintenance.housekeeping-interval-minutes",
            "yano.history.projection.maintenance.compaction-interval-minutes",
            "yano.history.projection.sections",
            "yano.history.projection.enabled",
            "yano.history.projection.sink",
            "yano.history.dir",
            "yano.history.archive.engine",
            "yano.history.archive.finality-blocks"})
    void liveConfigurationIsNotCaughtByTheRemovedPrefixes(String key) {
        assertThatCode(() -> initialize(with(key, "value"))).doesNotThrowAnyException();
    }

    @Test
    void anEmptyRemovedKeyIsToleratedBecauseItConfiguresNothing() {
        // A blank value in a template is not a stale setting an operator believes in.
        assertThatCode(() -> initialize(with("yano.history.worker.max-rows-per-batch", "")))
                .doesNotThrowAnyException();
    }

    @Test
    void everyRejectedPrefixHasACaseAbove() throws Exception {
        // Read the production list rather than restate it. A local copy compared against itself
        // proves nothing: adding a prefix to the service would leave it untested while this
        // still passed.
        var field = HistoryArchiveService.class.getDeclaredField("REMOVED_WORKER_PREFIXES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> production = (List<String>) field.get(null);

        List<String> covered = List.of(
                "yano.history.worker.", "yano.history.hot-store.", "yano.history.start-mode",
                "yano.history.datasets.", "yano.history.maintenance.", "yano.history.archive.sqlite.");

        assertThat(production)
                .as("a prefix added to HistoryArchiveService needs a case in"
                        + " removedKeysAreRejectedRatherThanIgnored, and one removed needs its case dropped")
                .containsExactlyInAnyOrderElementsOf(covered);
    }

    @Test
    void theRemovedSqliteEngineIsRejectedByName() {
        // sqlite was a supported archive engine until its store module was removed, so it is a
        // value carried forward from older deployment files. Enum.valueOf alone reports "No enum
        // constant com.bloxbean...ArchiveEngine.SQLITE", which names a Java class and says
        // nothing about what is allowed instead.
        Map<String, String> values = new LinkedHashMap<>();
        values.put("yano.history.archive.engine", "sqlite");

        assertThatThrownBy(() -> new HistoryArchiveService(configOf(values))
                .initializeProjectionReads(
                        mock(ArchiveIdentity.class), Set.of(), () -> 0L, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yano.history.archive.engine=sqlite is not supported")
                .hasMessageContaining("ducklake");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ducklake", "DuckLake", "  ducklake  ", "DUCKLAKE"})
    void theOnlySupportedEngineIsAccepted(String configured) {
        // Tested through the parsing rule rather than through initializeProjectionReads. Driving
        // acceptance end to end would open a real DuckLake archive under the default ./history,
        // writing into the source tree, and could only assert acceptance indirectly - by
        // expecting some later unrelated failure, which passes for the wrong reason.
        assertThat(HistoryArchiveService.parseEnum(
                "yano.history.archive.engine", configured, ArchiveEngine.class))
                .isEqualTo(ArchiveEngine.DUCKLAKE);
    }

    @Test
    void anUnknownEngineNamesTheAlternatives() {
        assertThatThrownBy(() -> HistoryArchiveService.parseEnum(
                "yano.history.archive.engine", "sqlite", ArchiveEngine.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("yano.history.archive.engine=sqlite is not supported;"
                        + " valid values are [ducklake]");
    }
}
