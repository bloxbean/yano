package com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsPinClient;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsProviderException;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.PinState;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hardened synchronous adapter for Kubo's local HTTP RPC API.
 *
 * <p>The adapter exposes exactly two fixed RPCs, never follows redirects,
 * never consults ambient proxy configuration, bounds every response before
 * parsing, and retains no provider failure details.</p>
 */
public final class KuboIpfsPinClient implements IpfsPinClient {
    static final int MAX_RESPONSE_BYTES = 16 * 1_024;
    private static final String EFFECT_ID_HEADER = "X-Yano-Effect-Id";
    private static final String PIN_LS_PATH = "/api/v0/pin/ls";
    private static final String PIN_ADD_PATH = "/api/v0/pin/add";
    private static final ProxySelector NO_PROXY = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // There is no selected proxy and no provider detail may escape.
        }
    };

    private final KuboClientConfig config;
    private final HttpClient httpClient;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a Kubo adapter with a private JDK HTTP client.
     *
     * @param config validated target construction values
     */
    public KuboIpfsPinClient(KuboClientConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(NO_PROXY)
                .connectTimeout(config.connectTimeout())
                .build();
    }

    @Override
    public PinState probe(CanonicalCid cid, byte[] effectIdHash) {
        CanonicalCid requestedCid = requireCid(cid);
        String effectId = effectId(effectIdHash);
        URI uri = rpcUri(PIN_LS_PATH, "arg", requestedCid.canonicalText(), "type", "all");
        try (ProviderResponse response = exchange(request(uri, effectId), Operation.PROBE)) {
            if (response.statusCode() == 200) {
                return KuboJsonResponseParser.parsePinState(response.body(), requestedCid);
            }
            if (response.statusCode() == 500) {
                String message;
                try {
                    message = KuboJsonResponseParser.parseError(response.body());
                } catch (IpfsProviderException malformedErrorResponse) {
                    throw failure(ConnectorErrorCode.SERVICE_UNAVAILABLE);
                }
                String expected = "path '" + requestedCid.canonicalText() + "' is not pinned";
                if (message.equals(expected)) {
                    return PinState.ABSENT;
                }
                throw failure(ConnectorErrorCode.SERVICE_UNAVAILABLE);
            }
            throw statusFailure(response.statusCode(), Operation.PROBE);
        }
    }

    @Override
    public void add(CanonicalCid cid, boolean recursive, byte[] effectIdHash) {
        CanonicalCid requestedCid = requireCid(cid);
        String effectId = effectId(effectIdHash);
        URI uri = rpcUri(PIN_ADD_PATH,
                "arg", requestedCid.canonicalText(),
                "recursive", Boolean.toString(recursive),
                "progress", "false");
        try (ProviderResponse response = exchange(request(uri, effectId), Operation.MUTATION)) {
            if (response.statusCode() == 500) {
                String message;
                try {
                    message = KuboJsonResponseParser.parseError(response.body());
                } catch (IpfsProviderException malformedAcknowledgement) {
                    throw failure(ConnectorErrorCode.ACK_UNKNOWN);
                }
                if (isKnownContentUnavailable(message, requestedCid)) {
                    throw failure(ConnectorErrorCode.CONTENT_UNAVAILABLE);
                }
                throw failure(ConnectorErrorCode.ACK_UNKNOWN);
            }
            if (response.statusCode() != 200) {
                throw statusFailure(response.statusCode(), Operation.MUTATION);
            }
            try {
                KuboJsonResponseParser.parseAddAcknowledgement(response.body(), requestedCid);
            } catch (IpfsProviderException malformedAcknowledgement) {
                // Kubo may already have committed the pin before emitting a
                // malformed or mismatched 200 acknowledgement. The executor
                // must re-probe rather than treating this as definitive.
                throw failure(ConnectorErrorCode.ACK_UNKNOWN);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            httpClient.shutdown();
            if (!httpClient.awaitTermination(config.closeTimeout())) {
                httpClient.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            httpClient.shutdownNow();
            Thread.currentThread().interrupt();
            throw failure(ConnectorErrorCode.SHUTDOWN);
        } catch (RuntimeException shutdownFailure) {
            httpClient.shutdownNow();
            throw failure(ConnectorErrorCode.INTERNAL_ERROR);
        }
    }

    private HttpRequest request(URI uri, String effectId) {
        ensureOpen();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header(EFFECT_ID_HEADER, effectId)
                .POST(HttpRequest.BodyPublishers.noBody());
        config.bearerToken().ifPresent(token -> request.header("Authorization", "Bearer " + token));
        try {
            return request.build();
        } catch (RuntimeException invalidRequest) {
            throw failure(ConnectorErrorCode.INTERNAL_ERROR);
        }
    }

    private ProviderResponse exchange(HttpRequest request, Operation operation) {
        ensureOpen();
        AtomicInteger observedStatus = new AtomicInteger(-1);
        AtomicReference<BoundedBodySubscriber> activeBody = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending = httpClient.sendAsync(request, responseInfo -> {
                observedStatus.set(responseInfo.statusCode());
                BoundedBodySubscriber subscriber = new BoundedBodySubscriber(
                        declaredResponseTooLarge(responseInfo.headers()));
                activeBody.set(subscriber);
                return subscriber;
            });
        } catch (RuntimeException transportFailure) {
            if (closed.get()) {
                throw failure(ConnectorErrorCode.SHUTDOWN);
            }
            throw operationFailure(operation, observedStatus.get(), false);
        }

        HttpResponse<byte[]> response;
        try {
            response = pending.get(config.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            abort(activeBody);
            Thread.currentThread().interrupt();
            throw operationFailure(operation, observedStatus.get(), true);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            abort(activeBody);
            throw operationFailure(operation, observedStatus.get(), false);
        } catch (ExecutionException transportFailure) {
            pending.cancel(true);
            abort(activeBody);
            if (containsCause(transportFailure, ResponseTooLargeException.class)) {
                throw invalidResponse(observedStatus.get(), operation);
            }
            if (closed.get() && operation == Operation.PROBE) {
                throw failure(ConnectorErrorCode.SHUTDOWN);
            }
            throw operationFailure(operation, observedStatus.get(), false);
        } catch (RuntimeException transportFailure) {
            pending.cancel(true);
            abort(activeBody);
            if (closed.get() && operation == Operation.PROBE) {
                throw failure(ConnectorErrorCode.SHUTDOWN);
            }
            throw operationFailure(operation, observedStatus.get(), false);
        }

        int status = response.statusCode();
        byte[] bytes = response.body();
        if (bytes == null || bytes.length > MAX_RESPONSE_BYTES
                || !request.uri().equals(response.uri())) {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
            throw invalidResponse(status, operation);
        }
        return new ProviderResponse(status, bytes);
    }

    private static boolean isKnownContentUnavailable(String message, CanonicalCid cid) {
        String canonical = cid.canonicalText();
        return message.equals("pin: block was not found locally (offline): ipld: could not find "
                + canonical)
                || message.equals("ipld: could not find " + canonical)
                || message.equals("merkledag: not found")
                || message.equals("pin: merkledag: not found")
                || message.equals("pin: failed to fetch " + canonical + ": merkledag: not found")
                || message.equals("pin: failed to resolve " + canonical + ": merkledag: not found");
    }

    private static IpfsProviderException statusFailure(int status, Operation operation) {
        ConnectorErrorCode code = switch (status) {
            case 401 -> ConnectorErrorCode.AUTH_UNAVAILABLE;
            case 403 -> ConnectorErrorCode.POLICY_DENIED;
            case 408 -> operation == Operation.MUTATION
                    ? ConnectorErrorCode.ACK_UNKNOWN
                    : ConnectorErrorCode.SERVICE_UNAVAILABLE;
            case 429 -> ConnectorErrorCode.RATE_LIMITED;
            default -> {
                if (operation == Operation.MUTATION
                        && (status < 400 || status > 499)) {
                    yield ConnectorErrorCode.ACK_UNKNOWN;
                }
                if (status >= 500 && status <= 599) {
                    yield ConnectorErrorCode.SERVICE_UNAVAILABLE;
                }
                yield ConnectorErrorCode.PROVIDER_REJECTED;
            }
        };
        return failure(code);
    }

    private static IpfsProviderException invalidResponse(int status, Operation operation) {
        if (operation == Operation.MUTATION) {
            // Explicit 4xx responses are known pre-mutation refusals. Once any
            // other mutation response is malformed/unreadable, acknowledgement
            // is unknown and only a subsequent probe can decide the outcome.
            if (status >= 400 && status <= 499) {
                return statusFailure(status, operation);
            }
            return failure(ConnectorErrorCode.ACK_UNKNOWN);
        }
        if ((status >= 500 && status <= 599) || status == 408) {
            return failure(ConnectorErrorCode.SERVICE_UNAVAILABLE);
        }
        return failure(ConnectorErrorCode.PROVIDER_REJECTED);
    }

    private static IpfsProviderException operationFailure(Operation operation,
                                                          int observedStatus,
                                                          boolean interrupted) {
        if (operation == Operation.MUTATION) {
            if (observedStatus >= 400 && observedStatus <= 499) {
                return statusFailure(observedStatus, operation);
            }
            return failure(ConnectorErrorCode.ACK_UNKNOWN);
        }
        return failure(interrupted
                ? ConnectorErrorCode.SHUTDOWN
                : ConnectorErrorCode.SERVICE_UNAVAILABLE);
    }

    private static boolean declaredResponseTooLarge(HttpHeaders headers) {
        return headers.firstValue("Content-Length").map(value -> {
            try {
                long length = Long.parseLong(value);
                return length < 0 || length > MAX_RESPONSE_BYTES;
            } catch (NumberFormatException invalidLength) {
                return true;
            }
        }).orElse(false);
    }

    private static boolean containsCause(Throwable failure,
                                         Class<? extends Throwable> expected) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (expected.isInstance(current)) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    private static void abort(AtomicReference<BoundedBodySubscriber> activeBody) {
        BoundedBodySubscriber subscriber = activeBody.get();
        if (subscriber != null) {
            subscriber.abort();
        }
    }

    private URI rpcUri(String path, String... queryPairs) {
        if (queryPairs.length == 0 || queryPairs.length % 2 != 0) {
            throw failure(ConnectorErrorCode.INTERNAL_ERROR);
        }
        StringBuilder uri = new StringBuilder(config.apiEndpoint().toASCIIString()).append(path).append('?');
        for (int index = 0; index < queryPairs.length; index += 2) {
            if (index != 0) {
                uri.append('&');
            }
            uri.append(percentEncode(queryPairs[index]))
                    .append('=')
                    .append(percentEncode(queryPairs[index + 1]));
        }
        try {
            return URI.create(uri.toString());
        } catch (RuntimeException invalidUri) {
            throw failure(ConnectorErrorCode.INTERNAL_ERROR);
        }
    }

    private static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte current : bytes) {
            int unsigned = Byte.toUnsignedInt(current);
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '.' || unsigned == '_' || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0x0f, 16)));
            }
        }
        return encoded.toString();
    }

    private static CanonicalCid requireCid(CanonicalCid cid) {
        if (cid == null) {
            throw failure(ConnectorErrorCode.INTERNAL_ERROR);
        }
        return cid;
    }

    private static String effectId(byte[] effectIdHash) {
        if (effectIdHash == null || effectIdHash.length != 32) {
            throw failure(ConnectorErrorCode.INTERNAL_ERROR);
        }
        return HexFormat.of().formatHex(effectIdHash);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw failure(ConnectorErrorCode.SHUTDOWN);
        }
    }

    private static IpfsProviderException failure(ConnectorErrorCode code) {
        return new IpfsProviderException(code);
    }

    private enum Operation {
        PROBE,
        MUTATION
    }

    private static final class ProviderResponse implements AutoCloseable {
        private final int statusCode;
        private final byte[] body;

        private ProviderResponse(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        private int statusCode() {
            return statusCode;
        }

        private byte[] body() {
            return body;
        }

        @Override
        public void close() {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final byte[] buffer = new byte[MAX_RESPONSE_BYTES];
        private final boolean rejectDeclaredLength;
        private Flow.Subscription subscription;
        private int size;
        private boolean done;

        private BoundedBodySubscriber(boolean rejectDeclaredLength) {
            this.rejectDeclaredLength = rejectDeclaredLength;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription newSubscription) {
            if (subscription != null) {
                newSubscription.cancel();
                return;
            }
            subscription = newSubscription;
            if (rejectDeclaredLength) {
                fail(new ResponseTooLargeException());
                return;
            }
            newSubscription.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> items) {
            if (done) {
                return;
            }
            for (ByteBuffer item : items) {
                int remaining = item.remaining();
                if (remaining > MAX_RESPONSE_BYTES - size) {
                    fail(new ResponseTooLargeException());
                    return;
                }
                item.get(buffer, size, remaining);
                size += remaining;
            }
            subscription.request(1);
        }

        @Override
        public synchronized void onError(Throwable providerFailure) {
            fail(new ResponseReadException());
        }

        @Override
        public synchronized void onComplete() {
            if (done) {
                return;
            }
            done = true;
            byte[] body = Arrays.copyOf(buffer, size);
            Arrays.fill(buffer, (byte) 0);
            result.complete(body);
        }

        private synchronized void fail(RuntimeException normalizedFailure) {
            if (done) {
                return;
            }
            done = true;
            Arrays.fill(buffer, (byte) 0);
            if (subscription != null) {
                subscription.cancel();
            }
            result.completeExceptionally(normalizedFailure);
        }

        private synchronized void abort() {
            fail(new ResponseReadException());
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {
        private ResponseTooLargeException() {
            super(null, null, false, false);
        }
    }

    private static final class ResponseReadException extends RuntimeException {
        private ResponseReadException() {
            super(null, null, false, false);
        }
    }
}
