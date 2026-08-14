package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveMaintenanceBudget;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class HistoryArchiveMaintenanceTest {
    @Test
    void runsBoundedMaintenanceOnceWhenDue() throws Exception {
        var service = new HistoryArchiveService(mock(Config.class));
        var backend = mock(ArchiveBackend.class);
        var budget = new ArchiveMaintenanceBudget(Duration.ofSeconds(3), 64L * 1024 * 1024);
        set(service, "backend", backend);
        set(service, "maintenanceInterval", Duration.ofMinutes(5));
        set(service, "maintenanceBudget", budget);
        ((AtomicLong) get(service, "nextMaintenanceNanos")).set(0);

        service.runMaintenanceIfDue();
        service.runMaintenanceIfDue();

        verify(backend, times(1)).maintain(budget);
        assertThat(get(service, "lastMaintenanceAt")).isNotNull();
        assertThat(get(service, "maintenanceError")).isNull();
    }

    @Test
    void deadlineSaturatesInsteadOfWrapping() {
        assertThat(HistoryArchiveService.nextMaintenanceDeadline(
                Long.MAX_VALUE - 1, Duration.ofNanos(2))).isEqualTo(Long.MAX_VALUE);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
