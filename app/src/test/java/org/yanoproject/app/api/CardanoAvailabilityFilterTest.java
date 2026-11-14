package org.yanoproject.app.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CardanoAvailabilityFilterTest {
    @Test
    void deploymentModeIsResolvedLazilyOnceAtRuntime() {
        CardanoAvailabilityFilter filter = new CardanoAvailabilityFilter();
        AtomicInteger resolutions = new AtomicInteger();
        filter.mode = () -> {
            resolutions.incrementAndGet();
            return "cardano";
        };
        assertThat(resolutions).hasValue(0);
        filter.filter(mock(ContainerRequestContext.class));
        filter.filter(mock(ContainerRequestContext.class));
        assertThat(resolutions).hasValue(1);
    }

    @Test
    void standaloneReturnsUnavailableBeforeResourceErrorHandling() {
        CardanoAvailabilityFilter filter = new CardanoAvailabilityFilter();
        filter.mode = () -> "app-ledger";
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        filter.filter(request);
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        assertThat(response.getValue().getStatus()).isEqualTo(503);
        assertThat(((Map<?, ?>) response.getValue().getEntity()).get("error")).isEqualTo("cardano_unavailable");
    }

    @Test
    void existingCardanoRequestsPassThroughUnchanged() {
        CardanoAvailabilityFilter filter = new CardanoAvailabilityFilter();
        filter.mode = () -> "cardano";
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        filter.filter(request);
        verifyNoInteractions(request);
    }
}
