package org.yanoproject.api.appchain;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AppLedgerOperatingProfileTest {
    @Test
    void legacyContextsAreUnchangedAndStandaloneHasAFixedDomain() {
        byte[] context = new byte[32];
        assertThat(AppLedgerOperatingProfile.from(Map.of())).isEqualTo(AppLedgerOperatingProfile.CARDANO);
        assertThat(AppLedgerOperatingProfile.CARDANO.bindContext(context)).isEqualTo(context).isNotSameAs(context);
        assertThat(HexFormat.of().formatHex(AppLedgerOperatingProfile.STANDALONE.bindContext(context)))
                .isEqualTo("94581fcb020417db54f8c236778e64f41937de6532c9c7d5fdd1b9bd41f8988c");
        assertThatThrownBy(() -> AppLedgerOperatingProfile.from(Map.of(
                AppLedgerOperatingProfile.SETTING, "unknown"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void standaloneRejectsL1ReferencesWithoutWeakeningHybridValidation() {
        AppBlock noL1 = block(0, new byte[0]);
        AppBlock l1 = block(123, new byte[32]);
        AppBlock malformed = block(0, new byte[32]);
        assertThat(AppLedgerOperatingProfile.STANDALONE.accepts(noL1)).isTrue();
        assertThat(AppLedgerOperatingProfile.STANDALONE.accepts(l1)).isFalse();
        assertThat(AppLedgerOperatingProfile.STANDALONE.accepts(malformed)).isFalse();
        assertThat(AppLedgerOperatingProfile.CARDANO.accepts(l1)).isTrue();
    }

    @Test
    void standaloneRejectsCardanoFeaturesAtConfigurationBoundary() {
        assertThatThrownBy(() -> AppLedgerOperatingProfile.STANDALONE.validate(
                AppChainConfig.builder("test").signingKeyHex("00".repeat(32)).l1StabilityDepth(1).build()))
                .hasMessageContaining("l1.stability-depth");
        assertThatThrownBy(() -> AppLedgerOperatingProfile.STANDALONE.validate(
                AppChainConfig.builder("test").signingKeyHex("00".repeat(32)).pluginSettings(Map.of("observers.deposits.type", "deposit")).build()))
                .hasMessageContaining("observers.deposits.type");
        assertThatCode(() -> AppLedgerOperatingProfile.STANDALONE.validate(
                AppChainConfig.builder("test").signingKeyHex("00".repeat(32)).pluginSettings(Map.of("observations.workers", "2")).build()))
                .doesNotThrowAnyException();
    }

    private static AppBlock block(long slot, byte[] hash) {
        return new AppBlock(3, "test", 1, new byte[32], slot, hash, 1,
                new byte[32], new byte[32], List.of(), new byte[32], FinalityCert.empty());
    }
}
