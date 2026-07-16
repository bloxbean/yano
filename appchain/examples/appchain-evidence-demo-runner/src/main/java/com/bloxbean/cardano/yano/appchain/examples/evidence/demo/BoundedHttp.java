package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Small no-redirect HTTP helper that bounds every response before parsing. */
final class BoundedHttp {
    private static final ProxySelector NO_PROXY = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // No proxy is selected and provider details are never reflected.
        }
    };

    private final HttpClient client;
    private final Duration timeout;

    BoundedHttp(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(NO_PROXY)
                .build();
        this.timeout = requestTimeout;
    }

    Response get(URI uri, Map<String, String> headers, int maximumBytes) {
        return send(request(uri, headers).GET().build(), maximumBytes);
    }

    Response post(URI uri, Map<String, String> headers, byte[] body, int maximumBytes) {
        return send(request(uri, headers)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), maximumBytes);
    }

    private HttpRequest.Builder request(URI uri, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(request::header);
        return request;
    }

    private Response send(HttpRequest request, int maximumBytes) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes");
        }
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.headers().firstValueAsLong("Content-Length")
                        .stream().anyMatch(length -> length > maximumBytes)) {
                    throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                }
                byte[] body = input.readNBytes(maximumBytes + 1);
                if (body.length > maximumBytes) {
                    throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                }
                return new Response(response.statusCode(), response.headers().map(), body);
            }
        } catch (DemoException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        } catch (IOException | RuntimeException failure) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
    }

    record Response(int status, Map<String, List<String>> headers, byte[] body) {
        Response {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
