package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict, root-secret-file-backed configuration for the one-shot local S3 bootstrap. */
record S3BootstrapConfig(URI endpoint,
                         String region,
                         SecretValue accessKey,
                         SecretValue secretKey,
                         Path iamSpecFile,
                         SecretValue runnerSecretKey,
                         SecretValue executorSecretKey,
                         String sourceBucket,
                         String destinationBucket,
                         boolean pathStyle) {
    private static final int MAX_CONFIG_BYTES = 16_384;
    private static final Set<String> KEYS = Set.of(
            "s3.endpoint", "s3.region", "s3.access-key-file", "s3.secret-key-file",
            "s3.iam-provider", "s3.iam-spec-file", "s3.runner-secret-key-file",
            "s3.executor-secret-key-file", "s3.source-bucket", "s3.destination-bucket",
            "s3.path-style");

    static S3BootstrapConfig load(Path configFile) {
        Map<String, String> values = readStrict(configFile);
        Path base = configFile.toAbsolutePath().normalize().getParent();
        try {
            URI endpoint = origin(required(values, "s3.endpoint"));
            String region = required(values, "s3.region");
            String source = required(values, "s3.source-bucket");
            String destination = required(values, "s3.destination-bucket");
            if (!"us-east-1".equals(region)
                    || !"rustfs-v1".equals(required(values, "s3.iam-provider"))
                    || !"evidence-staging".equals(source)
                    || !"evidence-archive".equals(destination)) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
            String pathStyle = values.getOrDefault("s3.path-style", "true");
            if (!"true".equals(pathStyle)) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
            return new S3BootstrapConfig(endpoint, region,
                    SecretFiles.read(resolve(base, required(values, "s3.access-key-file"))),
                    SecretFiles.read(resolve(base, required(values, "s3.secret-key-file"))),
                    resolve(base, required(values, "s3.iam-spec-file")),
                    SecretFiles.read(resolve(base, required(values, "s3.runner-secret-key-file"))),
                    SecretFiles.read(resolve(base, required(values, "s3.executor-secret-key-file"))),
                    source, destination, true);
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    private static Map<String, String> readStrict(Path path) {
        try {
            // This file selects the private endpoint that receives the
            // bootstrap root credential. Its integrity is therefore secret-
            // equivalent even though the credential values live elsewhere.
            byte[] content = BoundedFiles.read(path, MAX_CONFIG_BYTES, false, true);
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : text.split("\\R", -1)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                if (line.endsWith("\\")) {
                    throw new DemoException(DemoError.INVALID_CONFIG);
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
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
            return values;
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException failure) {
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

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).normalize().toAbsolutePath();
    }

    private static URI origin(String value) {
        try {
            URI uri = new URI(value);
            if (!"http".equals(uri.getScheme())
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || !privateIpv4(uri.getHost()) || uri.getPort() < 1 || uri.getPort() > 65_535
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    && !"/".equals(uri.getRawPath()))) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null);
        } catch (URISyntaxException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    private static boolean privateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int index = 0; index < parts.length; index++) {
            if (!parts[index].matches("0|[1-9][0-9]{0,2}")) {
                return false;
            }
            octets[index] = Integer.parseInt(parts[index]);
            if (octets[index] > 255) {
                return false;
            }
        }
        return octets[0] == 10
                || octets[0] == 127
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }
}
