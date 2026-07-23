package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiConfigTest {
    @TempDir
    Path temporary;

    @Test
    void servesWithOnlyThreeCredentialFreeKeysAndNoSecretFiles() throws Exception {
        Path configFile = temporary.resolve("ui.properties");
        Files.writeString(configFile, """
                ui.report-directory=reports
                ui.bind-address=127.0.0.1
                ui.port=8088
                """);
        UiConfig config = UiConfig.load(configFile);
        assertThat(config.reportDirectory()).isEqualTo(temporary.resolve("reports"));

        try (ReportServer server = new ReportServer(config.bindAddress(), 0,
                new ReportStore(config.reportDirectory()))) {
            server.start();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.port() + "/healthz")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void rejectsSecretsUnknownKeysDnsBindAndInvalidPort() throws Exception {
        Path config = temporary.resolve("ui.properties");
        Files.writeString(config, """
                ui.report-directory=reports
                ui.bind-address=ui.internal
                ui.port=8088
                """);
        assertThatThrownBy(() -> UiConfig.load(config)).isInstanceOf(DemoException.class);

        Files.writeString(config, """
                ui.report-directory=reports
                ui.bind-address=0.0.0.0
                ui.port=70000
                ui.api-key-file=secret
                """);
        assertThatThrownBy(() -> UiConfig.load(config))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.UNKNOWN_CONFIG_KEY);
    }

    @Test
    void rejectsOversizedAndMalformedUtf8Configuration() throws Exception {
        Path config = temporary.resolve("ui.properties");
        Files.write(config, new byte[16_385]);
        assertThatThrownBy(() -> UiConfig.load(config))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.INVALID_CONFIG);

        Files.write(config, new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThatThrownBy(() -> UiConfig.load(config))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.INVALID_CONFIG);
    }

    @Test
    void serveCommandStartsWithoutScenarioConfigOrAnySecretFile() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path config = temporary.resolve("ui-only.properties");
        Files.writeString(config, """
                ui.report-directory=reports
                ui.bind-address=127.0.0.1
                ui.port=%d
                """.formatted(port));
        AtomicInteger exit = new AtomicInteger(-1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Thread server = Thread.ofPlatform().start(() -> exit.set(EvidenceDemoMain.run(
                new String[]{"serve", "--config", config.toString()},
                new PrintStream(output), new PrintStream(error))));
        try {
            URI health = URI.create("http://127.0.0.1:" + port + "/healthz");
            HttpResponse<String> response = null;
            for (int attempt = 0; attempt < 40 && response == null; attempt++) {
                try {
                    response = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(health).build(),
                            HttpResponse.BodyHandlers.ofString());
                } catch (java.io.IOException unavailable) {
                    Thread.sleep(25);
                }
            }
            assertThat(response).isNotNull();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(output.toString()).contains("PASS command=serve port=" + port);
            assertThat(error.toString()).isEmpty();
        } finally {
            server.interrupt();
            server.join(5_000);
        }
        assertThat(server.isAlive()).isFalse();
        assertThat(exit).hasValue(0);
    }
}
