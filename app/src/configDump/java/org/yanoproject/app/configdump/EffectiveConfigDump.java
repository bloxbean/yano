package org.yanoproject.app.configdump;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * ADR-064 D8 consumer-check helper, compiled into the test classes of Yano's {@code app} and of the
 * downstream-consumer fixture: the {@code yano.*} and {@code quarkus.*} values (profile-qualified ones
 * included) that a running application inherits from its artifacts. Those are the Quarkus defaults and
 * build-time values and the configuration files packaged in jars, such as yano-app-core's
 * {@code application.yml}. Operator files, system properties, environment variables and test overrides
 * are not artifact defaults and are left out.
 */
public final class EffectiveConfigDump {

    /** Keys that name the application itself rather than configuration it inherits. */
    public static final Set<String> APPLICATION_ONLY_KEYS =
            Set.of("quarkus.application.name", "quarkus.application.version");

    private static final Set<String> QUARKUS_DEFAULT_SOURCES =
            Set.of("DefaultValuesConfigSource", "BuildTime RunTime Fixed", "Runtime Values");

    private EffectiveConfigDump() {
    }

    /** The effective artifact default of every key in scope; sources come highest ordinal first. */
    public static SortedMap<String, String> artifactDefaults() {
        SortedMap<String, String> dump = new TreeMap<>();
        for (ConfigSource source : ConfigProvider.getConfig().getConfigSources()) {
            if (!QUARKUS_DEFAULT_SOURCES.contains(source.getName()) && !source.getName().contains("jar:file:")) {
                continue;
            }
            for (String name : source.getPropertyNames()) {
                String key = name.startsWith("%") ? name.substring(name.indexOf('.') + 1) : name;
                if ((key.startsWith("yano.") || key.startsWith("quarkus.")) && !APPLICATION_ONLY_KEYS.contains(key)) {
                    dump.putIfAbsent(name, String.valueOf(source.getValue(name)));
                }
            }
        }
        return dump;
    }

    public static void write(Path file, Map<String, String> dump) throws IOException {
        Properties properties = new Properties();
        properties.putAll(dump);
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "ADR-064 D8 artifact configuration defaults");
        }
    }

    public static SortedMap<String, String> read(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        SortedMap<String, String> dump = new TreeMap<>();
        properties.stringPropertyNames().forEach(name -> dump.put(name, properties.getProperty(name)));
        return dump;
    }
}
