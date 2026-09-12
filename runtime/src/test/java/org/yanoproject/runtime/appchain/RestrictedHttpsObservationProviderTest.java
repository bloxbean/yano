package org.yanoproject.runtime.appchain;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestrictedHttpsObservationProviderTest {

    @Test
    void headerBoundIncludesTerminatorAndRejectsOneExtraByte() throws Exception {
        String prefix = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-Padding: ";
        String suffix = "\r\n\r\n";
        String exact = prefix + "x".repeat(32 * 1024 - prefix.length() - suffix.length()) + suffix;
        assertThat(RestrictedHttpsObservationProvider.readResponse(
                new ByteArrayInputStream(exact.getBytes(StandardCharsets.US_ASCII)), 4).body()).isEmpty();
        String oversized = prefix + "x".repeat(32 * 1024 - prefix.length() - suffix.length() + 1) + suffix;
        assertThatThrownBy(() -> RestrictedHttpsObservationProvider.readResponse(
                new ByteArrayInputStream(oversized.getBytes(StandardCharsets.US_ASCII)), 4))
                .isInstanceOf(IOException.class).hasMessageContaining("headers exceed bound");
    }

    @Test
    void rejectsSignedLengthsAndControlsBeforeWhitespaceTrimming() {
        for (String response : List.of(
                "HTTP/1.1 200 OK\r\nContent-Length: +3\r\n\r\nabc",
                "HTTP/1.1 200 OK\r\nContent-Length: -0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nX-Test: \u0000value\r\n\r\n",
                "HTTP/1.1 200 OK\r\nX-Test: value\u007f\r\n\r\n",
                "HTTP/1.1 200 O\u0000K\r\n\r\n")) {
            assertThatThrownBy(() -> RestrictedHttpsObservationProvider.readResponse(
                    new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII)), 4))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void ipv6TransportAndHostHeaderUseExactlyOneCanonicalBracketPair() {
        URI literal = URI.create("https://[2606:4700:4700::1111]/value");
        assertThat(RestrictedHttpsObservationProvider.transportHost(literal)).isEqualTo("2606:4700:4700::1111");
        assertThat(RestrictedHttpsObservationProvider.requestAuthority(literal)).isEqualTo("[2606:4700:4700::1111]");
        URI explicitPort = URI.create("https://[2606:4700:4700::1111]:443/value");
        assertThat(RestrictedHttpsObservationProvider.transportHost(explicitPort)).isEqualTo("2606:4700:4700::1111");
        assertThat(RestrictedHttpsObservationProvider.requestAuthority(explicitPort))
                .isEqualTo("[2606:4700:4700::1111]:443");
        URI hostname = URI.create("https://example.test:443/value");
        assertThat(RestrictedHttpsObservationProvider.transportHost(hostname)).isEqualTo("example.test");
        assertThat(RestrictedHttpsObservationProvider.requestAuthority(hostname)).isEqualTo("example.test:443");
    }

    @Test
    void rejectsRedirectsRateLimitsCompressionAndDuplicateEncodingWithoutReadingAClaim() throws Exception {
        for (int status : new int[]{301, 302, 307, 308, 429, 500, 503}) {
            var response = new RestrictedHttpsObservationProvider.Response(status,
                    Map.of("location", List.of("http://169.254.169.254/private")), new byte[0]);
            assertThatThrownBy(() -> RestrictedHttpsObservationProvider.validateSuccessfulResponse(
                    response, RestrictedHttpsObservationProvider.Mode.RAW_EXACT))
                    .isInstanceOf(IOException.class).hasMessage("Observation HTTPS source returned status " + status);
        }
        for (List<String> encodings : List.of(List.of("gzip"), List.of("br"), List.of("identity", "identity"))) {
            var response = new RestrictedHttpsObservationProvider.Response(200,
                    Map.of("content-encoding", encodings), new byte[0]);
            assertThatThrownBy(() -> RestrictedHttpsObservationProvider.validateSuccessfulResponse(
                    response, RestrictedHttpsObservationProvider.Mode.RAW_EXACT))
                    .isInstanceOf(IOException.class);
        }
        RestrictedHttpsObservationProvider.validateSuccessfulResponse(
                new RestrictedHttpsObservationProvider.Response(200, Map.of(), new byte[0]),
                RestrictedHttpsObservationProvider.Mode.RAW_EXACT);
    }

    @Test
    void attestationModesRequireExactlyOneCborMediaType() throws Exception {
        for (var mode : List.of(RestrictedHttpsObservationProvider.Mode.ATTESTED,
                RestrictedHttpsObservationProvider.Mode.MERKLE_ATTESTED)) {
            for (var headers : List.<Map<String, List<String>>>of(Map.of(),
                    Map.of("content-type", List.of("text/html")),
                    Map.of("content-type", List.of("application/cbor", "application/cbor")))) {
                assertThatThrownBy(() -> RestrictedHttpsObservationProvider.validateSuccessfulResponse(
                        new RestrictedHttpsObservationProvider.Response(200, headers, new byte[0]), mode))
                        .isInstanceOf(IOException.class);
            }
            RestrictedHttpsObservationProvider.validateSuccessfulResponse(
                    new RestrictedHttpsObservationProvider.Response(200,
                            Map.of("content-type", List.of("application/cbor")), new byte[0]), mode);
        }
    }

    @Test
    void connectionBudgetUsesOnlyTimeRemainingAfterDnsAndRejectsMixedAddresses() throws Exception {
        assertThat(RestrictedHttpsObservationProvider.remainingTimeoutMillis(
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))).isBetween(1, 50);
        assertThatThrownBy(() -> RestrictedHttpsObservationProvider.remainingTimeoutMillis(System.nanoTime() - 1))
                .isInstanceOf(IOException.class).hasMessageContaining("deadline");
        assertThatThrownBy(() -> RestrictedHttpsObservationProvider.validateResolvedAddresses(new InetAddress[]{
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8}),
                InetAddress.getByAddress(new byte[]{10, 0, 0, 1})}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-public");
    }

    @Test
    void oversizedHeadersDuplicateLengthsTruncationAndTrailersFailClosed() {
        for (String response : List.of(
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nx",
                "HTTP/1.1 200 OK\r\nX-Test: " + "x".repeat(33 * 1024) + "\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX-Trailer: x\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nx",
                "HTTP/1.1 200 OK\r\n Transfer-Encoding: chunked\r\n\r\n",
                "HTTP/1.1 200 OK\r\n\r\n12345")) {
            assertThatThrownBy(() -> RestrictedHttpsObservationProvider.readResponse(
                    new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII)), 4))
                    .isInstanceOf(IOException.class);
        }
    }

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
        for (String address : new String[]{"172.16.0.1", "172.31.255.254", "192.168.0.1",
                "198.18.0.1", "0.1.2.3", "224.0.0.1", "240.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1",
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
