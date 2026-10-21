package org.yanoproject.runtime.validation.shadowsync;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.YanoPropertyKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-056 Phase 7a: {@code shadow-sync-max-in-flight} is the one concurrency setting. */
class ShadowSyncSettingsTest {

    private static final String MAX_IN_FLIGHT = YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_IN_FLIGHT;

    private ListAppender warnings;

    @BeforeEach
    void attachAppender() {
        warnings = new ListAppender();
        LogManager.getLogger(ShadowSyncSettings.class).addAppender(warnings);
    }

    @AfterEach
    void detachAppender() {
        LogManager.getLogger(ShadowSyncSettings.class).removeAppender(warnings);
    }

    @Test
    void maxInFlightDefaultsToHalfTheCarriers() {
        int expected = Math.max(1, ShadowSyncSettings.carriers() / 2);
        assertThat(ShadowSyncSettings.fromGlobals(Map.of()).maxInFlight()).isEqualTo(expected);
        assertThat(ShadowSyncSettings.defaults().maxInFlight()).isEqualTo(expected);
        assertThat(ShadowSyncSettings.fromGlobals(Map.of(), 16).maxInFlight()).isEqualTo(8);
        assertThat(ShadowSyncSettings.fromGlobals(Map.of(), 3).maxInFlight()).isEqualTo(1);
        assertThat(ShadowSyncSettings.fromGlobals(Map.of(), 1).maxInFlight()).isEqualTo(1);
        assertThat(ShadowSyncSettings.fromGlobals(Map.of(MAX_IN_FLIGHT, "0"), 16).maxInFlight())
                .as("0, as in application.yml, is the default").isEqualTo(8);
        assertThat(warnings.events).isEmpty();
    }

    @Test
    void anExplicitValueIsHonoured() {
        assertThat(ShadowSyncSettings.fromGlobals(Map.of(MAX_IN_FLIGHT, "5"), 16).maxInFlight()).isEqualTo(5);
        assertThat(ShadowSyncSettings.fromGlobals(Map.of(MAX_IN_FLIGHT, 12), 16).maxInFlight()).isEqualTo(12);
        assertThat(warnings.events).isEmpty();
        assertThatThrownBy(() -> ShadowSyncSettings.fromGlobals(Map.of(MAX_IN_FLIGHT, "-1"), 16))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(MAX_IN_FLIGHT);
    }

    @Test
    void aValueNotBelowTheCarrierCountIsAcceptedWithAWarning() {
        assertThat(ShadowSyncSettings.fromGlobals(Map.of(MAX_IN_FLIGHT, "3"), 4).maxInFlight()).isEqualTo(3);
        assertThat(warnings.events).isEmpty();

        assertThat(ShadowSyncSettings.fromGlobals(Map.of(MAX_IN_FLIGHT, "4"), 4).maxInFlight()).isEqualTo(4);
        assertThat(warnings.events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getRenderedMessage()).contains(MAX_IN_FLIGHT + "=4").contains("4 virtual-thread carriers");
        });
    }

    @Test
    void theCarriersAreTheVirtualThreadSchedulersParallelism() {
        String key = "jdk.virtualThreadScheduler.parallelism";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "6");
            assertThat(ShadowSyncSettings.carriers()).isEqualTo(6);
            assertThat(ShadowSyncSettings.fromGlobals(Map.of()).maxInFlight()).isEqualTo(3);
            System.clearProperty(key);
            assertThat(ShadowSyncSettings.carriers()).isEqualTo(Runtime.getRuntime().availableProcessors());
        } finally {
            if (previous != null) {
                System.setProperty(key, previous);
            } else {
                System.clearProperty(key);
            }
        }
    }

    @Test
    void theRemovedThreadsKeyIsIgnored() {
        ShadowSyncSettings settings = ShadowSyncSettings.fromGlobals(
                Map.of("yano.validation.shadow-sync-threads", "3"), 16);
        assertThat(settings.maxInFlight()).isEqualTo(8);
    }

    private static final class ListAppender extends AppenderSkeleton {
        final List<LoggingEvent> events = new ArrayList<>();

        @Override
        protected void append(LoggingEvent event) {
            events.add(event);
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    }
}
