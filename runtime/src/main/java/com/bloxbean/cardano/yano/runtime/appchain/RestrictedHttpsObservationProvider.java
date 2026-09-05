package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAttestation;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCandidate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProvider;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRequest;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSourceConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Fixed-endpoint HTTPS acquisition with redirect denial, response bounds, and
 * fail-closed DNS/IP classification. Application parameters can be sent only
 * as an opaque POST body and can never select scheme, authority, port, or path.
 */
final class RestrictedHttpsObservationProvider implements ObservationProvider {
    enum Mode { ATTESTED, RAW_EXACT }
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();

    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "observation-https-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private final Mode mode;
    private final ObservationDefinition definition;
    private final URI endpoint;
    private final byte[] sourceId;
    private final String versionHeader;
    private final String method;
    private final Duration timeout;

    RestrictedHttpsObservationProvider(Mode mode, ObservationDefinition definition,
                                       Map<String, String> settings) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.definition = Objects.requireNonNull(definition, "definition");
        Set<String> allowedSettings = mode == Mode.RAW_EXACT
                ? Set.of("url", "method", "timeout-ms", "source-id", "version-header")
                : Set.of("url", "method", "timeout-ms");
        if (settings.keySet().stream().anyMatch(key -> !allowedSettings.contains(key))) {
            throw new IllegalArgumentException(
                    "Unclassified observation HTTPS provider setting");
        }
        this.endpoint = parseEndpoint(required(settings, "url"));
        this.method = settings.getOrDefault("method", "GET")
                .trim().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) {
            throw new IllegalArgumentException("Observation HTTPS method must be GET or POST");
        }
        long timeoutMillis = Long.parseLong(settings.getOrDefault("timeout-ms", "5000"));
        if (timeoutMillis < 100 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("Observation HTTPS timeout must be 100..60000 ms");
        }
        this.timeout = Duration.ofMillis(timeoutMillis);
        String configuredSourceId = settings.getOrDefault("source-id", endpoint.getHost());
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(configuredSourceId)) {
            throw new IllegalArgumentException("Observation HTTPS source-id must be ASCII");
        }
        this.sourceId = configuredSourceId.getBytes(StandardCharsets.US_ASCII);
        if (sourceId.length == 0 || sourceId.length > 256) {
            throw new IllegalArgumentException("Observation HTTPS source-id must be 1..256 bytes");
        }
        this.versionHeader = settings.getOrDefault("version-header", "ETag").trim();
        if (versionHeader.isEmpty() || versionHeader.length() > 128
                || versionHeader.chars().anyMatch(character -> character <= 32
                || character >= 127 || character == ':')) {
            throw new IllegalArgumentException("Invalid observation source version header");
        }
        if (mode == Mode.RAW_EXACT && !Arrays.equals(definition.sourceConfigurationDigest(),
                ObservationSourceConfiguration.httpsSourceDigest(
                        endpoint.toASCIIString(), method,
                        new String(sourceId, StandardCharsets.US_ASCII),
                        versionHeader.toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("Raw HTTPS settings differ from observation source identity");
        }
        validateEndpointSyntax(endpoint);
        if (isAddressLiteral(endpoint.getHost())) {
            validatePublicEndpoint(endpoint);
        }
    }

    @Override
    public ObservationCandidate acquire(ObservationRequest request) throws Exception {
        if (!Arrays.equals(request.definition().digest(), definition.digest())) {
            throw new IllegalArgumentException("Observation provider received another definition");
        }
        byte[] parameters = request.subscription().parameters();
        if (method.equals("GET") && parameters.length != 0) {
            throw new IllegalArgumentException(
                    "GET observation definition does not accept application parameters");
        }
        InetAddress address = resolvePublicAddresses(endpoint).getFirst();
        int maximum = mode == Mode.ATTESTED
                ? Math.min(definition.maxEvidenceBytes(), ObservationAttestation.MAX_ENCODED_BYTES)
                : definition.maxValueBytes();
        Response response = exchange(address, request, parameters, maximum);
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("Observation HTTPS source returned status " + response.status());
        }
        String contentEncoding = response.singleHeader("content-encoding", "identity").trim();
        if (!contentEncoding.isEmpty() && !contentEncoding.equalsIgnoreCase("identity")) {
            throw new IOException("Observation HTTPS source returned encoded content");
        }
        byte[] body = response.body();
        if (mode == Mode.ATTESTED) {
            ObservationAttestation attestation = ObservationAttestation.decode(body);
            if (!Arrays.equals(attestation.subscriptionId(), request.round().subscriptionId())
                    || attestation.roundNumber() != request.round().roundNumber()) {
                throw new IOException("Observation attestation identifies another round");
            }
            return new ObservationCandidate(attestation.sourceId(), attestation.claim(), body,
                    attestation.sourceVersion(), attestation.freshnessAnchorType(),
                    attestation.freshnessAnchor());
        }
        String version = response.singleHeader(
                versionHeader.toLowerCase(Locale.ROOT), "").trim();
        if (version.isEmpty()) {
            throw new IOException("Observation HTTPS source omitted " + versionHeader);
        }
        byte[] sourceVersion = version.getBytes(StandardCharsets.US_ASCII);
        if (sourceVersion.length > 256
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(version)) {
            throw new IOException("Observation HTTPS source version is not bounded ASCII");
        }
        return new ObservationCandidate(sourceId, body, new byte[0], sourceVersion,
                request.round().anchorType().code(), request.round().dueAnchor());
    }

    private Response exchange(InetAddress address, ObservationRequest request,
                              byte[] parameters, int maximum) throws IOException {
        int port = endpoint.getPort() == -1 ? 443 : endpoint.getPort();
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        try (Socket plain = new Socket()) {
            var deadline = DEADLINES.schedule(() -> {
                try {
                    plain.close();
                } catch (IOException ignored) {
                    // The acquiring worker reports the failed exchange.
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            try {
                plain.connect(new InetSocketAddress(address, port), timeoutMillis);
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket tls = (SSLSocket) factory.createSocket(
                        plain, endpoint.getHost(), port, true)) {
                    tls.setSoTimeout(timeoutMillis);
                    SSLParameters tlsParameters = tls.getSSLParameters();
                    tlsParameters.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!isAddressLiteral(endpoint.getHost())) {
                        tlsParameters.setServerNames(List.of(new SNIHostName(endpoint.getHost())));
                    }
                    tls.setSSLParameters(tlsParameters);
                    tls.startHandshake();
                    writeRequest(tls.getOutputStream(), request, parameters);
                    return readResponse(tls.getInputStream(), maximum);
                }
            } finally {
                deadline.cancel(false);
            }
        }
    }

    private void writeRequest(OutputStream output, ObservationRequest request,
                              byte[] parameters) throws IOException {
        String path = endpoint.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (endpoint.getRawQuery() != null) path += "?" + endpoint.getRawQuery();
        String host = endpoint.getHost().indexOf(':') >= 0
                ? "[" + endpoint.getHost() + "]" : endpoint.getHost();
        StringBuilder headers = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Connection: close\r\n")
                .append("Accept: ").append(mode == Mode.ATTESTED
                        ? "application/cbor" : "application/octet-stream").append("\r\n")
                .append("Accept-Encoding: identity\r\n")
                .append("X-Yano-Definition-Digest: ")
                .append(HexUtil.encodeHexString(definition.digest())).append("\r\n")
                .append("X-Yano-Subscription-Id: ")
                .append(HexUtil.encodeHexString(request.round().subscriptionId())).append("\r\n")
                .append("X-Yano-Round-Number: ")
                .append(request.round().roundNumber()).append("\r\n");
        if (method.equals("POST")) {
            headers.append("Content-Type: application/cbor\r\n")
                    .append("Content-Length: ").append(parameters.length).append("\r\n");
        }
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        if (method.equals("POST")) output.write(parameters);
        output.flush();
    }

    static Response readResponse(InputStream input, int maximum) throws IOException {
        byte[] headerBytes = readHeaders(input);
        String[] lines = new String(headerBytes, StandardCharsets.ISO_8859_1)
                .split("\r\n");
        if (lines.length == 0 || !lines[0].matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) {
            throw new IOException("Malformed observation HTTPS status line");
        }
        int status = Integer.parseInt(lines[0].substring(9, 12));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) continue;
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new IOException("Folded observation HTTPS header is forbidden");
            }
            int colon = line.indexOf(':');
            if (colon < 1) throw new IOException("Malformed observation HTTPS header");
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+")) {
                throw new IOException("Malformed observation HTTPS header name");
            }
            String value = line.substring(colon + 1).trim();
            if (value.chars().anyMatch(character -> character < 32 && character != '\t')) {
                throw new IOException("Malformed observation HTTPS header value");
            }
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        if (status < 200 || status >= 300) return new Response(status, headers, new byte[0]);
        String transferEncoding = singleHeader(headers, "transfer-encoding", "");
        String contentLength = singleHeader(headers, "content-length", "");
        if (!transferEncoding.isEmpty() && !contentLength.isEmpty()) {
            throw new IOException("Ambiguous observation HTTPS response framing");
        }
        byte[] body;
        if (!transferEncoding.isEmpty()) {
            if (!transferEncoding.equalsIgnoreCase("chunked")) {
                throw new IOException("Unsupported observation HTTPS transfer encoding");
            }
            body = readChunked(input, maximum);
        } else if (!contentLength.isEmpty()) {
            final long length;
            try {
                length = Long.parseLong(contentLength);
            } catch (NumberFormatException malformed) {
                throw new IOException("Malformed observation HTTPS content length", malformed);
            }
            if (length < 0 || length > maximum) {
                throw new IOException("Observation HTTPS response exceeds definition bound");
            }
            body = input.readNBytes(Math.toIntExact(length));
            if (body.length != length) {
                throw new IOException("Truncated observation HTTPS response");
            }
        } else {
            body = readBounded(input, maximum);
        }
        return new Response(status, headers, body);
    }

    static void validatePublicEndpoint(URI endpoint) {
        resolvePublicAddresses(endpoint);
    }

    private static List<InetAddress> resolvePublicAddresses(URI endpoint) {
        validateEndpointSyntax(endpoint);
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(endpoint.getHost());
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException("Observation endpoint DNS resolution failed", failure);
        }
        if (addresses.length == 0 || Arrays.stream(addresses)
                .anyMatch(address -> !isPublic(address))) {
            throw new IllegalArgumentException(
                    "Observation endpoint resolves to a non-public address");
        }
        return Arrays.stream(addresses)
                .sorted(Comparator.comparingInt((InetAddress address) -> address.getAddress().length)
                        .thenComparing(InetAddress::getAddress, Arrays::compareUnsigned))
                .toList();
    }

    private static void validateEndpointSyntax(URI endpoint) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null || endpoint.getHost() == null
                || endpoint.getHost().isBlank()) {
            throw new IllegalArgumentException("Observation endpoint must be an absolute HTTPS URL");
        }
        int port = endpoint.getPort();
        if (port != -1 && port != 443) {
            throw new IllegalArgumentException("Observation HTTPS endpoint must use port 443");
        }
    }

    private static boolean isAddressLiteral(String host) {
        return host.indexOf(':') >= 0 || host.chars().allMatch(character ->
                character == '.' || character >= '0' && character <= '9');
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] value = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = value[0] & 0xff;
            int second = value[1] & 0xff;
            return first != 0 && first != 10 && first != 127
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && second == 0)
                    && !(first == 192 && second == 168)
                    && !(first == 198 && (second == 18 || second == 19))
                    && first < 224;
        }
        if (address instanceof Inet6Address) {
            int first = value[0] & 0xff;
            // Only native global unicast. Translation/tunneling ranges can
            // otherwise embed a private IPv4 destination past this check.
            return (first & 0xe0) == 0x20
                    && !(first == 0x20 && value[1] == 0x02)
                    && !(first == 0x20 && value[1] == 0x01
                    && (value[2] & 0xff) < 2)
                    && !(first == 0x20 && value[1] == 0x01
                    && value[2] == 0x0d && (value[3] & 0xff) == 0xb8);
        }
        return false;
    }

    private static URI parseEndpoint(String value) {
        try {
            URI uri = URI.create(value).normalize();
            if (!uri.toASCIIString().equals(value)) {
                throw new IllegalArgumentException(
                        "Observation endpoint must use its canonical ASCII URI form");
            }
            return uri;
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Invalid observation HTTPS endpoint", malformed);
        }
    }

    private static String required(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing observation provider setting: " + key);
        }
        return value.trim();
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total = Math.addExact(total, read);
            if (total > maximum) {
                throw new IOException("Observation HTTPS response exceeds definition bound");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static byte[] readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int matched = 0;
        while (headers.size() <= 32 * 1024) {
            int value = input.read();
            if (value < 0) throw new IOException("Truncated observation HTTPS headers");
            headers.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : value == '\r' ? 1 : 0;
                default -> 4;
            };
            if (matched == 4) return headers.toByteArray();
        }
        throw new IOException("Observation HTTPS headers exceed bound");
    }

    private static byte[] readChunked(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        while (true) {
            String sizeLine = readAsciiLine(input, 128);
            if (!sizeLine.matches("[0-9A-Fa-f]+")) {
                throw new IOException("Malformed observation HTTPS chunk size");
            }
            final long chunkSize;
            try {
                chunkSize = Long.parseLong(sizeLine, 16);
            } catch (NumberFormatException malformed) {
                throw new IOException("Malformed observation HTTPS chunk size", malformed);
            }
            if (chunkSize == 0) {
                if (!readAsciiLine(input, 2).isEmpty()) {
                    throw new IOException("Observation HTTPS trailers are forbidden");
                }
                return output.toByteArray();
            }
            if (chunkSize > maximum - output.size()) {
                throw new IOException("Observation HTTPS response exceeds definition bound");
            }
            byte[] chunk = input.readNBytes(Math.toIntExact(chunkSize));
            if (chunk.length != chunkSize || input.read() != '\r' || input.read() != '\n') {
                throw new IOException("Truncated observation HTTPS chunk");
            }
            output.write(chunk);
        }
    }

    private static String readAsciiLine(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= maximum) {
            int value = input.read();
            if (value < 0) throw new IOException("Truncated observation HTTPS line");
            if (value == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("Malformed observation HTTPS line ending");
                }
                return line.toString(StandardCharsets.US_ASCII);
            }
            if (value < 32 || value > 126) {
                throw new IOException("Malformed observation HTTPS line");
            }
            line.write(value);
        }
        throw new IOException("Observation HTTPS line exceeds bound");
    }

    private static String singleHeader(Map<String, List<String>> headers,
                                       String name, String fallback) throws IOException {
        List<String> values = headers.get(name);
        if (values == null) return fallback;
        if (values.size() != 1) {
            throw new IOException("Duplicate observation HTTPS response header");
        }
        return values.getFirst();
    }

    record Response(int status, Map<String, List<String>> headers, byte[] body) {
        Response {
            Map<String, List<String>> copied = new LinkedHashMap<>();
            headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
            headers = Map.copyOf(copied);
            body = body.clone();
        }

        @Override public byte[] body() { return body.clone(); }

        private String singleHeader(String name, String fallback) throws IOException {
            return RestrictedHttpsObservationProvider.singleHeader(headers, name, fallback);
        }
    }
}
