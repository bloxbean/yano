package com.bloxbean.cardano.yano.wallet.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalBridgeHttpServer implements AutoCloseable {
    public static final String LOOPBACK_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 47_000;

    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String JAVASCRIPT_CONTENT_TYPE = "application/javascript; charset=utf-8";

    private final LocalCip30BridgeService bridgeService;
    private final BridgeHttpAccessPolicy accessPolicy;
    private final ObjectMapper objectMapper;
    private final HttpServer server;
    private final ExecutorService executor;

    private LocalBridgeHttpServer(
            LocalCip30BridgeService bridgeService,
            BridgeHttpAccessPolicy accessPolicy,
            int port) {
        this.bridgeService = Objects.requireNonNull(bridgeService, "bridgeService is required");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy is required");
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port), 16);
        } catch (BindException e) {
            throw new UncheckedIOException("Unable to create Yano Wallet CIP-30 bridge on "
                    + LOOPBACK_HOST + ":" + port + ". Another process may already be using the reserved bridge port.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create Yano Wallet CIP-30 bridge on "
                    + LOOPBACK_HOST + ":" + port, e);
        }
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "yano-wallet-bridge-http");
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(executor);
        this.server.createContext("/cip30", this::handleCip30);
        this.server.createContext("/yano-cip30.js", this::handleJavascriptBridge);
    }

    public static LocalBridgeHttpServer start(
            LocalCip30BridgeService bridgeService,
            BridgeHttpAccessPolicy accessPolicy) {
        return start(bridgeService, accessPolicy, DEFAULT_PORT);
    }

    public static LocalBridgeHttpServer start(
            LocalCip30BridgeService bridgeService,
            BridgeHttpAccessPolicy accessPolicy,
            int port) {
        LocalBridgeHttpServer bridgeHttpServer = new LocalBridgeHttpServer(bridgeService, accessPolicy, port);
        bridgeHttpServer.server.start();
        return bridgeHttpServer;
    }

    public URI endpointUri() {
        return URI.create("http://" + LOOPBACK_HOST + ":" + server.getAddress().getPort() + "/cip30");
    }

    public static URI defaultEndpointUri() {
        return URI.create("http://" + LOOPBACK_HOST + ":" + DEFAULT_PORT + "/cip30");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handleCip30(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 204, new byte[0]);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, BridgeHttpResponse.failure("METHOD_NOT_ALLOWED", "Only POST is supported", null));
            return;
        }

        try {
            BridgeHttpRequest request = objectMapper.readValue(exchange.getRequestBody(), BridgeHttpRequest.class);
            Object result = dispatch(exchange, request);
            sendJson(exchange, 200, BridgeHttpResponse.success(result));
        } catch (BridgeException e) {
            sendJson(exchange, statusCode(e.error()), BridgeHttpResponse.failure(e.error().name(), e.getMessage(), e.method()));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, BridgeHttpResponse.failure(BridgeError.INVALID_REQUEST.name(), e.getMessage(), null));
        } catch (Exception e) {
            sendJson(exchange, 500, BridgeHttpResponse.failure(BridgeError.BACKEND_ERROR.name(), e.getMessage(), null));
        }
    }

    private void handleJavascriptBridge(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, BridgeHttpResponse.failure("METHOD_NOT_ALLOWED", "Only GET is supported", null));
            return;
        }
        byte[] response = yanoCip30Javascript().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JAVASCRIPT_CONTENT_TYPE);
        send(exchange, 200, response);
    }

    private Object dispatch(HttpExchange exchange, BridgeHttpRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (request.method() == null || request.method().isBlank()) {
            throw new IllegalArgumentException("method is required");
        }
        String method = request.method().trim();
        if (BridgeMethod.ENABLE.cip30Name().equals(method)) {
            String origin = requestOrigin(exchange, request);
            Set<BridgePermission> permissions = request.permissions() == null || request.permissions().isEmpty()
                    ? Set.of(BridgePermission.READ_WALLET)
                    : request.permissions();
            if (!accessPolicy.allow(origin, permissions)) {
                throw new BridgeException(BridgeError.REFUSED, BridgeMethod.ENABLE, "Bridge origin was refused");
            }
            BridgeSession session = bridgeService.enable(origin, permissions);
            return new BridgeEnableResponse(session.origin(), session.token(), session.permissions().stream().map(Enum::name).toList());
        }
        return switch (method) {
            case "getNetworkId" -> {
                verifySessionOrigin(exchange, request, BridgeMethod.GET_NETWORK_ID);
                yield bridgeService.getNetworkId(request.token());
            }
            case "getBalance" -> {
                verifySessionOrigin(exchange, request, BridgeMethod.GET_BALANCE);
                yield bridgeService.getBalance(request.token());
            }
            case "getUtxos" -> {
                verifySessionOrigin(exchange, request, BridgeMethod.GET_UTXOS);
                yield bridgeService.getUtxos(request.token());
            }
            case "getChangeAddress" -> {
                verifySessionOrigin(exchange, request, BridgeMethod.GET_CHANGE_ADDRESS);
                yield bridgeService.getChangeAddress(request.token());
            }
            case "getRewardAddresses" -> {
                verifySessionOrigin(exchange, request, BridgeMethod.GET_REWARD_ADDRESSES);
                yield bridgeService.getRewardAddresses(request.token());
            }
            case "signTx" -> {
                verifySessionOrigin(exchange, request, BridgeMethod.SIGN_TX);
                yield bridgeService.signTx(request.token(), request.txCborHex(), Boolean.TRUE.equals(request.partialSign()));
            }
            case "submitTx" -> {
                verifySessionOrigin(exchange, request, BridgeMethod.SUBMIT_TX);
                yield bridgeService.submitTx(request.token(), request.txCborHex());
            }
            default -> throw new BridgeException(BridgeError.INVALID_REQUEST, null, "Unsupported CIP-30 method: " + method);
        };
    }

    private void verifySessionOrigin(HttpExchange exchange, BridgeHttpRequest request, BridgeMethod method) {
        bridgeService.verifySessionOrigin(request.token(), method, browserOrigin(exchange));
    }

    private String requestOrigin(HttpExchange exchange, BridgeHttpRequest request) {
        String headerOrigin = browserOrigin(exchange);
        if (headerOrigin == null || headerOrigin.isBlank()) {
            throw new BridgeException(BridgeError.REFUSED, BridgeMethod.ENABLE, "Browser Origin header is required");
        }
        if (request.origin() != null && !request.origin().isBlank() && !headerOrigin.equals(request.origin())) {
            throw new BridgeException(BridgeError.REFUSED, BridgeMethod.ENABLE, "Bridge origin mismatch");
        }
        return headerOrigin;
    }

    private String browserOrigin(HttpExchange exchange) {
        return exchange.getRequestHeaders().getFirst("Origin");
    }

    private int statusCode(BridgeError error) {
        if (error == null) {
            return 500;
        }
        return switch (error) {
            case INVALID_REQUEST -> 400;
            case UNAUTHORIZED -> 401;
            case REFUSED -> 403;
            case WALLET_NOT_READY -> 409;
            case BACKEND_ERROR -> 500;
        };
    }

    private void addCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin == null || origin.isBlank() ? "null" : origin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Vary", "Origin");
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] response = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
        send(exchange, statusCode, response);
    }

    private void send(HttpExchange exchange, int statusCode, byte[] response) throws IOException {
        exchange.sendResponseHeaders(statusCode, response.length);
        try (var outputStream = exchange.getResponseBody()) {
            outputStream.write(response);
        }
    }

    private String yanoCip30Javascript() {
        return """
                (function () {
                  'use strict';

                  const currentScript = document.currentScript;
                  const bridgeBase = currentScript && currentScript.src
                    ? new URL(currentScript.src).origin
                    : 'http://127.0.0.1:47000';
                  const endpoint = bridgeBase + '/cip30';
                  let token = null;

                  async function call(method, payload) {
                    const body = Object.assign({ method: method }, payload || {});
                    const response = await fetch(endpoint, {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify(body)
                    });
                    const json = await response.json();
                    if (!response.ok || !json.success) {
                      const err = new Error((json.error && json.error.message) || ('Yano Wallet bridge error: ' + method));
                      err.code = json.error && json.error.code;
                      err.method = json.error && json.error.method;
                      throw err;
                    }
                    return json.result;
                  }

                  function requireToken() {
                    if (!token) {
                      throw new Error('Yano Wallet is not enabled');
                    }
                    return token;
                  }

                  function api() {
                    return {
                      getNetworkId: function () {
                        return call('getNetworkId', { token: requireToken() });
                      },
                      getBalance: function () {
                        return call('getBalance', { token: requireToken() });
                      },
                      getUtxos: function () {
                        return call('getUtxos', { token: requireToken() });
                      },
                      getChangeAddress: function () {
                        return call('getChangeAddress', { token: requireToken() });
                      },
                      getRewardAddresses: function () {
                        return call('getRewardAddresses', { token: requireToken() });
                      },
                      signTx: async function (txCborHex, partialSign) {
                        const result = await call('signTx', {
                          token: requireToken(),
                          txCborHex: txCborHex,
                          partialSign: !!partialSign
                        });
                        return result.witnessSetCborHex || result;
                      },
                      submitTx: function (txCborHex) {
                        return call('submitTx', { token: requireToken(), txCborHex: txCborHex });
                      }
                    };
                  }

                  const yanoWallet = {
                    name: 'Yano Wallet',
                    apiVersion: '1.0.0',
                    icon: '',
                    supportedExtensions: [],
                    isEnabled: async function () {
                      return !!token;
                    },
                    enable: async function () {
                      const session = await call('enable', {
                        origin: window.location.origin,
                        permissions: ['READ_WALLET', 'SIGN_TX', 'SUBMIT_TX']
                      });
                      token = session.token;
                      return api();
                    }
                  };

                  window.cardano = window.cardano || {};
                  window.cardano.yano = yanoWallet;
                  Object.defineProperty(window.cardano, 'yanoWallet', {
                    value: yanoWallet,
                    enumerable: false,
                    configurable: true
                  });
                  window.yanoWallet = yanoWallet;
                }());
                """;
    }

    public record BridgeHttpRequest(
            String method,
            String token,
            String origin,
            Set<BridgePermission> permissions,
            String txCborHex,
            Boolean partialSign) {
    }

    public record BridgeEnableResponse(
            String origin,
            String token,
            List<String> permissions) {
    }

    public record BridgeHttpResponse(
            boolean success,
            Object result,
            Map<String, Object> error) {

        static BridgeHttpResponse success(Object result) {
            return new BridgeHttpResponse(true, result, null);
        }

        static BridgeHttpResponse failure(String code, String message, BridgeMethod method) {
            return new BridgeHttpResponse(
                    false,
                    null,
                    Map.of(
                            "code", code == null ? BridgeError.BACKEND_ERROR.name() : code,
                            "message", message == null ? "" : message,
                            "method", method == null ? "" : method.cip30Name()));
        }
    }
}
