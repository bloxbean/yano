package org.yanoproject.app.spi;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeysTest {

    @Test
    void parsesFullAndTopicScopedKeys() {
        assertThat(ApiKeys.parse(" full , scoped = orders | invoices ,, ")).containsExactly(
                Map.entry("full", Set.of()), Map.entry("scoped", Set.of("orders", "invoices")));
        assertThat(ApiKeys.parse(null)).isEmpty();
        assertThat(ApiKeys.parse(" , ")).isEmpty();
    }

    @Test
    void parsesPlainKeySets() {
        assertThat(ApiKeys.parseSet(" a, ,b ")).containsExactlyInAnyOrder("a", "b");
        assertThat(ApiKeys.parseSet(null)).isEmpty();
    }

    @Test
    void fullKeyIsAnUnscopedEntry() {
        assertThat(ApiKeys.hasFullKey(ApiKeys.parse("scoped=orders"))).isFalse();
        assertThat(ApiKeys.hasFullKey(ApiKeys.parse("scoped=orders,full"))).isTrue();
        assertThat(ApiKeys.hasFullKey(Map.of())).isFalse();
    }

    @Test
    void canonicalPathsHaveNoEncodingMatrixEmptyTrailingOrDotSegments() {
        assertThat(ApiKeys.isCanonical("/api/v1/plugin-operations/bundles")).isTrue();
        for (String path : new String[] {null, "", "/api/v1/", "/api//v1", "/api/%70lugin", "/api;x=1",
                "/api/./v1", "/api/../v1", "/api/.."}) {
            assertThat(ApiKeys.isCanonical(path)).as(String.valueOf(path)).isFalse();
        }
    }

    @Test
    void rawPathPrefersTheEncodedRequestUri() {
        UriInfo uri = mock(UriInfo.class);
        when(uri.getRequestUri()).thenReturn(URI.create("http://host/api/v1/plugin%2Doperations;x=1"));
        assertThat(ApiKeys.rawPath(uri)).isEqualTo("/api/v1/plugin%2Doperations;x=1");

        UriInfo withoutRequestUri = mock(UriInfo.class);
        when(withoutRequestUri.getPath(false)).thenReturn("api/v1/status");
        assertThat(ApiKeys.rawPath(withoutRequestUri)).isEqualTo("api/v1/status");

        UriInfo failing = mock(UriInfo.class);
        when(failing.getRequestUri()).thenThrow(new IllegalStateException("no request"));
        assertThat(ApiKeys.rawPath(failing)).isNull();
        assertThat(ApiKeys.rawPath(null)).isNull();
    }

    @Test
    void boundEntityPassesRequestsWithoutABody() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        assertThat(ApiKeys.boundEntity(request, 4)).isTrue();
        verify(request, never()).abortWith(any());
    }

    @Test
    void boundEntityRestoresABodyAtTheLimit() throws IOException {
        ContainerRequestContext request = withBody(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
        assertThat(ApiKeys.boundEntity(request, 4)).isTrue();
        ArgumentCaptor<InputStream> restored = ArgumentCaptor.forClass(InputStream.class);
        verify(request).setEntityStream(restored.capture());
        assertThat(restored.getValue().readAllBytes()).containsExactly(1, 2, 3, 4);
        verify(request, never()).abortWith(any());
    }

    @Test
    void boundEntityRejectsAnOversizedBody() {
        ContainerRequestContext request = withBody(new ByteArrayInputStream(new byte[5]));
        assertThat(ApiKeys.boundEntity(request, 4)).isFalse();
        assertThat(aborted(request).getStatus()).isEqualTo(413);
        assertThat(aborted(request).getEntity()).isEqualTo(Map.of("code", "REQUEST_TOO_LARGE",
                "error", "Request entity exceeds the size limit"));
    }

    @Test
    void boundEntityRejectsAnUnreadableBody() {
        ContainerRequestContext request = withBody(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("reset");
            }
        });
        assertThat(ApiKeys.boundEntity(request, 4)).isFalse();
        assertThat(aborted(request).getStatus()).isEqualTo(400);
    }

    private static ContainerRequestContext withBody(InputStream body) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.hasEntity()).thenReturn(true);
        when(request.getEntityStream()).thenReturn(body);
        return request;
    }

    private static Response aborted(ContainerRequestContext request) {
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        return response.getValue();
    }
}
