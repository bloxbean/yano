package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Strict reader and canonical writer for {@link PluginIndex#RESOURCE_PATH}. */
public final class PluginIndexCodec {
    /** Maximum encoded aggregate-index size. */
    public static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    /** Maximum JSON tokens accepted while decoding an index. */
    public static final int MAX_INDEX_TOKENS = 1_000_000;

    private static final Comparator<BundleDependency> DEPENDENCY_ORDER =
            Comparator.comparing(BundleDependency::id);
    private static final Comparator<BundleContribution> CONTRIBUTION_ORDER =
            Comparator.comparing((BundleContribution value) -> value.kind().manifestKey())
                    .thenComparing(BundleContribution::name)
                    .thenComparing(BundleContribution::provider);
    private static final ObjectMapper MAPPER = createMapper();

    /** Creates a strict schema-v1 aggregate-index codec. */
    public PluginIndexCodec() {
    }

    /**
     * Reads one bounded, strict index document without closing the input.
     *
     * @param input UTF-8 JSON input
     * @return immutable validated index
     * @throws IOException if the stream cannot be read
     * @throws PluginCatalogException if the encoding, JSON, or model is invalid
     */
    public PluginIndex read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] bytes = input.readNBytes(MAX_INDEX_BYTES + 1);
        if (bytes.length > MAX_INDEX_BYTES) {
            throw invalid("index exceeds " + MAX_INDEX_BYTES + " bytes", null);
        }
        return read(bytes);
    }

    /**
     * Reads one strict index document already held in memory.
     *
     * @param bytes UTF-8 JSON bytes
     * @return immutable validated index
     * @throws PluginCatalogException if the encoding, JSON, or model is invalid
     */
    public PluginIndex read(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_INDEX_BYTES) {
            throw invalid("index exceeds " + MAX_INDEX_BYTES + " bytes", null);
        }
        try {
            String json = decodeUtf8(bytes);
            validateTokenCount(json);
            RawIndex raw = MAPPER.readValue(json, RawIndex.class);
            return toIndex(raw);
        } catch (UnrecognizedPropertyException e) {
            throw invalid("index contains unknown field '" + safe(e.getPropertyName()) + "'", e);
        } catch (JsonMappingException e) {
            throw invalid("index has a missing field or wrong JSON type", e);
        } catch (StreamReadException e) {
            throw invalid("index contains malformed or duplicate-key JSON", e);
        } catch (IOException e) {
            throw invalid("index could not be parsed", e);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid(safeReason(e.getMessage()), e);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw invalid("index must be valid UTF-8", e);
        }
    }

    /**
     * Returns the canonical UTF-8 representation.
     *
     * @param index validated index to encode
     * @return bounded canonical JSON bytes
     * @throws PluginCatalogException if the encoded index exceeds platform limits
     */
    public byte[] write(PluginIndex index) {
        Objects.requireNonNull(index, "index");
        index.bundles().forEach(bundle -> validateManifestSnapshotSize(bundle.manifest()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            JsonGenerator json = MAPPER.getFactory().createGenerator(output);
            writeIndex(json, index);
            json.close();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory plugin index encoding failed", impossible);
        }
        if (output.size() > MAX_INDEX_BYTES) {
            throw invalid("encoded index exceeds " + MAX_INDEX_BYTES + " bytes", null);
        }
        return output.toByteArray();
    }

    /**
     * Writes the canonical UTF-8 representation without closing {@code output}.
     *
     * @param index validated index to encode
     * @param output destination stream
     * @throws IOException if the encoded bytes cannot be written
     * @throws PluginCatalogException if the encoded index exceeds platform limits
     */
    public void write(PluginIndex index, OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        output.write(write(index));
    }

    private static void writeIndex(JsonGenerator json, PluginIndex index) throws IOException {
        json.writeStartObject();
        json.writeNumberField("schemaVersion", index.schemaVersion());
        json.writeArrayFieldStart("bundles");
        for (IndexedBundle bundle : index.bundles()) {
            json.writeStartObject();
            json.writeFieldName("manifest");
            writeManifest(json, bundle.manifest());
            json.writeStringField("digest", bundle.digest());
            json.writeStringField("digestMode", bundle.digestMode().name());
            json.writeEndObject();
        }
        json.writeEndArray();
        json.writeArrayFieldStart("legacyProviders");
        for (IndexedLegacyProvider provider : index.legacyProviders()) {
            json.writeStartObject();
            json.writeStringField("kind", provider.kind().manifestKey());
            json.writeStringField("provider", provider.provider());
            json.writeStringField("digest", provider.digest());
            json.writeStringField("digestMode", provider.digestMode().name());
            json.writeEndObject();
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private static void writeManifest(JsonGenerator json, BundleManifest manifest) throws IOException {
        json.writeStartObject();
        json.writeNumberField("schemaVersion", manifest.schemaVersion());
        json.writeStringField("id", manifest.id());
        json.writeStringField("version", manifest.version().toString());
        json.writeObjectFieldStart("yanoApi");
        json.writeNumberField("min", manifest.yanoApi().min());
        json.writeNumberField("max", manifest.yanoApi().max());
        json.writeEndObject();
        json.writeArrayFieldStart("dependencies");
        for (BundleDependency dependency : manifest.dependencies().stream().sorted(DEPENDENCY_ORDER).toList()) {
            json.writeStartObject();
            json.writeStringField("id", dependency.id());
            if (dependency.minVersion() != null) {
                json.writeStringField("minVersion", dependency.minVersion().toString());
            }
            if (dependency.maxVersionExclusive() != null) {
                json.writeStringField("maxVersionExclusive", dependency.maxVersionExclusive().toString());
            }
            json.writeEndObject();
        }
        json.writeEndArray();
        json.writeArrayFieldStart("contributions");
        for (BundleContribution contribution
                : manifest.contributions().stream().sorted(CONTRIBUTION_ORDER).toList()) {
            json.writeStartObject();
            json.writeStringField("kind", contribution.kind().manifestKey());
            json.writeStringField("name", contribution.name());
            json.writeStringField("provider", contribution.provider());
            json.writeEndObject();
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private static PluginIndex toIndex(RawIndex raw) {
        if (raw == null || raw.schemaVersion == null) {
            throw new IllegalArgumentException("schemaVersion must be present");
        }
        if (raw.bundles == null) {
            throw new IllegalArgumentException("bundles must be present");
        }
        if (raw.legacyProviders == null) {
            throw new IllegalArgumentException("legacyProviders must be present");
        }
        if (raw.bundles.size() > PluginIndex.MAX_BUNDLES) {
            throw new IllegalArgumentException("plugin index contains too many bundles");
        }
        if (raw.legacyProviders.size() > PluginIndex.MAX_LEGACY_PROVIDERS) {
            throw new IllegalArgumentException("plugin index contains too many legacy providers");
        }
        List<IndexedBundle> bundles = new ArrayList<>(raw.bundles.size());
        for (RawBundle rawBundle : raw.bundles) {
            if (rawBundle == null || rawBundle.manifest == null) {
                throw new IllegalArgumentException("bundles must contain manifest objects");
            }
            BundleManifest manifest = toManifest(rawBundle.manifest);
            validateManifestSnapshotSize(manifest);
            bundles.add(new IndexedBundle(
                    manifest,
                    rawBundle.digest,
                    digestMode(rawBundle.digestMode)));
        }
        List<IndexedLegacyProvider> legacyProviders = new ArrayList<>(raw.legacyProviders.size());
        for (RawLegacyProvider rawProvider : raw.legacyProviders) {
            if (rawProvider == null) {
                throw new IllegalArgumentException("legacyProviders must not contain null");
            }
            legacyProviders.add(new IndexedLegacyProvider(
                    ContributionKind.fromManifestKey(rawProvider.kind),
                    rawProvider.provider,
                    rawProvider.digest,
                    digestMode(rawProvider.digestMode)));
        }
        return new PluginIndex(raw.schemaVersion, bundles, legacyProviders);
    }

    private static void validateTokenCount(String json) throws IOException {
        int tokens = 0;
        try (JsonParser parser = MAPPER.getFactory().createParser(json)) {
            while (parser.nextToken() != null) {
                if (++tokens > MAX_INDEX_TOKENS) {
                    throw invalid("index exceeds " + MAX_INDEX_TOKENS + " JSON tokens", null);
                }
            }
        }
    }

    private static void validateManifestSnapshotSize(BundleManifest manifest) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            JsonGenerator json = MAPPER.getFactory().createGenerator(output);
            writeManifest(json, manifest);
            json.close();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory manifest encoding failed", impossible);
        }
        if (output.size() > BundleManifestParser.MAX_MANIFEST_BYTES) {
            throw invalid("manifest snapshot for bundle '" + manifest.id()
                    + "' exceeds " + BundleManifestParser.MAX_MANIFEST_BYTES + " bytes", null);
        }
    }

    private static BundleManifest toManifest(RawManifest raw) {
        if (raw.schemaVersion == null) {
            throw new IllegalArgumentException("manifest schemaVersion must be present");
        }
        if (raw.yanoApi == null || raw.yanoApi.min == null || raw.yanoApi.max == null) {
            throw new IllegalArgumentException("manifest yanoApi.min and yanoApi.max must be present");
        }
        if (raw.dependencies != null
                && raw.dependencies.size() > CatalogValidation.MAX_DEPENDENCIES) {
            throw new IllegalArgumentException("manifest dependencies must contain at most 256 entries");
        }
        if (raw.contributions != null
                && raw.contributions.size() > CatalogValidation.MAX_CONTRIBUTIONS) {
            throw new IllegalArgumentException("manifest contributions must contain at most 256 entries");
        }
        List<BundleDependency> dependencies = new ArrayList<>();
        if (raw.dependencies != null) {
            for (RawDependency dependency : raw.dependencies) {
                if (dependency == null) {
                    throw new IllegalArgumentException("manifest dependencies must not contain null");
                }
                dependencies.add(new BundleDependency(
                        dependency.id,
                        optionalVersion(dependency.minVersion),
                        optionalVersion(dependency.maxVersionExclusive)));
            }
        }
        List<BundleContribution> contributions = new ArrayList<>();
        if (raw.contributions != null) {
            for (RawContribution contribution : raw.contributions) {
                if (contribution == null) {
                    throw new IllegalArgumentException("manifest contributions must not contain null");
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
                SemVersion.parse(raw.version),
                new YanoApiRange(raw.yanoApi.min, raw.yanoApi.max),
                dependencies,
                contributions);
    }

    private static SemVersion optionalVersion(String value) {
        return value == null ? null : SemVersion.parse(value);
    }

    private static PluginDigestMode digestMode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("digestMode must be present");
        }
        try {
            return PluginDigestMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("digestMode is not supported", e);
        }
    }

    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(MAX_INDEX_BYTES)
                        .maxNestingDepth(24)
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

    private static PluginCatalogException invalid(String reason, Throwable cause) {
        String message = "Invalid " + PluginIndex.RESOURCE_PATH + ": " + reason;
        return cause == null ? new PluginCatalogException(message) : new PluginCatalogException(message, cause);
    }

    private static String safe(String value) {
        if (value == null) {
            return "<unknown>";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_.$-]", "?");
        return sanitized.length() <= 160 ? sanitized : sanitized.substring(0, 160) + "...";
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 240
                || reason.indexOf('\n') >= 0 || reason.indexOf('\r') >= 0) {
            return "index value is invalid";
        }
        return reason;
    }

    private record RawIndex(
            Integer schemaVersion,
            List<RawBundle> bundles,
            List<RawLegacyProvider> legacyProviders
    ) {
    }

    private record RawBundle(RawManifest manifest, String digest, String digestMode) {
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

    private record RawLegacyProvider(String kind, String provider, String digest, String digestMode) {
    }
}
