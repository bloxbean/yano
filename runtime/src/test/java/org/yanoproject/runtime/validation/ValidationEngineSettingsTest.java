package org.yanoproject.runtime.validation;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.YanoPropertyKeys;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-056 §7: engine settings defaults and parsing. */
class ValidationEngineSettingsTest {

    @Test
    void defaultsAdmitThroughJavaJulc() {
        ValidationEngineSettings settings = ValidationEngineSettings.fromGlobals(Map.of());

        assertThat(settings).isEqualTo(ValidationEngineSettings.defaults());
        assertThat(settings.engine()).isEqualTo("java-julc");
        assertThat(settings.shadowEngines()).isEmpty();
        assertThat(settings.shadowDumpDir()).isNull();
        assertThat(settings.shadowSync()).isFalse();
        assertThat(settings.snapshotMaxAgeMs()).isEqualTo(30_000);
        assertThat(settings.maxLiveSnapshots()).isEqualTo(4);
        assertThat(settings.engineAdmission()).isTrue();
        assertThat(settings.usesEngineApi()).isTrue();
    }

    @Test
    void scalusAloneKeepsTheLegacyPath() {
        ValidationEngineSettings settings = ValidationEngineSettings.fromGlobals(Map.of(
                YanoPropertyKeys.Validation.ENGINE, "scalus",
                YanoPropertyKeys.Validation.SHADOW_ENGINES, "",
                YanoPropertyKeys.Validation.SHADOW_DUMP_DIR, ""));

        assertThat(settings.engineAdmission()).isFalse();
        assertThat(settings.affectsAdmission()).isFalse();
        assertThat(settings.usesEngineApi()).isFalse();
    }

    @Test
    void parsesEveryKey() {
        ValidationEngineSettings settings = ValidationEngineSettings.fromGlobals(Map.of(
                YanoPropertyKeys.Validation.ENGINE, " Amaru ",
                YanoPropertyKeys.Validation.SHADOW_ENGINES, "scalus",
                YanoPropertyKeys.Validation.SHADOW_DUMP_DIR, "/tmp/dumps",
                YanoPropertyKeys.Validation.SHADOW_SYNC, "true",
                YanoPropertyKeys.Validation.SNAPSHOT_MAX_AGE_MS, "5000",
                YanoPropertyKeys.Validation.MAX_LIVE_SNAPSHOTS, 6));

        assertThat(settings.engine()).isEqualTo("amaru");
        assertThat(settings.shadowEngines()).containsExactly("scalus");
        assertThat(settings.shadowDumpDir()).isEqualTo(Path.of("/tmp/dumps"));
        assertThat(settings.shadowSync()).isTrue();
        assertThat(settings.snapshotMaxAgeMs()).isEqualTo(5_000);
        assertThat(settings.maxLiveSnapshots()).isEqualTo(6);
        assertThat(settings.usesEngineApi()).isTrue();
        assertThat(settings.uses("AMARU")).isTrue();
    }

    @Test
    void shadowEnginesAloneSwitchTheEngineApiOnAndYamlListsParse() {
        assertThat(ValidationEngineSettings.fromGlobals(Map.of(YanoPropertyKeys.Validation.ENGINE, "scalus",
                YanoPropertyKeys.Validation.SHADOW_ENGINES, List.of("amaru"))).usesEngineApi()).isTrue();
        assertThat(ValidationEngineSettings.fromGlobals(Map.of(YanoPropertyKeys.Validation.ENGINE, "scalus",
                YanoPropertyKeys.Validation.SHADOW_ENGINES + "[0]", "amaru",
                YanoPropertyKeys.Validation.SHADOW_ENGINES + "[1]", "java-julc")).shadowEngines())
                .containsExactly("amaru", "java-julc");
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> ValidationEngineSettings.fromGlobals(Map.of(
                YanoPropertyKeys.Validation.ENGINE, "amaru", YanoPropertyKeys.Validation.SHADOW_ENGINES, "amaru")))
                .hasMessageContaining("must differ");
        assertThatThrownBy(() -> ValidationEngineSettings.fromGlobals(Map.of(
                YanoPropertyKeys.Validation.SHADOW_ENGINES, "java-julc")))
                .hasMessageContaining("'java-julc' (the default when yano.validation.engine is unset)");
        assertThatThrownBy(() -> ValidationEngineSettings.fromGlobals(Map.of(
                YanoPropertyKeys.Validation.SNAPSHOT_MAX_AGE_MS, "0")))
                .hasMessageContaining("snapshot-max-age-ms");
        assertThatThrownBy(() -> ValidationEngineSettings.fromGlobals(Map.of(
                YanoPropertyKeys.Validation.SNAPSHOT_MAX_AGE_MS, "soon")))
                .hasMessageContaining("not an integer");
    }
}
