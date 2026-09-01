package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackendProvider;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DuckLakeNativeImageMetadataTest {
    private static final String PROJECTION_SERVICE = ProjectionSinkProvider.class.getName();
    private static final String BACKEND_SERVICE = ArchiveBackendProvider.class.getName();
    private static final String PROJECTION_PROVIDER = DuckLakeProjectionSinkProvider.class.getName();
    private static final String BACKEND_PROVIDER = DuckLakeArchiveBackendProvider.class.getName();
    private static final String NATIVE_METADATA_ROOT =
            "META-INF/native-image/com.bloxbean.cardano/yano-archive-store-ducklake/";
    private static final String EXTENSION_METADATA_ROOT =
            "META-INF/native-image/com.bloxbean.cardano/"
                    + "yano-archive-store-ducklake-extensions/";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void providerDescriptorsAndNativeMetadataStayInExactLockstep() throws Exception {
        Map<String, String> providersByService = Map.of(
                PROJECTION_SERVICE, PROJECTION_PROVIDER,
                BACKEND_SERVICE, BACKEND_PROVIDER);

        providersByService.forEach((service, provider) ->
                assertThat(serviceEntries(service)).containsExactly(provider));
        providersByService.forEach(DuckLakeNativeImageMetadataTest::assertServiceProviderContract);

        assertThat(reflectionEntries()).containsExactlyInAnyOrderEntriesOf(Map.of(
                PROJECTION_PROVIDER, Set.of("name", "allDeclaredConstructors"),
                BACKEND_PROVIDER, Set.of("name", "allDeclaredConstructors")));
        assertThat(resourcePatterns()).containsExactlyInAnyOrder(
                quotedResource("META-INF/services/" + PROJECTION_SERVICE),
                quotedResource("META-INF/services/" + BACKEND_SERVICE));
        String duckDbVersion = System.getProperty("yano.duckdb.version");
        String extensionPlatform = System.getProperty("yano.duckdb.extension-platform");
        String extensionRoot = "duckdb-extensions/" + duckDbVersion + "/"
                + extensionPlatform + "/";
        assertThat(resourcePatterns(EXTENSION_METADATA_ROOT + "resource-config.json"))
                .containsExactlyInAnyOrder(
                        quotedResource(extensionRoot + "ducklake.duckdb_extension"),
                        quotedResource(extensionRoot + "ducklake.sha256"),
                        quotedResource(extensionRoot + "sqlite_scanner.duckdb_extension"),
                        quotedResource(extensionRoot + "sqlite_scanner.sha256"));
        assertThat(jniEntries()).containsExactlyInAnyOrderEntriesOf(expectedJniEntries());
        assertThat(nativeImageArgs())
                .containsExactlyInAnyOrder(
                        "--initialize-at-run-time=org.duckdb.DuckDBDriver",
                        "--initialize-at-run-time=org.duckdb.DuckDBNative");
    }

    private static Set<String> serviceEntries(String service) {
        Set<String> entries = new LinkedHashSet<>();
        for (String line : resourceText("META-INF/services/" + service).lines().toList()) {
            String entry = line.substring(0, commentStart(line)).trim();
            if (!entry.isEmpty()) {
                assertThat(entries.add(entry)).as("duplicate provider in %s", service).isTrue();
            }
        }
        return entries;
    }

    private static int commentStart(String line) {
        int comment = line.indexOf('#');
        return comment < 0 ? line.length() : comment;
    }

    private static void assertServiceProviderContract(String serviceName, String providerName) {
        try {
            Class<?> service = Class.forName(serviceName);
            Class<?> provider = Class.forName(providerName);

            assertThat(service.isAssignableFrom(provider)).isTrue();
            assertThat(Modifier.isPublic(provider.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(provider.getDeclaredConstructor().getModifiers())).isTrue();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("invalid ServiceLoader provider " + providerName, e);
        }
    }

    private static Map<String, Set<String>> reflectionEntries() {
        JsonNode entriesNode = jsonResource(NATIVE_METADATA_ROOT + "reflect-config.json");
        assertThat(entriesNode.isArray()).as("native reflection config is an array").isTrue();

        Map<String, Set<String>> entries = new LinkedHashMap<>();
        for (JsonNode entry : entriesNode) {
            assertThat(entry.isObject()).as("reflection entry %s", entry).isTrue();
            String name = entry.path("name").asText();
            assertThat(name).as("reflection entry name in %s", entry).isNotBlank();
            Set<String> properties = new LinkedHashSet<>();
            entry.properties().forEach(property -> properties.add(property.getKey()));
            assertThat(entry.path("allDeclaredConstructors").asBoolean())
                    .as("declared constructors for %s", name)
                    .isTrue();
            assertThat(entries.put(name, Set.copyOf(properties)))
                    .as("duplicate reflection entry for %s", name)
                    .isNull();
        }
        return entries;
    }

    private static Set<String> resourcePatterns() {
        return resourcePatterns(NATIVE_METADATA_ROOT + "resource-config.json");
    }

    private static Set<String> resourcePatterns(String path) {
        JsonNode config = jsonResource(path);
        assertThat(config.properties()).extracting(Map.Entry::getKey)
                .containsExactly("resources");
        JsonNode resources = config.path("resources");
        assertThat(resources.properties()).extracting(Map.Entry::getKey)
                .containsExactly("includes");
        JsonNode includes = resources.path("includes");
        assertThat(includes.isArray()).as("native resource includes is an array").isTrue();

        Set<String> patterns = new LinkedHashSet<>();
        for (JsonNode entry : includes) {
            assertThat(entry.properties()).extracting(Map.Entry::getKey)
                    .containsExactly("pattern");
            String pattern = entry.path("pattern").asText();
            assertThat(patterns.add(pattern))
                    .as("duplicate native resource pattern %s", pattern)
                    .isTrue();
        }
        return patterns;
    }

    private static String quotedResource(String resource) {
        return "\\Q" + resource + "\\E";
    }

    private static Map<String, Set<String>> expectedJniEntries() {
        return Map.ofEntries(
                Map.entry("byte[]", Set.of()),
                Map.entry("java.lang.Boolean", Set.of("method:booleanValue()")),
                Map.entry("java.lang.Byte", Set.of("method:byteValue()")),
                Map.entry("java.lang.Double", Set.of("method:doubleValue()")),
                Map.entry("java.lang.Float", Set.of("method:floatValue()")),
                Map.entry("java.lang.Integer", Set.of("method:intValue()")),
                Map.entry("java.lang.Long", Set.of("method:longValue()")),
                Map.entry("java.lang.Object", Set.of("method:toString()")),
                Map.entry("java.lang.Short", Set.of("method:shortValue()")),
                Map.entry("java.lang.String", Set.of(
                        "method:getBytes(java.nio.charset.Charset)")),
                Map.entry("java.lang.Throwable", Set.of("method:getMessage()")),
                Map.entry("java.math.BigDecimal", Set.of(
                        "method:longValue()",
                        "method:precision()",
                        "method:scale()",
                        "method:scaleByPowerOfTen(int)",
                        "method:toPlainString()")),
                Map.entry("java.nio.ByteBuffer", Set.of(
                        "method:order(java.nio.ByteOrder)")),
                Map.entry("java.nio.ByteOrder", Set.of("method:nativeOrder()")),
                Map.entry("java.nio.CharBuffer", Set.of("method:toString()")),
                Map.entry("java.nio.charset.Charset", Set.of(
                        "method:decode(java.nio.ByteBuffer)")),
                Map.entry("java.nio.charset.StandardCharsets", Set.of("field:UTF_8")),
                Map.entry("java.sql.Array", Set.of(
                        "method:getArray()", "method:getBaseTypeName()")),
                Map.entry("java.sql.SQLException", Set.of()),
                Map.entry("java.sql.SQLTimeoutException", Set.of()),
                Map.entry("java.sql.Struct", Set.of(
                        "method:getAttributes()", "method:getSQLTypeName()")),
                Map.entry("java.util.Iterator", Set.of(
                        "method:hasNext()", "method:next()")),
                Map.entry("java.util.List", Set.of("method:iterator()")),
                Map.entry("java.util.Map", Set.of("method:entrySet()")),
                Map.entry("java.util.Map$Entry", Set.of(
                        "method:getKey()", "method:getValue()")),
                Map.entry("java.util.Set", Set.of("method:iterator()")),
                Map.entry("java.util.UUID", Set.of(
                        "method:getLeastSignificantBits()",
                        "method:getMostSignificantBits()")),
                Map.entry("org.duckdb.DuckDBArray", Set.of(
                        "method:<init>(org.duckdb.DuckDBVector,int,int)")),
                Map.entry("org.duckdb.DuckDBDate", Set.of(
                        "method:getDaysSinceEpoch()")),
                Map.entry("org.duckdb.DuckDBHugeInt", Set.of(
                        "field:lower",
                        "field:upper",
                        "method:toBigDecimal(long,long,int)",
                        "method:toBigInteger(long,long)")),
                Map.entry("org.duckdb.DuckDBResultSetMetaData", Set.of(
                        "method:<init>(int,int,java.lang.String[],java.lang.String[],"
                                + "java.lang.String[],java.lang.String,java.lang.String[],"
                                + "java.lang.String[])")),
                Map.entry("org.duckdb.DuckDBScalarFunctionWrapper", Set.of(
                        "method:execute(java.nio.ByteBuffer,java.nio.ByteBuffer,"
                                + "java.nio.ByteBuffer)")),
                Map.entry("org.duckdb.DuckDBStruct", Set.of(
                        "method:<init>(java.lang.String[],org.duckdb.DuckDBVector[],int,"
                                + "java.lang.String)")),
                Map.entry("org.duckdb.DuckDBTableFunctionWrapper", Set.of(
                        "method:executeBind(java.nio.ByteBuffer)",
                        "method:executeFunction(java.nio.ByteBuffer,java.nio.ByteBuffer)",
                        "method:executeGlobalInit(java.nio.ByteBuffer)",
                        "method:executeLocalInit(java.nio.ByteBuffer)")),
                Map.entry("org.duckdb.DuckDBTime", Set.of()),
                Map.entry("org.duckdb.DuckDBTimestamp", Set.of(
                        "method:getMicrosEpoch()", "method:valueOf(java.lang.Object)")),
                Map.entry("org.duckdb.DuckDBTimestampTZ", Set.of()),
                Map.entry("org.duckdb.DuckDBVector", Set.of(
                        "field:constlen_data",
                        "field:varlen_data",
                        "method:<init>(java.lang.String,int,boolean[])",
                        "method:retainConstlenData()")),
                Map.entry("org.duckdb.ProfilerPrintFormat", Set.of(
                        "field:GRAPHVIZ",
                        "field:HTML",
                        "field:JSON",
                        "field:NO_OUTPUT",
                        "field:QUERY_TREE",
                        "field:QUERY_TREE_OPTIMIZER")),
                Map.entry("org.duckdb.QueryProgress", Set.of(
                        "method:<init>(double,long,long)")),
                Map.entry("org.duckdb.user.DuckDBMap", Set.of(
                        "method:getSQLTypeName()")));
    }

    private static Map<String, Set<String>> jniEntries() {
        JsonNode entriesNode = jsonResource(NATIVE_METADATA_ROOT + "jni-config.json");
        assertThat(entriesNode.isArray()).as("native JNI config is an array").isTrue();

        Map<String, Set<String>> entries = new LinkedHashMap<>();
        for (JsonNode entry : entriesNode) {
            assertThat(entry.isObject()).as("JNI entry %s", entry).isTrue();
            assertThat(entry.properties()).extracting(Map.Entry::getKey)
                    .contains("name")
                    .isSubsetOf("name", "fields", "methods");
            String name = entry.path("name").asText();
            assertThat(name).as("JNI entry name in %s", entry).isNotBlank();

            Set<String> members = new LinkedHashSet<>();
            for (JsonNode field : entry.path("fields")) {
                assertThat(field.properties()).extracting(Map.Entry::getKey)
                        .containsExactly("name");
                assertThat(members.add("field:" + field.path("name").asText()))
                        .as("duplicate JNI field in %s", name)
                        .isTrue();
            }
            for (JsonNode method : entry.path("methods")) {
                assertThat(method.properties()).extracting(Map.Entry::getKey)
                        .containsExactlyInAnyOrder("name", "parameterTypes");
                assertThat(method.path("parameterTypes").isArray())
                        .as("JNI method parameters in %s", method)
                        .isTrue();
                List<String> parameters = new ArrayList<>();
                method.path("parameterTypes").forEach(parameter ->
                        parameters.add(parameter.asText()));
                String signature = "method:" + method.path("name").asText()
                        + "(" + String.join(",", parameters) + ")";
                assertThat(members.add(signature))
                        .as("duplicate JNI method in %s", name)
                        .isTrue();
            }
            assertThat(entries.put(name, Set.copyOf(members)))
                    .as("duplicate JNI entry for %s", name)
                    .isNull();
        }
        return entries;
    }

    private static Set<String> nativeImageArgs() {
        String propertiesText = resourceText(NATIVE_METADATA_ROOT + "native-image.properties");
        List<String> propertyLines = propertiesText.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        assertThat(propertyLines).as("native-image.properties exact property lines").hasSize(1);

        Properties properties = new Properties();
        try {
            properties.load(new StringReader(propertiesText));
        } catch (IOException e) {
            throw new AssertionError("failed to parse native-image.properties", e);
        }
        assertThat(properties.stringPropertyNames()).containsExactly("Args");
        return Set.of(properties.getProperty("Args").trim().split("\\s+"));
    }

    private static JsonNode jsonResource(String path) {
        ClassLoader loader = DuckLakeNativeImageMetadataTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        } catch (IOException e) {
            throw new AssertionError("failed to parse classpath JSON resource " + path, e);
        }
    }

    private static String resourceText(String path) {
        ClassLoader loader = DuckLakeNativeImageMetadataTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("failed to read classpath resource " + path, e);
        }
    }
}
