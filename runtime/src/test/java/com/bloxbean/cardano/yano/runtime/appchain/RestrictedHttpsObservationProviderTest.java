package com.bloxbean.cardano.yano.runtime.appchain;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestrictedHttpsObservationProviderTest {

    @Test
    void rejectsLocalPrivateLinkLocalAndControlPlaneAddresses() throws Exception {
        assertThat(RestrictedHttpsObservationProvider.isPublic(
                InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(RestrictedHttpsObservationProvider.isPublic(
                InetAddress.getByName("10.20.30.40"))).isFalse();
        assertThat(RestrictedHttpsObservationProvider.isPublic(
                InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(RestrictedHttpsObservationProvider.isPublic(
                InetAddress.getByName("100.100.100.200"))).isFalse();
        assertThat(RestrictedHttpsObservationProvider.isPublic(
                InetAddress.getByName("::1"))).isFalse();
        assertThat(RestrictedHttpsObservationProvider.isPublic(
                InetAddress.getByName("fd00::1"))).isFalse();
        assertThat(RestrictedHttpsObservationProvider.isPublic(
                InetAddress.getByName("8.8.8.8"))).isTrue();
        for (String address : new String[]{"64:ff9b::a00:1", "2002:a00:1::1",
                "::a00:1", "2001::a00:1", "2001:db8::1"}) {
            assertThat(RestrictedHttpsObservationProvider.isPublic(InetAddress.getByName(address)))
                    .as(address).isFalse();
        }
        assertThat(RestrictedHttpsObservationProvider.isPublic(
                InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
    }

    @Test
    void endpointPolicyRequiresHttpsCanonicalAuthorityAndPort() {
        assertThatThrownBy(() -> RestrictedHttpsObservationProvider.validatePublicEndpoint(
                URI.create("http://example.com/value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestrictedHttpsObservationProvider.validatePublicEndpoint(
                URI.create("https://user@example.com/value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestrictedHttpsObservationProvider.validatePublicEndpoint(
                URI.create("https://example.com:8443/value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestrictedHttpsObservationProvider.validatePublicEndpoint(
                URI.create("https://127.0.0.1/value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-public");
    }

    @Test
    void boundedHttpParserAcceptsCanonicalFramingAndRejectsSmugglingOrOverflow()
            throws Exception {
        byte[] fixed = ("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc")
                .getBytes(StandardCharsets.US_ASCII);
        assertThat(RestrictedHttpsObservationProvider.readResponse(
                new ByteArrayInputStream(fixed), 3).body())
                .isEqualTo("abc".getBytes(StandardCharsets.US_ASCII));

        byte[] ambiguous = ("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n"
                + "Transfer-Encoding: chunked\r\n\r\nabc")
                .getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> RestrictedHttpsObservationProvider.readResponse(
                new ByteArrayInputStream(ambiguous), 3))
                .hasMessageContaining("Ambiguous");

        byte[] oversized = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "4\r\nabcd\r\n0\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> RestrictedHttpsObservationProvider.readResponse(
                new ByteArrayInputStream(oversized), 3))
                .hasMessageContaining("exceeds definition bound");
    }
}
