package com.bloxbean.cardano.yano.catalog;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict and size-bounded parser for {@code META-INF/yano/plugins/<id>.json}. */
public final class BundleManifestParser {
    /** Directory containing bundle-id-qualified authoring manifests. */
    public static final String RESOURCE_DIRECTORY = "META-INF/yano/plugins/";
    /** File suffix required for authoring manifests. */
    public static final String RESOURCE_SUFFIX = ".json";
    /** Maximum encoded manifest size accepted by the strict parser. */
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;

    private static final Pattern DUPLICATE_FIELD = Pattern.compile("Duplicate field ['\"]([^'\"]+)['\"]");
    private static final ObjectMapper MAPPER = createMapper();

    /** Creates a strict schema-v1 parser. */
    public BundleManifestParser() {
    }

    /**
     * Parses a bounded manifest stream without closing it.
     *
     * @param resourcePath qualified manifest resource path
     * @param input UTF-8 JSON input
     * @return immutable validated manifest
     * @throws PluginCatalogException if the path, encoding, JSON, or model is invalid
     */
    public BundleManifest parse(String resourcePath, InputStream input) {
        Objects.requireNonNull(input, "input");
        String expectedId = idFromResourcePath(resourcePath);
        try {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) {
                throw error(resourcePath, "$", "manifest exceeds 65536 bytes", null);
            }
            return parseBytes(resourcePath, expectedId, bytes);
        } catch (PluginCatalogException e) {
            throw e;
        } catch (IOException e) {
            throw error(resourcePath, "$", "manifest could not be read", e);
        }
    }

    /**
     * Parses bounded manifest bytes.
     *
     * @param resourcePath qualified manifest resource path
     * @param bytes UTF-8 JSON bytes
     * @return immutable validated manifest
     * @throws PluginCatalogException if the path, encoding, JSON, or model is invalid
     */
    public BundleManifest parse(String resourcePath, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        String expectedId = idFromResourcePath(resourcePath);
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw error(resourcePath, "$", "manifest exceeds 65536 bytes", null);
        }
        return parseBytes(resourcePath, expectedId, bytes);
    }

    private BundleManifest parseBytes(String resourcePath, String expectedId, byte[] bytes) {
        String json = decodeUtf8(resourcePath, bytes);
        try {
            RawManifest raw = MAPPER.readValue(json, RawManifest.class);
            BundleManifest manifest = toManifest(raw);
            if (!expectedId.equals(manifest.id())) {
                throw error(resourcePath, "id", "must match the resource filename", null);
            }
            return manifest;
        } catch (PluginCatalogException e) {
            throw e;
        } catch (UnrecognizedPropertyException e) {
            String path = jsonPath(e);
            if ("$".equals(path)) {
                path += "." + safeField(e.getPropertyName());
            }
            throw error(resourcePath, path, "unknown field", e);
        } catch (JsonMappingException e) {
            throw error(resourcePath, jsonPath(e), "field is missing or has the wrong JSON type", e);
        } catch (StreamReadException e) {
            throw error(resourcePath, duplicateFieldPath(e), "malformed JSON", e);
        } catch (IOException e) {
            throw error(resourcePath, "$", "manifest could not be parsed", e);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw error(resourcePath, fieldFromValidation(e.getMessage()), safeReason(e.getMessage()), e);
        }
    }

    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(MAX_MANIFEST_BYTES)
                        .maxNestingDepth(16)
                        .maxNumberLength(16)
                        .maxStringLength(CatalogValidation.MAX_PROVIDER_CLASS_LENGTH)
                        .build())
                .build();
        return JsonMapper.builder(factory)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .build();
    }

    private static BundleManifest toManifest(RawManifest raw) {
        if (raw == null) {
            throw new IllegalArgumentException("$ must contain one JSON object");
        }
        if (raw.schemaVersion == null) {
            throw new IllegalArgumentException("schemaVersion must be present");
        }
        SemVersion version = SemVersion.parse(raw.version);
        if (raw.yanoApi == null || raw.yanoApi.min == null || raw.yanoApi.max == null) {
            throw new IllegalArgumentException("yanoApi.min and yanoApi.max must be present");
        }
        List<BundleDependency> dependencies = new ArrayList<>();
        if (raw.dependencies != null) {
            for (RawDependency dependency : raw.dependencies) {
                if (dependency == null) {
                    throw new IllegalArgumentException("dependencies must not contain null entries");
                }
                dependencies.add(new BundleDependency(
                        dependency.id,
                        parseOptionalVersion(dependency.minVersion),
                        parseOptionalVersion(dependency.maxVersionExclusive)));
            }
        }
        List<BundleContribution> contributions = new ArrayList<>();
        if (raw.contributions != null) {
            for (RawContribution contribution : raw.contributions) {
                if (contribution == null) {
                    throw new IllegalArgumentException("contributions must not contain null entries");
                }
                contributions.add(new BundleContribution(
                        ContributionKind.fromManifestKey(contribution.kind),
                        contribution.name,
                        contribution.provider));
            }
        }
        return new BundleManifest(
                raw.schemaVersion,
                raw.id,
                version,
                new YanoApiRange(raw.yanoApi.min, raw.yanoApi.max),
                dependencies,
                contributions);
    }

    private static SemVersion parseOptionalVersion(String value) {
        return value == null ? null : SemVersion.parse(value);
    }

    private static String decodeUtf8(String resourcePath, byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw error(resourcePath, "$", "manifest must be valid UTF-8", e);
        }
    }

    private static String idFromResourcePath(String resourcePath) {
        if (resourcePath == null || !resourcePath.startsWith(RESOURCE_DIRECTORY)
                || !resourcePath.endsWith(RESOURCE_SUFFIX)) {
            throw error(resourcePath, "resource", "must use META-INF/yano/plugins/<id>.json", null);
        }
        String filename = resourcePath.substring(
                RESOURCE_DIRECTORY.length(), resourcePath.length() - RESOURCE_SUFFIX.length());
        if (filename.isEmpty() || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0) {
            throw error(resourcePath, "resource", "must use META-INF/yano/plugins/<id>.json", null);
        }
        try {
            return CatalogValidation.bundleId(filename, "resource filename");
        } catch (IllegalArgumentException e) {
            throw error(resourcePath, "resource", "filename must be a valid bundle id", e);
        }
    }

    private static PluginCatalogException error(
            String resourcePath,
            String field,
            String reason,
            Throwable cause
    ) {
        String message = "Invalid plugin manifest " + safeResource(resourcePath)
                + " at " + safeField(field) + ": " + reason;
        return cause == null ? new PluginCatalogException(message) : new PluginCatalogException(message, cause);
    }

    private static String jsonPath(JsonMappingException exception) {
        StringBuilder path = new StringBuilder("$");
        for (JsonMappingException.Reference reference : exception.getPath()) {
            if (reference.getFieldName() != null) {
                path.append('.').append(safeField(reference.getFieldName()));
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String duplicateFieldPath(StreamReadException exception) {
        Matcher matcher = DUPLICATE_FIELD.matcher(String.valueOf(exception.getOriginalMessage()));
        return matcher.find() ? "$." + safeField(matcher.group(1)) : "$";
    }

    private static String fieldFromValidation(String message) {
        if (message == null || message.isBlank()) {
            return "$";
        }
        int separator = message.indexOf(' ');
        String candidate = separator < 0 ? message : message.substring(0, separator);
        if (candidate.isEmpty()) {
            return "$";
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (!isSafeFieldCharacter(candidate.charAt(i))) {
                return "$";
            }
        }
        return candidate;
    }

    private static String safeReason(String message) {
        if (message == null || message.isBlank() || message.length() > 240
                || message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            return "manifest value is invalid";
        }
        return message;
    }

    private static String safeResource(String resourcePath) {
        if (resourcePath == null) {
            return "<unknown>";
        }
        String sanitized = resourcePath.replaceAll("[^A-Za-z0-9._/-]", "?");
        return sanitized.length() <= 220 ? sanitized : sanitized.substring(0, 220) + "...";
    }

    private static String safeField(String field) {
        if (field == null || field.isBlank()) {
            return "$";
        }
        StringBuilder sanitizedBuilder = new StringBuilder(Math.min(field.length(), 163));
        for (int i = 0; i < field.length() && i < 160; i++) {
            char character = field.charAt(i);
            sanitizedBuilder.append(isSafeFieldCharacter(character) ? character : '?');
        }
        if (field.length() > 160) {
            sanitizedBuilder.append("...");
        }
        return sanitizedBuilder.toString();
    }

    private static boolean isSafeFieldCharacter(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '_'
                || character == '$'
                || character == '.'
                || character == '['
                || character == ']'
                || character == '-';
    }

    private record RawManifest(
            Integer schemaVersion,
            String id,
            String version,
            RawYanoApi yanoApi,
            List<RawDependency> dependencies,
            List<RawContribution> contributions
    ) {
    }

    private record RawYanoApi(Integer min, Integer max) {
    }

    private record RawDependency(String id, String minVersion, String maxVersionExclusive) {
    }

    private record RawContribution(String kind, String name, String provider) {
    }
}
