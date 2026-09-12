package org.yanoproject.app.api.tx;

import org.yanoproject.api.MempoolAdminGateway;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MempoolAdminResourceTest {
    private static final String HASH = "ab".repeat(32);
    private final MempoolAdminResource resource = new MempoolAdminResource();
    private final MempoolAdminGateway gateway = mock(MempoolAdminGateway.class);

    MempoolAdminResourceTest() {
        resource.gateway = gateway;
        resource.apiKey = Optional.of("test-secret-012345678901234567890123456789");
    }

    @Test
    void disabledByDefaultEvenWithValidCredentials() {
        assertThat(MempoolAdminResource.EvictionResponse.class.isAnnotationPresent(RegisterForReflection.class)).isTrue();
        assertThat(resource.evict(HASH, "test-secret-012345678901234567890123456789").getStatus()).isEqualTo(404);
        verifyNoInteractions(gateway);
    }

    @Test
    void requiresConfiguredKeyAndValidCredentialsBeforeValidatingHash() {
        resource.enabled = true;
        resource.apiKey = Optional.empty();
        assertThat(resource.evict(HASH, null).getStatus()).isEqualTo(503);
        resource.apiKey = Optional.of(" ");
        assertThat(resource.evict(HASH, " ").getStatus()).isEqualTo(503);
        resource.apiKey = Optional.of("short");
        assertThat(resource.evict(HASH, "short").getStatus()).isEqualTo(503);
        resource.apiKey = Optional.of("test-secret-012345678901234567890123456789");
        assertThat(resource.evict("invalid", null).getStatus()).isEqualTo(401);
        assertThat(resource.evict(HASH, "wrong").getStatus()).isEqualTo(401);
        assertThat(resource.evict("invalid", "test-secret-012345678901234567890123456789").getStatus()).isEqualTo(400);
        verifyNoInteractions(gateway);
    }

    @Test
    void returnsEvictedHashesAndExplicitNetworkWarning() {
        resource.enabled = true;
        List<String> hashes = List.of(HASH, "cd".repeat(32));
        when(gateway.evictTransaction(HASH)).thenReturn(hashes);
        var response = resource.evict(HASH.toUpperCase(), "test-secret-012345678901234567890123456789");
        assertThat(response.getStatus()).isEqualTo(200);
        var body = (MempoolAdminResource.EvictionResponse) response.getEntity();
        assertThat(body.txHash()).isEqualTo(HASH);
        assertThat(body.evictedTxHashes()).isEqualTo(hashes);
        assertThat(body.warning()).contains("does not cancel", "queued", "peers");
    }

    @Test
    void absentTransactionIsIdempotentAndUnavailableRuntimeReturns503() {
        resource.enabled = true;
        when(gateway.evictTransaction(HASH)).thenReturn(List.of());
        var body = (MempoolAdminResource.EvictionResponse) resource.evict(HASH, "test-secret-012345678901234567890123456789").getEntity();
        assertThat(body.evictedTxHashes()).isEmpty();
        when(gateway.evictTransaction(HASH)).thenThrow(new IllegalStateException("stopped"));
        assertThat(resource.evict(HASH, "test-secret-012345678901234567890123456789").getStatus()).isEqualTo(503);
        resource.gateway = MempoolAdminGateway.UNAVAILABLE;
        assertThat(resource.evict(HASH, "test-secret-012345678901234567890123456789").getStatus()).isEqualTo(503);
    }
}
