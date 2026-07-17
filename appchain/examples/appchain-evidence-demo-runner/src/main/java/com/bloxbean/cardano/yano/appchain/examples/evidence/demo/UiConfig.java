package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict credential-free configuration for the read-only report server. */
record UiConfig(Path reportDirectory, String bindAddress, int port) {
    private static final int MAX_CONFIG_BYTES = 16_384;
    private static final Set<String> KEYS = Set.of(
            "ui.report-directory", "ui.bind-address", "ui.port");

    static UiConfig load(Path configFile) {
        Map<String, String> values = readStrict(configFile);
        Path base = configFile.toAbsolutePath().normalize().getParent();
        String report = required(values, "ui.report-directory");
        Path path = Path.of(report);
        Path resolved = (path.isAbsolute() ? path : base.resolve(path))
                .normalize().toAbsolutePath();
        String bind = bind(required(values, "ui.bind-address"));
        int port = port(required(values, "ui.port"));
        return new UiConfig(resolved, bind, port);
    }

    private static Map<String, String> readStrict(Path path) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : BoundedFiles.readUtf8(
                    path, MAX_CONFIG_BYTES, false, false).lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0 || line.endsWith("\\")) {
                    throw new DemoException(DemoError.INVALID_CONFIG);
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (!KEYS.contains(key)) {
                    throw new DemoException(DemoError.UNKNOWN_CONFIG_KEY);
                }
                if (values.putIfAbsent(key, value) != null) {
                    throw new DemoException(DemoError.INVALID_CONFIG);
                }
            }
            if (!values.keySet().equals(KEYS)) {
                throw new DemoException(DemoError.MISSING_CONFIG_KEY);
            }
            return values;
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new DemoException(DemoError.MISSING_CONFIG_KEY);
        }
        return value;
    }

    private static String bind(String value) {
        if (Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1", "::").contains(value)) {
            return value;
        }
        throw new DemoException(DemoError.INVALID_CONFIG);
    }

    private static int port(String value) {
        if (!value.matches("[1-9][0-9]{0,4}")) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        int port = Integer.parseInt(value);
        if (port > 65_535) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        return port;
    }
}
